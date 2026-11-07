---
name: sqlite-jdbcclient
description: >-
  Invoke EVERY TIME any SQL, JdbcClient call or repository is written or
  changed in fin-track-mcp (adapter-persistence) — mandatory, not
  optional. Covers Spring JdbcClient over the xerial sqlite-jdbc driver:
  named parameters only (never string-concatenated SQL), RowMapper /
  record mapping, integer minor units <-> BigDecimal conversion at the
  repository boundary, idempotent import with INSERT ... ON CONFLICT DO
  NOTHING (vs INSERT OR IGNORE), batch inserts in ONE transaction,
  aggregations per month / category / merchant done in SQL (SUM, GROUP BY,
  COALESCE), indexes, SQLite pragmas (WAL, busy_timeout, foreign_keys,
  transaction_mode=IMMEDIATE), single-writer concurrency and "database is
  locked" (SQLITE_BUSY), the DataSource built from a file path in
  @ConfigurationProperties, and translating SQLException /
  DataAccessException into a persistence-port exception. Keywords: SQLite,
  JdbcClient, JdbcTemplate, NamedParameterJdbcTemplate, batchUpdate,
  RowMapper, TransactionTemplate, Hikari, sqlite-jdbc, repository,
  TransactionRepository, category_overrides, import_log, upsert, SUM,
  GROUP BY, minor units, amount_minor.
---

# SQLite + Spring JdbcClient (adapter-persistence)

Exact versions: check `pom.xml`. The Spring Boot BOM manages `sqlite-jdbc` (`org.xerial:sqlite-jdbc`),
HikariCP, Flyway and Spring Framework — never pin them yourself.

## Overview

All SQL lives in **`adapter-persistence`** (package `com.artyomh.finance.adapter.persistence.<table-group>`,
e.g. `…persistence.transaction`, `…persistence.category`, `…persistence.importlog` — see `package-layout`).
It implements the **port interfaces from `application`** (`TransactionRepository`, and whatever
override / import-log ports exist) with plain classes over a `JdbcClient`. Nothing else in the repo
touches JDBC: the application layer sees ports, domain types and its own exceptions only; the MCP
server only **wires** the repositories as beans (CLAUDE.md: Spring beans are declared in the server
module).

**Core principle:** the schema is owned by Flyway (`flyway-migrations`); this skill starts at "the
table exists". One direction:

```
Flyway migration (.sql)  →  SQLite file  →  JdbcClient SQL in a *Repository  →  domain records via the port
```

**Decision framework — apply to every query:** **correctness → safety → speed, in that order.** A
faster query that can double-count a transaction or round a dram away loses to a slower correct one;
between equally correct options pick the safer (named params, immutable records, one transaction);
only then optimize. **Always prefer one set-based statement over row-by-row work, and aggregate in SQL,
not in Java loops.**

**Living skill:** when real work reveals a better, faster or safer pattern than written here — verified,
not guessed — update this file in the same change.

## When to use / when not

**Use when:** writing or reviewing any SQL or `JdbcClient` call; adding a repository method or port
implementation; wiring the SQLite `DataSource`; debugging `SQLITE_BUSY` / "database is locked";
mapping rows to domain records; translating persistence failures.

**Not for:** creating or changing tables/indexes (→ `flyway-migrations`); how `Money` rounds or converts
USD→AMD (→ `money-and-fx`; this skill only stores what the domain hands it); dedupe-hash rules and
`RowOutcome` folding (→ `statement-import`); which category wins (→ `categorization-rules`); test layout
(→ `spring-boot-testing`).

## Quick reference

| Task | How |
|---|---|
| Driver | `org.xerial:sqlite-jdbc` (runtime) + `spring-boot-starter-jdbc` in persistence/server — BOM-managed |
| Client | `JdbcClient` (`org.springframework.jdbc.core.simple.JdbcClient`), constructor-injected; `JdbcClient.create(dataSource)` in tests |
| Parameters | **named only**: `.sql("... WHERE month = :month").param("month", month)` (§2) |
| Zero-or-one row | `.query(mapper).optional()` — never `single()` on a maybe-missing row |
| Exactly one row | `.query(Long.class).single()` (e.g. `COUNT(*)`) |
| Many rows | `.query(mapper).list()` |
| Row → record | explicit `RowMapper` lambda / private static method (§3); `query(Record.class)` only for flat, same-named, primitive-typed rows |
| Money in/out | `long` minor units in SQL, `BigDecimal` in Java — convert **in the repository** (§4) |
| Idempotent insert | `INSERT ... ON CONFLICT (id) DO NOTHING`; affected rows 0 = duplicate (§5) |
| Many inserts | `NamedParameterJdbcTemplate.batchUpdate(sql, SqlParameterSource[])` inside **one** `TransactionTemplate` (§6) |
| Totals per month/category | `SUM(...) ... GROUP BY` in SQL, `COALESCE(SUM(x), 0)` (§7) |
| Pragmas | in the JDBC URL: `journal_mode=WAL&busy_timeout=5000&foreign_keys=true&transaction_mode=IMMEDIATE` (§8) |
| DB file path | `@ConfigurationProperties` record in the server; create parent dir before connecting (§9) |
| Failures | catch `DataAccessException` at the repository, rethrow the domain `StorageException` kind (§10) |

## 1. Repository shape

A repository is a **plain class** implementing an application port, declared as a bean in the server's
`FinanceBeans` (an `@Repository` annotation is tolerated but not preferred — `hexagonal-architecture`), constructor injection, `private final` fields, SQL as `private static
final String` constants named for what they do.

```java
package com.artyomh.finance.adapter.persistence.transaction;

public final class SqliteTransactionRepository implements TransactionRepository {

    private static final String FIND_BY_ID = """
        SELECT id, date, description, merchant, direction, amount_minor, currency,
               amount_amd_minor, exchange_rate, category, source_file
        FROM transactions
        WHERE id = :id
        """;

    private final JdbcClient jdbc;

    public SqliteTransactionRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<Transaction> findById(TransactionId id) {
        try {
            return jdbc.sql(FIND_BY_ID)
                .param("id", id.value())
                .query(SqliteTransactionRepository::toTransaction)
                .optional();
        } catch (DataAccessException e) {
            throw new StorageException("Could not read transactions from the local database.", e);
        }
    }
}
```

(Type and method names are illustrative — match what `domain` / `application` actually
define; `StorageException` is the storage kind of the domain's `FinanceException` hierarchy, see
`hexagonal-architecture`.)

- **Select explicit columns**, never `SELECT *` — a later migration adding a column must not change
  mapping behaviour.
- **One repository per aggregate / table group** (`transactions`, `category_overrides`, `import_log`),
  each implementing its port. A query that joins two tables lives with the port that owns the question.
- Text blocks for SQL; no SQL in services, tools or tests outside the persistence module (tests may run
  verification SQL — see `spring-boot-testing`).

## 2. Named parameters only — never build SQL from strings

```java
// correct
jdbc.sql("SELECT ... FROM transactions WHERE month = :month AND category = :category")
    .param("month", month.toString())
    .param("category", category.name())
    ...

// WRONG — injection hole and plan-cache miss; a description containing ' breaks it
jdbc.sql("SELECT ... WHERE merchant = '" + merchant + "'")
```

- Every value goes through `:name` + `.param(..)`, or `.paramSource(record)` when a record's accessor
  names equal the parameter names. No positional `?` either — named params survive column reordering.
- **Optional filters** (`search_transactions(query, month, category)`): bind every parameter and let SQL
  ignore the absent ones — `(:month IS NULL OR month = :month)` — instead of concatenating `WHERE`
  fragments. Identifiers (column names, sort direction) are **never** user input: pick them from an
  enum-to-constant `switch`.
- `LIKE` search: bind the pattern, escape `%`/`_` in user text, and declare the escape:
  `description LIKE :pattern ESCAPE '\'`. Use `LOWER(...)` on both sides or `COLLATE NOCASE` for
  case-insensitivity (SQLite's `LIKE` is ASCII-case-insensitive only — Armenian/Cyrillic text is not).
- Enum values are bound as `enum.name()` and read back with `Enum.valueOf` — the migration's `CHECK`
  constraint lists the same names (`flyway-migrations`).
- Bind `LocalDate` as its ISO string (`date.toString()`), never a `java.sql.Date` — sqlite-jdbc would
  store epoch millis by default and `month`/`date` comparisons would silently break.

## 3. Mapping rows to records

Domain types are immutable records; map them in a **private static method** of the repository (one
place per row shape, readable, testable through the repository test):

```java
private static Transaction toTransaction(ResultSet rs, int rowNum) throws SQLException {
    Currency currency = Currency.valueOf(rs.getString("currency"));
    return new Transaction(
        new TransactionId(rs.getString("id")),
        LocalDate.parse(rs.getString("date")),
        rs.getString("description"),
        rs.getString("merchant"),
        Direction.valueOf(rs.getString("direction")),
        Money.ofMinorUnits(rs.getLong("amount_minor"), currency),
        Money.ofMinorUnits(rs.getLong("amount_amd_minor"), Currency.AMD),
        new BigDecimal(rs.getString("exchange_rate")),
        Category.valueOf(rs.getString("category")),
        rs.getString("source_file"));
}
```

- **`query(Some.class)`** (implicit `SimplePropertyRowMapper`) is fine only for flat projection records
  whose components are plain `String`/`long`/`int` and whose names match the column aliases
  (`SELECT category, SUM(amount_amd_minor) AS totalMinor ...`). It cannot build `Money`, enums from
  `CHECK`ed text with your rules, or `LocalDate` from text reliably — use a mapper then.
- Nullable columns: `rs.getLong` returns `0` for `NULL` — use `rs.getObject("x", Long.class)` or a
  `NOT NULL` column. Prefer `NOT NULL` in the schema.
- Return domain records or application result records (`CategoryTotal`, `MonthSummary`, …) — **never**
  `Map<String, Object>`, `ResultSet` or a persistence-only mirror record (CLAUDE.md bans mirroring
  records). A private row record inside the repository is acceptable when one query's columns don't
  match any domain type and are immediately converted.

## 4. Minor units ↔ BigDecimal — at the repository boundary only

SQLite has **no DECIMAL**: a `NUMERIC`/`REAL` column stores IEEE doubles and `0.1 + 0.2` drifts. Money is
stored as **`INTEGER` minor units** (`9.99 USD → 999`); the repository is the only place that converts.

```java
// write — Money is already normalized to currency.minorDigits(); longValueExact fails loudly on overflow
.param("amountMinor", tx.money().toMinorUnits())
.param("currency", tx.money().currency().name())

// read
Money money = Money.ofMinorUnits(rs.getLong("amount_minor"), Currency.valueOf(rs.getString("currency")));
```

- Use the domain's `Money.toMinorUnits()` / `Money.ofMinorUnits(..)` and `Currency.minorDigits()`
  (`money-and-fx` owns scale and rounding; AMD has 2 minor digits per ISO 4217: `1500 AMD → 150000`).
  Never re-implement the conversion in a repository.
- The conversion is exact or throws (`setScale(.., UNNECESSARY)`, `longValueExact()`): a mis-scaled
  amount fails instead of becoming a silently wrong total. Rounding is a **domain** decision made before
  the value reaches the repository.
- **`exchange_rate` is `TEXT`** holding `BigDecimal.toPlainString()` — read back with
  `new BigDecimal(text)`. Never `REAL`; never `toString()` (scientific notation like `4E+2`).
- Never `rs.getDouble`, `rs.getBigDecimal` on a money column, or `double` anywhere on the path.
- Amounts are **positive**; `direction` says in/out (CLAUDE.md). Signed totals are computed in SQL with
  `CASE direction WHEN 'INCOME' THEN amount_amd_minor ELSE -amount_amd_minor END` when a net figure is
  needed.

## 5. Idempotent import — `ON CONFLICT (id) DO NOTHING`

The transaction id is the domain's dedupe hash (`statement-import`), stored as `id TEXT PRIMARY KEY`.
Re-importing a file must add nothing:

```sql
INSERT INTO transactions (id, date, month, description, merchant, direction, amount_minor, currency,
                          amount_amd_minor, exchange_rate, category, source_file)
VALUES (:id, :date, :month, :description, :merchant, :direction, :amountMinor, :currency,
        :amountAmdMinor, :exchangeRate, :category, :sourceFile)
ON CONFLICT (id) DO NOTHING
```

- Affected rows **1 → `Imported`, 0 → `Duplicate`** — the repository reports which ids were new; the
  application folds that into `RowOutcome`s / `ImportSummary`.
- **Prefer `ON CONFLICT (id) DO NOTHING` over `INSERT OR IGNORE`.** `OR IGNORE` also swallows `NOT NULL`
  and `CHECK` violations — a row with an invalid `direction` would be silently counted as a
  "duplicate". `ON CONFLICT (id)` ignores only the uniqueness conflict on the key you name; everything
  else still fails loudly.
- Upsert for overrides (`set_category` is idempotent too):
  ```sql
  INSERT INTO category_overrides (merchant, category, updated_at)
  VALUES (:merchant, :category, :updatedAt)
  ON CONFLICT (merchant) DO UPDATE SET category = excluded.category, updated_at = excluded.updated_at
  ```
  `updated_at` comes from an injected `Clock` / the caller — not `CURRENT_TIMESTAMP` — so tests are
  deterministic.

## 6. Batch inserts in ONE transaction

SQLite in autocommit mode makes **every statement its own transaction with an fsync** — 2,000 rows can
take seconds and a crash halfway leaves half an import. An import is one unit: all rows + its
`import_log` row commit together or not at all.

`JdbcClient` (Spring Framework 7.0) has **no batch method** — for batches use
`NamedParameterJdbcTemplate.batchUpdate` (same named-parameter SQL), and wrap the unit in a
`TransactionTemplate`. Both are built from the same `DataSource`, so they share the transaction.

```java
public final class SqliteTransactionRepository implements TransactionRepository {

    private final JdbcClient jdbc;
    private final NamedParameterJdbcOperations batchJdbc;
    private final TransactionOperations tx;

    public SqliteTransactionRepository(JdbcClient jdbc, NamedParameterJdbcOperations batchJdbc,
                                       TransactionOperations tx) { ... }

    @Override
    public Set<TransactionId> saveNew(ImportBatch batch) {
        try {
            return tx.execute(status -> insertBatch(batch));
        } catch (DataAccessException e) {
            throw new StorageException("Could not save the imported transactions; nothing was written.", e);
        }
    }

    private Set<TransactionId> insertBatch(ImportBatch batch) {
        List<Transaction> rows = batch.transactions();
        int[] counts = batchJdbc.batchUpdate(INSERT_TRANSACTION, rows.stream()
            .map(SqliteTransactionRepository::params)
            .toArray(SqlParameterSource[]::new));
        jdbc.sql(INSERT_IMPORT_LOG).paramSource(...).update();
        return newIds(rows, counts);
    }
}
```

- Because the application layer has **no Spring** (no `@Transactional` in services), the **transaction
  boundary lives in the repository method that represents one atomic unit** (`saveAll` is one SQLite
  transaction — `hexagonal-architecture`). Shape the port so one call = one unit (e.g. rows + their
  `import_log` entry) rather than exposing begin/commit.
- `TransactionOperations` (the interface of `TransactionTemplate`) is injected; the server wires
  `new TransactionTemplate(new DataSourceTransactionManager(dataSource))` or Boot's auto-configured one.
- sqlite-jdbc returns one update count per batch entry; **assert it in the repository test** (import
  the same fixture twice → second run reports zero new ids). If a driver ever returns
  `Statement.SUCCESS_NO_INFO`, fall back to `SELECT id FROM transactions WHERE id IN (:ids)` before
  inserting.
- Chunk very large `IN (:ids)` lists to ≤ 500 values (SQLite's bound-parameter limit is high in modern
  builds, but keep statements small). A statement export is hundreds of rows — no chunking needed for
  the batch itself.
- **No loop of single `update()` calls** outside a transaction. Inside one transaction a loop is merely
  slower; still prefer `batchUpdate`.

## 7. Aggregations in SQL

Tools answer questions like "spending by category in 2026-10" — do the arithmetic in the database, in
integer minor units, and return small result records.

```sql
-- spending_by_category(month)
SELECT category, COALESCE(SUM(amount_amd_minor), 0) AS total_minor, COUNT(*) AS tx_count
FROM transactions
WHERE month = :month AND direction = 'EXPENSE'
GROUP BY category
ORDER BY total_minor DESC, category

-- top_merchants(month, limit)
SELECT merchant, SUM(amount_amd_minor) AS total_minor, COUNT(*) AS tx_count
FROM transactions
WHERE month = :month AND direction = 'EXPENSE'
GROUP BY merchant
ORDER BY total_minor DESC, merchant
LIMIT :limit

-- list_months()
SELECT DISTINCT month FROM transactions ORDER BY month
```

- **`SUM`, never `TOTAL`** — `TOTAL()` returns a floating-point value; `SUM` over `INTEGER` stays an
  integer (and raises on overflow instead of losing precision). `SUM` over zero rows is `NULL` →
  `COALESCE(..., 0)` where a scalar total is read.
- Always add a **deterministic tie-breaker** to `ORDER BY` (`, category` / `, merchant`) — tool output
  must be stable across runs and tests.
- Totals are in **AMD minor units** (`amount_amd_minor`) — never `SUM(amount_minor)` across currencies
  (`Money` must never add USD to AMD; the SQL must not either).
- Bind `LIMIT :limit` from a validated value (the tool clamps it; the max lives in config/constants).
- Effective category with user overrides: if overrides apply at read time, `LEFT JOIN
  category_overrides o ON o.merchant = t.merchant` and group by `COALESCE(o.category, t.category)`; if
  `set_category` rewrites stored rows, do the override upsert and the `UPDATE transactions SET category`
  in **one** transaction. Which model applies is a `categorization-rules` decision — follow it, keep
  every query consistent with it.
- Month comparison: one query with `WHERE month IN (:monthA, :monthB) GROUP BY month, category`, not two
  round trips stitched in Java.

## 8. SQLite pragmas & the single writer

SQLite allows **many readers, one writer** per database file. Set connection pragmas in the **JDBC
URL** so every pooled connection gets them (sqlite-jdbc maps URL query parameters onto `SQLiteConfig`
pragmas):

```
jdbc:sqlite:/Users/me/.fin-track-mcp/finance.db?journal_mode=WAL&busy_timeout=5000&foreign_keys=true&transaction_mode=IMMEDIATE&synchronous=NORMAL
```

| Pragma | Why |
|---|---|
| `journal_mode=WAL` | Readers don't block the writer and vice versa. Persistent in the file; leaves `-wal`/`-shm` side files (gitignore `*.db*`) |
| `busy_timeout=5000` | Wait up to 5 s for a lock instead of failing instantly with `SQLITE_BUSY` ("database is locked") |
| `foreign_keys=true` | FK enforcement is **off by default and per connection** — must be set on every connection |
| `transaction_mode=IMMEDIATE` | `BEGIN IMMEDIATE` takes the write lock up front. A `DEFERRED` transaction that reads then writes can fail with `SQLITE_BUSY` **without** honouring `busy_timeout` when another writer got in first |
| `synchronous=NORMAL` | Safe with WAL (durable across app crashes; may lose the last commit on power loss) and much faster than `FULL` |

- The server is single-user over STDIO, so contention is rare — but Claude Desktop can call tools
  concurrently and the user may run a second process (MCP Inspector) on the same file. Keep write
  transactions **short** (no file parsing inside the transaction: parse first, then write once).
- A **small Hikari pool** (e.g. `maximum-pool-size: 4`) is plenty; WAL readers run in parallel and writes
  serialize on the file lock. Don't set the pool to 1 without checking Flyway startup still gets the
  connections it needs.
- Never open the database from two different `DataSource`s in one process.

## 9. DataSource from `@ConfigurationProperties`

The file path is configuration, not code. The properties record and the beans live in
`mcp-server` (`com.artyomh.finance.mcp.config`, beans in `FinanceBeans` or a sibling `PersistenceConfig`); persistence classes only receive a `JdbcClient` /
`NamedParameterJdbcOperations` / `TransactionOperations`.

```java
@ConfigurationProperties("finance.storage")
public record StorageProperties(@NotNull Path path, @DefaultValue("5000") int busyTimeoutMillis) {}
```

```yaml
finance:
  storage:
    path: ${user.home}/.fin-track-mcp/finance.db
```

```java
@Configuration
@EnableConfigurationProperties(StorageProperties.class)
class PersistenceConfig {

    @Bean
    DataSource dataSource(StorageProperties properties) throws IOException {
        Files.createDirectories(properties.path().toAbsolutePath().getParent());
        HikariDataSource dataSource = new HikariDataSource();
        dataSource.setJdbcUrl(jdbcUrl(properties));
        dataSource.setMaximumPoolSize(MAX_POOL_SIZE);
        return dataSource;
    }

    @Bean
    TransactionRepository transactionRepository(JdbcClient jdbc, NamedParameterJdbcOperations batchJdbc,
                                                TransactionOperations tx) {
        return new SqliteTransactionRepository(jdbc, batchJdbc, tx);
    }
}
```

- sqlite-jdbc **creates the file but not missing parent directories** — create them first, or the first
  connection fails with a confusing "path to … does not exist".
- Boot auto-configures `JdbcClient`, `NamedParameterJdbcTemplate`, the `DataSourceTransactionManager`,
  `TransactionTemplate` and Flyway from that `DataSource` — check which beans the Boot version provides
  before declaring your own (`spring-boot-starter-jdbc`).
- Flyway runs on startup against the same `DataSource` (`flyway-migrations`), so the schema exists before
  the first tool call.
- Never default the path into the working directory or the jar directory: Claude Desktop launches the
  jar with an arbitrary cwd. Use an absolute default under `${user.home}`.
- Never log the full path at INFO if it contains the user's name; never log row contents (PII).

## 10. Exception translation

Spring translates `SQLException` to `DataAccessException` subtypes; **that is still a framework
exception and must not cross the module boundary** (CLAUDE.md). Every public repository method catches
`DataAccessException` and rethrows the domain's **`StorageException`** kind (`com.artyomh.finance.domain.exception`,
or a persistence subclass of it in `…adapter.persistence.exception`). The cause may be attached for the
log file, but the message is user copy and the MCP translator never forwards causes (`mcp-server-tools`):

| Underlying failure | Typical Spring type | Rethrow as |
|---|---|---|
| `SQLITE_BUSY` / "database is locked" after `busy_timeout` | `CannotAcquireLockException` / `PessimisticLockingFailureException` | `StorageException` ("The finance database is busy (another process has it open). Retry in a moment.") |
| File unreadable / not a database / disk full | `DataAccessResourceFailureException`, `UncategorizedSQLException` | `StorageException` naming the configured `finance.storage.path` setting |
| `CHECK` / `NOT NULL` violation | `DataIntegrityViolationException` | a bug, not user error — `StorageException`, **no row values** in the message |

- If `StorageException` has no cause-taking constructor (the hierarchy is message-only by design), log the
  `DataAccessException` at WARN to the log file first, then throw — the cause must survive somewhere for
  debugging, but never on the tool-error path.
- The message names the operation ("Could not save imported transactions"), never descriptions,
  merchants or amounts (PII), never the raw SQLite message.
- Duplicates are **not** exceptions — they are the `ON CONFLICT DO NOTHING` path (§5).
- The MCP layer maps these to actionable tool errors (`mcp-server-tools`).
- `optional()` on no row is not an error; "month with no data" is decided by the service, which then
  lists available months.

## Common mistakes

- **String-concatenated SQL** or positional `?` — named parameters only.
- **`REAL`/`NUMERIC`/`double` for money**, `TOTAL()`, `SUM(amount_minor)` across currencies.
- **`INSERT OR IGNORE`** hiding constraint violations as "duplicates".
- **Autocommit batch** — an import split over hundreds of transactions; half-written on failure.
- **`foreign_keys` set once via `PRAGMA` on one connection** — the next pooled connection doesn't have it.
- **Deferred write transactions** failing with "database is locked" despite `busy_timeout`.
- **Binding `LocalDate`/`java.sql.Date` directly** — stored as epoch millis, breaks text comparisons.
- **`SELECT *`**, missing `ORDER BY` tie-breakers, Java-side `stream().collect(groupingBy(...))` totals.
- **Letting `DataAccessException` escape** the repository, or putting SQL text/row values in messages.
- **`@Transactional` on adapter or application classes** — transactions via `TransactionOperations` inside
  the repository; beans are declared in the server (`FinanceBeans`).

## References

- `references/repository-example.md` — a complete `SqliteTransactionRepository` (insert batch,
  aggregation, mapping, translation) and its `@TempDir` repository test.
