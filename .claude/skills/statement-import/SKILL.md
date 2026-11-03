---
name: statement-import
description: >-
  Invoke BEFORE writing or changing statement import in fin-track-mcp: the StatementParser port,
  EvocaXlsxParser (Apache POI), GenericCsvParser (Commons CSV), ParserFactory (format from file
  headers), ImportService, deterministic transaction ids / dedupe / occurrence index, import_log and
  file_hash, the sealed RowResult (Imported / Duplicate / Rejected) and ImportSummary, merchant
  extraction and card-number masking, date/number/locale parsing, or adding a new bank format.
  Triggers: "import", "parser", "parse", "xlsx", "Excel", "POI", "csv", "Commons CSV", "BOM",
  "delimiter", "statement", "Evoca", "bank format", "new bank", "ParserFactory", "dedupe",
  "duplicate", "idempotent", "transaction id", "hash", "RowResult", "rejected row", "merchant",
  "PAN", "card number", "import_log".
---

# Statement import

Goal: any supported bank export → categorized, AMD-converted `Transaction`s, **idempotently**, with every row
accounted for. See `money-and-fx` (amounts), `categorization-rules` (category), `sqlite-jdbcclient`
(`saveAll`), `mcp-server-tools` (`import_statement` path validation).

## The sequence (deck slide 16)

```
Claude ─import_statement(filePath)─▶ ImportTools ─importFile(path)─▶ ImportService
  ImportService ─parse(path)──────────▶ StatementParser (= ParserFactory → EvocaXlsxParser | GenericCsvParser)
                ◀─ParsedStatement(rows, rejected, fileHash)
  for each row: id (hash) → merchant → Transaction → CategorizationChain → FX (amountAmd, rate)
  ImportService ─saveAll(txs)─────────▶ TransactionRepository   (INSERT … ON CONFLICT(id) DO NOTHING; returns inserted ids)
  ImportService ─log(ImportLogEntry)──▶ TransactionRepository   (import_log: file name, file_hash, counts, time)
                ◀─ImportSummary(added, duplicates, rejected[])  ─▶ JSON result to Claude
```

`ImportService` lives in `application`, plain constructor, no Spring. One `saveAll` = one SQLite
transaction (the repository owns it).

## Port and factory (Composite + Factory)

```java
// application
public interface StatementParser {
    boolean supports(StatementHeader header);
    ParsedStatement parse(Path file);
}

public record StatementHeader(FileKind kind, List<String> headerCells) { }   // FileKind { XLSX, CSV }
public record StatementRow(int rowNumber, LocalDate date, String description, Direction direction, Money money) { }
public record ParsedStatement(String fileName, String fileHash, List<StatementRow> rows, List<Rejected> rejected) { }
```

`ParserFactory` (`adapter-parser`) **implements `StatementParser`** and is the bean the server
injects into `ImportService`, so the application sees one port and never knows which bank:

1. Sniff the kind by **magic bytes**, not extension: `PK\u0003\u0004` → XLSX (zip); otherwise text → CSV.
   Extension only pre-filters in the tool.
2. Read the header: first non-empty row(s) — XLSX scans the first N rows for the header row (banks put
   titles/account info above it); CSV reads the first record after BOM removal.
3. Normalize cells (trim, collapse whitespace, uppercase) and ask each registered parser `supports(header)`
   in order; first match wins. **Specific before generic**: `EvocaXlsxParser`, then `GenericCsvParser`.
4. None → `UnsupportedFormatException("Unrecognized statement format in <file>. Supported: Evoca .xlsx, "
   + "CSV with columns Date, Description, Amount, Currency.")` — header names are fine, cell values are not.

The exact Evoca column titles come from the real export / `mockdata` (single source; do not
hard-code a second copy in tests). Match on a **required set** of normalized titles, tolerant to order and to
extra columns.

## EvocaXlsxParser (Apache POI)

- Open read-only: `WorkbookFactory.create(file.toFile(), null, true)`, try-with-resources. Catch
  `IOException`, `EncryptedDocumentException`, `NotOfficeXmlFileException`, POI runtime exceptions →
  `UnsupportedFormatException`/`StatementReadException`. No POI type leaves the class.
- **Limits:** file size is capped in the tool; POI's zip-bomb guard (`ZipSecureFile` min inflate ratio,
  max entry size) is on by default — don't disable it. Cap rows at `finance.import.max-rows`; beyond it →
  reject the file with the limit in the message. Statements are small, so the user-model API is fine;
  switch to the XSSF event/SAX API only if a measured file needs it (verify API against POI docs then).
- **Cell types** — switch on `cell.getCellType()`; for `FORMULA` use `getCachedFormulaResultType()` and read
  the cached value. **Never evaluate formulas.** `BLANK`/missing cell → absent, not `""`.
- **Dates:** numeric cell with `DateUtil.isCellDateFormatted(cell)` → `cell.getLocalDateTimeCellValue()
  .toLocalDate()`; string cell → try formats from config (`dd.MM.yyyy`, `dd/MM/yyyy`, `yyyy-MM-dd`,
  optional time part ignored) with `DateTimeFormatter.ofPattern(p).withResolverStyle(ResolverStyle.STRICT)`
  (pattern `uuuu` for STRICT). Unparseable → `Rejected(row, UNPARSEABLE_DATE)`.
- **Numbers:** numeric cell → `BigDecimal.valueOf(cell.getNumericCellValue())` then
  `setScale(currency.minorDigits(), HALF_EVEN)` (Excel stores doubles; this absorbs `0.30000000000000004`).
  String cell → locale-tolerant parse (below). Debit/credit columns or sign → `Direction`; the `Money` is the
  absolute value. Zero → `Rejected(ZERO_AMOUNT)`.

**Locale-tolerant amount text** (one private method; table-tested):
strip spaces, NBSP ` `, narrow NBSP ` `, apostrophes; `(x)` or leading/trailing `-` = negative;
if both `,` and `.` occur, the **last** one is the decimal separator; if only one kind occurs once and is
followed by exactly 3 digits, it is grouping (`1,500` → 1500); otherwise decimal (`9,99` → 9.99). Then
`new BigDecimal(normalized)`; failure → `Rejected(UNPARSEABLE_AMOUNT)`.

## GenericCsvParser (Commons CSV)

- Charset: strict UTF-8 decoder (`CodingErrorAction.REPORT`); malformed → `UnsupportedFormatException("Save the
  CSV as UTF-8")`. Armenian/Russian text must survive.
- **BOM:** if the first char is `﻿`, drop it (Excel writes it; otherwise the first header is `"﻿Date"`
  and never matches).
- **Delimiter:** sniff from the header line — count `,` `;` `\t` outside quotes, pick the max (European Excel
  exports use `;`).
- Format: `CSVFormat.DEFAULT.builder().setDelimiter(d).setHeader().setSkipHeaderRecord(true).setTrim(true)
  .setIgnoreEmptyLines(true).get()` (`build()` is deprecated in recent Commons CSV — verify against the
  version in the pom). Look columns up by normalized header name, not index.
- `record.getRecordNumber()` + header offset = the row number reported to the user. A record with the wrong
  column count → `Rejected(MALFORMED_ROW)`, not an exception.

## Row outcomes

```java
public sealed interface RowResult permits Imported, Duplicate, Rejected {
    int rowNumber();
}
public record Imported(int rowNumber, String transactionId) implements RowResult { }
public record Duplicate(int rowNumber, String transactionId) implements RowResult { }
public record Rejected(int rowNumber, RejectReason reason) implements RowResult { }

public enum RejectReason {
    MISSING_DATE, UNPARSEABLE_DATE, UNPARSEABLE_AMOUNT, ZERO_AMOUNT, TOO_MANY_DECIMALS,
    UNSUPPORTED_CURRENCY, MISSING_DESCRIPTION, MALFORMED_ROW
}
```

A bad row is **data, not an exception**. Reasons carry the column, never the cell value (no statement content
in tool output beyond what tools are meant to show). Fold exhaustively:

```java
for (RowResult result : results) {
    switch (result) {
        case Imported imported -> added++;
        case Duplicate duplicate -> duplicates++;
        case Rejected rejected -> rejections.add(rejected);
    }
}
```

Invariant (assert in tests): `added + duplicates + rejected == data rows in the file`.

## Deterministic id and dedupe

```
id = hex(SHA-256(date ISO ␟ normalizedDescription ␟ amount.toPlainString() ␟ currency ␟ occurrenceIndex))
```

- `␟` = `\u001F` (unit separator) so `"AB"+"C"` ≠ `"A"+"BC"`. `normalizedDescription` = masked, trimmed,
  whitespace-collapsed (the same text that is stored). `amount` at the currency's scale.
- **Occurrence index** = how many earlier rows *in this file* had the same `(date, description, amount,
  currency)`. Two identical 1,200 AMD coffees on one day are two real purchases; without the index the second
  hashes equal to the first and is silently dropped as a "duplicate". With it they are `#0` and `#1`, and
  re-importing the file (or an overlapping export listing the same day in full) reproduces both ids.
  Limitation: an export that cuts a day in half can shift indexes — accept; document in the summary if seen.
- Direction is not in the hash per CLAUDE.md; changing the recipe re-ids all history — grill first.
- The repository inserts with `ON CONFLICT(id) DO NOTHING` and returns the inserted ids; ids not returned →
  `Duplicate`. Never "check then insert" in the service.

**`import_log`**: file name, `file_hash` (SHA-256 of the bytes, computed while reading), counts, imported-at.
A matching hash means "this exact file was imported before" — still run (it is idempotent and yields
`added = 0`), and surface `previouslyImportedOn` in the summary so Claude can say so.

## Merchant extraction & card masking (pure functions in `domain`)

Bank descriptions look like `POS 4578****1234 SAS SUPERMARKET YEREVAN AM`.

1. **Mask first, in the parser, before anything stores or logs the text.** Any 12–19 digit run (spaces/dashes
   allowed) → `****` + last 4. Already-masked tokens (`4578****1234`) → `****1234`. **Never store a full PAN**,
   not in `description`, `merchant`, logs or test fixtures.
2. Merchant = description minus operation prefixes (`POS`, `ATM`, `E-COM`, `PURCHASE`, …), masked-card tokens,
   and trailing location tokens (city + ISO country) → uppercase, whitespace-collapsed: `SAS SUPERMARKET`.
   Prefix/location lists are named constants or config, not inline literals.
3. Empty after stripping → merchant = normalized description (never blank).

Table-test both with real-shaped (fake) strings, including Armenian and Cyrillic text.

## Adding a new bank (checklist)

1. `grill-me` — get a sample export (anonymized) and the column semantics (sign vs debit/credit, date format).
2. Add a matching generator to `mockdata` (fixed seed) — it is the fixture.
3. New `XxxParser implements StatementParser` in `adapter-parser`; `supports` on a required header set
   **distinct** from existing parsers (add a test that no two parsers accept the same header).
4. Register it in `ParserFactory` (server wiring order: specific first, generic CSV last).
5. Tests: happy path, every `RejectReason`, BOM/delimiter/locale variants, re-import adds 0.
6. Update the `UnsupportedFormatException` "Supported:" list and the `import_statement` description.

## Common mistakes

- Choosing the parser by extension; matching headers by index.
- Throwing on a bad row (aborts the whole import) instead of `Rejected`.
- Evaluating formulas; `cell.toString()` for amounts; `new BigDecimal(double)`.
- Logging descriptions/amounts at INFO; echoing cell values in reasons.
- Dedupe by `(date, amount)` or by querying first; forgetting the occurrence index.
- Storing the unmasked description "just for now".
