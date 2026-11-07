---
name: comment-style
description: >-
  Invoke BEFORE writing or changing ANY comment or JavaDoc in Java code of the fin-track-mcp repo
  (every Maven module, also Flyway SQL and test sources) — the maximum-brevity bar. Covers when
  a JavaDoc is warranted at all, its one-sentence cap, the ban on @param/@return/@throws tags, when
  a `//` comment earns its line (a why the code cannot state), the rule that an existing JavaDoc is
  condensed, never deleted, and the one exception: @McpTool/@McpToolParam descriptions are product
  copy for Claude. Triggers: new class / method / record / exception / constant, "add javadoc",
  "document this", "comment this", "explain what this does", "reduce the comments", "too many
  comments", a comment longer than one sentence, an @param/@return tag, a `//` line restating the
  code, a section banner, commented-out code.
---

# Comment style (maximum brevity)

**The code is the documentation. A comment earns its line only by saying something the code cannot.** The default for
new code is **no comment**. When one is warranted, it is one sentence.

Brevity is not a licence to delete: an existing JavaDoc block documents an API element, and removing it is a regression.
Condense it instead.

## JavaDoc

- **Don't add one by default.** A well-named class/method needs none. `findByMonth` is documented by its signature; a
  JavaDoc saying "Finds transactions by month." is pure noise.
- **Add one only for something the signature cannot carry:** an invariant, an ordering constraint, a non-obvious *why*,
  the reason a constant has that value, which rule in the categorization chain must run first.
- **Cap: one sentence, ≤ 2 physical lines.** If it won't fit, cut at the first top-level `—`, `:`
  or `;` and keep the head.
- **No tags at all** — no `@param`, `@return`, `@throws`, `@author`, `@since`. Nothing publishes a javadoc artifact from
  this repo, so tags are cost with no reader. Inline `{@code}` / `{@link}` is fine *inside* the sentence.
- **Never delete an existing block — condense it to its summary sentence.**
- **Don't fight the IDE over the form.** IntelliJ re-expands a one-line `/** … */` back into the 3-line block. Brevity
  is measured in *sentences*, not braces — the 3-line block holding one sentence is correct and final.

```java
// BAD — restates the signature, tags add nothing, 6 lines for zero information
/**
 * Imports the given statement file.
 *
 * @param file the statement file to import
 * @return the import summary
 * @throws UnknownStatementFormatException if no parser accepts the file
 */
public ImportSummary importStatement(Path file) { … }

// GOOD — no JavaDoc; the name and types already say this
public ImportSummary importStatement(Path file) { … }

// GOOD — one sentence, because the *reason* is not in the signature
/**
 * Runs before {@code KnownMerchantRule} so a user's {@code set_category} correction always beats the built-in merchant table.
 */
public final class MerchantOverrideRule implements CategorizationRule { … }
```

## The exception: MCP tool copy

`@McpTool(description = …)` and `@McpToolParam(description = …)` are **not comments** — they are product copy that
Claude reads to decide which tool to call and how to fill its arguments. They are clear and complete: what the tool
answers, the expected input format (`month` as `YYYY-MM`), limits (max 50 results), and what an empty result means. The
brevity rules above still apply to any JavaDoc on the same class or method — which usually means none, because the
description already says it. See `mcp-server-tools`.

## `//` comments

- **Default: none.** A comment describing *what* the next line does means the name is wrong — delete the comment and fix
  the name.
- **Keep it only for a *why* the code cannot state.** The kinds that earn their place here:
    - an ordering constraint — `// hash before categorizing: a later override must not change the transaction id`
    - a deliberate no-op — `// a Duplicate row was imported earlier, so there is nothing to write`
    - an external-format hazard — `// Evoca exports the amount as text with a thousands separator in the AMD sheet only`
    - a protocol hazard — `// stdout is the JSON-RPC channel; log to stderr`
    - a `// TODO` with a real follow-up (and ideally an issue reference)
- **One line**, trailing on the line it qualifies or directly above it. Never a paragraph.
- **Never:** section banners (`// ---- helpers ----`), changelog/attribution notes, commented-out code, a comment
  restating an annotation (`// MCP tool`), or a note that you obeyed a convention
  (`// constructor injection per CLAUDE.md`).

## Beyond Java

- **Flyway migrations:** at most one leading `--` line saying *why* the change exists; the DDL says what. No
  column-by-column commentary (not even "amounts are minor units" — the column name `amount_minor` says it).
- **Tests:** the method name is the documentation (`keywordRuleDoesNotMatchEnaInsideOpenAi`). Never label AAA
  sections — no `// given`, `// when`, `// then`, `// arrange`. A blank line separates the phases. An assertion
  message (AssertJ `.as(...)`) is the place for an explanation, not a comment.
- **Mock data** (`mockdata`): the fixed seed and merchant tables are code — name them well; a one-line `//`
  may say *why* a fixture row exists (`// same row twice: exercises the occurrence index in the id hash`).
- **Docs/markdown:** CLAUDE.md already wants docs short — same instinct, one sentence per point.

## Red flags — stop and cut

- A JavaDoc whose first sentence paraphrases the method name.
- Any `@param` / `@return` / `@throws` you were about to type.
- A JavaDoc block growing past 2 lines "because the method is complex" → the *method* is the problem; split it
  (`effective-java`).
- Writing a comment to explain a block inside a method → extract a named private method instead.
- Deleting a JavaDoc block outright "since brevity is the rule" → condense, don't delete.
- Shortening an `@McpTool` description to one terse phrase "for brevity" → that is product copy; keep it complete.

## Checklist before finishing a file

- [ ] Every new comment says something the code cannot?
- [ ] Every JavaDoc ≤ 1 sentence (2 lines max), no tags?
- [ ] Every pre-existing JavaDoc still present, just condensed?
- [ ] No banners, no commented-out code, no what-comments, no AAA labels?
- [ ] `@McpTool` / `@McpToolParam` descriptions complete enough for Claude to pick and call the tool?
