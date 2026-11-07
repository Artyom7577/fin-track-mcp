---
name: grill-me
description: Invoke BEFORE starting ANY new feature, MCP tool, resource, schema change, bank format, categorization rule, design decision, or non-trivial refactor in the fin-track-mcp repo (local personal-finance MCP server, Java/Spring Boot). A relentless one-question-at-a-time interview that stress-tests the plan until user and agent reach a shared understanding. Triggers: "add", "build", "implement", "create", "redesign", "change how", "should we", a new tool or question Claude should answer, a new requirement or business rule, a new bank export, an ambiguous request, or any task with more than one defensible approach. Skip only for trivial mechanical fixes (typos, formatting, a rename) or when the user explicitly says "just do it". (Adapted from mattpocock/skills grill-me.)
---

# Grill me

Interview the user relentlessly about every aspect of the task until you reach a shared
understanding. Walk down each branch of the decision tree, resolving dependencies between decisions
one-by-one. For each question, provide your recommended answer.

Ask the questions **one at a time**, waiting for feedback on each question before continuing.
Asking multiple questions at once is bewildering.

If a *fact* can be found by exploring the environment (filesystem, code, mock data, the bank export
fixtures, tools), look it up rather than asking. The *decisions*, though, are the user's — put each
one to them and wait for their answer.

Do not act until the user confirms you have reached a shared understanding.

## In this repo, always grill at least these branches

### Every task

- **The question being answered**: which natural-language question will the user ask Claude
  ("where did my money go in October?", "what am I subscribed to?"), and which tool call(s) and
  numbers let Claude answer it? The server returns numbers and structured data; Claude writes the
  prose.
- **Money**: does this touch amounts? Which currency is reported (`amountAmd` totals vs the original
  `money`), which exchange rate applies and from where, rounding, and does anything risk adding USD
  to AMD? See `money-and-fx`.
- **Categories**: does this need a new category (it shouldn't — the 16-value enum is fixed; fixes go
  through `set_category` overrides), a new keyword/merchant rule, and where in the chain? See
  `categorization-rules`.
- **Placement**: which module/package (`domain` / `application` / an adapter /
  `mcp-server`, see `hexagonal-architecture` + `package-layout`), whether a port is needed,
  and whether something existing already owns the responsibility.

### Tools & data

- **Contract**: exact tool name, parameters (formats like `YYYY-MM`, limits like max 50), the result
  record, the `@McpTool` description Claude will route on, and read-only vs write. See
  `mcp-server-tools`.
- **Failure modes**: what each bad input / unknown file / unknown bank format / month with no data /
  unknown category or merchant / malformed row / SQLite locked / unsupported currency returns —
  which `FinanceException` subtype, and which tool error message listing valid options ("No data
  for 2026-03. Available: 2026-05 to 2026-10"). A bad *row* is a `Rejected` `RowOutcome`, not an
  exception.
- **Idempotency & data**: does it write? Is re-running it safe (re-import adds nothing)? Does it need
  a table or column (`flyway-migrations`, `sqlite-jdbcclient`), and how do existing rows migrate?
- **Privacy**: bank data is personal financial PII. Does anything log descriptions, merchants or
  amounts above DEBUG, write to stdout (breaks STDIO), reach the network, or risk committing a real
  statement? Fixtures come from `mockdata` only.
- **"Later" seams**: does a planned later feature (CBA daily rates, a second bank, PDF, budgets,
  Streamable HTTP, dashboard) need its interface now so adding it never changes this code — and are
  we resisting building it now?

When the user answers with a decision that contradicts an existing convention (CLAUDE.md, a skill,
or a memory), surface the conflict in the next question instead of silently picking a side.
