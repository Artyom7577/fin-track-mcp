# Finance MCP — architecture rules

> When a rule meets reality and loses, change the rule here in the same change — don't drift silently.

Ask Claude "where did my money go in October?" and get a real answer from your own bank statements. This repo is a
**local MCP server**: it imports bank exports (.xlsx / .csv), categorizes every transaction, converts USD to AMD,
stores them in SQLite, and exposes a small set of question-shaped **MCP tools** that Claude Desktop calls over
**STDIO**. Java 25, Spring Boot 4, Spring AI 2.0 (MCP Java SDK 2.0), Maven multi-module. Versions live in `pom.xml`.
Design deck (source of the decisions below): https://claude.ai/artifact/AJJgpoyATXruyfTzR5cf9v

**Core principle: MCP is only the front door. The domain never knows it exists.** Business logic is plain Java,
testable without Spring or MCP; the MCP layer only translates tool calls into service calls. The server never writes
prose — it returns numbers and structured data; Claude decides which tools to call and writes the explanation.

> **Current state:** the 7 modules exist with configured POMs but no code yet — only `FinanceMcpApplication`
> (an MCP server over STDIO with zero tools) and its context test. Phase 1 fills them in.

## Module boundaries (strict, hexagonal — dependencies point inward)

| Module | Owns | Depends on |
|---|---|---|
| root `pom.xml` (parent) | Java 25, Spring Boot 4 + Spring AI 2.0 BOMs, plugin versions, the module list | — |
| `domain` | `Transaction`, `Money`, `Currency`, `Direction`, `Category`, categorization rules (`CategorizationRule` chain), subscription/unusual-spend detection. **Pure Java — no Spring, no POI, no JDBC** | nothing |
| `application` | Use-case services (`ImportService`, `SpendingService`, `SubscriptionService`, `ComparisonService`, …), **port interfaces** (`StatementParser`, `TransactionRepository`, `ExchangeRateProvider`), result records (`ImportSummary`, `RowResult`). **No Spring, no framework imports** | domain |
| `adapter-parser` | `EvocaXlsxParser` (Apache POI), `GenericCsvParser` (Commons CSV), `ParserFactory` (picks the parser from file headers) | application |
| `adapter-persistence` | `SqliteTransactionRepository` etc. (`JdbcClient`), Flyway migrations (`src/main/resources/db/migration`) | application |
| `adapter-fx` | `FixedRateProvider` (rate from config); later `CbaRateProvider` + cache | application |
| `mockdata` | Generator for fake Evoca-style statements (fixed random seed); used by tests as fixtures and for demos | domain |
| `mcp-server` | The **only** Spring Boot application: `@McpTool` classes, `@McpResource` (`finance://categories`), wiring, `@ConfigurationProperties`, STDIO config | all of the above |

- Base package `com.artyomh.finance.<module>`: `domain`, `application`, `adapter.parser`, `adapter.persistence`,
  `adapter.fx`, `mockdata`, `mcp`. Source roots `<module-dir>/src/main/java/com/artyomh/finance/…`.
- **Nothing depends on the server; the domain depends on nothing; adapters never depend on each other.** If a parser
  needs a category, it asks the application layer, not the persistence adapter. The application never imports POI,
  SQLite, JDBC or Spring AI classes. `graphify check` exits 2 on a violation; ArchUnit tests fail the build.
- New tool → `mcp-server` (thin: validate input, call one service, map the result). New use case or port →
  `application`. New business rule / value type → `domain`. New bank format → a new
  `StatementParser` in `adapter-parser`, registered in `ParserFactory`. New table/query → persistence.
- **"Later" features must already have their interface in the MVP** (daily CBA rates, more banks, PDF, budgets,
  Streamable HTTP, web dashboard) so adding them never changes existing code — but don't build them now.

## Ports & patterns (each answers "what breaks if we don't?")

| Port | Defined in | MVP adapter | Later |
|---|---|---|---|
| `StatementParser` | application | `EvocaXlsxParser`, `GenericCsvParser` | `AmeriaParser`, `PdfParser` |
| `TransactionRepository` | application | `SqliteTransactionRepository` | Postgres repository |
| `ExchangeRateProvider` | application | `FixedRateProvider` (config) | `CbaRateProvider` + cache |
| `CategorizationRule` | domain | `OverrideRule`, `MerchantRule`, `KeywordRule`, fallback → `OTHER` / `OTHER_INCOME` by direction | ML-based rule |

Hexagonal (overall) · Adapter (one parser per bank format) · Factory (parser from file headers) · Strategy
(exchange-rate source) · Chain of Responsibility (categorization: `Optional<Category> apply(Transaction)`, empty =
"ask the next rule", first answer wins) · Repository · Facade (one service call per MCP tool) · Value Object
(`Money` — never add USD to AMD) · DI (Spring wires adapters into services in the server module only).

## Domain rules

- **Money:** `BigDecimal` in Java, **integer minor units in SQLite** (9.99 USD → `999`); the repository converts at the
  boundary. Never `double`/`float`. `Money` is always positive; `Direction` (`EXPENSE`/`INCOME`) says in or out.
  `Transaction` keeps the original fact (`money`) **and** `amountAmd` + `exchangeRate` so totals are one-currency and
  the rate is auditable. Currencies: `AMD`, `USD`.
- **Category** is a fixed enum of 16 values, each with a display name and a `Direction` (3 income: `SALARY`,
  `FREELANCE`, `OTHER_INCOME`; 13 expense: `RENT`, `UTILITIES`, `INTERNET_MOBILE`, `SUBSCRIPTIONS`, `GROCERIES`,
  `CAFES_RESTAURANTS`, `TRANSPORT`, `HEALTH_FITNESS`, `SHOPPING`, `TRAVEL`, `CASH`, `TRANSFERS`, `OTHER`). Users fix
  mistakes per merchant with `set_category` (stored in `category_overrides`), never by inventing categories.
- **Keyword matching is whole-word**: "ENA" (electricity) must not match "OPENAI".
- **Idempotent import:** transaction id = hash of date, description, amount, currency and occurrence index — importing
  the same file twice adds nothing. `import_log` records file name/hash and added/duplicate/rejected counts.
- **Every row ends in exactly one outcome:** `sealed interface RowResult permits Imported, Duplicate, Rejected`;
  `ImportService` folds them into an `ImportSummary` (added, duplicates, rejected with reasons).

## MCP tools (the product surface — see `mcp-server-tools`)

`import_statement(filePath)` · `list_months()` · `spending_by_category(month)` · `top_merchants(month, limit)` ·
`compare_months(monthA, monthB)` · `find_subscriptions()` · `unusual_transactions(month)` ·
`search_transactions(query, month, category)` (max 50) · `set_category(merchant, category)`; resource
`finance://categories`. MVP core three: `spending_by_category`, `find_subscriptions`, `compare_months`.

- Small structured results (records), clear descriptions (Claude picks tools by description), read-only by default,
  idempotent import, and **errors that list valid options** ("No data for 2026-03. Available: 2026-05 to 2026-10").
- **STDIO hygiene:** stdout is the JSON-RPC channel. No banner, no console logging to stdout, no `System.out` — logs
  go to a file or stderr. One stray line breaks the protocol.
- Nothing leaves the machine: no network calls in the MVP, no telemetry. Bank data is personal financial PII — never
  log descriptions, merchants or amounts above DEBUG, never commit real statements (mock data only).

## Coding conventions (one style, always)

- **Grill BEFORE building.** Before any new feature, tool, schema change, design decision or non-trivial refactor,
  invoke `grill-me` and interview the user one question at a time. Facts are looked up; decisions are the user's.
  Skip only for trivial mechanical fixes or an explicit "just do it".
- **Project skills FIRST.** `.claude/skills/*` outrank general knowledge; invoke every skill whose trigger matches.
- **Mandatory skill invocations — every time:**
  - module placement / a new port / an import across modules → `hexagonal-architecture` + `package-layout`
  - any MCP tool, resource, tool description or server config → `mcp-server-tools`. A PreToolUse hook
    (`spring-ai-docs-reminder.sh`) requires reading the current Spring AI MCP docs (WebFetch, the version in the pom)
    before editing Spring AI code — Spring AI 2.0 differs from 1.x tutorials
  - any SQL / `JdbcClient` / repository → `sqlite-jdbcclient`; any schema change → `flyway-migrations`
  - money, currency, rounding, exchange rates → `money-and-fx`
  - parsers, `ParserFactory`, import, dedupe, `RowResult` → `statement-import`
  - categorization rules, keywords, overrides → `categorization-rules`
  - any comment or JavaDoc → `comment-style`; tests → `spring-boot-testing`
- **Principal-engineer bar.** `effective-java` before any method/class; `java-25-idioms`; `oop-architecture` for
  responsibilities; `design-patterns` when a named pattern fits; `abstraction-design` before ANY interface, sealed
  hierarchy, generic or shared supertype — abstraction follows polymorphic use, never shape (ports listed above are
  pre-approved). **≤ 3–4 parameters** (else a parameter-object record); **no boolean flag params**; **no magic
  numbers/strings** (constants, enums, or `@ConfigurationProperties` for tunables like the fixed USD rate, limits,
  thresholds); small single-responsibility methods.
- **Comments: maximum brief** (`comment-style`): default none; a JavaDoc is one sentence, no tags; `//` only for a *why*.
  Exception: `@McpTool`/`@McpToolParam` descriptions are product copy for Claude — clear and complete.
- **Constructor injection only**, `private final` fields, no `@Autowired`. Config via `@ConfigurationProperties`
  records. Domain/application classes are plain constructors — Spring beans are declared in the server module.
- **Always braces.** **Never a ternary `?:` in `src/main`** (tests exempt). **No `var` locals.** Weaken types to the most
  abstract that suffices.
- **Records** for value objects, results and tool outputs; `sealed` + exhaustive `switch` (no `default`) for closed
  sets; enums for fixed choices. No Lombok.
- **No micro utility classes, no mirroring records.** A ≲15-line helper stays a private method, even if duplicated.

## Exceptions & edge cases (always think about them)

Consider failure modes up front — file not found / unreadable, unknown bank format, malformed row, month with no data,
unknown category or merchant, duplicate import, SQLite locked, unsupported currency — then reuse or create a fitting
exception. Never let a raw framework exception (POI, CSV, `DataAccessException`, `SQLException`) leak across a module
boundary.

- **Each module owns its exceptions** in a flat `exception` package; a sealed base in `domain`
  (`FinanceException`) with subtypes where callers must distinguish. Adapters catch library failures and rethrow a
  port-level exception. A bad *row* is not an exception — it is a `Rejected` `RowResult` with a reason.
- **The MCP layer maps exceptions to tool errors** with an actionable message listing valid options; never a stack
  trace, never a raw library message, never file contents.

## Definition of done (enforced)

After changing Java, before finishing, in order:

1. **`resolve-duplicates`** over the changes;
2. **`abstraction-design`** — a verdict per new/changed type ("stay flat" is valid);
3. the **`style-check`** agent — fix everything it reports;
4. `./mvnw -q test` (or `./mvnw -q -pl <module> -am test`) green;
5. **`/premium-review`** — quality, security, performance and MCP-contract lanes in parallel, then a completeness
   critic; fix every confirmed BLOCKER/HIGH, put the rest to the user.

The `Stop` hook `.claude/hooks/stop-enforce-checks.sh` blocks the turn until this is done whenever a `*.java` file
changed. `PostToolUse` hooks after each edit: `format-warn.sh` (formatting drift), `package-layout-warn.sh`
(placement, advisory), `stdio-hygiene-warn.sh` (stdout writes in main code) and `graphify-check.sh` (regenerates
`docs/generated/*.md`; hard feedback on a module-direction violation).

## Build / test / run

- Requires **JDK 25**. `./mvnw clean verify`; `./mvnw test`; one module: `./mvnw -pl domain -am test`.
- Tests: JUnit 5, AssertJ, ArchUnit (module rules). Domain/application tests need **no Spring context** (milliseconds);
  parsers on mock .xlsx/.csv fixtures; SQLite repository on a temp file; `@SpringBootTest` integration imports a mock
  file then queries every tool; MCP contract tests over STDIO / MCP Inspector. `mockdata` (fixed seed) feeds
  every level. See `spring-boot-testing` and the `java-testing` agent.
- Run locally: `./mvnw -pl mcp-server -am package`, then register the jar in Claude Desktop's
  `claude_desktop_config.json` (`"command": "java", "args": ["-jar", "<abs path>.jar"]`), or debug with
  `npx @modelcontextprotocol/inspector java -jar <jar>`.
- No secrets exist in the MVP; the SQLite file path and the fixed rate come from config. Never commit `*.db` or real
  statements.

## Roadmap

Phase 1 (MVP): parent + 7 modules · domain + Category · mock data generator · Evoca-style XLSX/CSV parsers · SQLite +
Flyway · 3 core tools over STDIO — done when Claude Desktop answers "where did my money go in October?" from the mock
statement. Phase 2: remaining 6 tools, CBA daily rates, budgets, second bank, MCP contract tests. Phase 3: Streamable
HTTP, web dashboard, PDF, real statements.
