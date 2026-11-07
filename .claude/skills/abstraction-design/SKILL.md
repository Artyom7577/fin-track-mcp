---
name: abstraction-design
description: >-
  Invoke BEFORE introducing, changing, or reviewing any interface, sealed hierarchy, generic type
  parameter, or shared supertype in Java code of the fin-track-mcp repo — and whenever 2+
  records/classes look similar and "extracting the common part" is tempting (the per-tool result
  records, XlsxFormatReader vs CsvFormatReader internals, the monthly summary records). Also invoke
  when the Stop hook requires an abstraction pass over changed Java types (it is a
  definition-of-done step). Triggers: "abstraction", "interface", "port", "sealed", "hierarchy",
  "base class", "extract common", "shared shape", "generic", "supertype", a new record resembling
  an existing one, refactoring similar types, designing result/outcome types, "where should this
  interface live".
---

# Abstraction design (when to abstract, and how)

## Overview

**Abstraction follows polymorphic use, never shape.** A supertype is justified by a *consumer that
treats types uniformly*, not by field names that happen to match. Grep is the arbiter: if no call
site dispatches over the candidate types, the abstraction has zero clients and must not exist.

Related skills — this one decides *whether*; they cover *mechanics*: `java-25-idioms`
(record/sealed syntax, exhaustive switch), `effective-java` (type weakening, parameter objects),
`design-patterns` (named patterns), `oop-architecture` (responsibilities, module seams),
`hexagonal-architecture` (where a port lives).

## Pre-approved abstractions (CLAUDE.md)

These already passed the gate; don't relitigate them, and don't treat them as precedent for others:

| Abstraction | Gate | Why |
|---|---|---|
| `StatementParser` (application port) | #4 | Hexagonal port: `StatementFileParser` now, test fakes in `application`, more banks / PDF planned |
| `FormatReader` (internal to `adapter-parser`) | #1 | `StatementFileParser` dispatches over `XlsxFormatReader` and `CsvFormatReader` |
| `sealed Cell` (`adapter-parser/format`) | #2 | Closed set of cell kinds; `EvocaRowMapper` switches exhaustively |
| `TransactionRepository` (application port) | #4 | Hexagonal port; keeps JDBC/SQLite out of the application, planned second adapter (Postgres), in-memory double in tests |
| `ExchangeRateProvider` (application port) | #4 | Strategy at a seam: `FixedRateProvider` now, `CbaRateProvider` + cache planned |
| `CategorizationRule` (domain) | #1 | The chain iterates a `List<CategorizationRule>` — `MerchantOverrideRule`, `KnownMerchantRule`, `KeywordRule`, later ML |
| `sealed RowOutcome` = `Imported \| Duplicate \| Rejected` | #2 | Closed outcome set; `ImportService` folds it exhaustively into `ImportSummary` |
| `sealed FinanceException` + subtypes | #2 | Closed failure set; the MCP layer maps each subtype to an actionable tool error |

A *new* port, a new sealed hierarchy, or a new subtype of these still needs a verdict.

## The decision gate

Before introducing ANY interface / hierarchy / generic / shared supertype, at least one must be
YES — verified in code, not assumed:

1. **Dispatch exists.** A method accepts more than one of the types, a `switch`/`instanceof` runs
   over them, or a collection mixes them (`StatementFileParser` choosing a `FormatReader`; the categorization
   chain iterating rules). Verify with grep, never from memory. For a *new* design, the dispatching
   consumer must be created in the same change and named in the verdict — an abstraction never
   precedes or outlives its consumer.
2. **A closed set of variants/outcomes** needs exhaustive handling → sealed interface + record or
   class variants, consumed by an exhaustive `switch` with **no `default`** (`RowOutcome`;
   `FinanceException` and the MCP layer's error mapping).
3. **A genuinely type-independent algorithm** exists → generic (rare here; a fold or
   top-N over arbitrary elements only when the logic is provably identical for every element type).
4. **A dependency must be swappable at a module seam** — a test double or a second implementation
   that is actually planned in CLAUDE.md's "Later" column, not imagined. The port lives in
   `application` (or `domain` for pure rules) and the adapter in a
   `adapter-*` module; dependencies point inward only.

**All four NO → stay flat, and say so.** "Stay flat" is a first-class verdict, not a failure to
finish the job.

## What does NOT pass the gate

- **Shared field shape.** `CategorySpend(category, totalAmd, count)` and
  `MerchantSpend(merchant, totalAmd, count)` share fields — and stay independent records unless
  something consumes them uniformly: they answer different tools and change for different reasons.
  No `BaseSpendRow` / `HasTotal`.
- **Same-named fields with different semantics.** `money` (the original fact, any currency) vs
  `amountAmd` (converted, for totals); `month` as a `YearMonth` query vs a `YYYY-MM` string tool
  parameter; `description` (raw bank text) vs `merchant` (normalized). Nominal overlap is
  convergence, not duplication.
- **A merge/assembly point.** One private `toResult(...)` method per tool IS the abstraction for
  shared knowledge — don't replace it with a type hierarchy.
- **Parser internals.** A second bank's row rules and `EvocaRowMapper` will both parse dates and
  amounts — that is not a reason for an `AbstractRowMapper`. The port is the abstraction; shared
  ≲15-line helpers stay private in each, substantial shared logic goes to a collaborator.
- **An interface "for testability".** Plain constructors already let a test pass a fake or a mock
  of the concrete class. An interface with one `*Impl` is not a seam — the pre-approved ports are
  seams because a second adapter is planned.

## Mechanism catalogue (once the gate passes)

Records cannot extend classes; the toolbox is:

| Need | Mechanism | Rules |
|---|---|---|
| Uniform treatment of open set | Interface over records/classes | Small, capability-named (what callers *do*, not the data); lives next to its consumer, in the innermost module the dependency direction allows |
| Closed set of variants/outcomes | Sealed interface + record/class variants | All subtypes in one package (exceptions stay flat in the module's `exception` package); exhaustive `switch`, no `default` |
| Shared component group | Composition (nested record) | For a tool result this **changes the JSON Claude receives** — a contract decision (`mcp-server-tools`), not a refactor; never do it to "dedup" fields |
| Type-independent algorithm | Generic | Only when logic is provably identical for all type arguments; no speculative type parameters |
| Swappable collaborator | Port interface at a module seam | Defined in application/domain, implemented in an adapter; created when the second implementation or test double is real |

## Anti-rules

- No marker interfaces; no single-implementation interfaces (unless gate #4 genuinely holds).
  `graphify check` warns on them.
- No `Identifiable`/`HasMoney`/`Categorized`-style interfaces nothing dispatches through.
- No simulated base classes for records (no default-method-heavy "base" interfaces).
- Tool result records never enter hierarchies — they serialize to Claude; keep them flat. (A
  polymorphic tool result is a contract design decision — grill it, don't slide into it.)
- A sealed hierarchy never spans modules (subtypes must share a package).
- Don't move types to `domain` for shape — the domain is for business rules and value
  types that genuinely belong there.

## Verdict discipline (what "running this skill" means)

For every new or changed type, and for every group of 2+ similar types the change touches:

1. Name the candidate types.
2. Answer the four gate questions — with grep evidence for #1, not intuition (or cite the
   pre-approved table).
3. Record the verdict: `stay flat` or `abstract via <mechanism>` + the consumer that justifies it.

A pass that produces "stay flat" for everything is a *successful* pass.
