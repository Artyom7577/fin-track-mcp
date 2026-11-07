---
name: statement-import
description: >-
  Invoke BEFORE writing or changing statement import in fin-track-mcp: the StatementParser port,
  StatementFileParser (file checks, format choice), the format readers (XlsxFormatReader with Apache
  POI, CsvFormatReader with Commons CSV), the evoca layout (header aliases, row rules, AmountText),
  StatementReadException / ReadFailure, ParserLimits, ImportService, deterministic transaction ids /
  dedupe / occurrence index, import_log and file_hash, RowOutcome and ImportSummary, merchant
  extraction and card-number masking, date/number/locale parsing, or adding a new bank format.
  Triggers: "import", "parser", "parse", "xlsx", "Excel", "POI", "csv", "Commons CSV", "BOM",
  "delimiter", "statement", "Evoca", "bank format", "new bank", "StatementFileParser", "dedupe",
  "duplicate", "idempotent", "transaction id", "hash", "RowOutcome", "rejected row", "merchant",
  "PAN", "card number", "import_log".
---

# Statement import

Goal: any supported bank export → categorized, AMD-converted `Transaction`s, **idempotently**, with every row
accounted for. See `money-and-fx` (amounts), `categorization-rules` (category), `sqlite-jdbcclient`
(`saveAll`), `mcp-server-tools` (`import_statement` path validation).

## The sequence (deck slide 16)

```
Claude ─import_statement(filePath)─▶ ImportTools ─importFile(path)─▶ ImportService
  ImportService ─parse(path)──────────▶ StatementParser (= StatementFileParser)
                ◀─ParseResult(rows, problems, warnings)
  for each row: mask → id (hash) → merchant → Transaction → Categorizer → FX (amountAmd, rate)
  ImportService ─saveNew(txs)─────────▶ TransactionRepository   (INSERT … ON CONFLICT(id) DO NOTHING; returns inserted ids)
                ◀─ImportSummary(added, duplicates, rejected[], warnings[])  ─▶ JSON result to Claude
```

`ImportService` lives in `application`, plain constructor, no Spring. One `saveNew` = one SQLite
transaction (the repository owns it).

## The port

```java
// application
public interface StatementParser {
    ParseResult parse(Path file);
}
public record ParseResult(SequencedCollection<ParsedRow> rows, Collection<RowProblem> problems,
                          Collection<FileWarning> warnings) { }
public record ParsedRow(int lineNumber, LocalDate date, String description, Direction direction, Money money) { }
public record RowProblem(int lineNumber, String reason) { }
public record FileWarning(Code code, String message, int rowCount) { }   // ROWS_AFTER_TOTALS, SHEET_NOT_READ
```

The application never learns the file format or the bank. A bad **row** is a `RowProblem` (rejected = broken
rows only). Data the file holds but the import skips **as a whole** — the lines after a totals line, a sheet
after the first — is a `FileWarning` with the number of rows it covers; it is passed through to
`ImportSummary.warnings` and never counted as rows, so `added + duplicates + rejected == data rows` holds. Only a
file that cannot be read at all throws `StatementReadException` (`adapter-parser/exception`, a `FinanceException`
whose `code()` is its `ReadFailure`: `FILE_NOT_READABLE` (missing or unreadable path), `FILE_DAMAGED` (POI could
not open or read the workbook), `FILE_EMPTY`, `FILE_TOO_LARGE`, `LEGACY_XLS`, `PDF_NOT_SUPPORTED`, `WEB_PAGE`,
`EXTENSION_MISMATCH`, `HEADER_NOT_FOUND`, `NOT_UTF8`, `MALFORMED_CSV`, `TOO_MANY_ROWS`). Messages name the file
and say what to do; they never contain cell values or library messages.

## adapter-parser layout

```
adapter.parser
├── StatementFileParser   implements StatementParser — checks in order, picks a reader, hands lines to evoca
├── ParserLimits          (maxXlsxBytes, maxXlsxXmlBytes, maxCsvBytes, maxDataRows), DEFAULT; built from finance.import.*
├── format/               bank-agnostic, the only place POI and Commons CSV appear (ArchUnit)
│   ├── FormatReader      the one internal interface; read(file, isHeader) → Optional<SourceSheet>
│   ├── XlsxFormatReader, CsvFormatReader, DataLines (row limit while reading)
│   └── Cell (sealed: Text | Numeric | DateCell | Blank | Invalid), SourceLine, SourceSheet, UnreadSheet
├── evoca/                EvocaColumns (header aliases), EvocaRowMapper (row rules), AmountText
└── exception/            StatementReadException, ReadFailure
```

`format` never depends on `evoca`; neither depends on the root package; nothing depends on persistence, fx,
mcp or Spring (`ParserArchitectureTest`).

## File checks (StatementFileParser, in this order)

Each step reads only what it needs; every failure is a `StatementReadException`.

1. **Readable** regular file → else `FILE_NOT_READABLE`.
2. **Not empty**: 0 bytes → `FILE_EMPTY` "… is empty. Export the statement again."
3. **First bytes** (up to 512): `D0 CF 11 E0` (OLE2) → `LEGACY_XLS` "Old .xls format is not supported. Open it in
   Excel and save as .xlsx."; `%PDF` → `PDF_NOT_SUPPORTED` "PDF statements are not supported. Export as .xlsx or
   .csv."; `<html` / `<!doctype` after an optional UTF-8 BOM and whitespace → `WEB_PAGE` "This file is a web page,
   not a spreadsheet. Export as .xlsx or .csv."; `PK\3\4` → XLSX; anything else → CSV.
4. **Size for that format**: over `maxXlsxBytes` / `maxCsvBytes` → `FILE_TOO_LARGE` "File is 2.4 MB; the limit
   for .xlsx is 1.5 MB. Split the export by period." (MB = 1,000,000 bytes via `BigDecimal`, locale-free). The
   size check follows the sniff because the cap depends on the format.
5. **Extension** must agree, case-insensitive (`.xlsx` with zip bytes, `.csv` with text) → else
   `EXTENSION_MISMATCH`.
6. **Unpacked sheet size** (.xlsx only, in `XlsxFormatReader` before POI loads anything): the worksheet parts
   and `sharedStrings.xml` are inflated and counted (stopping one buffer past the limit, so a forged zip
   directory can't hide the real size); over `maxXlsxXmlBytes` → `FILE_TOO_LARGE`. This catches repetitive
   workbooks locally; POI's JVM-wide `ZipSecureFile` settings stay at their defaults.
7. **Header** found in the first 20 lines → else `HEADER_NOT_FOUND`, listing `EvocaColumns.expected()`.

## Header and end of data (evoca)

- A cell matches a column when, after NFKC + whitespace collapse + trim + `toLowerCase(Locale.ROOT)` and a split
  on `/`, **any part equals** an alias: Date `date ամսաթիվ дата`, Description `description նկարագրություն
  описание`, Debit `debit ելք расход дебет`, Credit `credit մուտք приход кредит`, Currency `currency արժույթ
  валюта` (all required), Balance `balance մնացորդ остаток` (optional, ignored). Order-free, extra columns
  ignored; a column matching twice (or one cell matching two columns) disqualifies the row.
- Blank lines (all cells empty or whitespace, NBSP included) are skipped anywhere.
- A **totals line** (date cell, or the first non-empty cell when the date is empty, starts with `total`,
  `ընդամենը`, `итого`, `всего`) ends the data. After it, totals and balance lines (`balance`, `մնացորդ`,
  `остаток`, `сальдо` as a word prefix) are ignored; any other non-blank lines give **one** `FileWarning`
  `ROWS_AFTER_TOTALS` "N rows after the totals line (from line L) were not imported" with `rowCount` N (a second
  account block never vanishes silently).
  Multi-section parsing is not supported yet.
- Line numbers are 1-based physical file lines (XLSX row index + 1; CSV: the line a record starts on).

## XLSX (XlsxFormatReader)

- `OPCPackage.open(file, PackageAccess.READ)` + `new XSSFWorkbook(pkg)`, released with `pkg.revert()` (closing a
  read-only package logs a warning). Any POI/`IOException`, the zip-bomb guard or an XML error (including a
  DOCTYPE/XXE attempt), also while cells are read → `FILE_DAMAGED` "… is damaged or not a real Excel file".
  POI's global `ZipSecureFile` settings stay at their defaults — they are JVM-wide statics.
- Only the first sheet is read; every other sheet with data → one `FileWarning` `SHEET_NOT_READ` "sheet 2
  ('USD') was not imported" with its non-blank row count. The position is 1-based; the name is file text that
  reaches Claude, so control and format characters are removed, card numbers masked, and it is cut to 40
  characters.
- The 1904 date system (old Mac files) is honoured by POI's `getLocalDateTimeCellValue`. A merged cell holds its
  value only in its first cell; the rest read as blank (a merged date over two rows → "missing date").
- Data lines get exactly the header's width (cells right of the header are never read).
- Cell types: `FORMULA` → the cached result type, **never evaluated**; date-formatted `NUMERIC` → `DateCell`;
  other `NUMERIC` → `Numeric(value, text from new DataFormatter(Locale.ROOT).formatRawCellContents)`; `STRING`
  → `Text` (whitespace-only → `Blank`); `BOOLEAN`/`ERROR` → `Invalid`.

## CSV (CsvFormatReader)

- `Files.readString` (strict UTF-8, malformed → `NOT_UTF8` "Save it as \"CSV UTF-8\""; no 1251 fallback); one
  leading BOM dropped; `\r\n`, `\n`, `\r` all fine.
- **Delimiter** = the first of `,` `;` `\t` under which the header is found (works below a title block).
- `CSVFormat.DEFAULT.builder().setDelimiter(d).setIgnoreEmptyLines(false).setTrailingData(true).get()` — empty
  lines are kept so line numbers stay exact; trailing data after a closing quote is kept, so the only structural
  error left is an **unclosed quote** → the whole file fails with `MALFORMED_CSV` "Unclosed quote starting at line
  N. Fix the file and import again." (a partial import would make the month quietly wrong).
- Fewer cells than the header, or non-blank cells beyond it → "row has 4 columns, header has 6".

## Row rules (EvocaRowMapper) — one problem per row, checks in this order

1. **Width** (above). 2. **Date**: `DateCell`; text strictly `dd.MM.uuuu` or `uuuu-MM-dd` (`ResolverStyle.STRICT`,
   `Locale.ROOT`) with an optional ` HH:mm[:ss]` ignored; slash formats are rejected (day and month can't be told
   apart) until a real export needs them; a non-date number → "date is a number, not a date". 3. **Description**
   (below). 4. **Currency**: trimmed, `toUpperCase(Locale.ROOT)`, exactly `AMD`/`USD`, else "unsupported currency;
   supported: AMD, USD". 5. **Amounts**:
   - Numeric cell: `BigDecimal.valueOf(double)` → `setScale(minorDigits, HALF_EVEN)`; if that moved the value by
     more than 0.000001 → "more than 2 decimal places in Debit column" (absorbs `0.30000000000000004`, catches a
     real `1234.567`).
   - Text (`AmountText`, locale-free): `.` is the only decimal point; digits group by threes with **spaces only**
     (space, NBSP, narrow NBSP; one kind per value). **Commas are never accepted** — `1,500` is 1500 in one export
     and 1.5 in another, a silent 1000× error. Letters, symbols, parentheses → "unreadable amount"; a leading `-`
     → "negative amount in Debit column"; more decimals than the currency allows → the decimals problem. Add
     comma grouping only with a real export as its test.
   - Exactly one of Debit/Credit positive → `EXPENSE`/`INCOME`. A zero beside a positive amount counts as empty
     (banks print `0.00` in the unused column). Both empty → "no amount in Debit or Credit"; both positive →
     "both Debit and Credit are filled"; only zeros → "amount is zero"; never guess a sign.
   - `BOOLEAN`/`ERROR` in any of these columns → "cell has an error value in X column".

Reasons name the column, never the cell value.

## Limits

`ParserLimits` is passed through constructors (the readers get `maxDataRows`, so `TOO_MANY_ROWS` "More than
50,000 rows; split the export by period" fires while reading). For .xlsx the **byte cap is the real memory
guard**: `XSSFWorkbook` loads the whole sheet before the row limit can fire.

Measured 2026-11-07 (JDK 25, POI 5.5.1), 50,000 default-scenario rows:

| | Size | Limit |
|---|---|---|
| .xlsx file | 1,348,141 bytes; parsing needs about 250 MB heap (fails at `-Xmx240m`, passes at `-Xmx256m`) | `maxXlsxBytes` 1,500,000 |
| .xlsx sheet XML unpacked | 11,458,803 (sheet) + 1,657 (shared strings) bytes | `maxXlsxXmlBytes` 23,000,000 (about 2×) |
| .csv file | 3,605,694 bytes | `maxCsvBytes` 7,200,000 (about 2×) |
| rows | 50,000 | `maxDataRows` 50,000 |

A repetitive 2.6 MB workbook (185,000 identical rows) unpacks to 40.7 MB and needed 512–768 MB heap before
the unpacked check existed; it is now rejected before POI loads it.
`StatementFileParserTest.fiftyThousandRowFilesFitUnderEveryLimit` keeps the caps aligned with the row limit.
SAX streaming is out of scope unless the limits must grow a lot.

**These are the only size limits.** `import_statement` validates the real path against the allowed folders
**before** calling the parser (and before any size check), and may pre-check sizes only with the same
`finance.import.*` values (see `mcp-server-tools`).

## Row outcomes

```java
public sealed interface RowOutcome {
    record Imported(Transaction transaction) implements RowOutcome { }
    record Duplicate(TransactionId id) implements RowOutcome { }
    record Rejected(RowProblem problem) implements RowOutcome { }
}
```

A bad row is **data, not an exception**. Reasons carry the column, never the cell value (no statement content
in tool output beyond what tools are meant to show). `ImportService` folds outcomes exhaustively (no `default`)
into `ImportSummary`.

Invariant (assert in tests): `added + duplicates + rejected == data rows in the file`.

## Deterministic id and dedupe

```
id = hex(SHA-256(date ISO ␟ maskedDescription ␟ direction ␟ amount.toPlainString() ␟ currency ␟ occurrenceIndex))
```

- `␟` = `\u001F` (unit separator) so `"AB"+"C"` ≠ `"A"+"BC"`. `normalizedDescription` = masked, trimmed,
  whitespace-collapsed (the same text that is stored). `amount` at the currency's scale.
- **Occurrence index** = how many earlier rows *in this file* had the same `(date, description, amount,
  currency)`. Two identical 1,200 AMD coffees on one day are two real purchases; without the index the second
  hashes equal to the first and is silently dropped as a "duplicate". With it they are `#0` and `#1`, and
  re-importing the file (or an overlapping export listing the same day in full) reproduces both ids.
  Limitation: an export that cuts a day in half can shift indexes — accept; document in the summary if seen.
- Direction is part of the hash (`TransactionId.of`); changing the recipe re-ids all history — grill first.
- The repository inserts with `ON CONFLICT(id) DO NOTHING` and returns the inserted ids; ids not returned →
  `Duplicate`. Never "check then insert" in the service.

**`import_log`**: file name, `file_hash` (SHA-256 of the bytes, computed while reading), counts, imported-at.
A matching hash means "this exact file was imported before" — still run (it is idempotent and yields
`added = 0`), and surface `previouslyImportedOn` in the summary so Claude can say so.

## Merchant extraction & card masking (pure functions in `domain`)

Bank descriptions look like `POS 4578****1234 SAS SUPERMARKET YEREVAN AM`.

1. **Normalize, then mask, in the parser, before anything stores or logs the text** (`EvocaRowMapper`):
   control characters (C0/C1, e.g. tab, line break) → space; Unicode format characters (category Cf: zero-width,
   bidi overrides) removed; whitespace runs (NBSP included) → one space; trim; then `CardNumberMasker.mask`: any
   **12–19 digit** run (Maestro can be 12) with optional single spaces or dashes between digits →
   `****` + last 4, so a number split by a tab or line break is still caught. Already-masked tokens
   (`4578****1234`) stay. Keep the original case; **no NFKC on descriptions** (it turns Armenian `և` into `եւ`,
   changing the text and the id). Empty after this → "missing description". `ImportService` masks again before
   hashing (idempotent). **Never store a full PAN**, not in `description`, `merchant`, logs or test fixtures.
   Known limitation: masking feeds the transaction id, so two different payments with the same date, amount,
   description and last four digits are distinct within one file (occurrence index), but the second is skipped
   as a duplicate if it arrives in a later import.
2. Merchant = description minus operation prefixes (`POS`, `ATM`, `E-COM`, `PURCHASE`, …), masked-card tokens,
   and trailing location tokens (city + ISO country) → uppercase, whitespace-collapsed: `SAS SUPERMARKET`.
   Prefix/location lists are named constants or config, not inline literals.
3. Empty after stripping → merchant = normalized description (never blank).

Table-test both with real-shaped (fake) strings, including Armenian and Cyrillic text.

## Test input

- **Round trip and malformed rows** come from `mockdata` (test scope: `Scenarios`, `StatementGenerator`, both
  writers into `@TempDir`): parsed rows equal the `GeneratedStatement` (balance ignored); each malformed row gives
  one problem at list position + 2 and the rows around it still parse. Mock XLSX uses real date and numeric
  cells; CSV is text, UTF-8 with BOM.
- **Hand-made** files cover what the writer never produces: CSV fixtures committed in
  `adapter-parser/src/test/resources/irregular/` (title block, totals then a USD block, semicolon, blank trailing
  lines, quoted line break, unclosed quote); XLSX shapes built at test time by `XlsxFixture` (typed cells,
  formulas with cached values, error cells, extra sheets, the 1904 date system, merged cells, a repetitive sheet
  over the unpacked cap) — a committed POI binary can't be reviewed. A near-Evoca header from another bank
  (signed Amount column, or "Transaction date"/"Details") must give `HEADER_NOT_FOUND`.
- **`irregular/libreoffice-saved.xlsx`** is a real spreadsheet program's file (inline strings, its own date
  formats) and must parse to the same rows as `title-block.csv`. Regenerate it only when the CSV changes, from
  `adapter-parser/src/test/resources/irregular/`, with the import pinned so the machine's locale doesn't matter
  (comma, `"`, UTF-8 = 76, start at line 1, column 1 as DD/MM/YY, column 5 as text, en-US = 1033):

  ```
  soffice --headless --infilter="CSV:44,34,76,1,1/4/2/1/3/1/4/1/5/2/6/1,1033" \
      --convert-to xlsx:"Calc Office Open XML" --outdir /tmp/lo title-block.csv
  cp /tmp/lo/title-block.xlsx libreoffice-saved.xlsx
  ```
- Wrong files (OLE2, HTML, renamed, empty, oversized) are raw bytes written in the test.
- Full card numbers are built at runtime (`"4578" + "1234".repeat(3)`), never a 16-digit literal.
- Results are asserted equal under `hy-AM` and `Asia/Yerevan`. An anonymized real export becomes one more
  committed fixture when available.

## Adding a new bank (checklist)

1. `grill-me` — get a sample export (anonymized) and the column semantics (sign vs debit/credit, date format).
2. Add a matching generator to `mockdata` (fixed seed) — it is the fixture.
3. New sibling package of `evoca` (e.g. `ameria`) with its header aliases and row rules, reusing `format`
   unchanged. Only when a second bank exists does a bank-layout interface pass the `abstraction-design` gate;
   `StatementFileParser` then tries layouts in order and needs a test that no two accept the same header.
4. Tests: happy path, every reason, BOM/delimiter/locale variants, re-import adds 0.
5. Update the "Supported:" text in `StatementFileParser` and the `import_statement` description.

## Common mistakes

- Trusting the extension alone; matching headers by index.
- Throwing on a bad row (aborts the whole import) instead of a `RowProblem`.
- Evaluating formulas; `cell.toString()` for amounts; `new BigDecimal(double)`; guessing a decimal comma.
- Logging descriptions/amounts at INFO; echoing cell values in reasons.
- Dedupe by `(date, amount)` or by querying first; forgetting the occurrence index.
- Storing the unmasked description "just for now".
