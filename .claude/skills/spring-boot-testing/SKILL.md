---
name: spring-boot-testing
description: >-
  Invoke when writing, structuring, or debugging tests (TDD) in fin-track-mcp
  (Java 25, Spring Boot 4, Spring AI 2.0 MCP server; modules domain,
  application, adapter-parser, adapter-persistence, adapter-fx, mockdata,
  mcp-server) — choosing between a plain JUnit 5 + AssertJ unit test (no
  Spring context; hand-written fakes of ports before Mockito), a parser test
  on mock .xlsx/.csv fixtures from mockdata, a SQLite repository test on a
  @TempDir file with Flyway applied, ArchUnit module-rule tests, a
  @SpringBootTest integration test in mcp-server (import a mock file, then
  call every tool), and MCP contract tests over STDIO (MCP Java SDK
  McpSyncClient + StdioClientTransport, MCP Inspector). Triggers: "test",
  "TDD", RED→GREEN→REFACTOR with ./mvnw test, where a test belongs, test
  dependencies (spring-boot-starter-test, archunit-junit5, flyway-core test
  scope), fakes vs mocks, @MockitoBean, a slow suite from too many
  application contexts, flaky tests from dates/locale/random seeds, testing
  RowOutcome outcomes, idempotent import, money rounding, tool error messages.
---

# Testing & TDD (Spring Boot 4, Java 25)

Exact versions: check `pom.xml`. Boot 4 manages JUnit Jupiter, AssertJ, Mockito, Flyway and
`sqlite-jdbc`; never pin them. ArchUnit (`com.tngtech.archunit:archunit-junit5`) and the MCP SDK client
are **not** Boot-managed: version in a parent `<properties>` entry / the Spring AI BOM.

## Overview

fin-track-mcp is a **blocking, single-client, local** server: tests call plain objects, repositories
and tool beans directly and assert the result. No network, no Docker, no Testcontainers — SQLite is a
file, so the "real database" is a temp file in every test that needs one.

**Core principle:** reach for the *cheapest* test that proves the behaviour.

1. Pure logic → **unit test** (domain/application; plain JUnit 5 + AssertJ, **no Spring context**,
   milliseconds). Constructor injection + ports make this trivial:
   `new SpendingService(fakeRepository)`.
2. One adapter against its real technology → **adapter test** (parsers on real `.xlsx`/`.csv` files
   from `mockdata`; the SQLite repository on a `@TempDir` file with the real Flyway migrations).
3. Architecture → **ArchUnit** rules (module direction, no Spring in the core).
4. The wiring → **one `@SpringBootTest`** in `mcp-server`: import a mock statement, then call every tool.
5. The wire protocol → **MCP contract tests over STDIO** against the packaged jar.

**Nothing leaves the machine and nothing is real:** no network in any test, no real bank statements
(mock data only — real statements are personal financial PII), no `*.db` committed, no dependence on
`~/.fin-track-mcp`.

## When to use / when not

**Use when:** choosing the test kind and its location; writing fakes; wiring test dependencies; testing
parsers, repositories, tools, error messages, the protocol; a suite is slow or flaky.

**Not for:** production wiring of SQL (`sqlite-jdbcclient`), schema (`flyway-migrations`), tools
(`mcp-server-tools`), parsing rules (`statement-import`), money rules (`money-and-fx`) — this skill
*tests* those. The `java-testing` agent consults this skill.

## Quick reference

| Test kind | Entry point | Module | Use for |
|---|---|---|---|
| Unit | `new Service(fakePort)` / `new Money(..)` + AssertJ | `domain`, `application`, `adapter-fx`, tool classes in `mcp-server` | rules, use cases, value objects, input validation |
| Port fake | hand-written `InMemoryTransactionRepository implements TransactionRepository` | `application` tests | state-based assertions, no stubbing noise |
| Mock (rare) | `mock(..)` / `@ExtendWith(MockitoExtension.class)` | any | "this must NOT be called", interaction-only contracts |
| Parser test | `parser.parse(fixturePath)` on a `@TempDir` file written by `mockdata` | `adapter-parser` | headers, row mapping, `Rejected` reasons, edge rows |
| Repository test | `Flyway…migrate()` + `JdbcClient.create(ds)` on `@TempDir/test.db` | `adapter-persistence` | SQL, idempotency, minor units, aggregations, constraints |
| Migration test | `Flyway.migrate()/validate()` + `sqlite_schema` assertions | `adapter-persistence` | STRICT, CHECKs, indexes, upgrade paths |
| Architecture | `@AnalyzeClasses` + `@ArchTest` | `mcp-server` (sees all modules) | direction, purity, no adapter-to-adapter |
| Integration | `@SpringBootTest` + `spring.ai.mcp.server.stdio=false` + temp DB | `mcp-server` | import → every tool, bean wiring, Flyway at startup |
| Contract | `McpClient.sync(new StdioClientTransport(..))` against the jar | `mcp-server` (`@Tag("contract")`) | tool list, schemas, errors over JSON-RPC, STDIO hygiene |
| Manual | `npx @modelcontextprotocol/inspector java -jar <jar>` | — | eyeballing descriptions and error text |

## 1. Test taxonomy — where each test lives

Tests mirror the class-under-test's package in the **same module** (`package-layout` rule 7).

- **`domain`:** unit tests only. `Money` arithmetic and scale, `Category`/`Direction` invariants, each
  `CategorizationRule` and the chain order, whole-word keyword matching ("ENA" must not match
  "OPENAI"), subscription / unusual-spend detection, transaction-id hashing (same input → same id;
  occurrence index distinguishes identical same-day rows).
- **`application`:** unit tests with **hand-written fakes of the ports**. `ImportService` folds
  `RowOutcome`s into `ImportSummary`; importing the same rows twice → `added 0, duplicates n`; "month with
  no data" throws with the available range; services never see a framework type.
- **`adapter-parser`:** `StatementFileParser` on files written by `mockdata`'s writers (round trip, malformed
  rows), hand-made fixtures in `src/test/resources/irregular/`, `XlsxFixture` for typed cells; every
  `ReadFailure` and every row reason has a test.
- **`adapter-persistence`:** repository + migration tests on a temp SQLite file (§5).
- **`adapter-fx`:** unit tests (`FixedRateProvider` from a properties record; date is carried even if
  ignored).
- **`mockdata`:** determinism — same seed → byte-identical rows; the generated file parses.
- **`mcp-server`:** tool unit tests (input validation, error text), the description lint test,
  `ArchitectureTest`, one `@SpringBootTest`, contract tests.

A domain unit test — no Spring, no mocks:

```java
package com.artyomh.finance.domain.categorization;

class KeywordRuleTest {

    private final KeywordRule rule = new KeywordRule(Map.of("ENA", Category.UTILITIES));

    @Test
    void keywordMatchesAsWholeWord() {
        assertThat(rule.apply(expense("ENA ELECTRICITY PAYMENT"))).contains(Category.UTILITIES);
    }

    @Test
    void keywordInsideAnotherWordDoesNotMatch() {
        assertThat(rule.apply(expense("OPENAI *CHATGPT SUBSCR"))).isEmpty();
    }
}
```

Naming: test class = `<ClassUnderTest>Test`; method names are **behaviour sentences in camelCase**
(`importingTheSameFileTwiceAddsNothing`, `unknownCategoryErrorListsValidCategories`) — no `test`
prefix, no `should_` underscores. One behaviour per test; arrange / act / assert separated by blank
lines.

## 2. RED → GREEN → REFACTOR

1. **RED** — write the failing test first. It fails to compile or fails its assertion — for the right
   reason.
2. **GREEN** — the minimum production code, obeying CLAUDE.md: constructor injection, braces, no
   ternary in `src/main`, no `var`, records, module exceptions, no magic numbers.
3. **REFACTOR** — clean up with the test as the safety net.

Run: `./mvnw -q -pl <module> -am test` while iterating; full `./mvnw -q test` before finishing
(definition of done). Contract tests run after `package` (§8).

## 3. Test dependencies (test scope)

- every module: `spring-boot-starter-test` brings JUnit Jupiter, AssertJ, Mockito — fine even in
  `domain`/`application` **as test scope only** (ArchUnit checks main classes). If you prefer a lighter
  core, `junit-jupiter` + `assertj-core` suffice there.
- `adapter-parser`, `adapter-persistence`: `mockdata` (test scope) for fixtures.
- `adapter-persistence`: `org.flywaydb:flyway-core` (test scope; the server brings
  `spring-boot-starter-flyway` at runtime) and `org.xerial:sqlite-jdbc`.
- `mcp-server`: `spring-boot-starter-test`, `archunit-junit5`, the MCP SDK client classes (already on the
  classpath via `spring-ai-starter-mcp-server` → `mcp-core`), `mockdata`. Boot 4 per-technology test
  starters (`spring-boot-starter-jdbc-test`, `spring-boot-starter-flyway-test`) only if a slice is used.

Check the actual `*/pom.xml` before adding — don't duplicate what a starter already brings.

## 4. Fakes before mocks (application layer)

Ports are small interfaces in domain terms, so a **hand-written fake** is usually shorter and clearer
than Mockito stubbing, and it tests *state* ("the repository now holds 3 rows") rather than calls.

```java
package com.artyomh.finance.application.fake;

public final class InMemoryTransactionRepository implements TransactionRepository {

    private final Map<TransactionId, Transaction> rows = new LinkedHashMap<>();

    @Override
    public int saveAll(List<Transaction> transactions) {
        int added = 0;
        for (Transaction t : transactions) {
            if (rows.putIfAbsent(t.id(), t) == null) {
                added++;
            }
        }
        return added;
    }

    @Override
    public List<Transaction> findByMonth(YearMonth month) { ... }
}
```

```java
package com.artyomh.finance.application.service.importing;

class ImportServiceTest {

    private final InMemoryTransactionRepository repository = new InMemoryTransactionRepository();
    private final ImportService service = new ImportService(
        FakeParsers.returning(MockStatements.october2026()), CATEGORIZATION, FIXED_RATES, repository);

    @Test
    void importingTheSameStatementTwiceAddsNothing() {
        ImportSummary first = service.importStatement(STATEMENT);
        ImportSummary second = service.importStatement(STATEMENT);

        assertThat(first.added()).isPositive();
        assertThat(second.added()).isZero();
        assertThat(second.duplicates()).isEqualTo(first.added());
    }

    @Test
    void malformedRowIsRejectedWithReasonAndOthersStillImport() { ... }
}
```

- Fakes live in test sources (`…/application/fake`), shared by the module's tests; they implement the
  port contract faithfully (dedupe by id, ordering) — a fake that lies makes tests lie. When the port's
  contract is subtle, run the **same contract test** against the fake and the SQLite repository.
- Use Mockito only to assert an interaction that has no state (`verify(repository, never()).saveAll(any())`
  when every row is rejected) or for a throwing collaborator. `@MockitoBean` (not the removed
  `@MockBean`) only in a Spring test.
- Time: inject a `Clock` (`Clock.fixed(..)`), never `LocalDate.now()` in code under test. Randomness:
  fixed seeds (`mockdata`). Locale: never depend on the default — parse/format with explicit `Locale.ROOT`.

## 5. Adapter tests — real files, real SQLite

**Parsers** (`adapter-parser`), fed by `mockdata`:

```java
package com.artyomh.finance.adapter.parser.evoca;

class StatementFileParserTest {

    @TempDir
    Path dir;

    private final StatementFileParser parser = new StatementFileParser(ParserLimits.DEFAULT);

    @Test
    void roundTripsTheDefaultScenario() {
        GeneratedStatement statement = generate(Scenarios.defaultSixMonths());

        ParseResult result = parser.parse(write(new XlsxStatementWriter(CLOCK), statement));

        assertThat(result.rows()).containsExactlyElementsOf(expectedRows(statement));
    }

    @Test
    void amountWithTooManyDecimalsIsRejectedNotRounded() { ... }

    @Test
    void corruptFileThrowsUnsupportedFormatNotAPoiException() { ... }
}
```

- Cover the edges `statement-import` lists: empty file, header-only, unknown headers, blank rows, a
  `9.999` amount, USD rows, Armenian/Cyrillic text, a truncated/corrupt `.xlsx` (must surface as the
  domain exception, never POI's).
- Small hand-crafted edge-case files may live in `src/test/resources/statements/` — generated, fake,
  never a real export.

**SQLite repository** (`adapter-persistence`) on a temp file with the real migrations — full example in
`sqlite-jdbcclient/references/repository-example.md`:

```java
@TempDir
Path dir;

@BeforeEach
void setUp() {
    DataSource ds = new DriverManagerDataSource("jdbc:sqlite:" + dir.resolve("test.db") + "?foreign_keys=true");
    Flyway.configure().dataSource(ds).locations("classpath:db/migration").load().migrate();
    repository = new SqliteTransactionRepository(JdbcClient.create(ds), new NamedParameterJdbcTemplate(ds),
        new TransactionTemplate(new DataSourceTransactionManager(ds)));
}
```

Must-have repository tests: duplicate save inserts once; minor units round-trip exactly (assert the
stored `INTEGER` too); totals in AMD only; deterministic ordering; a failing row rolls back the whole
batch; an override upsert replaces, not duplicates. Migration tests: `flyway-migrations/references/
jdbcclient-and-testing.md`.

**Never H2, never `jdbc:sqlite::memory:`** for these — an in-memory DB is per-connection (a pool sees
empty databases) and skips the file/WAL behaviour production has.

## 6. ArchUnit — module rules

One `ArchitectureTest` in `mcp-server` tests (`com.artyomh.finance.mcp`), importing
`com.artyomh.finance` with `ImportOption.DoNotIncludeTests`. The rules (full code in
`hexagonal-architecture`): domain depends only on `java..`; application has no Spring / POI / CSV /
`java.sql` / adapter / mcp imports; adapter slices don't depend on each other; nothing outside `mcp`
depends on `mcp`; Spring AI only in `mcp`; tools don't touch adapters. Add a rule whenever a review finds
a structural mistake a rule could have caught — and make it fail first (RED) on a deliberate violation.

## 7. `@SpringBootTest` integration — import, then every tool

One context for the whole module (context caching: every distinct property/mock set is another boot +
Flyway run). STDIO **off** in tests — otherwise the server reads the test JVM's stdin.

```java
package com.artyomh.finance.mcp;

@SpringBootTest(properties = "spring.ai.mcp.server.stdio=false")
class FinanceServerIntegrationTest {

    @TempDir
    static Path dir;

    @DynamicPropertySource
    static void storage(DynamicPropertyRegistry registry) {
        registry.add("finance.storage.path", () -> dir.resolve("it.db").toString());
    }

    @Autowired
    ImportTools importTools;

    @Autowired
    SpendingTools spendingTools;

    // … every tool group

    @Test
    void importedMockStatementAnswersEveryTool() {
        Path statement = MockStatements.writeEvocaXlsx(dir, MockStatements.sixMonths());

        ImportSummary summary = importTools.importStatement(statement.toString());

        assertThat(summary.added()).isPositive();
        assertThat(spendingTools.listMonths()).isNotEmpty();
        assertThat(spendingTools.spendingByCategory("2026-10").categories()).isNotEmpty();
        // compare_months, top_merchants, find_subscriptions, unusual_transactions,
        // search_transactions, set_category — each with an assertion on the mock data's known facts
    }

    @Test
    void reimportingTheSameFileAddsNothing() { ... }

    @Test
    void unknownMonthErrorListsTheAvailableRange() { ... }
}
```

- **Every tool is called at least once** — keep a guard test that reflects over `@McpTool` methods and
  fails if a tool name has no entry in the test's covered set, so a new tool can't ship untested.
- Assert **facts the fixed-seed mock data guarantees** (exact totals, a known subscription), not just
  "non-empty" — mockdata is deterministic so exact numbers are stable.
- Nothing provider-like needs mocking (no network). Use `@MockitoBean` only to force an error path
  (e.g. a repository throwing `StorageException` → tool error text) — and put such tests in a separate
  class so the main context stays shared.

## 8. MCP contract tests over STDIO

Prove the jar speaks MCP correctly and **stdout stays clean**. Run against the packaged jar, tagged so
the default `test` phase can exclude them (`@Tag("contract")`, run in `verify` / on demand):

```java
@Tag("contract")
class StdioContractTest {

    @TempDir
    Path dir;

    @Test
    void serverListsAllToolsAndReturnsStructuredErrors() {
        ServerParameters params = ServerParameters.builder("java")
            .args("-jar", serverJar().toString(), "--finance.storage.path=" + dir.resolve("c.db"))
            .build();
        McpSyncClient client = McpClient.sync(new StdioClientTransport(params, McpJsonDefaults.getMapper()))
            .requestTimeout(Duration.ofSeconds(20))
            .build();
        try {
            client.initialize();

            assertThat(client.listTools().tools()).extracting(McpSchema.Tool::name)
                .containsExactlyInAnyOrder(EXPECTED_TOOLS);
            CallToolResult result = client.callTool(new CallToolRequest("spending_by_category", Map.of("month", "2026-03")));
            assertThat(result.isError()).isTrue();
        } finally {
            client.closeGracefully();
        }
    }
}
```

Signatures verified against `mcp-core` 2.0.0 (`StdioClientTransport(ServerParameters, McpJsonMapper)`,
`McpSyncClient.initialize/listTools/callTool/readResource/closeGracefully`); re-check with `javap` if the
SDK version changes (→ `mcp-server-tools`).

Contract checklist: exact tool-name set; every tool and parameter has a description; read-only tools
carry `readOnlyHint`; an error result's text lists valid options and contains no stack trace, SQL, file
contents or library message; `finance://categories` returns all 16 categories; a full import → query
round trip; the connection survives (a stray stdout line would surface as a JSON parse error).
Manual pass with MCP Inspector before a release.

## Common mistakes

- **`@SpringBootTest` for pure logic** — boots Spring + Flyway; use a unit test.
- **Spring, Mockito-heavy stubbing or a context in domain/application tests** — fakes and plain objects.
- **A fake that doesn't honour the port contract** (no dedupe) — tests pass, production differs.
- **H2 / in-memory SQLite / hand-written `CREATE TABLE`** instead of real migrations on a temp file.
- **Tests touching `~/.fin-track-mcp`**, a shared DB file, or relying on test order.
- **Real statements or real merchants** in fixtures — mock data only; never commit `*.db`.
- **STDIO left on in `@SpringBootTest`** — the server consumes the test's stdin; set `stdio=false`.
- **Too many distinct contexts** — every unique `@MockitoBean`/property combination is another boot.
- **"isNotEmpty" assertions on deterministic mock data** — assert the exact known figures.
- **`LocalDate.now()`, default locale, unseeded randomness** — flaky tests.
- **Wrong package / module** — tests mirror the class-under-test.
