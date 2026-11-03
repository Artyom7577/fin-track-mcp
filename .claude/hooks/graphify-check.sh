#!/usr/bin/env bash
# PostToolUse hook (matcher: Edit|Write|MultiEdit).
#
# After a Java edit in a Maven module: regenerate docs/generated/*.md and run graphify's checks.
#   HARD (exit 2, stderr to Claude): a cross-module import against the hexagonal direction (see CLAUDE.md).
#   warn (exit 0, `additionalContext`): single-/zero-implementation interfaces, DTO records in hierarchies.
set -uo pipefail
. "$(dirname "$0")/_lib.sh"

HOOK_INPUT=$(cat)
file=$(json_get tool_input.file_path)

case "$file" in
  */target/*) exit 0 ;;
  */domain/src/*.java|*/application/src/*.java|*/adapter-*/src/*.java|*/mockdata/src/*.java|*/mcp-server/src/*.java) ;;
  *) exit 0 ;;
esac

cd "$REPO_ROOT" 2>/dev/null || exit 0
script=".claude/skills/graphify/graphify.sh"
[ -f "$script" ] || exit 0

bash "$script" generate >/dev/null 2>&1 || true

out=$(bash "$script" check 2>&1)
rc=$?
if [ $rc -eq 2 ]; then
  printf '%s\n' "$out" >&2
  exit 2
fi
[ -n "$out" ] && emit_context PostToolUse "$out"
exit 0
