#!/usr/bin/env bash
# PostToolUse hook (matcher: Edit|Write|MultiEdit).
#
# Non-blocking: warns about formatting drift in the file just edited.
#   *.java — 4-space indent, <=120 cols, no trailing whitespace, final newline.
# Dependency-free heuristics (BSD/GNU grep both work). Always exits 0; the warning goes to the user
# (`systemMessage`) and to Claude (`additionalContext`).
set -uo pipefail
. "$(dirname "$0")/_lib.sh"

HOOK_INPUT=$(cat)
file=$(json_get tool_input.file_path)

case "$file" in
  *.java) max=120 ;;
  *) exit 0 ;;
esac
case "$file" in */target/*|*/generated/*) exit 0 ;; esac
[ -f "$file" ] || exit 0

tab=$(printf '\t')
warns=()
grep -n "^${tab}" "$file" >/dev/null 2>&1 && warns+=("tab indentation (repo uses spaces)")
grep -nE "[ ${tab}]+\$" "$file" >/dev/null 2>&1 && warns+=("trailing whitespace")

long=$(awk -v m="$max" 'length > m {c++} END {print c+0}' "$file")
[ "${long:-0}" -gt 0 ] && warns+=("${long} line(s) over ${max} cols")

if [ -s "$file" ] && [ -n "$(tail -c1 "$file")" ]; then
  warns+=("no final newline")
fi

[ ${#warns[@]} -eq 0 ] && exit 0

joined=$(printf '%s; ' "${warns[@]}"); joined=${joined%; }
emit_context PostToolUse "format-warn ($(basename "$file")): ${joined}" 1
exit 0
