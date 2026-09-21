#!/usr/bin/env bash
# Headless Claude Code validation runner.
# Usage: ./validate.sh [optional path filter]
set -euo pipefail

FILTER="${1:-}"

PROMPT="Validate files changed in the last commit${FILTER:+" matching $FILTER"}.

Steps:
1. git diff --name-only HEAD~1 HEAD
2. For each changed file, find and run all related tests
3. Grep changed files for: TODO, FIXME, mock, fake, dummy, localhost, 127.0.0.1
4. Report:

   Changed files: <list>
   Unit tests: N passed / N failed
   Mock data found: <list or 'none'>
   Overall: VALIDATED or BLOCKED (with reason)

Show exact test output. Do NOT claim VALIDATED without running tests."

exec claude -p "$PROMPT" \
  --allowedTools "Bash,Read" \
  --output-format text
