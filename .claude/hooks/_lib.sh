#!/usr/bin/env bash
# Shared helpers for the hooks. Portable to macOS stock tools (bash 3.2, BSD stat/grep).
# JSON goes through jq when installed, else python3 — the hooks must not silently no-op without jq.

# json_get <dot.path>  — reads hook input JSON from $HOOK_INPUT, prints the value or nothing.
json_get() {
  if command -v jq >/dev/null 2>&1; then
    printf '%s' "$HOOK_INPUT" | jq -r ".$1 // empty" 2>/dev/null
  else
    printf '%s' "$HOOK_INPUT" | python3 -I -c '
import json, sys
try:
    v = json.load(sys.stdin)
    for k in sys.argv[1].split("."):
        v = v.get(k) if isinstance(v, dict) else None
    if v is not None and v is not False:
        print(str(v).lower() if isinstance(v, bool) else v)
except Exception:
    pass' "$1"
  fi
}

# Hook JSON output helpers:
#   emit_context <event> <msg> [user]  → additionalContext (and systemMessage when user=1)
emit_context() {
  python3 -I -c '
import json, sys
event, msg, user = sys.argv[1], sys.argv[2], sys.argv[3]
out = {"hookSpecificOutput": {"hookEventName": event, "additionalContext": msg}}
if user == "1":
    out["systemMessage"] = msg
print(json.dumps(out))' "$1" "$2" "${3:-0}"
}

emit_block() {
  python3 -I -c 'import json, sys; print(json.dumps({"decision": "block", "reason": sys.argv[1]}))' "$1"
}

# mtime <file> — epoch seconds on both BSD (macOS) and GNU stat.
mtime() {
  stat -f '%m' "$1" 2>/dev/null || stat -c '%Y' "$1" 2>/dev/null
}

# Repo root (= the Maven project root), resolved through symlinks.
REPO_ROOT=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd -P)/../..
REPO_ROOT=$(cd "$REPO_ROOT" && pwd -P)
