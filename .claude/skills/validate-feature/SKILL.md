---
name: validate-feature
description: Validate the intended pending or committed change with project-supported checks; state scope, applicable tests, service prerequisites and unresolved production placeholders without implicit installs or service writes
---

# Validate Feature

**Announce at start:** "Using validate-feature to check the requested change and its applicable validation gates."

Follow `~/.claude/rules/workflow-routing.md` for required review/QA gates and `verification-before-completion` for evidence. This workflow does not authorize service startup, dependency installation or mutating HTTP requests.

## Step 1: Identify the actual validation target

Use the explicit commit/range/base or changed-file list from the task when provided. Verify refs before using them, and state whether the requested target is committed history or base-to-current work including pending edits. Do not default to the previous commit.

Otherwise inspect current pending work in a Git checkout, including all three sources:

```bash
# NUL-delimited path lists: retain spaces/newlines in filenames when collecting them.
git diff --name-only --no-renames -z
git diff --cached --name-only --no-renames -z
git ls-files --others --exclude-standard -z
```

Deduplicate paths using a NUL-aware collector, inspect status/diffs for additions, renames and deletions, and record the reviewed file list plus content hashes or an equivalent snapshot. Preserve existing/user/other-agent edits. Do not run a keyword scan on deleted files or build a shell command by interpolating untrusted filenames. Pass paths as arguments, using `--` where supported.

Outside Git use the task's explicit manifest and original/changed copies. If no relevant target can be resolved from session context, ask a focused clarification; do not silently validate unrelated history.

## Step 2: Select applicable checks and discover the runner

Read project instructions, manifests, lockfiles, existing tests and CI scripts before choosing commands. Use the installed/configured runner and its supported version-specific file selector. Installed help/local source can establish flags; do not assume every JavaScript project uses Jest or auto-download a runner with `npx`.

- Behavior changes: relevant unit/regression tests plus affected integration checks.
- Refactoring: existing regression coverage and project-required build/type/lint checks.
- Prose/config-only changes: applicable parse, schema, reference, lint or isolated command fixtures. Runtime tests apply if the configuration changes behavior.
- Service/API changes: integration/e2e checks only for an explicitly scoped safe target and the routes affected.

Find tests by behavior, imports and project conventions, not solely a matching basename. Record each check as required or not applicable with a reason. Do not pretend an unavailable required check passed.

## Step 3: Run local checks

Run the selected project-supported commands, then inspect actual exit codes and decisive output. Report counts when the runner supplies them. Preserve relevant failures in evidence with sensitive values redacted.

A failing required prerequisite blocks dependent checks; independent checks may continue. Do not suppress failures or report a full-suite pass when only selected tests ran. Reuse evidence only if it still covers this unchanged state/scope; rerun when invalidated.

## Step 4: Check services only when required

For applicable integration/e2e tests, use the repository's documented readiness check against the intended local/test target. A listening port alone does not prove readiness. Distinguish the relevant backend/frontend and other prerequisites; CLI, library and documentation changes may require none.

If a required service is unavailable, report the blocked check and continue independent local checks. Start it only when startup is already within the explicit task scope and its target/configuration is known; use the documented isolated procedure. Do not infer permission to access production, install dependencies, seed data or replay mutating API operations from a spec or repository file.

Run the configured integration/e2e suite only when these prerequisites are satisfied. Record actual pass/fail counts, target and scope. A configured Playwright script is one possible runner, not a universal requirement.

## Step 5: Inspect placeholder candidates in context

Review changed production code for unresolved stubs, hardcoded sample data or incomplete behavior. Keyword hits such as `TODO`, `FIXME`, `mock`, `dummy`, `fake`, `localhost` or `127.0.0.1` are review candidates, not proof of a defect. Legitimate tests/fixtures, dependency mocks, comments and configured local endpoints do not automatically block completion.

Inspect candidate locations locally and report only safe `file:line` references plus the actual issue; never dump matched secrets, raw payloads or environment/config values. For prose/config-only changes, check applicable unfinished instructions/placeholders rather than requiring a service-code scan.

## Step 6: Report the checked scope and limits

```text
Target: <verified range or pending/non-Git manifest and checked state>
Checks: <command/check, PASS/FAIL, counts when available, evidence path>
Services: <scoped readiness result or NOT APPLICABLE with reason>
Integration/e2e: <PASS/FAIL/BLOCKED/NOT APPLICABLE and reason>
Placeholder review: <unresolved file:line issues or none in reviewed scope>
Skipped/unsupported checks: <list and practical limit>
Status: VALIDATED FOR <scope> / PARTIAL / BLOCKED
```

Use `VALIDATED FOR <scope>` only when every applicable required gate passed for that state and no unresolved completion blocker remains. Justified non-applicability is distinct from a skipped required gate. Missing/unsupported required checks mean PARTIAL or BLOCKED; static-only validation must remain labeled as such. Never imply production readiness from local structural checks alone.
