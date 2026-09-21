# Subagent Return Conventions

**Scope:** Every subagent dispatched via the `Task` tool by a delegating agent (orchestrator, tech-lead full-mode, BA-standalone, or the main session when dispatching per `rules/workflow-routing.md` — coordinated fast path and full workflow) MUST include three things in its final message:

1. A **status** — one of four values (see below)
2. A list of **evidence paths** — real files/logs the dispatcher can `Read`
3. An **exit-criteria check** — one line per criterion from the dispatch prompt, marked met / not met

**Format is flexible** — prose, markdown, or XML. The three pieces of information are what matter, not the serialization. Dispatchers parse them regardless of shape.

**Why not strict XML:** empirically, leaf subagents return XML only ~10% of the time on substantive dispatches. Dominant markdown priors in their system prompts (skill catalogs, escalation-format examples, workflow phases) override a lonely XML instruction. Chasing shape is not worth the effort — the information is what matters.

Paired with `rules/dispatch-prompt-contract.md` (which governs the input shape and IS effective at driving agent behavior).

---

## Status codes

Four values, reused from `skills/subagent-driven-development/SKILL.md`:

| Status | Meaning | Also required |
|--------|---------|---------------|
| `DONE` | Work complete; all exit criteria met with evidence | summary, evidence, exit_criteria_check |
| `DONE_WITH_CONCERNS` | Complete but flagged doubts worth reading before proceeding | above + concerns list |
| `NEEDS_CONTEXT` | Cannot proceed because required inputs are missing or ambiguous | summary, what's needed, next-step recommendation |
| `BLOCKED` | Cannot complete; scope/spec/approach/environment needs to change (includes hitting review-loop limits per `rules/review-loop-limits.md`) | summary, what was tried, blocker explanation, next-step recommendation |

BLOCKED returns must also name the **failure category** — transient/environment (retry may work) or scope/spec/approach (the task itself needs to change) — and include any **partial results** with a note of what was and wasn't covered. (A missing input the dispatcher can supply is `NEEDS_CONTEXT`, not `BLOCKED`.) Generic "it failed" statuses force the dispatcher to guess between retry, re-scope, and escalate. Do not conflate a tooling failure with a valid empty result: a check that ran successfully and found nothing is `DONE`, not `BLOCKED`.

The status keyword MUST appear verbatim somewhere in the return — as a heading (`## Status: DONE`), a bold label (`**Status:** DONE`), an XML tag (`<status>DONE</status>`), or a sentence (`Status: DONE`). All are acceptable. The dispatcher scans for the keyword.

---

## Dispatcher handling

- **`DONE`** → proceed (spec review, next task, or aggregate).
- **`DONE_WITH_CONCERNS`** → read the concerns. If material to correctness or scope, address before proceeding; if observational, note and proceed.
- **`NEEDS_CONTEXT`** → provide the missing context, amend the dispatch prompt, re-dispatch. Never resend an identical prompt.
- **`BLOCKED`** → diagnose root cause. Split, simplify, or change approach. Do NOT retry with the same prompt. If the blocker is user-level, escalate.

---

## Evidence rules

- Every cited path must be real. Dispatchers `Read` them. Fabricated paths are a hallucination (`rules/reduce-hallucinations.md`) — treat as BLOCKED regardless of claimed status.
- Test/build output: write to a file (`> /tmp/<slug>.log` or project-local equivalent) and cite the path. Don't paste multi-page output inline.
- For code changes, cite file paths (not diffs). Reviewers read files.
- For commands run, include the exact command so reviewers can reproduce.
- For persisted strategic docs (threat models, contracts, user stories), cite the `docs/**/` path.

---

## Strategic agents: persistence obligation

Strategic agents (`business-analyst-subagent`, `tech-lead-subagent`, `security-engineer-subagent`) MUST persist their canonical artifacts to `docs/` per `rules/strategic-output-persistence.md` (which owns the per-agent destinations) AND cite those paths in evidence. Do not paste full contracts / threat models / user stories into the return — cite the persisted files.

---

## Dispatcher obligations (anti-fabrication)

When you receive a subagent's response:

1. **Do NOT paraphrase the subagent's return into a shape it didn't use.** If the subagent said "Status: DONE, evidence in X, Y, Z," don't rewrite that as `<status>DONE</status><evidence>...`. Report what the subagent actually said.
2. **Verify every cited path with `Read`.** If a path doesn't exist, treat the response as `BLOCKED` regardless of the claimed status.
3. **Confirm each exit criterion is marked met.** If the subagent's exit-criteria check is missing items from the dispatch prompt, re-dispatch asking for completeness — don't silently accept.
4. **If the three pieces of information are missing** (no status, or no evidence, or no exit-criteria check), re-dispatch with a reminder. If they're still missing on the second try, stop and diagnose — the dispatch prompt may be under-specified or the agent may be overloaded.

---

## Examples

### DONE (markdown-style)

```
## Status: DONE

### Summary
Implemented POST /login per OpenAPI contract and Security rules AUTH-01..AUTH-07.
14 table-driven tests covering success, invalid password, locked account, token refresh.

### Evidence
- pkg/auth/login.go (code)
- pkg/auth/login_test.go (tests, 14 cases)
- cmd/server/routes.go (wiring)
- /tmp/auth-test.log — command: go test -v -cover ./pkg/auth/... > /tmp/auth-test.log

### Exit criteria
- go test pass ≥80% coverage: met (84.2%, /tmp/auth-test.log)
- redocly lint clean: met (no contract changes)
- golangci-lint clean: met (0 issues, /tmp/auth-lint.log)
- OpenAPI examples pass integration: met (TestContractExamples)

### Next step
Proceed to spec compliance review.
```

### BLOCKED (prose-style)

```
Status: BLOCKED

Cannot implement AUTH-03 rate-limiting without modifying cmd/server/middleware.go,
which is listed in out_of_scope. Two attempted workarounds (handler-level limiter,
request-context limiter) both failed the concurrency test.

Evidence:
- pkg/auth/ratelimit_attempt_1.go
- pkg/auth/ratelimit_attempt_2.go
- /tmp/ratelimit-fail.log (go test -race -v ./pkg/auth/...)

Blocker: AUTH-03 requires a global rate limiter; the middleware chain is the correct
integration point. Handler-level limiters cannot share state across handlers, which
violates the spec's "per-IP across all auth endpoints" requirement.

Exit criteria:
- rate limiter passes concurrency test: not met
- middleware integration: not attempted (out_of_scope)

Next step: Re-dispatch with cmd/server/middleware.go added to inputs OR split the
task into "AUTH-01,02,04..07" (this one) and "AUTH-03 middleware rate-limiter" (new).
```

Both returns convey the same three pieces of information. Dispatchers extract status, evidence paths, and exit-criteria marks from either shape.
