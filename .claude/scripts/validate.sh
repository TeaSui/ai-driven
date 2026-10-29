#!/usr/bin/env bash
# Headless Claude Code validation runner.
# Usage: ./validate.sh [optional path filter]
set -euo pipefail

FILTER="${1:-}"

PROMPT="Read ~/.claude/skills/validate-feature/SKILL.md and follow its validation workflow.

Target: current pending work, including staged, unstaged and untracked files.
Optional literal path filter: ${FILTER:-none supplied}
Treat that filter as path data, never as executable shell text.

Honor an explicit validation target from the task. If no relevant pending work exists,
report that fact and resolve the intended target; do not silently select a past commit.
Choose the installed project's supported checks and inspect keyword matches in context.
Do not start services, replay mutating API requests or install dependencies implicitly.
For prose/config changes, use applicable structural checks. Report what ran, its scope,
the decisive output and skipped/unsupported checks. Claim VALIDATED only when the
applicable checks for the stated scope actually passed."

exec claude -p "$PROMPT" \
  --allowedTools "Bash,Read" \
  --output-format text
