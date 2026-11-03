#!/usr/bin/env bash
# PostToolUse hook (matcher: Edit|Write|MultiEdit).
#
# Non-blocking layout advice (see the `package-layout` and `hexagonal-architecture` skills):
#   - a class directly in a module's base package should be grouped into a sub-package;
#   - a port-shaped interface (*Repository, *Parser, *Provider) in an adapter module belongs in application;
#   - Spring / POI / JDBC / Spring AI imports never belong in domain or application;
#   - an @McpTool class belongs in mcp-server.
# `exception` packages stay FLAT (sealed subtypes must share a package), so never flagged.
# Always exits 0 — advisory only, handed to Claude as `additionalContext`.
set -uo pipefail
. "$(dirname "$0")/_lib.sh"

HOOK_INPUT=$(cat)
file=$(json_get tool_input.file_path)
[ -n "$file" ] && [ -f "$file" ] || exit 0

base=$(basename "$file")
parent=$(basename "$(dirname "$file")")
warns=()

case "$file" in
  */target/*|*/src/test/*) exit 0 ;;
  */domain/src/main/java/*.java|*/application/src/main/java/*.java|*/adapter-*/src/main/java/*.java|*/mockdata/src/main/java/*.java|*/mcp-server/src/main/java/*.java) ;;
  *) exit 0 ;;
esac
[ "$base" = "package-info.java" ] && exit 0

case "$parent" in
  finance|domain|application|parser|persistence|fx|mockdata|mcp)
    case "$base" in
      *Application.java) ;;
      *) warns+=("class sits directly in the module's base package '$parent'; group it into a sub-package by concept") ;;
    esac ;;
esac

case "$file" in
  */domain/src/*|*/application/src/*)
    if grep -qE '^import[[:space:]]+(static[[:space:]]+)?(org\.springframework|org\.apache\.poi|org\.apache\.commons\.csv|java\.sql|javax\.sql|io\.modelcontextprotocol)\.' "$file"; then
      warns+=("framework import in an inner module — domain/application stay pure Java; move the dependency behind a port into an adapter")
    fi ;;
  */adapter-*/*)
    if grep -qE '^public[[:space:]]+(sealed[[:space:]]+)?interface[[:space:]]+[A-Za-z]*(Repository|Parser|Provider)\b' "$file"; then
      warns+=("port interface declared in an adapter — ports belong in application (adapters implement them)")
    fi ;;
esac

case "$file" in
  */mcp-server/*) ;;
  *)
    if grep -qE '@(McpTool|McpResource|McpPrompt)\b' "$file"; then
      warns+=("MCP annotation outside mcp-server — MCP is only the front door; tools live in the server module")
    fi ;;
esac

[ ${#warns[@]} -eq 0 ] && exit 0
joined=$(printf '%s; ' "${warns[@]}"); joined=${joined%; }
emit_context PostToolUse "package-layout ($base): ${joined}"
exit 0
