---
name: effective-java
description: >-
  Invoke BEFORE writing or changing any Java method/class in the fin-track-mcp repo — the
  principal-engineer quality bar for readable, effective code. Covers method & API design (replace
  long parameter lists with a parameter object/record; no boolean/flag params; small cohesive methods;
  command-query separation), constructor injection (and plain constructors in domain/application),
  named constants / @ConfigurationProperties instead of magic numbers/strings/booleans, weakening
  types to the most abstract that suffices (Collection over List, etc.), immutability & null
  discipline, BigDecimal money arithmetic, and meaningful names. Triggers: new/edited method, "too
  many arguments", "magic number", "refactor", "clean up", "readable", "best practice", writing any
  service, parser, rule, repository, MCP tool class or mapper.
---

# Effective Java (principal-engineer quality bar)

Apply these on **every** method/class you write or touch. When in doubt, optimise for the reader.
(Type weakening below is about *using* the most abstract existing type — *creating* a new
abstraction is gated by the `abstraction-design` skill.)

## Method & API design

- **Few parameters — ≤ 3–4** (CLAUDE.md). Past that, introduce a **parameter object** — a
  `record` — instead of a positional list. A long list is unreadable and easy to mis-order.
  ```java
  // BAD — 7 positional args, three Strings and two nullable filters waiting to be transposed
  search(String query, String month, String category, String merchant,
         BigDecimal minAmount, int limit, boolean includeIncome)
  // GOOD — one cohesive query value; the tool maps its params into it once
  search(TransactionQuery query)
  ```
  Reuse the record the service already takes — don't mint a mirroring one. A `record` with
  many fields is fine — that's a cohesive value, not a "long parameter list".
- **No flag/boolean parameters.** `totals(month, true)` is opaque at the call site. Split into two
  intention-revealing methods (`expenseTotals` / `incomeTotals`), or pass an enum
  (`Direction.EXPENSE`). A boolean carrying requested *state* is data, not a flag, and is fine.
- **Small, cohesive methods**, one responsibility each. Extract a private helper when a method does
  two things or needs a comment to explain a block.
- **Command-Query Separation** — a method either returns a value or causes a side effect, not both
  in a surprising way. (`ImportService.importStatement` writing rows *and* returning the
  `ImportSummary` is the deliberate exception: the tool contract returns it.)
- **Return the weakest useful type**, accept the widest (see below).

## Dependency injection — constructor only

- Every collaborator comes through **one constructor**, assigned to `private final` fields. No
  `@Autowired` anywhere. Domain and application classes are **plain Java** — no `@Service`, no
  Spring imports; `mcp-server` declares them as beans.
  ```java
  public final class ImportService {
      private final ParserFactory parsers;
      private final TransactionRepository transactions;
      private final Categorizer categorizer;

      public ImportService(ParserFactory parsers, TransactionRepository transactions,
                           Categorizer categorizer) {
          this.parsers = parsers;
          this.transactions = transactions;
          this.categorizer = categorizer;
      }
  }
  ```
- **No Lombok** (CLAUDE.md) — write the constructor.
- Config goes in via a `@ConfigurationProperties` record in the server module (database path,
  fixed USD→AMD rate, search limit) — not scattered `@Value` strings; services receive the plain
  values or a provider, never the Spring properties type.
- A constructor taking more than ~5 collaborators is the class telling you it has two jobs
  (`oop-architecture`) — the parameter-count rule doesn't apply to injection constructors, but the
  smell does.
- Unit tests construct the class directly, no Spring context:
  `new ImportService(parsers, inMemoryRepository, categorizer)`.

## No magic numbers / strings / booleans

Name every non-obvious literal as a `private static final` constant (or enum, or a
`@ConfigurationProperties` value when the user may tune it). Especially: the fixed USD rate,
search result caps, subscription cadence tolerances, unusual-spend thresholds, header names a
parser matches on, date formats.
```java
// BAD
if (results.size() > 50) { … }
BigDecimal amd = usd.multiply(new BigDecimal("387.5"));
// GOOD
private static final int MAX_SEARCH_RESULTS = 50;
if (results.size() > MAX_SEARCH_RESULTS) { … }
ExchangeRate rate = rates.rateFor(Currency.USD, date);
```
The exchange rate is **data** (an `ExchangeRateProvider` backed by config), never a literal in a
service. Category keywords and merchant tables are data owned by their rule, not literals scattered
across services.

## Weaken types to the most abstract that suffices

(IntelliJ/Qodana `TypeMayBeWeakened`.) Declare params, fields, locals, and returns as the weakest
type the code actually uses — prefer interfaces over concrete classes.
```java
// BAD — nothing needs List or ArrayList here
BigDecimal totalAmd(ArrayList<Transaction> transactions) { for (Transaction t : transactions) { … } }
// GOOD — only iterated
BigDecimal totalAmd(Iterable<Transaction> transactions) { for (Transaction t : transactions) { … } }
```
Accept the widest input a method uses (`Collection<T>` when you need `size()`/`stream()`,
`Iterable<T>` when you only loop). Don't over-abstract past readability; **no `var` locals**
(CLAUDE.md — write the declared type).

## Money

- `BigDecimal` in Java, wrapped in `Money` with its `Currency`; **never `double`/`float`**. Integer
  minor units only in SQLite — the repository converts at the boundary (9.99 USD ↔ `999`).
- `Money` is always positive; `Direction` says in or out. Never encode an expense as a negative
  amount.
- Never add or compare across currencies — totals use `amountAmd`; the original `money` and
  `exchangeRate` stay for audit.
- Compare `BigDecimal` with `compareTo`, not `equals` (`1.0` vs `1.00`); set scale and
  `RoundingMode` explicitly at the one place that converts. See `money-and-fx`.

## Immutability & nulls

- Value objects / results / tool outputs → `record` (see `java-25-idioms`). No setters on domain
  state.
- Avoid returning `null` collections — return an empty/immutable one. `Optional` only as a return
  type at a lookup boundary — and as the `CategorizationRule` contract (empty = "ask the next rule").

## Also

- Follow the repo's hard rules (CLAUDE.md): **constructor injection**, **always braces**,
  **no ternary** in `src/main`, **no `var`**, records for results, package layout, **no `System.out`**
  (stdout is the MCP channel).
- For class collaborations / choosing a pattern → `design-patterns`; for SOLID & structure →
  `oop-architecture`; for records/sealed/switch idioms → `java-25-idioms`.

## Quick checklist before finishing a method

- [ ] ≤ 3–4 params, or a parameter-object `record`? No boolean flags?
- [ ] Collaborators via the constructor into `private final` fields? No Spring in domain/application?
- [ ] Every literal named (no magic numbers/strings), rate/limits/thresholds configurable?
- [ ] Types weakened to the most abstract that suffices? No `var`?
- [ ] One responsibility; reads top-to-bottom without a comment to decode a block?
- [ ] Immutable value types; no null collections returned; no floating-point money; no cross-currency math?
