# Migrations ↔ JdbcClient repositories, and testing migrations

## 1. Flyway → repositories (one direction)

Flyway migrations are the **single source of truth for the schema**. `JdbcClient` repositories in
`adapter-persistence` (`sqlite-jdbcclient`) *assume* that schema; there is no code generation, so the
compiler cannot catch a drift between a migration and a query. The safety net is the **repository
test running the real migrations on a real SQLite file**.

The loop for any schema change:

1. **Write the migration** — a new `V<n>__…sql` in `adapter-persistence/src/main/resources/db/migration`.
   Never edit an applied file.
2. **Update every query touching the changed table** — `grep -rn "<table>" adapter-persistence/src/main`
   — explicit column lists make the affected statements easy to find.
3. **Update mappers and parameter sources** (`sqlite-jdbcclient` §3, §5).
4. **Run** `./mvnw -q -pl adapter-persistence -am test` — the migration test and every repository test
   migrate a fresh temp file.

Enum `CHECK` lists and the Java enums must agree: a test asserts it (§2).

## 2. Testing migrations on a `@TempDir` SQLite file

Migrations are verified on **real SQLite with the real driver** — never H2, never an in-memory DB
created by hand. No Spring context is needed: plain `Flyway` + `JdbcClient`.

```java
package com.artyomh.finance.adapter.persistence;

class MigrationsTest {

    @TempDir
    Path dir;

    private DataSource dataSource;
    private JdbcClient jdbc;

    @BeforeEach
    void setUp() {
        dataSource = new DriverManagerDataSource("jdbc:sqlite:" + dir.resolve("finance.db") + "?foreign_keys=true");
        jdbc = JdbcClient.create(dataSource);
    }

    @Test
    void allMigrationsApplyCleanlyAndNothingIsPending() {
        Flyway flyway = flyway();

        flyway.migrate();
        flyway.validate();

        assertThat(flyway.info().pending()).isEmpty();
    }

    @Test
    void everyTableIsStrict() {
        flyway().migrate();

        List<String> nonStrict = jdbc.sql("""
                SELECT name FROM pragma_table_list
                WHERE schema = 'main' AND type = 'table' AND strict = 0
                  AND name NOT LIKE 'sqlite_%' AND name <> 'flyway_schema_history'
                """)
            .query(String.class).list();

        assertThat(nonStrict).isEmpty();
    }

    @Test
    void categoryCheckAcceptsExactlyTheCategoryEnum() {
        flyway().migrate();

        for (Category category : Category.values()) {
            assertThatCode(() -> insertOverride("M-" + category, category.name())).doesNotThrowAnyException();
        }
        assertThatThrownBy(() -> insertOverride("M-X", "FOOD")).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void moneyColumnsRejectNonIntegers() { ... }   // STRICT: inserting '9.99' into amount_minor fails

    @Test
    void expectedIndexesExist() {
        flyway().migrate();

        assertThat(jdbc.sql("SELECT name FROM sqlite_schema WHERE type = 'index' AND tbl_name = 'transactions'")
                .query(String.class).list())
            .contains("idx_transactions_month", "idx_transactions_merchant");
    }

    private Flyway flyway() {
        return Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").load();
    }

    private void insertOverride(String merchant, String category) {
        jdbc.sql("INSERT INTO category_overrides (merchant, category, updated_at) VALUES (:m, :c, :t)")
            .param("m", merchant).param("c", category).param("t", "2026-10-01T00:00:00Z")
            .update();
    }
}
```

Notes:
- `pragma_table_list` (SQLite ≥ 3.37) exposes the `strict` flag — the bundled driver is newer.
- Asserting the `CHECK` against `Category.values()` makes adding an enum constant without a migration
  fail the build instead of failing the user's next import.
- The test lives in `adapter-persistence` and needs `domain` only for the enum — allowed
  (persistence → application → domain).
- `@TempDir` is per test → each test starts from an empty file; no cleanup, no ordering coupling.

## 3. Testing a data migration / table rebuild (upgrade path)

A fresh empty database proves nothing about a rebuild or backfill. Migrate to the **previous** version,
insert representative rows, then migrate to latest and assert:

```java
@Test
void rebuildKeepsEveryRowAndIndex() {
    Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").target("2").load().migrate();
    insertTransactions(fixtureRows());   // include nasty ones: USD + AMD, max-length descriptions, Armenian text

    flyway().migrate();

    assertThat(jdbc.sql("SELECT COUNT(*) FROM transactions").query(Long.class).single())
        .isEqualTo(fixtureRows().size());
    assertThat(jdbc.sql("SELECT SUM(amount_amd_minor) FROM transactions").query(Long.class).single())
        .isEqualTo(expectedTotalMinor());
    assertThat(jdbc.sql("PRAGMA foreign_key_check").query().listOfRows()).isEmpty();
    // + the index assertion above
}
```

Rows come from `mockdata` (fixed seed) or small local factories — never real statements.

## 4. Checklist before merging a migration

- New `V<n>__…sql`; no applied file edited; `n` = highest + 1.
- One logical change; no personal or test data.
- Every new table `STRICT`; money `INTEGER` minor units; rates/dates `TEXT`; enums `CHECK`ed and equal to
  the Java enum.
- SQLite ALTER limits respected — anything beyond add/rename/drop column is a rebuild with explicit column
  lists, indexes/triggers/views recreated, `defer_foreign_keys` + `foreign_key_check` if FKs exist.
- No `PRAGMA foreign_keys`, `VACUUM` or `PRAGMA journal_mode` in a migration.
- Queries, mappers and parameter sources updated (`sqlite-jdbcclient`).
- `MigrationsTest` + repository tests green on a temp file; upgrade-path test for any data-moving change.
