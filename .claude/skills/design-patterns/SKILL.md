---
name: design-patterns
description: >-
  Invoke when a design in Java code of the fin-track-mcp repo would benefit from a named pattern —
  object creation, swapping behaviour/algorithms (per bank format, per exchange-rate source, per
  categorization heuristic), adapting an external library or file format, hiding a use case behind
  one call, or modelling outcomes. A when-to-use catalogue (creational / structural / behavioural +
  enterprise patterns) fitted to this hexagonal Java 25 + Spring Boot 4 + Spring AI MCP + SQLite /
  JdbcClient + records + sealed-types codebase. Triggers: "which pattern", "factory", "builder",
  "strategy", "adapter", "facade", "chain of responsibility", "value object", "too many
  constructors", "if/else on a type", "swap implementation", modelling success/failure outcomes.
---

# Design patterns (when to use, in this codebase)

Patterns are a means, not a goal — reach for one only when it removes real complexity. Prefer the
simplest form (often a `record`, a `sealed` type, or a lambda) over a heavyweight classic pattern.
Any pattern that introduces an interface/hierarchy must first pass the `abstraction-design`
skill's decision gate (abstraction follows polymorphic use, never shape). The patterns CLAUDE.md
already commits to (Hexagonal, Adapter, Factory, Strategy, Chain of Responsibility, Repository,
Facade, Value Object, DI) are decided — apply them, don't relitigate them.

## Creational

- **Parameter object / record** — many related args → one immutable `record` (see `effective-java`).
  The everyday "pattern" here; use it first (`TransactionQuery(month, category, text)` instead of
  three nullable positional args).
- **Static factory** — hide construction / name the intent: `Money.of("9.99", USD)`,
  `Money.ofMinor(999, USD)`, `YearMonth.parse(month)` wrapped as a domain `Month` if one exists.
  Prefer to a public constructor when creation has logic or a meaningful name.
- **Factory (selection)** — `ParserFactory` picks the `StatementParser` from the file's headers.
  It iterates the injected parsers and asks each whether it accepts the file; it does not `switch`
  on bank names. An unknown format is a `FinanceException` listing the supported formats.
- **Builder** — only for objects with many *optional* fields where a record's canonical constructor
  is unwieldy (a mock-statement row with a dozen optional columns in `mockdata`). Don't add
  a builder to a simple record like `Transaction` or `ImportSummary`.

## Structural

- **Adapter / anti-corruption layer** — wrap an external library or file format so its types don't
  leak inward. `EvocaXlsxParser` adapts Apache POI cells into `RowResult`s; `GenericCsvParser`
  adapts Commons CSV; `SqliteTransactionRepository` adapts `JdbcClient` rows (minor units) into
  `Transaction`s (`BigDecimal`); `FixedRateProvider` adapts config into a rate. Library types never
  cross the adapter.
- **Facade** — one service call per MCP tool: `spending_by_category(month)` →
  `SpendingService.byCategory(month)`, which hides "load month → group by category → sum
  `amountAmd` → sort". Keep the facade thin; push logic into domain types and collaborators. The
  `@McpTool` class is itself a facade over the service — validate input, call one method, map the
  result.
- **DTO / mapper** — translate between layers (`CategorySpendResult.from(CategoryTotal)`); never
  expose a JDBC row, a POI cell or a mutable internal through a tool result.

## Behavioural

- **Strategy** — replace an `if/else`/`switch` that selects behaviour with an interface +
  implementations (or a lambda) when the set varies or grows: `ExchangeRateProvider` is
  `FixedRateProvider` today and `CbaRateProvider` + cache later; `StatementParser` gets one
  implementation per bank format. Spring injects `List<StatementParser>` into `ParserFactory` in the
  server module's wiring.
- **Chain of Responsibility** — categorization: an ordered `List<CategorizationRule>`, each
  `Optional<Category> apply(Transaction)`; empty means "ask the next rule", first answer wins, the
  chain ends in a fallback to `OTHER`. Order is `MerchantOverrideRule` → `KnownMerchantRule` → `KeywordRule` (YAML
  before defaults for both) → fallback. Each rule is individually testable; a new heuristic is a new link, not a new branch in
  one 80-line method. See `categorization-rules`.
- **Sealed type + exhaustive switch (algebraic result)** — model a closed set of outcomes as a
  `sealed interface` permitting `record`s, matched with a `switch` that has **no** `default`:
  `RowResult = Imported | Duplicate | Rejected(reason)`, folded by `ImportService` into an
  `ImportSummary`. A new case is a compile error at every consumer. See `java-25-idioms`.
- **Template method** — a fixed skeleton with pluggable steps; in modern Java prefer passing a
  lambda to a higher-order method (`readRows(sheet, this::toRowResult)`) over an abstract-class
  template shared by parsers.

## Enterprise / domain

- **Value Object** — `Money` (`BigDecimal` + `Currency`, always positive, `Direction` says in or
  out) refuses to add USD to AMD; `Category` is an enum carrying its display name and `Direction`.
  Equality by value; invariants in the canonical constructor. See `money-and-fx`.
- **Repository** — encapsulate persistence behind a domain-shaped port (`TransactionRepository`
  in `application`); callers never see SQL, `JdbcClient` or minor units. One repository owns
  one table's writes. See `sqlite-jdbcclient`.
- **Idempotency key** — the transaction id is a hash of date, description, amount, currency and
  occurrence index; importing the same file twice adds nothing (a row whose id exists is a
  `Duplicate`). `import_log` records the file hash and counts. See `statement-import`.
- **Policy / specification** — isolate a business rule in its own object instead of scattering
  `if`s across services: subscription detection (same merchant, similar amount, monthly cadence)
  and unusual-spend detection live in `domain` as named, unit-tested policies.
- **Dependency injection** — domain/application classes are plain constructors; Spring declares
  them as beans in `mcp-server` only. Never put `@Service` on an application class.

## Rule of thumb

Introduce a pattern to delete duplication or an `if/switch` that keeps growing, to create a seam for
testing, or to name a collaboration that's already implicit. If it adds indirection without removing
complexity, don't. Pair with `oop-architecture` (SOLID/structure) and `effective-java` (mechanics).
