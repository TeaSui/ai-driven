---
description: Debug a multi-service error using 4 parallel agents — config check, live diagnostics, code path trace, git regression history — then synthesize into a single root cause and fix
argument-hint: "<error description>"
---

Error: **$ARGUMENTS**

Spawn four agents simultaneously (single message, four Task calls):

## Agent 1 — Config
Check all config for this error:
- Environment variables (`.env`, `config/`, system env)
- Connection strings, profiles, credentials paths
- Service-to-service settings (ports, hostnames, feature flags)
Report: what's configured vs what's expected.

## Agent 2 — Diagnostics
Run live checks:
- `curl`/`wget` the relevant endpoints — capture HTTP status + body
- Check ports: `lsof -i :<port>` or `ss -tlnp`
- List tables / buckets / queues if applicable
Report: which services respond, which don't — exact output.

## Agent 3 — Code path
Trace from error surface to origin:
- Where does the error surface? What calls it with what arguments?
- Trace backward to where data is malformed or state is wrong
Report: exact `file:line` where failure originates, with relevant code snippet.

## Agent 4 — Git history
Check for recent regressions:
- `git log --oneline -20` on files related to the error
- `git diff HEAD~5..HEAD -- <relevant files>`
Report: any commit in the last 20 that could explain this error.

## Synthesis (after all four agents return)

1. Read all four reports
2. State root cause: one specific, falsifiable sentence
3. Cross-check: does the root cause explain all four agents' findings?
4. Implement the fix — cite the specific finding that justifies each change
5. Verify with the `verification-before-completion` skill

**Do NOT implement a fix before all four agents return.**
