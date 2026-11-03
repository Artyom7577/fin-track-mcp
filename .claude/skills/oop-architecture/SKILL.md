---
name: oop-architecture
description: >-
  Invoke when designing or refactoring class structure and collaborations in Java code of the
  fin-track-mcp repo — deciding a class's responsibility, splitting a god-class, choosing
  composition vs inheritance, or wiring dependencies. Applies SOLID, encapsulation, high cohesion /
  low coupling, and the project's hexagonal, inward-pointing module boundaries (domain ←
  application ← adapter-* / mcp-server). Triggers: "new class",
  "responsibility", "split this class", "SOLID", "coupling", "architecture", "where should this
  live", a service or tool class growing past ~200 lines or gaining a second reason to change.
---

# OOP & architecture

Before introducing ANY interface, hierarchy, or shared supertype, run the `abstraction-design`
skill's decision gate — abstraction follows polymorphic use, never shape. For module placement and
new ports, also run `hexagonal-architecture` + `package-layout`.

## SOLID (practical)

- **S — Single responsibility.** One reason to change per class. A service that parses the file
  *and* deduplicates rows *and* categorizes them *and* converts USD to AMD is four classes
  (`StatementParser`, the id hashing in `ImportService`, the `CategorizationRule` chain,
  `ExchangeRateProvider`). Split when a class grows a second concern.
- **O — Open/closed.** Extend via new types, not by editing a `switch` that must handle all cases —
  a new bank format is a new `StatementParser` registered in `ParserFactory`; a new categorization
  heuristic is a new `CategorizationRule` in the chain. *Except* the deliberate exhaustive `switch`
  over a `sealed` type (that's the point: a new `RowResult` subtype forces a compile error where it
  must be handled).
- **L — Liskov.** Subtypes honour the supertype's contract. Every `CategorizationRule` returns
  `Optional.empty()` for "not mine" and never throws for an unrecognised merchant; every
  `StatementParser` reports a bad row as `Rejected`, never by skipping it silently. Prefer
  `sealed` + `record` algebraic types over open inheritance hierarchies.
- **I — Interface segregation.** Small, role-focused interfaces; don't force clients to depend on
  methods they don't use. `ExchangeRateProvider` exposes a rate lookup, not a 20-method
  `FxFacade`; a read-only spending query doesn't need the import-side repository methods.
- **D — Dependency inversion.** Ports live in `application` (`StatementParser`,
  `TransactionRepository`, `ExchangeRateProvider`); adapters implement them. High-level policy
  (services) never sees low-level detail — an application service never touches a POI `Row`, a
  `JdbcClient`, a `SQLException` or a Spring AI type. Collaborators come through the
  **constructor** (`private final` fields).

## Structure

- **High cohesion, low coupling.** Group by domain (see `package-layout`); keep a class's data and
  the behaviour over it together. A class that only forwards calls (thin pass-through) should be
  merged — the one sanctioned forwarder is the `@McpTool` class, which validates input, calls one
  service and maps the result.
- **Composition over inheritance.** Reuse by delegating to a collaborator, not by extending a base
  class for code sharing. Inheritance is for genuine subtyping (`sealed` hierarchies). No
  `AbstractStatementParser` holding shared POI helpers — a parser delegates to a collaborator or
  keeps a private method.
- **Encapsulation.** No leaking mutable internals; return copies/immutables. Domain types are
  `record`s or classes with invariants enforced in the constructor (`Money` is never negative and
  always has a currency; a `Transaction` always carries `amountAmd` and the `exchangeRate` that
  produced it).
- **Tell, don't ask.** Put behaviour on the object that owns the data (`money.plus(other)` refusing
  a currency mismatch, `category.direction()`) instead of pulling its fields out to decide
  elsewhere.

## Module boundaries (this repo — strict, hexagonal, inward)

`domain` depends on nothing; `application` → domain; each `adapter-*` →
application; `mockdata` → domain; `mcp-server` → all of them. **Nothing depends on
the server, and adapters never depend on each other** — if a parser needs a category, it asks the
application layer, not the persistence adapter. `graphify check` exits 2 on a violation and
ArchUnit tests fail the build.

- POI / Commons CSV only in `adapter-parser`; `JdbcClient`, SQLite and Flyway only in
  `adapter-persistence`; rate sources only in `adapter-fx`; Spring Boot,
  `@McpTool`, `@ConfigurationProperties` and bean wiring only in `mcp-server`. Domain and
  application are plain Java — no Spring annotations, beans are declared in the server.
- Don't let a library type leak across a boundary — map to the port's own type at the edge:
  a parser yields `RowResult`s / domain `Transaction`s, never a POI `Row`; the repository returns
  `Transaction` / summary records, never a JDBC `ResultSet` or minor-unit `long`s (it converts at
  the boundary); a tool returns its own small result record, never an internal domain type that
  might grow fields Claude shouldn't see.
- Library exceptions (POI, CSV, `DataAccessException`, `SQLException`) are caught in the adapter
  and rethrown as the port-level `FinanceException` subtype; the MCP layer maps those to tool
  errors that list valid options.
- Shared code goes to the **lowest module both users depend on** (usually `domain` for
  value logic, `application` for use-case logic) — but only shared *logic* or genuinely
  shared value types, never "this looks reusable". There is no `common` module.

## When applying

Pick the smallest change that removes the smell: extract a class for a second responsibility, an
interface for a seam that has a real second implementation or test double, or a collaborator for
reuse. Verify with tests (domain/application tests need no Spring context). For the concrete
method/field mechanics (params, constants, types) use `effective-java`; for named collaboration
shapes use `design-patterns`.
