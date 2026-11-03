# Flyway setup & configuration reference (SQLite)

Spring Boot 4, SQLite via `org.xerial:sqlite-jdbc`. Property keys are Spring Boot's `spring.flyway.*`
binding of Flyway's configuration — when in doubt, check the Boot "Common Application Properties"
appendix for the version in `pom.xml`.

## 1. Where things go in the multi-module build

Module directories (root `pom.xml` is the parent): `domain`, `application`, `adapter-parser`,
`adapter-persistence`, `adapter-fx`, `mockdata`, `mcp-server`. Java packages stay
`com.artyomh.finance.<module>`.

| Artifact | Module | Why |
|---|---|---|
| Migrations `V__`/`R__` | `adapter-persistence/src/main/resources/db/migration` | the persistence adapter owns every table; the files ship in its jar |
| `org.xerial:sqlite-jdbc` (runtime) | `adapter-persistence` (and transitively `mcp-server`) | the driver its repositories run on |
| `org.flywaydb:flyway-core` (**test** scope) | `adapter-persistence` | repository/migration tests migrate a temp file without Spring |
| `spring-boot-starter-flyway`, `spring-boot-starter-jdbc` | `mcp-server` | the only Spring Boot app; Boot's Flyway auto-config migrates at startup |
| `spring.flyway.*`, `finance.storage.path` | `mcp-server/src/main/resources/application.yaml` | runtime config |
| Nothing | `domain`, `application`, `adapter-parser`, `adapter-fx`, `mockdata` | they never touch the database (ArchUnit forbids `java.sql` in domain/application) |

SQLite support ships **inside `flyway-core`** (`org.flywaydb.core.internal.database.sqlite`) — there is no
`flyway-database-sqlite` artifact. If Flyway logs that the SQLite version is newer than it has been
tested with, that is a warning, not a failure.

## 2. DataSource and Flyway

There is exactly one `DataSource`, built from `StorageProperties` in `mcp-server`
(`com.artyomh.finance.mcp.config`) — see `sqlite-jdbcclient` §9 for the bean and the URL pragmas.
Boot's Flyway auto-configuration uses that `DataSource`, so the URL's pragmas (`busy_timeout`,
`foreign_keys`, `journal_mode=WAL`) apply to migrations too.

```yaml
finance:
  storage:
    path: ${user.home}/.fin-track-mcp/finance.db

spring:
  flyway:
    locations: classpath:db/migration   # the default; stated for readers
```

The parent directory must exist before Flyway's first connection — the `DataSource` bean creates it.
No credentials exist (SQLite file); never put the path in a migration.

## 3. Flyway config key reference

| Key | Default | Purpose / our value |
|---|---|---|
| `spring.flyway.enabled` | `true` | Keep `true`. Tests that don't need a DB don't start a context at all |
| `spring.flyway.locations` | `classpath:db/migration` | Keep |
| `spring.flyway.clean-disabled` | `true` | **Keep `true`** — `clean` would delete the user's history |
| `spring.flyway.validate-on-migrate` | `true` | Keep — a checksum drift must stop startup loudly |
| `spring.flyway.baseline-on-migrate` | `false` | Not needed: the DB file is always created by Flyway from `V1` |
| `spring.flyway.out-of-order` | `false` | Keep `false` (single owner) |
| `spring.flyway.table` | `flyway_schema_history` | Keep |
| `spring.flyway.placeholder-replacement` | `true` | Fine; migrations contain no `${` |
| `spring.flyway.mixed` | `false` | Irrelevant for SQLite (all DDL is transactional) |

## 4. Optional — back up before migrating

The user's `finance.db` is the only copy of their categorized history. A `FlywayMigrationStrategy` can
snapshot the file **only when migrations are pending**:

```java
@Bean
FlywayMigrationStrategy backupThenMigrate(StorageProperties storage, Clock clock) {
    return flyway -> {
        if (flyway.info().pending().length > 0) {
            backup(storage.path(), clock);   // e.g. Files.copy to finance.db.bak-<timestamp> while no connection writes
        }
        flyway.migrate();
    };
}
```

- With WAL, a raw file copy can miss data still in `-wal`; the safe online copy is SQL
  `VACUUM INTO '<path>'` on a connection. Decide the approach (and whether to build it at all) with
  `grill-me` — this is a product decision, not a default.
- Never ship `flyway.repair()` in the strategy — it hides checksum drift that should fail loudly.

## 5. Version ordering rules

- Versioned files sort numerically, dot-separated, segment-by-segment: `1` < `2` < `2.1` < `10`.
- Underscores in the version part act as dots (`V2_1__x.sql` == `2.1`); `__` separates version and
  description.
- `R__` files have no version; they run **after** all pending versioned migrations and re-run whenever
  their checksum changes — use them only for views (`CREATE VIEW` must be preceded by
  `DROP VIEW IF EXISTS`, SQLite has no `CREATE OR REPLACE VIEW`).
- This repo uses plain sequential integers. On a merge collision, renumber the **unmerged** file.

## 6. SQLite-specific facts Flyway relies on

- DDL is transactional; Flyway wraps each migration in a transaction → a failure leaves no partial
  schema.
- `PRAGMA foreign_keys` cannot change inside a transaction (silently ignored); `PRAGMA
  defer_foreign_keys = ON` can, and resets at commit.
- `VACUUM` cannot run inside a transaction — never put it in a migration.
- `STRICT` needs SQLite ≥ 3.37 (the bundled `sqlite-jdbc` native library is far newer). Older external
  tools (an old DB Browser) may refuse to open a STRICT database — acceptable.
