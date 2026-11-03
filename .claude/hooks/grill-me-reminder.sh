#!/usr/bin/env bash
# UserPromptSubmit hook: nudge the agent to run the grill-me interview before starting new work.
# Plain stdout on exit 0 is injected as context for the turn (advisory, non-blocking).
echo "Reminder: if this prompt starts a new feature, MCP tool, schema change, design decision, or non-trivial refactor, invoke the 'grill-me' skill FIRST and interview one question at a time until shared understanding — before writing any code. Skip only for trivial mechanical fixes, direct answers to an ongoing grilling, or an explicit 'just do it'."
exit 0
