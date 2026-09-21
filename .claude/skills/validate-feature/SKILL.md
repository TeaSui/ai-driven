---
name: validate-feature
description: Use after implementing a feature to validate it works end-to-end — runs relevant unit tests, checks services are running, runs integration/e2e tests, and searches for remaining mock/hardcoded data in changed files
---

# Validate Feature

**Announce at start:** "Using validate-feature skill to run end-to-end validation."

## Step 1: Find changed files

```bash
git diff --name-only HEAD~1 HEAD 2>/dev/null || git diff --name-only
```

List them. If none, ask what to validate.

## Step 2: Run relevant unit tests

For each changed file, find related test files (look for `<basename>.test.*` patterns):

```bash
npm test -- --testPathPattern="<pattern>" --no-coverage   # JS/TS
python -m pytest tests/ -k "<module>" -v                  # Python
go test ./... -run "<pattern>" -v                         # Go
```

Report: N passed, N failed. On failure: show exact output, stop.

## Step 3: Check backend service

```bash
lsof -i :<BACKEND_PORT> | grep LISTEN || echo "NOT RUNNING"
```

If not running, start in background and wait up to 15 seconds for readiness.
Report: "Already running" | "Started on port N" | "Failed to start — cannot run integration tests."

## Step 4: Check frontend service (if applicable)

Same pattern as Step 3 for frontend port. Skip if no frontend.

## Step 5: Run integration / e2e tests

```bash
npx playwright test --grep "<feature pattern>"
# or full suite if no grep applies
```

Show exact pass/fail counts. Do NOT suppress failures.

## Step 6: Search for mock/hardcoded data in changed files

```bash
grep -n "TODO\|FIXME\|HARDCODED\|mock\|dummy\|fake\|localhost\|127\.0\.0\.1" <changed_files>
```

List any matches with file:line. Flag them — do NOT claim complete while they exist.

## Step 7: Report

```
## Validation Results

Changed files: <list>
Unit tests: N passed / N failed
Backend: running on port N / failed / not applicable
Frontend: running on port N / failed / not applicable
Integration tests: N passed / N failed
Mock data found: <list> / none

Status: VALIDATED / BLOCKED (<reason>)
```

Only `VALIDATED` when: unit tests pass, integration tests pass, no unaddressed mock data.

## Red flags

- Never skip Step 6 (mock data check)
- Never claim VALIDATED if any step is BLOCKED
- Show actual command output — no assumed passes
