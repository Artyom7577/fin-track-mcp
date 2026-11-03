#!/usr/bin/env bash
# PostToolUse hook (matcher: Edit|Write|MultiEdit).
#
# The server talks JSON-RPC over stdout (MCP STDIO transport): one stray line breaks the protocol.
# Flags stdout writes in main Java sources and a server config that would print a banner or console logs.
# Always exits 0 — advisory, to the user (`systemMessage`) and Claude (`additionalContext`).
set -uo pipefail
. "$(dirname "$0")/_lib.sh"

HOOK_INPUT=$(cat)
file=$(json_get tool_input.file_path)
[ -n "$file" ] && [ -f "$file" ] || exit 0

case "$file" in
  */target/*|*/src/test/*) exit 0 ;;
esac

warns=()
case "$file" in
  */src/main/java/*.java)
    grep -nE 'System\.(out|err)\.|\.printStackTrace\(' "$file" >/dev/null 2>&1 \
      && warns+=("System.out/err or printStackTrace in main code — stdout is the MCP channel; use a logger that writes to a file/stderr")
    ;;
  */mcp-server/src/main/resources/application*.yaml|*/mcp-server/src/main/resources/application*.yml)
    grep -qE 'banner-mode:[[:space:]]*off' "$file" \
      || warns+=("spring.main.banner-mode: off missing — the Spring banner on stdout corrupts STDIO")
    ;;
  *) exit 0 ;;
esac

[ ${#warns[@]} -eq 0 ] && exit 0
joined=$(printf '%s; ' "${warns[@]}"); joined=${joined%; }
emit_context PostToolUse "stdio-hygiene ($(basename "$file")): ${joined}" 1
exit 0
