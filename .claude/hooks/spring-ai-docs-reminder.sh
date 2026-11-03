#!/usr/bin/env bash
# PreToolUse hook (matcher: Edit|Write|MultiEdit) — before an edit to Spring AI / MCP code, require the current docs.
# Spring AI moves fast (2.x, MCP Java SDK 2.0); APIs written from memory are often 1.x and stale. Advisory:
# `additionalContext` to Claude.
set -uo pipefail
. "$(dirname "$0")/_lib.sh"

HOOK_INPUT=$(cat)
file=$(json_get tool_input.file_path)

case "$file" in
  */target/*) exit 0 ;;
  */application*.yaml|*/application*.yml|*/application*.properties)
    content="$(json_get tool_input.content)$(json_get tool_input.new_string)"
    printf '%s' "$content" | grep -q 'spring.ai\|ai:\|mcp' || exit 0 ;;
  *.java)
    content="$(json_get tool_input.content)$(json_get tool_input.new_string)"
    printf '%s' "$content" | grep -qE 'org\.springframework\.ai|io\.modelcontextprotocol|@Mcp[A-Z]' || exit 0 ;;
  *) exit 0 ;;
esac

python3 -I -c '
import json
msg = ("Spring AI / MCP code: invoke the mcp-server-tools skill and VERIFY every annotation, class and property "
       "against the current reference docs with WebFetch (https://docs.spring.io/spring-ai/reference/api/mcp/ — the "
       "Spring AI version in pom.xml). Never write Spring AI or MCP SDK calls from memory; no 1.x or deprecated APIs. "
       "Keep stdout clean: STDIO is the JSON-RPC channel.")
print(json.dumps({"hookSpecificOutput": {"hookEventName": "PreToolUse", "additionalContext": msg}}))'
exit 0
