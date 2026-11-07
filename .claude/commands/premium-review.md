---
description: Run the premium-review team in parallel against the current diff or named files — quality, security, performance and MCP-contract lanes — then collate a unified ranked report.
---

You are orchestrating the **premium-review team**. Specialist agents review the same target in parallel; you collate
their findings into a single ranked report.

This command fans out several subagents at once — it is **expensive**. Run it for meaningful diffs (a feature, a
refactor, a new tool or migration, the definition-of-done step), not for one-line changes. For a quick pass on your
working tree, prefer `/code-review`.

# Step 1 — Identify the target

If the user named files in `$ARGUMENTS`, the target is those files.
Otherwise, detect the repo state and pick the first option that works:

1. **Git repo with a `main` branch and commits on top of it** (`git rev-parse --verify main` succeeds):
   - `git diff --stat main...HEAD` and `git status --short` → file set
   - `git log main...HEAD --oneline` → commit intent
   - `git diff main...HEAD -- <file>` / `git diff -- <file>` → hunks (per file, on demand)
2. **Git repo without `main`** (another default branch, or no commits yet): use the working tree —
   `git diff --stat HEAD` (skip if there is no `HEAD`), `git status --short`, and untracked files
   `git ls-files --others --exclude-standard`. If the default branch has another name (`git symbolic-ref
   refs/remotes/origin/HEAD`, or `master`), use it in place of `main` as in option 1.
3. **Not a git repo** (`git rev-parse --is-inside-work-tree` fails): there is no diff to compute. Ask the user which
   files or modules to review (suggest the files changed in this session, if you know them) and stop until they answer.

Ignore `target/`, `docs/generated/` and other build output. If nothing changed and no files were named, report "no
changes to review" and stop.

Every changed file goes to all four lanes below; each lane ignores what is outside its remit.

# Step 2 — Fan out in parallel

In a **single message**, spawn one `Agent` call per lane below, each with its real `subagent_type`. The agent
definition *is* its system prompt. Tell each to review the target and produce its structured report verbatim in the
format its definition specifies. Tell each how the target was identified (option 1/2/3 above) so it uses the same
commands.

| `subagent_type`         | Must also read (pass as input)                                                                                         |
|-------------------------|------------------------------------------------------------------------------------------------------------------------|
| `quality-reviewer`      | `CLAUDE.md` module table (for module-direction checks)                                                                 |
| `security-engineer`     | `pom.xml` and module poms (`*/pom.xml`), `mcp-server/src/main/resources/application*.yaml`, any `logback*.xml`, `.gitignore` |
| `performance-engineer`  | `adapter-persistence/src/main/resources/db/migration/*.sql` (existing indexes), `mcp-server/src/main/resources/application*.yaml` |
| `mcp-contract-reviewer` | `mcp-server/src/main/resources/application*.yaml`, any `logback*.xml`, `mcp-server/pom.xml`, the previous version of every changed `@McpTool` class and result record |

While the repo is still the single-module scaffold, the equivalent paths are `pom.xml`, `src/main/resources/application*.yaml`
and `src/main/resources/db/migration`. Skip any path that does not exist yet rather than failing.

Pass every subagent the exact same diff scope so reports are comparable.

# Step 3 — Completeness critic

After the reports return, spawn **one** `Agent` call (`subagent_type: general-purpose`) with the diff file list
and all reports. Ask: *"Which changed files or hunks did no reviewer substantively address, and which risk
dimensions went unexamined?"* Common blind spots for this project:

- **Money:** rounding mode/scale in USD→AMD conversion, minor-unit ↔ `BigDecimal` conversion at the repository
  boundary, `double`/`float` anywhere, mixing AMD and USD in a sum, totals computed from `money` instead of `amountAmd`.
- **Idempotent import:** transaction-id hash stability (field order, normalization, date/amount formatting, occurrence
  index) — any change silently re-imports or merges history; `import_log` counts.
- **Schema:** Flyway/SQLite drift — an edited applied migration, a column the repository reads that no migration
  creates, SQLite `ALTER TABLE` limits, missing index for a new predicate.
- **Module direction:** a framework import in domain/application, an adapter depending on another adapter, logic in
  the server module.
- **Tool contract:** a renamed/removed tool, param or result field; unit change; description drift; error copy that no
  longer lists valid options; `finance://categories` out of sync with `Category`.
- **PII:** descriptions, merchants, amounts or card/account numbers in logs (above DEBUG) or tool errors; stdout
  writes.
- **Parser edge cases:** empty file, header-only, malformed/blank rows, unknown currency, sign conventions, date
  formats, merged cells, a second bank's headers mis-detected by `StatementFileParser`.
- **Categorization:** whole-word matching, rule precedence (override → merchant → keyword → `OTHER`), direction
  mismatch.
- **Tests:** a changed behavior with no test (refer to `java-testing`).

It returns a short bullet list only — no new deep findings, never invented. "No blind spots" is a valid answer.

# Step 4 — Collate

One **unified report**, sections in this order. Deduplicate findings raised by two or more agents (list once,
attribute to all, take the highest severity).

```
## Diff under review
<one paragraph: files, commits (or "working tree" / "named files"), scope>

## BLOCKERs (across all agents)
- [agent → file:line] <finding>. <one-line rationale>

## HIGH
## MEDIUM
## LOW (bundled)

## QUESTIONS for the author (no business-logic guessing)
- [agent → file:line] <observation>. Without knowing <X>, this cannot be rated. Please clarify.

## Cross-agent corroborations

## Coverage blind spots (from completeness critic)

## Final verdict
PASS | PASS WITH HIGH | FAIL
<one paragraph rationale>
```

# Rules

- Do not editorialize, override, or "translate" agent verdicts. Quote their language.
- Do not invent findings beyond what the agents reported.
- A business-logic ambiguity is a QUESTION — bubble it up, don't resolve it yourself.
- Cite the agent name alongside file:line for every entry.
- A clean PASS is a valid and useful outcome.
- Per `CLAUDE.md`'s definition of done: confirmed BLOCKER/HIGH findings get fixed; everything else goes to the user.
