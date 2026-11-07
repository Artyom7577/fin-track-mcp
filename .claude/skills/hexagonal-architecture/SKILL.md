---
name: hexagonal-architecture
description: >-
  Invoke BEFORE placing any new class, adding a port or adapter, adding a Maven dependency or a
  cross-module import, creating an exception type, or declaring a Spring bean in fin-track-mcp. Covers
  the module map (domain, application, adapter-parser,
  adapter-persistence, adapter-fx, mockdata, mcp-server), dependency
  direction, ports vs adapters, the "where does it go" decision table, "later features have interfaces
  now", the exception hierarchy across modules, why domain/application are Spring-free, and ArchUnit
  rules. Use together with package-layout. Triggers: "which module", "where should this go", "new port",
  "new adapter", "import from", "dependency", "ArchUnit", "bean", "@Configuration", "layering",
  "hexagonal", "ports and adapters".
---

# Hexagonal architecture (ports & adapters)

CLAUDE.md "Module boundaries" is the law; this skill is how to apply it. `package-layout` covers packages
inside a module; `abstraction-design` must still approve any *new* interface (the four ports below are
pre-approved).

## The picture

```
                mcp-server  (Spring Boot, @McpTool, wiring, @ConfigurationProperties)
                 │      │       │          │
   adapter-parser  adapter-persistence  adapter-fx        mockdata
          └──────────┬──────┘───────────┘                       │
                application (use cases + ports)          │
                         │                                       │
                   domain  ◄──────────────────────────────┘
```

Arrows are Maven `<dependency>` edges and **only point down**. Three invariants:

1. **The domain depends on nothing** (JDK only — no Spring, POI, JDBC, Jackson, SLF4J optional at most: prefer none).
2. **Nothing depends on the server.** It is the composition root.
3. **Adapters never depend on each other.** The parser does not know SQLite exists; the FX adapter does not
   know about parsers. Cross-adapter needs go through an application port.

Plus: `application` imports only `domain` and the JDK. `mockdata` depends on
domain, POI and Commons CSV only (tests in other modules use it with `<scope>test</scope>`); inside it,
`writer` → `generator` → `scenario`, and nothing depends on `cli`.

## Ports and adapters

A **port** is an interface owned by the inner layer, expressed in domain types, describing what the use case
needs — not what the technology offers. An **adapter** implements it in an outer module and translates.

| Port | Owner | Signature sketch | MVP adapter(s) | Later (interface must already fit) |
|---|---|---|---|---|
| `StatementParser` | application | `ParseResult parse(Path file)` | `StatementFileParser` (Evoca .xlsx / .csv) | an `ameria` layout, a PDF format reader |
| `TransactionRepository` | application | `int saveAll(List<Transaction>)` (skips existing ids), `List<Transaction> findByMonth(YearMonth)`, `Optional<MonthRange> availableMonths()` … | `SqliteTransactionRepository` | Postgres |
| `ExchangeRateProvider` | application | `ExchangeRate rateFor(Currency from, LocalDate date)` | `FixedRateProvider` | `CbaRateProvider` + cache |
| `CategorizationRule` | domain | `Optional<Category> apply(Transaction tx)` | `MerchantOverrideRule`, `KnownMerchantRule`, `KeywordRule`, fallback | ML rule |

Port rules:

- Parameters and results are **domain/application types** (records, enums). Never `Workbook`, `ResultSet`,
  `CSVRecord`, `JsonNode`, `HttpResponse`.
- The port carries the date even where the MVP ignores it (`rateFor(USD, date)` while the fixed provider
  returns one rate) — that is "later features have interfaces now".
- Adapters catch library exceptions and rethrow the port-level domain exception
  (`StorageException`, `UnsupportedFormatException`); no `SQLException`/`IOException`/POI type crosses.

## Where does new code go?

| You are adding… | Module / package | Notes |
|---|---|---|
| A value type, enum, business rule, per-transaction calculation | `domain` | pure, unit-tested in ms |
| A read-side analysis over many transactions (spending, comparison, subscription detection) | `application/analysis/<usecase>` | needs ports and `Percent`; still framework-free |
| A use case ("compare two months") | `application` service | plain constructor, ports injected |
| A port (new outside capability) | `application` (or domain for rule-like strategies) | run `abstraction-design` |
| Use-case result record (`ImportSummary`, `CategorySpending`) | `application` | MCP returns it as-is when it fits |
| A new bank format | `adapter-parser`: a sibling package of `evoca` reusing `format`, chosen in `StatementFileParser` | `statement-import` |
| A table, column, query | `adapter-persistence` + Flyway migration | `sqlite-jdbcclient`, `flyway-migrations` |
| A new rate source | `adapter-fx` implementing `ExchangeRateProvider` | `money-and-fx` |
| A tool / resource / config / bean wiring | `mcp-server` | `mcp-server-tools` |
| Tunable (limits, thresholds, rate, paths) | `@ConfigurationProperties` record in server; passed into the plain class's constructor as a domain record | never read env in domain |
| Fake statements (scenario, generator, writers, CLI) | `mockdata` | fixed seed + fixed `Clock` |

If a class "needs" something from a sibling adapter, the design is wrong: lift the need into an application
port and let the server wire both sides.

## Why domain/application have no Spring

- Tests run without a context (milliseconds, no flakiness).
- The core survives a framework change (Streamable HTTP, a web dashboard, a CLI) untouched.
- No hidden wiring: every dependency is a visible constructor parameter.

So: no `@Service`, `@Component`, `@Transactional`, `@Value` in domain/application. The server declares beans:

```java
@Configuration
@ConfigurationPropertiesScan
public class FinanceBeans {

    @Bean
    CategorizerFactory categorizerFactory(CategoryOverrideRepository overrides, CategorizationProperties props) {
        return new CategorizerFactory(overrides, List.of(
                new KnownMerchantRule(props.merchants()),
                new KnownMerchantRule(DefaultCategorizationData.knownMerchants()),
                new KeywordRule(props.keywords()),
                new KeywordRule(DefaultCategorizationData.keywords())));
    }

    @Bean
    ImportService importService(StatementParser parser, TransactionRepository repository,
                                ExchangeRateProvider rates, CategorizerFactory categorizers) {
        return new ImportService(parser, repository, rates, categorizers);
    }
}
```

Adapters *may* be annotated (`@Repository` in persistence) only if the module already depends on Spring
(persistence needs `JdbcClient`); still prefer declaring them in `FinanceBeans` so wiring is in one place.
Transactions: the repository owns its transaction boundary (`saveAll` is one SQLite transaction), so the
application stays annotation-free.

## Exceptions across modules

`sealed` permits only subclasses in the **same package** (we don't use JPMS), so a sealed base in domain
cannot be extended from another module directly. Shape:

```java
// domain: com.artyomh.finance.domain.exception
public sealed abstract class FinanceException extends RuntimeException
        permits InvalidInputException, NotFoundException, UnsupportedFormatException, StorageException {
    protected FinanceException(String message) {
        super(message);
    }
}
public non-sealed class NotFoundException extends FinanceException { … }   // kinds callers distinguish
```

Modules subclass a **kind** in their own flat `exception` package (`NoDataForMonthException extends
NotFoundException` in application). The MCP layer can switch exhaustively over the four kinds. Messages are
user copy (see `mcp-server-tools`). Keep library exceptions as the cause only *inside* the adapter's log —
the MCP translator never forwards causes.

## ArchUnit (one test class per module, or a shared `ArchitectureTest` in the server)

```java
@AnalyzeClasses(packages = "com.artyomh.finance", importOptions = ImportOption.DoNotIncludeTests.class)
class ArchitectureTest {

    @ArchTest
    static final ArchRule domainIsPure = noClasses().that().resideInAPackage("..finance.domain..")
            .should().dependOnClassesThat().resideOutsideOfPackages("..finance.domain..", "java..");

    @ArchTest
    static final ArchRule applicationOnlyKnowsDomain = noClasses().that().resideInAPackage("..finance.application..")
            .should().dependOnClassesThat().resideInAnyPackage(
                    "org.springframework..", "org.apache.poi..", "org.apache.commons.csv..",
                    "java.sql..", "..finance.adapter..", "..finance.mcp..");

    @ArchTest
    static final ArchRule adaptersAreIsolated = slices().matching("..finance.adapter.(*)..")
            .should().notDependOnEachOther();

    @ArchTest
    static final ArchRule nothingDependsOnServer = noClasses().that().resideOutsideOfPackage("..finance.mcp..")
            .should().dependOnClassesThat().resideInAPackage("..finance.mcp..");

    @ArchTest
    static final ArchRule mcpAnnotationsOnlyInServer = noClasses().that().resideOutsideOfPackage("..finance.mcp..")
            .should().dependOnClassesThat().resideInAPackage("org.springframework.ai..");

    @ArchTest
    static final ArchRule toolsUseServicesNotAdapters = noClasses().that().resideInAPackage("..finance.mcp.tool..")
            .should().dependOnClassesThat().resideInAnyPackage("..finance.adapter..");
}
```

Maven already prevents most upward imports (the dependency isn't there); ArchUnit catches what Maven can't:
Spring in application, adapter-to-adapter via a transitive path, tools reaching around services.
`graphify check` (exit 2) is the second net — see `graphify`.

## "Later" features — interfaces now, code later

| Later | What must exist now | What must *not* exist now |
|---|---|---|
| CBA daily rates | `ExchangeRateProvider.rateFor(currency, date)`; `exchangeRate` stored per transaction | HTTP client, cache |
| More banks / PDF | `StatementParser.parse(Path)`; bank-agnostic `format` readers below bank packages | `ameria` layout, PDF reader |
| Postgres | `TransactionRepository` in domain terms, no SQLite types leak | second adapter |
| Streamable HTTP / dashboard | tools are thin; services return records | web starter, controllers |
| ML categorization | `CategorizationRule` chain | model code |
| Budgets | nothing yet beyond a clean `SpendingService` | tables |

## Common mistakes

- `@Service` on an application class "just for convenience".
- A port that mirrors a library (`readSheet(Sheet)`), or returns `Map<String, Object>`.
- Parser calling the repository to check duplicates (dedupe is `ImportService` + `saveAll`).
- Putting result records in the server and mapping 1:1 from an identical application record (mirroring).
- An adapter throwing `IOException`/`DataAccessException` upward.
- Reading `@Value`/`System.getenv` outside the server module.
