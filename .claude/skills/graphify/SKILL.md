---
name: graphify
description: >-
  Use when visualizing or checking the Java type landscape and module dependencies of the Finance MCP
  server — records, sealed hierarchies, interfaces, implements/permits edges, and the hexagonal
  direction (mcp → adapters → application → domain; adapters never depend on each other; nothing
  depends on mcp). Triggers: "graphify", "diagram", "type hierarchy", "architecture graph", "module
  dependencies", "mermaid", "visualize the types", reviewing an abstraction refactor, a
  dependency-direction question, or regenerating docs/generated after adding/removing/moving Java types.
---

# Graphify (type & module graphs)

## Overview

One dependency-free script renders the type landscape and module-dependency graph as Mermaid, and
mechanically checks the hexagonal rules from `CLAUDE.md`. Diagrams live in `docs/generated/` and are
**generated — never hand-edited**.

## Commands (from the repo root)

```bash
bash .claude/skills/graphify/graphify.sh generate   # rewrite docs/generated/*.md
bash .claude/skills/graphify/graphify.sh check      # findings; exit 2 = hard violation
```

Both exit 0 silently while no module holds Java sources (the current single-module
scaffold is ignored). Runs on stock macOS (bash 3.2, BSD grep/sed/awk, system perl) and Linux CI.

## Modules it knows

| Key | Directory | Package |
|---|---|---|
| `domain` | `domain` | `com.artyomh.finance.domain` |
| `application` | `application` | `com.artyomh.finance.application` |
| `parser` | `adapter-parser` | `com.artyomh.finance.adapter.parser` |
| `persistence` | `adapter-persistence` | `com.artyomh.finance.adapter.persistence` |
| `fx` | `adapter-fx` | `com.artyomh.finance.adapter.fx` |
| `mockdata` | `mockdata` | `com.artyomh.finance.mockdata` |
| `mcp` | `mcp-server` | `com.artyomh.finance.mcp` |

A new module means editing `MODULES`, `module_dir` and `dep_allowed` in the script — and `CLAUDE.md`.

## What `generate` produces

- `docs/generated/type-landscape.md` — one Mermaid `classDiagram` per module: every top-level public
  record/class/interface/enum with `<<stereotype>>`, `implements`/`extends`/`permits` edges, and `has`
  edges where a record component references another project type (`Transaction --> Money : has`).
- `docs/generated/module-deps.md` — a flowchart of actual cross-module `import`s in main sources; an
  edge against the direction is drawn dotted and labelled `violates`.

## What `check` enforces

Allowed main-source edges: `application → domain`; `parser|persistence|fx → application, domain`;
`mockdata → domain`; `mcp → every other module`. Test sources may additionally import `mockdata`
(fixtures). Everything else — `domain → *`, adapter → adapter, anything → `mcp` — is a violation.

| Finding | Severity | Rule |
|---|---|---|
| Import against the direction, main **or** test sources | **HARD (exit 2)** | CLAUDE.md module boundaries |
| Non-sealed interface with 0 or 1 implementations (test doubles count), outside a `port` package | warn | Justify via `abstraction-design` or inline it (`@FunctionalInterface` exempt) |
| DTO record (`request`/`response`/`dto` package) implementing anything | warn | DTO records stay flat (`abstraction-design`) |

Ports (`…/port/…`) are exempt from the single-implementation warning: they are the pre-approved
hexagonal extension points (`hexagonal-architecture`). Parsing is heuristic: it reads each file's
first `public … record|class|interface|enum` header; a fully-qualified type use without an `import`
is invisible to the direction check (and is itself a smell — import it). ArchUnit tests are the
build-time backstop for the same rules.

## Hook integration (automatic)

`.claude/hooks/graphify-check.sh` runs on every `<module>/src/**/*.java` `Edit`/`Write`
(PostToolUse): it regenerates the diagrams silently and runs `check`. A hard violation feeds back as
blocking feedback; warnings are advisory. Commit regenerated `docs/generated/*.md` with the type change.

## When editing this skill's script

1. `bash -n .claude/skills/graphify/graphify.sh` — keep it bash-3.2 clean (no `declare -A`, `mapfile`,
   `${var,,}`, `grep -P`, `sed -i` without `''`, `stat -c`).
2. Run it on a tree with no module sources — both commands exit 0 with no output.
3. Point it at a throwaway tree (`CLAUDE_PROJECT_DIR=<dir> bash …/graphify.sh check`) with one planted
   adapter → adapter import and one `domain → application` import; confirm exit 2 for both, and that a
   test-scope import of `mockdata` passes. Remove the plants.
