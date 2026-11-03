---
name: resolve-duplicates
description: Use this skill whenever the user mentions "duplicates", "code duplication", "DRY", or asks you to clean up or resolve duplicated logic — in this repo's Java code (any Maven module, tests included). Also use it as a strict behavioral guide to proactively avoid copy-pasting code or creating duplicated logic during your own implementations, and as the first step of the definition of done. Carries fin-track-mcp placement rules for the hexagonal modules (domain, application, adapters, mcp-server).
---

# Resolve Duplicates (DRY Principle)

Your objective is to relentlessly eliminate code duplication across the codebase applying the DRY (Don't Repeat
Yourself) principle — while knowing which duplication is deliberate and must stay.

## This repo (fin-track-mcp) — CLAUDE.md wins

Where this generic skill conflicts with `CLAUDE.md`, CLAUDE.md wins.

- **No micro utility classes.** A tiny (≲15-line) snippet repeated in two classes stays inline as a private method
  in each — that duplication is deliberate; don't "dedup" it into a `*Utils` class.
- **No mirroring records** — don't mint a record that copies another's fields (a `SearchTransactionsParams` that
  mirrors `TransactionQuery` field-for-field is banned until the shapes truly diverge).
- **Abstraction only through the `abstraction-design` gate** — no base classes or default-method interfaces to
  share shape. `EvocaXlsxParser` and `GenericCsvParser` both parse dates and amounts; that is *not* a reason for an
  `AbstractStatementParser`. `CategorySpend` and `MerchantSpend` both have a total and a count; not a reason for a
  base record.
- **Shared logic goes to the innermost module both users already depend on** — never against the dependency
  direction:
  - money / currency / rounding logic → a method on `Money` (or a domain type) in `domain`
    (`money-and-fx`), not two hand-rolled `BigDecimal` conversions;
  - use-case logic used by two services or two tools → `application` (a method on the service that owns the
    concern, so each `@McpTool` stays one service call);
  - two adapters never share code by depending on each other — logic both need belongs in application/domain, or
    stays duplicated if it is a ≲15-line library-specific helper.
- **SQL is not duplicated across repositories** — one repository owns one table's writes; the minor-unit ↔
  `BigDecimal` conversion lives in one place in `adapter-persistence` (see `sqlite-jdbcclient`).
- **Categorization logic lives in the chain** — a keyword check copied into a parser or a service is a duplicate of
  a `CategorizationRule`; route it through the chain (`categorization-rules`).
- **Tool error messages that list valid options** ("Available: 2026-05 to 2026-10") are built in one place per
  concern in `mcp-server`, not re-formatted in every tool.

## Core Directives

1. **Never Create Duplicates**:
   When you are generating new code or implementing a feature, DO NOT copy-paste blocks of logic. If you find yourself
   writing the same non-trivial logic twice, stop and encapsulate it — within one file a private helper; beyond that,
   see "This repo" above for where it may go.

2. **Global Pattern Matching**:
   If a user points out a duplicate in two files, do not assume those are the *only* two places. Always consider if the
   same logic might exist elsewhere in the codebase (every module, `mockdata` and tests included) and
   proactively search for it.

3. **Intelligent Centralization**:
   Extract shared logic into a single centralized place. Choose the most semantically appropriate home:
   - A private helper method (if contained within one file).
   - A method on the domain type or service/collaborator that owns the concern, in the innermost module both
     callers can reach.
   - A static utility class only when it is substantial (not ≲15 lines) — never a base class or default-method
     interface for shape (see `abstraction-design`).

4. **Parameterization**:
   Identify the variants (variables or types that change between the duplicated blocks) and pass them as arguments to
   the new shared method. A variant that selects *behaviour* is a strategy, a new `CategorizationRule`, a new
   `StatementParser`, or a separate method — not a boolean parameter.

5. **Control Flow Awareness**:
   If the duplicated code contains `return`, `break`, `continue`, early returns, or thrown exceptions, handle the
   extraction carefully. You may need to return an explicit state (an enum, a sealed result such as `RowResult`, an
   `Optional`) or accept a callback so the caller retains correct execution flow.

## Implementation Workflow

When asked to resolve duplicates by the user (or as the definition-of-done pass):

1. **Review the Logic**: Carefully trace the duplicated segments to ensure they do exactly the same thing. Look for
   hidden subtleties (mutable state, variable shadowing, a different scale or `RoundingMode` on a `BigDecimal`,
   `equals` vs `compareTo`, whole-word vs substring keyword matching, a different date format per bank).
2. **Decide**: deliberate duplication (micro-helper, convergent shape) stays — say so; real duplication gets one home.
3. **Abstract**: Create the new shared method.
4. **Replace**: Refactor all original instances to call the new centralized one.
5. **Verify**: Ensure identical behavior — `./mvnw -q -pl <module> -am test`.
