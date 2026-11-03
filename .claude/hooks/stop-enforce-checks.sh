#!/usr/bin/env bash
# Stop hook: when a turn ends with changed Java, block ONCE and require the definition-of-done checks (CLAUDE.md)
# before finishing.
#
# Two loop guards, because the first cannot be relied on:
#   1. stop_hook_active — set on the continuation after a Stop hook blocks (observed missing at times).
#   2. A marker (dod-checked-backend) compared by mtime against the newest changed file. The turn touches it once the
#      checks pass; any later edit makes it stale again.
# "Changed" = uncommitted in git. Outside a git repo: files newer than the marker; the first stop just records a
# baseline (run `git init` for exact tracking).
set -uo pipefail
. "$(dirname "$0")/_lib.sh"

HOOK_INPUT=$(cat)
[ "$(json_get stop_hook_active)" = "true" ] && exit 0

cd "$REPO_ROOT" 2>/dev/null || exit 0

if git rev-parse --git-dir >/dev/null 2>&1; then
  state_dir=$(git rev-parse --git-dir)
  in_git=1
else
  state_dir=".claude/.dod-state"
  mkdir -p "$state_dir"
  in_git=0
fi
marker="$state_dir/dod-checked-backend"

# changed_java — prints changed .java files under the repo root (excludes build output).
changed_java() {
  if [ $in_git -eq 1 ]; then
    { git diff --name-only HEAD -- '*.java' 2>/dev/null
      git ls-files --others --exclude-standard -- '*.java' 2>/dev/null; } | grep -v '/target/' | sort -u
  else
    [ -f "$marker" ] || { touch "$marker"; return 0; }
    find . \( -name target -o -name generated -o -name .git \) -prune -o \
      -type f -name '*.java' -newer "$marker" -print 2>/dev/null
  fi
}

# needs_checks <files> — true when files exist and are newer than the marker.
needs_checks() {
  files=$1
  [ -z "$files" ] && return 1
  [ -f "$marker" ] || return 0
  m=$(mtime "$marker"); m=${m:-0}
  newest=0
  while IFS= read -r f; do
    [ -f "$f" ] || continue
    t=$(mtime "$f"); t=${t:-0}
    [ "$t" -gt "$newest" ] && newest=$t
  done <<< "$files"
  [ "$newest" -gt "$m" ]
}

java_changed=$(changed_java)
needs_checks "$java_changed" || exit 0

n=1
steps="($n) invoke the resolve-duplicates skill and remove any duplication;"
n=$((n+1)); steps="$steps ($n) invoke the abstraction-design skill and record a verdict for every new/changed type (\"stay flat\" is valid);"
n=$((n+1)); steps="$steps ($n) dispatch the style-check agent on the diff and fix what it reports;"
n=$((n+1)); steps="$steps ($n) run \`./mvnw -q test\` (or \`./mvnw -q -pl <module> -am test\`) and fix failures."

reasons="You changed Java. Before finishing, in order: $steps Then run /premium-review on the working-tree diff; fix every confirmed BLOCKER and HIGH and put MEDIUM/LOW items and QUESTIONS to the user instead of guessing. Finally run: touch \"$marker\" — records the checks so this hook stops re-asking until those files change again. After all are done, stop."
emit_block "$reasons"
exit 0
