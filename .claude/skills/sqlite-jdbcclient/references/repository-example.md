# Worked example — `SqliteTransactionRepository` + its test

Names follow `CLAUDE.md`, `hexagonal-architecture` and `money-and-fx` (`Money.toMinorUnits()`,
`Money.ofMinorUnits(..)`, `StorageException`). Port method names are illustrative — implement the port
as `application` actually declares it. Comments are kept to the house minimum (`comment-style`).

## The repository

```java
package com.artyomh.finance.adapter.persistence.transaction;

public final class SqliteTransactionRepository implements TransactionRepository {

    private static final Logger LOG = LoggerFactory.getLogger(SqliteTransactionRepository.class);
    private static final int INSERTED = 1;

    private static final String INSERT = """
        INSERT INTO transactions (id, date, month, description, merchant, direction, amount_minor, currency,
                                  amount_amd_minor, exchange_rate, category, source_file)
        VALUES (:id, :date, :month, :description, :merchant, :direction, :amountMinor, :currency,
                :amountAmdMinor, :exchangeRate, :category, :sourceFile)
        ON CONFLICT (id) DO NOTHING
        """;

    private static final String SPENDING_BY_CATEGORY = """
        SELECT category, SUM(amount_amd_minor) AS total_minor, COUNT(*) AS tx_count
        FROM transactions
        WHERE month = :month AND direction = :direction
        GROUP BY category
        ORDER BY total_minor DESC, category
        """;

    private static final String MONTHS = "SELECT DISTINCT month FROM transactions ORDER BY month";

    private final JdbcClient jdbc;
    private final NamedParameterJdbcOperations batchJdbc;
    private final TransactionOperations tx;

    public SqliteTransactionRepository(JdbcClient jdbc, NamedParameterJdbcOperations batchJdbc,
                                       TransactionOperations tx) {
        this.jdbc = jdbc;
        this.batchJdbc = batchJdbc;
        this.tx = tx;
    }

    @Override
    public int saveAll(List<Transaction> transactions) {
        try {
            return tx.execute(status -> insertAll(transactions));
        } catch (DataAccessException e) {
            throw storageFailure("Could not save the imported transactions; nothing was written.", e);
        }
    }

    @Override
    public List<CategoryTotal> totalsByCategory(YearMonth month, Direction direction) {
        try {
            return jdbc.sql(SPENDING_BY_CATEGORY)
                .param("month", month.toString())
                .param("direction", direction.name())
                .query(SqliteTransactionRepository::toCategoryTotal)
                .list();
        } catch (DataAccessException e) {
            throw storageFailure("Could not read category totals from the local database.", e);
        }
    }

    @Override
    public List<YearMonth> months() {
        try {
            return jdbc.sql(MONTHS).query((rs, rowNum) -> YearMonth.parse(rs.getString("month"))).list();
        } catch (DataAccessException e) {
            throw storageFailure("Could not read months from the local database.", e);
        }
    }

    private int insertAll(List<Transaction> transactions) {
        SqlParameterSource[] batch = transactions.stream()
            .map(SqliteTransactionRepository::params)
            .toArray(SqlParameterSource[]::new);
        return (int) Arrays.stream(batchJdbc.batchUpdate(INSERT, batch)).filter(count -> count == INSERTED).count();
    }

    private static SqlParameterSource params(Transaction t) {
        return new MapSqlParameterSource()
            .addValue("id", t.id().value())
            .addValue("date", t.date().toString())
            .addValue("month", YearMonth.from(t.date()).toString())
            .addValue("description", t.description())
            .addValue("merchant", t.merchant())
            .addValue("direction", t.direction().name())
            .addValue("amountMinor", t.money().toMinorUnits())
            .addValue("currency", t.money().currency().name())
            .addValue("amountAmdMinor", t.amountAmd().toMinorUnits())
            .addValue("exchangeRate", t.exchangeRate().toPlainString())
            .addValue("category", t.category().name())
            .addValue("sourceFile", t.sourceFile());
    }

    private static CategoryTotal toCategoryTotal(ResultSet rs, int rowNum) throws SQLException {
        return new CategoryTotal(
            Category.valueOf(rs.getString("category")),
            Money.ofMinorUnits(rs.getLong("total_minor"), Currency.AMD),
            rs.getInt("tx_count"));
    }

    private static StorageException storageFailure(String message, DataAccessException cause) {
        LOG.warn(message, cause);
        return new StorageException(message);
    }
}
```

Notes:

- `month` is derived from `date` **in one place** (here) — never trust a caller-supplied month string to
  match the date.
- `exchange_rate` via `toPlainString()` — never `toString()` (`4E+2`).
- An empty `transactions` list should short-circuit to `0` before opening a transaction if the port
  allows empty input.
- The log line carries the operation and the Spring exception (SQL text with `:named` placeholders,
  no bound values) — no descriptions, merchants or amounts (PII rule in `CLAUDE.md`).

## Wiring (server module)

```java
// com.artyomh.finance.mcp.config.FinanceBeans (excerpt)
@Bean
TransactionRepository transactionRepository(JdbcClient jdbc, NamedParameterJdbcOperations batchJdbc,
                                            TransactionOperations transactionOperations) {
    return new SqliteTransactionRepository(jdbc, batchJdbc, transactionOperations);
}
```

`JdbcClient`, `NamedParameterJdbcTemplate` and `TransactionTemplate` are auto-configured by Boot from the
single SQLite `DataSource` (verify with `--debug` / the conditions report if a bean is missing).

## The repository test (no Spring context)

Lives in `adapter-persistence/src/test/java/com/artyomh/finance/adapter/persistence/transaction/`.
A real SQLite file in `@TempDir`, the real Flyway migrations, the real driver — no mocks, no H2.

```java
class SqliteTransactionRepositoryTest {

    @TempDir
    Path dir;

    private SqliteTransactionRepository repository;
    private JdbcClient jdbc;

    @BeforeEach
    void setUp() {
        String url = "jdbc:sqlite:" + dir.resolve("test.db") + "?foreign_keys=true&busy_timeout=5000";
        DataSource dataSource = new DriverManagerDataSource(url);
        Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").load().migrate();
        jdbc = JdbcClient.create(dataSource);
        repository = new SqliteTransactionRepository(jdbc, new NamedParameterJdbcTemplate(dataSource),
            new TransactionTemplate(new DataSourceTransactionManager(dataSource)));
    }

    @Test
    void savingTheSameTransactionsTwiceInsertsThemOnce() {
        List<Transaction> rows = List.of(groceries("2026-10-03", "12500.00"), groceries("2026-10-04", "8000.00"));

        int first = repository.saveAll(rows);
        int second = repository.saveAll(rows);

        assertThat(first).isEqualTo(2);
        assertThat(second).isZero();
    }

    @Test
    void amountsRoundTripExactlyThroughMinorUnits() {
        Transaction usd = usdSubscription("2026-10-05", "9.99", "387.50");

        repository.saveAll(List.of(usd));

        assertThat(jdbc.sql("SELECT amount_minor FROM transactions").query(Long.class).single()).isEqualTo(999L);
        assertThat(jdbc.sql("SELECT exchange_rate FROM transactions").query(String.class).single())
            .isEqualTo("387.50");
    }

    @Test
    void categoryTotalsAreSummedInAmdAndOrderedDeterministically() { ... }

    @Test
    void aFailingRowRollsBackTheWholeBatch() { ... }   // e.g. a row violating a CHECK → count(*) stays 0
}
```

- `DriverManagerDataSource` opens a new connection per call — fine for a test; pragmas therefore go in
  the URL, exactly as in production.
- Build fixture transactions from **`mockdata`** builders (fixed seed), or tiny local factory
  methods in the test — never real statements.
- Each test gets a fresh file (`@TempDir` is per-test by default) — no cleanup SQL, no ordering coupling.
- Assert stored representation (`amount_minor = 999`, `exchange_rate = '387.50'`) **and** the round trip
  through the port — the first catches scale bugs the second would hide.
