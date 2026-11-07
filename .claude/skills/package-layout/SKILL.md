---
name: package-layout
description: Invoke when placing or organizing Java classes into packages in fin-track-mcp — deciding which module and package a new class goes in (domain, application, adapter-parser, adapter-persistence, adapter-fx, mockdata, mcp-server under com.artyomh.finance.<module>), adding a value type, rule, use-case service, port, result record, parser, repository, rate provider, MCP tool / resource / config class, exception, or test, or moving / reorganizing packages. Enforces concept sub-packages in domain (transaction, money, category, categorization, …), importing / analysis/<usecase> + flat port in application, by-bank/format and by-table sub-packages in adapters, tool/<area> + resource + config + exception in mcp, flat exception packages everywhere, and tests mirroring the class-under-test's package. Use together with hexagonal-architecture (which module) — this skill decides which package. Triggers: "which package", "where does this go", "new class", "move class", "reorganize packages", "result record", "port", "tool class", a class sitting directly in a module's base package, a package-layout-warn.sh warning.
---

# Package layout (fin-track-mcp)

`hexagonal-architecture` decides the **module** (and the dependency direction); this skill decides the
**package inside it**. Think in terms of the concept a class belongs to, not just its layer.

> Package names below are **examples of the convention**, not a fixed vocabulary — use the conventional
> name that fits the concept (`transaction`, `money`, `subscription`, `evoca`, `importlog`, …).
> Java keywords can't be package names: `import` → `importing` / `statement`.

## Modules

Module directories (root `pom.xml` is the parent): `domain`, `application`, `adapter-parser`,
`adapter-persistence`, `adapter-fx`, `mockdata`, `mcp-server`. Base package
`com.artyomh.finance.<module>`: `domain`, `application`, `adapter.parser`, `adapter.persistence`,
`adapter.fx`, `mockdata`, `mcp`. Source roots `<module-dir>/src/main/java/com/artyomh/finance/…`.
Direction (CLAUDE.md): the domain depends on nothing; adapters never depend on each other; nothing
depends on the server.

**No class sits directly in a module's base package** (except the `*Application` main class in `mcp`)
— group it into a concept sub-package.

| Module | Layout |
|---|---|
| `domain` | **By concept**: `transaction`, `money`, `category`, `categorization`, `merchant`; flat `exception` (detection over many transactions lives in `application/analysis`) |
| `application` | **`service/<usecase>`** (service + its result records), flat **`port`** (outbound interfaces + types only they use), flat `exception` |
| `adapter-parser` | `StatementFileParser` + `ParserLimits` at the root; **by format** in `format` (readers, `Cell`); **by bank** in `evoca` (header, row rules); flat `exception` |
| `adapter-persistence` | **By table group**: `transaction`, `category` (overrides), `importlog`; flat `exception`. Migrations in `src/main/resources/db/migration` |
| `adapter-fx` | **By rate source**: `fixed` (`FixedRateProvider`); later `cba` |
| `mockdata` | **By use case**: `scenario` (`Scenario` + Builder, sealed `ScenarioEntry`, `Scenarios` Object Mother), `generator` (`StatementGenerator`, `GeneratedStatement`, sealed `StatementLine`), `writer` (`StatementWriter`, XLSX / CSV), `cli` (mains). `writer` → `generator` → `scenario` only; `writer` never sees `scenario`; nothing depends on `cli` (ArchUnit `MockdataArchitectureTest`) |
| `mcp` (`mcp-server`) | `tool/<area>`, `resource`, `config`, `exception`; `FinanceMcpServerApplication` at the root |

## The convention

1. **Domain: one sub-package per concept** — never a flat `model`.
    - `domain/transaction`: `Transaction`, `TransactionId`, `Direction`.
    - `domain/money`: `Money`, `Currency`, `ExchangeRate` (→ `money-and-fx`).
    - `domain/category`: `Category` (the fixed 16-value enum).
    - `domain/categorization`: `CategorizationRule`, `MerchantOverrideRule`, `KnownMerchantRule`, `KeywordRule`, `DefaultCategorizationData`, the
      fallback and the chain (→ `categorization-rules`).
    - A type used by two concepts lives with the one that **owns** its meaning (`Direction` with
      `transaction`, even though `Category` references it).

2. **Application: top-level use-case packages + flat `port`.**
    - `importing/ImportService` with its results `ImportSummary` and the sealed
      `RowOutcome` (`Imported`, `Duplicate`, `Rejected`) — a sealed family stays in **one** package.
    - Read-side analysis lives under `analysis/<usecase>`: `analysis/spending/SpendingService` +
      `MonthlySpending`, `CategorySpending`, `MerchantSpending`, `MonthSummary`;
      `analysis/comparison/ComparisonService` + `MonthComparison`, `CategoryChange`;
      `analysis/subscription/SubscriptionService` + `MonthlyRecurrenceDetector`, `Subscription`, … .
    - `comparison` may depend on `spending`; `spending` and `subscription` never depend on `comparison`
      (ArchUnit `AnalysisArchitectureTest` in `application` tests).
    - **Result records live next to the service that returns them.** Something shared by several
      analysis use cases (`Percent`) sits at the `analysis` root as `public`, not in a `common`/`dto` package.
    - `port`: `StatementParser`, `TransactionRepository`, `ExchangeRateProvider`, override / import-log
      ports — and the types that exist only to cross a port (`ParseResult`, `ParsedRow`, `RowProblem`).
      Ports are pre-approved abstractions (CLAUDE.md); a new one still goes through `abstraction-design`.
    - A command/parameter record a service takes (`ImportRequest`) lives in that `service/<usecase>`.

3. **Adapters: grouped by the outside thing they adapt.**
    - Parser: `adapter/parser/StatementFileParser`, `adapter/parser/format/XlsxFormatReader`,
      `adapter/parser/evoca/EvocaRowMapper`. A new bank = a new sibling package (`ameria`) + one
      registration (→ `statement-import`). Helpers used by one format stay package-private in it.
    - Persistence: `adapter/persistence/transaction/SqliteTransactionRepository`,
      `…/category/SqliteCategoryOverrideRepository`, `…/importlog/SqliteImportLogRepository`. Row mappers
      are private methods of the repository, not a `mapper` package (→ `sqlite-jdbcclient`).
    - FX: `adapter/fx/fixed/FixedRateProvider`; later `adapter/fx/cba/CbaRateProvider` + its cache.
    - Port interfaces **never** live in an adapter (the hook warns) — adapters implement them.

4. **MCP server: thin, by role.**
    - `mcp/tool/<area>/<Area>Tools` — one `@Component` per cohesive tool group
      (`tool/spending/SpendingTools`, `tool/statement/ImportTools`, `tool/category/CategoryTools`,
      `tool/subscription/SubscriptionTools`). Input-shape helpers shared by several areas
      (`ToolInputs` — month / limit parsing) sit at the `tool` root, `public`.
    - **Tool outputs are the application result records** — don't mirror them. A record that exists
      only for the wire (a genuinely different shape) lives in that tool's `tool/<area>` package.
    - `mcp/resource`: `@McpResource` classes (`finance://categories`).
    - `mcp/config`: `FinanceBeans` (all bean wiring), `@ConfigurationProperties` records
      (`StorageProperties`, `ImportProperties`, `FxProperties`, `DetectionProperties`), the SQLite
      `DataSource`, STDIO/logging config.
    - `mcp/exception`: exception → tool-error mapping (`ToolErrors`, `ToolFailure`) (→ `mcp-server-tools`).

5. **Exception packages stay FLAT** in every module — never concept-grouped. `domain/exception` holds
   the abstract, **non-sealed** `FinanceException` (sealed subtypes would all have to share its package,
   no JPMS); other modules subclass it in their own flat `exception` package
   (`application/exception/MonthNotFoundException`) — see `hexagonal-architecture`.

6. **No grab-bag packages.** No `util`, `common`, `helper`, `dto`, `model` packages at a module root. A
   helper used by one package is package-private there; a ≲15-line helper stays a private method
   (CLAUDE.md). Named constants live in the class that owns their meaning.

7. **Tests mirror the class-under-test's package** in the same module
   (`application/src/test/java/com/artyomh/finance/application/analysis/spending/SpendingServiceTest`).
   Hand-written port fakes used by several application tests go in a test-source `…/application/fake`
   package; cross-module fixtures come from `mockdata`, never a test-jar of another module.
   Cross-module ArchUnit tests sit in `mcp-server` tests (`com.artyomh.finance.mcp.ArchitectureTest`) where every
   module is on the classpath (→ `spring-boot-testing`).

## Decision shortcuts

| You are adding… | Package |
|---|---|
| Value type / enum / business rule | `domain/<concept>` |
| Exception kind | `domain/exception`; module-specific subtype → `<module>/exception` |
| Use case | `application/importing` or `application/analysis/<usecase>/<Name>Service` |
| What a use case returns | same use-case package |
| Outbound capability | `application/port` |
| Bank format | `adapter/parser/<bank-or-format>` |
| Table / query | `adapter/persistence/<table-group>` + migration |
| Rate source | `adapter/fx/<source>` |
| Mock scenario / generator / file writer / CLI | `mockdata/scenario`, `mockdata/generator`, `mockdata/writer`, `mockdata/cli` |
| MCP tool | `mcp/tool/<area>` |
| Resource | `mcp/resource` |
| Bean / properties / DataSource | `mcp/config` |
| Exception → tool error | `mcp/exception` |

## Correcting a violation

1. Move the file into the right sub-package and update its `package` declaration.
2. Fix imports everywhere: classes that were same-package now need an explicit import; update every
   `import com.artyomh.finance.…OldPkg.Class;` across all modules (main + test).
3. Package-private members now reached from another package: make them `public` only if the move is right;
   otherwise reconsider the move.
4. `./mvnw -q test-compile` from the root, then the affected modules' tests.
5. `bash .claude/skills/graphify/graphify.sh check` — a move must not introduce a reversed import.

## Enforcement

The `.claude/hooks/package-layout-warn.sh` PostToolUse hook warns (advisory, non-blocking) when a class
sits directly in a module's base package, a port-shaped interface (`*Repository`, `*Parser`,
`*Provider`) is declared in an adapter, Spring / POI / JDBC / MCP imports appear in `domain` or
`application`, or an `@McpTool`/`@McpResource` appears outside `mcp-server`. `graphify-check.sh` and
the ArchUnit tests hard-fail a dependency against the direction.
