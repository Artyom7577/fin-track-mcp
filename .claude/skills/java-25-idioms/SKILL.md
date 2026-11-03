---
name: java-25-idioms
description: >-
  Use when writing or reviewing Java 25 code in the fin-track-mcp repo (Spring Boot 4, Spring AI
  MCP server, hexagonal Maven modules) and picking modern language idioms: records for value
  objects (Money, Transaction), results and MCP tool outputs, compact canonical constructors for
  validation and normalization, sealed interfaces + records as algebraic result types (RowResult =
  Imported | Duplicate | Rejected, the FinanceException hierarchy), exhaustive pattern-matching
  switch with record deconstruction and guarded (when) patterns and deliberately NO default branch,
  instanceof pattern matching, text blocks, sequenced collections (getFirst/getLast/reversed),
  Stream.toList, switch expressions over enums (Category, Direction, Currency), Optional only at
  boundaries and as the CategorizationRule contract, immutability / null discipline, and the
  blocking, single-client concurrency model of a STDIO MCP server. Keywords: record, sealed,
  permits, pattern matching, switch, deconstruction, exhaustive, immutability, value object, enum.
---

# Java 25 Modern Idioms (this codebase)

## Overview

Java 25 is the release level (`pom.xml`), so **every** Java 25 language feature is available. This
skill is the house style for using them.

This skill covers the *mechanics* of records/sealed types. **Whether** to introduce an
interface, hierarchy, or generic at all is decided by the `abstraction-design` skill —
invoke it first (abstraction follows polymorphic use, never shape).

Where idioms live by module (base package `com.artyomh.finance.*`):
**`domain`** (`…finance.domain`) is home for value objects (`Money`, `Transaction`),
enums (`Category`, `Direction`, `Currency`), the `CategorizationRule` chain, detection policies and
the sealed `FinanceException` base — pure Java, no Spring; **`application`**
(`…finance.application`) holds use-case services, port interfaces and result records
(`ImportSummary`, sealed `RowResult`) and the exhaustive switches over them; **adapters**
(`…finance.adapter.parser|persistence|fx`) map library types into those records;
**`mcp-server`** (`…finance.mcp`) holds `@McpTool` classes, their small result records and
the `@ConfigurationProperties` records. Package placement: `package-layout`.

**Core principle:** make illegal states unrepresentable. A validated value object cannot hold a
bad value (a negative `Money`, a `Money` without a currency, a `Transaction` without its
`amountAmd`); a sealed result type cannot be an outcome you forgot to handle. The compiler, not a
runtime check, enforces it.

**Concurrency model:** plain **blocking / imperative** Java. The server speaks MCP over STDIO to a
single client (Claude Desktop); no WebFlux, no `Mono`/`Flux`, no reactive anything (§7).

## When to use / when not

**Use when:** modelling domain data or the result of an operation; deciding record vs class vs
enum; writing a `switch` over a closed set; validating input at a boundary; deciding where
`Optional` belongs.

**Not for:** SQL and row mapping (`sqlite-jdbcclient`), schema (`flyway-migrations`), money rounding
and rates (`money-and-fx`), parser mechanics (`statement-import`), tool wiring and descriptions
(`mcp-server-tools`), deduplication strategy (`resolve-duplicates`). This skill is about the
language.

## Quick reference

| Idiom | Use when | Key syntax |
|---|---|---|
| Record | value object, result, tool output | `record Money(BigDecimal amount, Currency currency) {}` |
| Compact constructor | validate / normalize on construction | `public Money { if (amount.signum() < 0) { … } }` |
| Sealed interface + records | closed set of outcomes (ADT) | `sealed interface RowResult permits Imported, Duplicate, Rejected {}` |
| Pattern `switch`, no `default` | exhaustively handle a sealed type | `switch (r) { case Rejected(int row, String why) -> …; }` |
| Guarded pattern | matched shape **plus** a condition | `case Rejected(int row, String why) when row == HEADER_ROW -> …` |
| `instanceof` pattern | conditional downcast without a cast | `if (e instanceof SQLException sql && …)` |
| Text block | multi-line SQL / JSON fixture | `""" … """` |
| Sequenced collection | first / last / reversed | `months.getFirst()`, `months.getLast()` |
| `Stream.toList()` | terminal → unmodifiable list | `stream.map(…).toList()` |
| Switch expression | map value → value | `String sign = switch (direction) { … };` |
| `Optional` (boundary only) | maybe-absent lookup / "not mine" rule | `Optional<Category> apply(Transaction t)` |

## 1. Records for value objects, results and tool outputs

A record is the default for anything that is "fields + equality" with no mutable identity. The
compact canonical constructor is where you **validate and normalize** — it runs before the fields
are assigned.

```java
package com.artyomh.finance.domain;

import java.math.BigDecimal;
import java.util.Objects;

public record Money(BigDecimal amount, Currency currency) {
    public Money {
        Objects.requireNonNull(amount, "amount");
        Objects.requireNonNull(currency, "currency");
        if (amount.signum() < 0) {
            throw new IllegalArgumentException("money is always positive; use Direction for sign");
        }
    }

    public Money plus(Money other) {
        if (other.currency != currency) {
            throw new CurrencyMismatchException(currency, other.currency);
        }
        return new Money(amount.add(other.amount), currency);
    }
}
```

```java
package com.artyomh.finance.mcp.spending;

import java.math.BigDecimal;
import java.util.List;

public record SpendingByCategoryResult(String month, BigDecimal totalAmd, List<CategorySpend> categories) {
    public SpendingByCategoryResult {
        categories = List.copyOf(categories);
    }
}
```

Tool results are small records in `mcp-server` (they serialize to the JSON Claude reads);
use-case results (`ImportSummary`) live in `application`. Don't restate validation per
layer, and don't mint a record that merely mirrors an existing one (CLAUDE.md). Records deserialize
and serialize through their canonical constructor and accessors — no annotations needed.

## 2. Sealed interface + records = algebraic result type

When an operation has a **fixed, known set of outcomes**, model the whole result space as a sealed
hierarchy. Callers cannot invent a new case, and the compiler proves they handled every one (§3).

```java
package com.artyomh.finance.application.imports;

import com.artyomh.finance.domain.Transaction;

public sealed interface RowResult
        permits RowResult.Imported, RowResult.Duplicate, RowResult.Rejected {

    record Imported(Transaction transaction) implements RowResult {}
    record Duplicate(String transactionId)    implements RowResult {}
    record Rejected(int rowNumber, String reason) implements RowResult {}
}
```

A parser or service returns the type instead of throwing for *expected* outcomes — a malformed row
or a re-imported row is normal, not exceptional:

```java
RowResult classify(ParsedRow row) {
    if (row.amount().isEmpty()) {
        return new RowResult.Rejected(row.number(), MISSING_AMOUNT);
    }
    Transaction transaction = toTransaction(row);
    if (transactions.existsById(transaction.id())) {
        return new RowResult.Duplicate(transaction.id());
    }
    return new RowResult.Imported(transaction);
}
```

Failures that must abort the whole call (file not found, unknown bank format, SQLite locked) are
**exceptions** from the sealed `FinanceException` hierarchy, mapped to an actionable tool error in
`mcp-server` — a result type is for outcomes the caller legitimately branches on.

## 3. Exhaustive pattern-matching switch — omit `default` on purpose

Deconstruct records directly in `case` labels, add `when` guards for conditions, and **do not
write a `default` branch** over a sealed type.

```java
ImportSummary add(ImportSummary summary, RowResult result) {
    return switch (result) {
        case Imported _                       -> summary.withAdded();
        case Duplicate _                      -> summary.withDuplicate();
        case Rejected(int row, String reason) -> summary.withRejected(row, reason);
    };
}
```

**Why omitting `default` is the point:** with no `default`, adding a fourth permitted subtype
(say `Skipped`) makes every such switch **fail to compile** until you handle it — the compiler
hands you the list of every place to update. A `default` branch silently swallows the new case and
the bug ships. Use the unnamed pattern `_` for a case whose data you don't need. Guarded cases come before the unguarded one for the same record. The same holds for
switches over `Category`, `Direction` and `Currency` — list every constant, no `default`.

## 4. `instanceof` patterns and text blocks

`instanceof` pattern binds the cast in one step:

```java
if (cause instanceof SQLException sql && sql.getErrorCode() == SQLITE_BUSY) {
    throw new DatabaseLockedException(sql);
}
```

No `var` locals — this repo always writes the declared (weakest sufficient) type.

Text blocks for multi-line strings — SQL in `adapter-persistence` (static only, parameters
bound through `JdbcClient`, never concatenated input) and CSV/JSON fixtures in tests.

## 5. Sequenced collections, `Stream.toList()`, switch expressions, `Optional`

```java
// SequencedCollection: first/last/reversed directly (no get(0) / get(size() - 1)).
YearMonth earliest = availableMonths.getFirst();
YearMonth latest   = availableMonths.getLast();

// Stream.toList() returns an UNMODIFIABLE list — replaces collect(Collectors.toList()).
List<String> merchants = transactions.stream().map(Transaction::merchant).toList();

// Switch EXPRESSION maps value -> value (exhaustive over the enum, no default).
int minorUnitScale = switch (currency) {
    case AMD -> AMD_SCALE;
    case USD -> USD_SCALE;
};

// Optional is a RETURN type at a lookup boundary — never a field or parameter.
Optional<Category> override = overrides.findByMerchant(merchant);
```

`Optional<Category>` is also the `CategorizationRule` contract: empty means "ask the next rule".
Never `null`, never a sentinel `Category`.

## 6. Immutability & null discipline

Prefer immutable records with unmodifiable collections; validate in the canonical constructor;
keep nulls out of the domain (empty collections, not `null`; a value object or a sealed case
instead of a null field).

```java
public record ImportSummary(int added, int duplicates, List<RejectedRow> rejected) {
    public ImportSummary {
        rejected = List.copyOf(rejected);   // unmodifiable defensive copy; rejects null elements
    }
}
```

A record component that is a mutable array or collection is a leak unless you copy it in **and**
never hand the internal reference back out. A `byte[]` (raw file contents) in a record is always a
leak and breaks `equals` — keep file bytes out of domain records; pass the `Path` and let the parser
read it.

## 7. Concurrency — blocking, single client, keep it simple

The server runs over STDIO for one client; tool calls are short, local, blocking reads and writes to
a single SQLite file. There is nothing to scale:

- Don't add executors, `@Async`, parallel streams or virtual-thread tuning to "speed up" a tool —
  SQLite has one writer; concurrent writes only produce `SQLITE_BUSY`, which surfaces as a
  `FinanceException`, not a retry storm.
- Keep import atomic: one transaction per file in the repository adapter, so a failure halfway
  leaves no partial import.
- Never write to stdout from any thread — it is the JSON-RPC channel.
- If Streamable HTTP lands later (multiple clients), revisit this section then — don't pre-build for it.

## Common mistakes

- **`sealed`/record where a plain `enum` suffices.** A fixed set of *constants with no per-instance
  data* (`Direction`, `Currency`, `Category` with its display name and direction) is an `enum`.
  Sealed + records only when cases carry different data.
- **Adding a `default` branch over a sealed type or enum.** It silently defeats exhaustiveness (§3).
- **Mutable state leaking through a record component.** Defensively copy in the canonical
  constructor (`List.copyOf`, `Set.copyOf`).
- **Leaking persistence shape into records.** Map rows (minor-unit `long`s) into immutable domain
  records at the repository boundary — never pass `long` minor units or a `ResultSet` upward (see
  `sqlite-jdbcclient`).
- **`Optional` fields or parameters.** Return type at a lookup boundary (and the rule contract) only.
- **`double` for money.** `BigDecimal` in `Money`; minor-unit `long` only inside SQLite. Compare
  with `compareTo`, not `equals`.
- **A ternary to save an `if`.** No `?:` in `src/main` — use an `if` or a switch expression.
