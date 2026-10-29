---
description: Debug a multi-service error using useful independent config, diagnostics, code-path and history investigations, then verify a root cause and scoped fix
argument-hint: "<error description>"
---

Error: **$ARGUMENTS**

Classify the task using `~/.claude/rules/workflow-routing.md`; complete required Security/contract prerequisites before implementing. Follow `~/.claude/rules/dispatch-prompt-contract.md` and `rules/subagent-return-format.md`. Dispatch only useful independent branches using the available Agent tool (`Task` alias is supported); keep dependent investigation sequential. When agents are unavailable, report the missing independent gate and use an authorized local fallback.

All reports disclose presence, counts, safe identifiers, filenames or explicitly redacted metadata only. Never print credentials, environment/config values, raw auth headers, connection strings, PII or request/response bodies. Diagnostics must stay within the authorized safe target; do not start services or replay mutating requests by default.

Choose from these four investigation branches:

## Agent 1 — Config
Check all config for this error:
- Required environment/config keys: presence and validation status only
- Connection/profile/credential-source availability: safe labels and filenames only
- Service-to-service settings (ports, hostnames, feature flags)
Report: which required configuration is present/missing or invalid; redact values.

## Agent 2 — Diagnostics
Run live checks:
- Use configured read-only health checks on the authorized target; report status, content type and redacted metadata, never raw body/auth headers
- Check ports: `lsof -i :<port>` or `ss -tlnp`
- Inspect required resource availability if within scope; report counts/safe identifiers rather than sensitive contents
Report: which prerequisites respond and the relevant sanitized result; mark unavailable/skipped checks explicitly.

## Agent 3 — Code path
Trace from error surface to origin:
- Where does the error surface? What calls it, and what safe metadata describes the arguments?
- Trace backward to where data is malformed or state is wrong
Report: exact `file:line` where failure originates, with relevant code snippet.

## Agent 4 — Git history
Check for recent regressions:
- `git log --oneline -20` on files related to the error
- Inspect the verified relevant range and current staged/unstaged/untracked changes; handle shallow/unborn history explicitly
Report: any commit in the last 20 that could explain this error.

## Synthesis (after selected investigations return)

1. Read all selected reports and their cited evidence
2. State root cause: one specific, falsifiable sentence
3. Cross-check the hypothesis against the collected findings; state remaining uncertainty
4. Implement the fix — cite the specific finding that justifies each change
5. Verify with the `verification-before-completion` skill

**Do not implement a fix before the necessary investigation and routing prerequisites are satisfied.**
