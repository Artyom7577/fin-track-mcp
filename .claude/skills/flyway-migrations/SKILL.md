---
name: flyway-migrations
description: >-
  Invoke for EVERY schema change in fin-track-mcp — creating, editing,
  ordering or troubleshooting SQLite migrations with Flyway: versioned (V__)
  or repeatable (R__) SQL under
  adapter-persistence/src/main/resources/db/migration; the V1__init
  schema (transactions, category_overrides, import_log); SQLite DDL limits
  (ALTER TABLE only renames / adds / drops columns — anything else is the
  12-step table-rebuild pattern), no DECIMAL (integer minor units, TEXT for
  rates and ISO dates), STRICT tables, CHECK constraints for enums
  (direction, currency, category), PRAGMA foreign_keys / defer_foreign_keys,
  WAL side files, indexes on month / merchant; spring-boot-starter-flyway
  wiring in mcp-server and spring.flyway.* (locations,
  clean-disabled, validate-on-migrate); diagnosing checksum mismatch, failed
  or pending migrations, "database is locked" during migrate; testing
  migrations on a @TempDir SQLite file. Never edit an applied migration —
  the user's own finance.db is production.
---

# Flyway Migrations (Spring Boot 4 + SQLite)

Exact versions: check `pom.xml` — Flyway, `sqlite-jdbc` and Spring Boot are BOM-managed; never pin them.

## Overview & core principle

Flyway is the **single source of truth for the database schema**. Every DDL change (tables, columns,
indexes, constraints, views, triggers) is a Flyway SQL migration applied in a deterministic order at
server startup. Nothing else creates or alters schema — not hand-run `sqlite3`, not code in a
repository, not a test.

The one rule that governs everything: **once a migration has been applied anywhere — including the
user's own `~/.fin-track-mcp/finance.db` — it is immutable. You never edit it; you add a new
migration.** Flyway records a checksum per applied file; editing one makes validation fail and the MCP
server refuse to start (Claude Desktop then just shows the server as failed). There is no "dev
database we can wipe": the local file holds months of imported statements and the user's
`set_category` corrections.

**`adapter-persistence` owns all tables and therefore all migrations**
(`adapter-persistence/src/main/resources/db/migration`); **`mcp-server`** owns the
runtime Flyway/DataSource config. See `references/setup-and-config.md`.

## This repo — rules that bite

- **Next version = highest existing version plus one** (`V2__…` after `V1__init.sql`). One owner, one
  directory, one `flyway_schema_history`; `out-of-order` stays `false`.
- **Money is `INTEGER` minor units** (`amount_minor`, `amount_amd_minor`), never `REAL`/`NUMERIC`/
  `DECIMAL` — SQLite has no decimal type; those affinities store IEEE doubles. Exchange rates are
  `TEXT` (`BigDecimal.toPlainString()`), dates are ISO `TEXT` (`2026-10-14`), months `TEXT` (`2026-10`),
  timestamps ISO-8601 `TEXT` with offset/`Z` (→ `money-and-fx`, `sqlite-jdbcclient`).
- **Every table is `STRICT`** — without it SQLite happily stores `'abc'` in an `INTEGER` column.
- **Closed sets are `CHECK`ed**: `direction IN ('EXPENSE','INCOME')`, `currency IN ('AMD','USD')`,
  `category IN (<the 16 Category names>)`. The lists must equal the Java enums' `name()`s. Adding an enum
  constant therefore needs a migration (a table rebuild — see §SQLite ALTER limits); that cost is
  deliberate for a fixed set.
- **Invariants live in the database too**: amounts `> 0` (direction carries the sign), counts `>= 0`,
  `month = substr(date, 1, 7)`. The database is the last line of defence against a bad import.
- **No PII in migrations** — never seed real merchants, descriptions or amounts. Reference data that is
  identical for every user (none in the MVP) is the only data a `V__` may insert.
- **Applied migrations are immutable** — never edit, rename or renumber one.

## When to use

- Any schema change (`CREATE`/`ALTER`/`DROP TABLE`, indexes, `CHECK`s, views, triggers).
- Configuring `spring.flyway.*`, a `FlywayMigrationStrategy`, or programmatic `Flyway` in tests.
- Diagnosing startup failure: checksum mismatch, failed migration, pending migration, locked database.

## When NOT to use

- Writing queries or repositories, pragmas on connections — **`sqlite-jdbcclient`**. This skill stops at
  "the schema is defined".
- Runtime DML that is application behaviour (importing rows, upserting an override) — application code.
- Test structure beyond migration tests — `spring-boot-testing`.

## Quick reference

| Concern | Answer |
|---|---|
| Migrations location | `adapter-persistence/src/main/resources/db/migration` (= default `classpath:db/migration`) |
| First file | `V1__init.sql` — `transactions`, `category_overrides`, `import_log` |
| Versioned file | `V<n>__<description>.sql`, e.g. `V2__add_transactions_note.sql` |
| Repeatable file | `R__<description>.sql` (views) — re-run when its checksum changes, after all versioned |
| Undo (`U__`) | paid editions only — **not used**; fix forward |
| Version ordering | sequential integers; never timestamps |
| Run at startup | automatic — Boot runs Flyway on the SQLite `DataSource` before the first tool call |
| Enable | `spring-boot-starter-flyway` in `mcp-server`; SQLite support is in `flyway-core` (no `flyway-database-*` module) |
| Never | `clean` — keep `spring.flyway.clean-disabled=true` (default); it would wipe the user's data |
| Column changes beyond add/rename/drop | table rebuild in one migration (§SQLite ALTER limits) |
| Test | plain Flyway on a `@TempDir` SQLite file (`references/jdbcclient-and-testing.md`) |

## Setup (summary — full detail in `references/setup-and-config.md`)

- `mcp-server/pom.xml`: `spring-boot-starter-flyway` (+ `spring-boot-starter-jdbc`,
  `org.xerial:sqlite-jdbc`). The migration `.sql` files arrive on the classpath inside the
  persistence jar.
- `adapter-persistence/pom.xml`: `org.xerial:sqlite-jdbc` and `org.flywaydb:flyway-core` with
  **test** scope, so repository tests migrate a temp file without Spring.
- `application.yaml` keeps Flyway defaults (`locations: classpath:db/migration`, `clean-disabled: true`,
  `validate-on-migrate: true`).

## `V1__init.sql`

```sql
CREATE TABLE transactions (
    id               TEXT    NOT NULL PRIMARY KEY,
    date             TEXT    NOT NULL CHECK (date GLOB '[0-9][0-9][0-9][0-9]-[0-9][0-9]-[0-9][0-9]'),
    month            TEXT    NOT NULL CHECK (month = substr(date, 1, 7)),
    description      TEXT    NOT NULL,
    merchant         TEXT    NOT NULL,
    direction        TEXT    NOT NULL CHECK (direction IN ('EXPENSE', 'INCOME')),
    amount_minor     INTEGER NOT NULL CHECK (amount_minor > 0),
    currency         TEXT    NOT NULL CHECK (currency IN ('AMD', 'USD')),
    amount_amd_minor INTEGER NOT NULL CHECK (amount_amd_minor > 0),
    exchange_rate    TEXT    NOT NULL,
    category         TEXT    NOT NULL CHECK (category IN (
                         'SALARY', 'FREELANCE', 'OTHER_INCOME',
                         'RENT', 'UTILITIES', 'INTERNET_MOBILE', 'SUBSCRIPTIONS', 'GROCERIES',
                         'CAFES_RESTAURANTS', 'TRANSPORT', 'HEALTH_FITNESS', 'SHOPPING', 'TRAVEL',
                         'CASH', 'TRANSFERS', 'OTHER')),
    source_file      TEXT    NOT NULL
) STRICT;

CREATE INDEX idx_transactions_month ON transactions (month);
CREATE INDEX idx_transactions_merchant ON transactions (merchant);

CREATE TABLE category_overrides (
    merchant   TEXT NOT NULL PRIMARY KEY,
    category   TEXT NOT NULL CHECK (category IN (
                   'SALARY', 'FREELANCE', 'OTHER_INCOME',
                   'RENT', 'UTILITIES', 'INTERNET_MOBILE', 'SUBSCRIPTIONS', 'GROCERIES',
                   'CAFES_RESTAURANTS', 'TRANSPORT', 'HEALTH_FITNESS', 'SHOPPING', 'TRAVEL',
                   'CASH', 'TRANSFERS', 'OTHER')),
    updated_at TEXT NOT NULL
) STRICT;

CREATE TABLE import_log (
    id          INTEGER NOT NULL PRIMARY KEY,
    file_name   TEXT    NOT NULL,
    file_hash   TEXT    NOT NULL,
    imported_at TEXT    NOT NULL,
    added       INTEGER NOT NULL CHECK (added >= 0),
    duplicates  INTEGER NOT NULL CHECK (duplicates >= 0),
    rejected    INTEGER NOT NULL CHECK (rejected >= 0)
) STRICT;
```

Why each choice:

- `id TEXT PRIMARY KEY` is the domain dedupe hash (`statement-import`); `ON CONFLICT (id) DO NOTHING`
  makes re-import add nothing. `import_log.id INTEGER PRIMARY KEY` is the rowid alias — auto-assigned,
  no `AUTOINCREMENT` needed (it only adds a never-reuse guarantee we don't need).
- `month` is stored (not computed per query) and indexed because every tool filters by month; the
  `CHECK` keeps it consistent with `date`. `merchant` is indexed for `top_merchants`, overrides and
  subscriptions.
- `import_log` deliberately has no `UNIQUE (file_hash)`: re-importing the same file is allowed and logged
  (it just adds 0). If the product decides otherwise, that's a `grill-me` question and a new migration.
- No foreign keys in V1 (no table references another by key). If one is added later,
  `foreign_keys=true` is set per connection in the JDBC URL (`sqlite-jdbcclient` §8).
- Whether `merchant` may be empty / `NOT NULL` follows `statement-import`'s normalization rule; the
  schema must match it, not guess it.

## SQLite ALTER limits — the table-rebuild pattern

SQLite's `ALTER TABLE` supports only: `RENAME TO`, `RENAME COLUMN`, `ADD COLUMN`, `DROP COLUMN`.

- **`ADD COLUMN`**: no `PRIMARY KEY`/`UNIQUE`; a `NOT NULL` column needs a non-null `DEFAULT`; the
  default must be constant. Fine for "add a nullable/defaulted column".
- **`DROP COLUMN`** fails if the column is indexed, part of a key/`UNIQUE`, referenced by a `CHECK`,
  FK, view or trigger — drop those first (or rebuild).
- **Anything else** — change a type, add/alter a `CHECK` (e.g. a new `Category`), change `NOT NULL`,
  add a table-level constraint — needs the **rebuild pattern**, all in **one** migration:

```sql
-- V3__allow_category_gifts.sql
CREATE TABLE transactions_new (
    -- full new definition, STRICT, with the changed CHECK
) STRICT;

INSERT INTO transactions_new (id, date, month, description, merchant, direction, amount_minor, currency,
                              amount_amd_minor, exchange_rate, category, source_file)
SELECT id, date, month, description, merchant, direction, amount_minor, currency,
       amount_amd_minor, exchange_rate, category, source_file
FROM transactions;

DROP TABLE transactions;
ALTER TABLE transactions_new RENAME TO transactions;

CREATE INDEX idx_transactions_month ON transactions (month);
CREATE INDEX idx_transactions_merchant ON transactions (merchant);
```

Rules for a rebuild:

- **Explicit column lists** on both sides of the `INSERT … SELECT` — never `SELECT *` (column order may
  differ).
- Order is *create new → copy → drop old → rename new*; renaming the old table first breaks references
  in views/triggers.
- **Recreate every index, trigger and view** that belonged to the old table — `DROP TABLE` removes them.
  Diff `sqlite_schema` before/after in the migration test.
- SQLite DDL is transactional and Flyway runs each SQLite migration in a transaction, so a failing
  rebuild rolls back cleanly. But **`PRAGMA foreign_keys = OFF` is a no-op inside a transaction**: if
  FKs ever point at the rebuilt table, use `PRAGMA defer_foreign_keys = ON;` at the top of the migration
  and end with a check (`PRAGMA foreign_key_check;` returns violations — the migration test must
  assert it is empty).
- Narrowing a `CHECK` (removing an allowed value) fails on existing rows — migrate the data first
  (`UPDATE … SET category = 'OTHER' WHERE category = 'X'`) in the same file, before the copy.

## WAL, locks and migrating a live file

- `journal_mode=WAL` is set on the connection URL (`sqlite-jdbcclient`) and is persistent in the file.
  It creates `finance.db-wal` / `finance.db-shm` next to the database — never delete them while the
  server runs; gitignore `*.db*`.
- Migration runs at startup with the single server process; if another process (MCP Inspector, a
  second Claude Desktop session, a `sqlite3` shell) holds a write lock, migrate waits up to
  `busy_timeout` then fails with "database is locked". Close the other process; don't raise the timeout
  to hide it.
- A migration that rebuilds a large table holds the write lock for its duration — fine at personal
  scale (thousands of rows), but keep each migration to one logical change.

## Common mistakes

- **Editing an applied migration** → checksum mismatch, server won't start on the user's machine. Fix
  forward. (`flyway repair` realigns checksums only for a file edited *before* it applied anywhere.)
- **`REAL`/`NUMERIC`/`DECIMAL` for money or rates**; dates as epoch numbers.
- **Non-`STRICT` tables**; enum columns without a `CHECK`; `CHECK` lists drifting from the Java enum.
- **`ALTER TABLE … ALTER COLUMN`** / `ADD CONSTRAINT` — not SQLite; rebuild instead.
- **Rebuild without recreating indexes** — queries silently become full scans.
- **`PRAGMA foreign_keys = OFF` inside a migration** — ignored inside the transaction.
- **`SELECT *` in a rebuild copy** — column order drift corrupts data.
- **Running `clean`** — wipes the user's finance history; keep `clean-disabled=true`.
- **Bundling unrelated changes** in one migration; **test or personal data in a `V__`**.
- **A test that creates schema by hand** instead of running the real migrations.

## References

- `references/setup-and-config.md` — module placement, dependencies, `spring.flyway.*` keys, file
  location from `@ConfigurationProperties`, `FlywayMigrationStrategy` (pre-migration backup), version
  ordering.
- `references/jdbcclient-and-testing.md` — how migrations meet `JdbcClient` repositories, testing
  migrations on a `@TempDir` file (fresh migrate, schema assertions, data-migration upgrade path), the
  pre-merge checklist.
