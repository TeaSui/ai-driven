# Dispatch Prompt Contract

**Scope:** This contract governs every `Task` dispatch made by a delegating agent — including the **main session** when it dispatches implementers (coordinated fast path leaf dispatches, and full-workflow dispatches via `subagent-driven-development`). Fast-path work does not dispatch subagents — the main session edits directly — so there is no "fast path" case for this contract to exempt. See `rules/workflow-routing.md` for when a task dispatches at all.

**Delegating agents bound by this contract:**
- `agent-orchestrator` (Phase 4: DELEGATE)
- `tech-lead-subagent` (Phase 3: DELEGATE — full mode only; `(contract)` / "Stop after Phase 2" dispatches do not delegate)
- `business-analyst-subagent` (Phase 4: DELEGATE — standalone mode only; orchestrator-dispatched BA MUST NOT re-dispatch)
- **main session** (coordinated fast path: direct leaf dispatches; full workflow: implementer dispatches after TechLead contract-mode, per `rules/workflow-routing.md`)

**Leaf subagents (backend, frontend, mobile, devops, data, AI, AWS, UI/UX, security, QA, api-test, code-reviewer) are consumers:** they read this shape on input and return per `rules/subagent-return-format.md` on output. They do not dispatch.

**Detection signal:** a subagent knows it received a contract dispatch because its prompt contains a `<return_format>` tag referencing `rules/subagent-return-format.md`. If that tag is absent, the subagent responds in whatever shape fits the prompt.

---

## Why this shape

Derived directly from `skills/building-with-the-claude-api/docs/best-practices/prompt-engineering/prompting-best-practices.md`:

- **"Structure prompts with XML tags"** — consistent descriptive tags reduce misinterpretation; each field has its own tag.
- **"Be clear and direct"** — every field is stated explicitly and scoped. Do not rely on the model generalizing implicit intent.
- **"Optimize parallel tool calling"** — independent dispatches ship in one message with multiple `Task` calls.
- **"Controlling subagent spawning"** — nested delegators are told when they MAY NOT re-dispatch.

Existing conventions preserved (do not restate — cite):
- Review loop limits → `rules/review-loop-limits.md`
- Strategic output persistence → `rules/strategic-output-persistence.md`
- Security precedence → `rules/security.md`
- Parallel dispatch discipline → `skills/dispatching-parallel-agents/SKILL.md`

---

## Required prompt shape

Every **implementation dispatch** prompt MUST contain the following sections, in this order. Omit a section only when explicitly marked optional.

**Shape is content-first:** the nine fields are the contract; XML tags are the *recommended* serialization, but markdown headings carrying the same fields are compliant. Empirically (see `rules/subagent-return-format.md`), field completeness drives subagent behavior, not the angle brackets. The detection signal for a contract dispatch remains an explicit return-format section referencing `rules/subagent-return-format.md` (tagged `<return_format>` or a heading).

**Review / advisory dispatches** (spec review, code-quality review, QA, threat-model, requirements) use a lighter minimum: `role`, `context`, `task`, and `return_format`. Add `inputs`/`exit_criteria`/`out_of_scope` only when they carry real content — a review has no file-mutation scope to fence.

**Model-aware steering:** before issuing any dispatch, consult the "Model-Specific Steering" section of `skills/dispatching-parallel-agents/references/model-routing.md` for the dispatch's RESOLVED model (override > agent frontmatter > dispatching agent's own model) and style the prompt accordingly — explicit enumerated scope for literal models (sonnet/opus-4.8), coverage-not-filtering language in reviewer dispatches to literal models, full slot-filling for haiku, terse with no verification instructions for frontier overrides. Subagents cannot see `rules/` or user CLAUDE.md — the dispatch prompt is the only channel, so steering content must be written into it, not referenced.

```xml
<role>
One sentence naming who the subagent is for this task (e.g., "You are the backend-engineer-subagent implementing the /login endpoint per the TechLead contract").
</role>

<context>
Minimum facts the subagent needs to act:
- Why this task exists (the parent's goal, not the whole project)
- Upstream agents and their outputs (Security rules? TechLead contract? BA stories?)
- Who dispatched you (orchestrator / tech-lead / BA-standalone)
Do NOT paste conversation history. Construct exactly what they need.
Ordering: put load-bearing facts (IDs, amounts, contract paths, hard constraints) at the TOP of a
long context — models process the beginning and end of long inputs reliably but can drop the middle.
Trim verbose upstream tool output to the relevant fields before pasting it in.
</context>

<inputs>
  <file path="..."/>                    <!-- Every file the subagent may read or modify -->
  <contract ref="docs/contracts/..."/>  <!-- Optional; include when TechLead contract applies -->
  <security_rules ref="docs/security/..."/>  <!-- Optional; verbatim or by reference when trust-boundary -->
  <requirements ref="docs/requirements/..."/> <!-- Optional; BA stories -->
  <domain_model ref="docs/domain/..."/>       <!-- Optional; glossary/domain model — use its terms verbatim, do not redefine them -->
</inputs>
<!--
  Dispatcher obligation (anti-fabrication): before issuing the Task call, the
  dispatcher MUST verify every <file path>, <contract ref>, <security_rules ref>,
  <requirements ref>, and <domain_model ref> exists via Read or Glob. If a path is planned-but-not-
  yet-created (e.g., a new file the subagent will author), mark it `status="new"`
  and do NOT try to Read it — but every path without that marker MUST resolve.
  Fabricated input paths propagate into subagent work; per rules/reduce-hallucinations.md
  they are defects with the same severity as fabricated evidence paths.
-->


<task>
A single imperative instruction. Literal phrasing. No "please consider" or "it might be nice to". State scope explicitly — do not assume the model will generalize.
</task>

<exit_criteria>
- Concrete, measurable condition 1 (e.g., "tests in pkg/auth pass with `go test ./pkg/auth/...`")
- Concrete, measurable condition 2 (e.g., "OpenAPI spec validates: `redocly lint openapi.yaml`")
- Concrete, measurable condition 3 (e.g., "no lint errors: `golangci-lint run`")
Every criterion must be verifiable by running a command and reading output.
</exit_criteria>

<out_of_scope>
Explicit "do NOT touch" list. Common items:
- "Do NOT refactor outside the listed files"
- "Do NOT change database migrations"
- "Do NOT modify the TechLead contract — if it looks wrong, return NEEDS_CONTEXT"
</out_of_scope>

<evidence>
What artifacts the subagent must produce AND how the reviewer will verify:
- Files created/modified (paths)
- Test output written to file (path + command)
- Logs/traces saved (path)
- docs/ persistence (if strategic agent, per rules/strategic-output-persistence.md)
Forbidden: "should work", "looks good", paraphrased success. Only actual command output counts.
</evidence>

<bailout>
- If you cannot resolve an ambiguity from the inputs provided → return NEEDS_CONTEXT
- If 2 fix attempts on the same issue fail → return BLOCKED (do NOT start a third)
- If the task turns out to require touching out_of_scope files → return BLOCKED with a scope-change request
Review loop limits: see rules/review-loop-limits.md.
</bailout>

<return_format>
Your return MUST include three things (prose, markdown, or XML — shape is flexible):
1. A status: one of DONE, DONE_WITH_CONCERNS, NEEDS_CONTEXT, BLOCKED
2. Evidence: file paths / log paths the dispatcher can Read to verify your work
3. Exit-criteria check: one line per criterion above, marked met | not met — reason
Full conventions: rules/subagent-return-format.md.
</return_format>

<delegation>
<!-- Required for nested delegators (BA, tech-lead) when dispatched by orchestrator -->
You are dispatched by the orchestrator. Do NOT re-dispatch to Level-2 agents via Task. Document requirements/design/contracts for the orchestrator to dispatch.
</delegation>
```

---

## Parallel dispatch

When the dispatcher fans out to N independent subagents:

1. All N `Task` calls MUST be in a **single assistant message** with multiple tool_use blocks.
2. Each subagent gets its own `<inputs>` block with non-overlapping `<file>` scopes.
3. Dependencies (e.g., UI/UX before frontend, contract before backend+frontend) MUST be resolved before parallel fan-out — never parallelize across a dependency edge.

See `skills/dispatching-parallel-agents/SKILL.md` for the selection rubric (when to fan out vs. sequence).

---

## Re-dispatch discipline

If a subagent returns `NEEDS_CONTEXT`, `BLOCKED`, or reviewer rejection:

1. **Read the actual return payload.** Do not re-dispatch based on assumed failure.
2. **Diagnose root cause.** Is it missing context, wrong scope, ambiguous spec, or a tool/environment issue?
3. **Amend the prompt.** Add the missing inputs, tighten scope, or split the task.
4. **Never resend the identical prompt.** If the prompt caused failure once, it will cause failure again.

Hard limits (see `rules/review-loop-limits.md`): max 2 fix rounds per issue category (spec OR quality); max 4 total review iterations per task. Hit the limit → escalate, do not loop.

---

## What NOT to include in dispatch prompts

- Conversation history from your session (the subagent is a fresh context — construct what it needs)
- Vague instructions ("make it good", "handle edge cases")
- Implicit scope ("update the related files") — enumerate paths
- Negative-only constraints without positives (pair "do NOT X" with "DO Y")
- Duplicated review-loop / strategic-persistence rules (reference the rule files)

---

## Minimal example (backend implementer, orchestrator-dispatched)

```xml
<role>You are the backend-engineer-subagent implementing the /login endpoint per the TechLead contract and Security rules.</role>

<context>
Parent: agent-orchestrator. Upstream: Security produced AUTH-01..AUTH-07 rules (docs/security/auth-login.md); TechLead produced the OpenAPI contract (docs/contracts/auth-login.yaml). You are dispatched in parallel with frontend-engineer-subagent, which consumes the same contract.
</context>

<inputs>
  <file path="pkg/auth/login.go"/>
  <file path="pkg/auth/login_test.go"/>
  <file path="cmd/server/routes.go"/>
  <contract ref="docs/contracts/auth-login.yaml"/>
  <security_rules ref="docs/security/auth-login.md"/>
</inputs>

<task>Implement POST /login per the OpenAPI contract. Validate inputs per Security rules AUTH-01..AUTH-04. Issue JWT per AUTH-05..AUTH-07. Write table-driven tests covering success, invalid password, locked account, and expired-token refresh.</task>

<exit_criteria>
- `go test ./pkg/auth/...` passes with ≥80% coverage
- `redocly lint docs/contracts/auth-login.yaml` clean
- `golangci-lint run ./pkg/auth/...` clean
- Contract examples in the OpenAPI file pass integration test against the handler
</exit_criteria>

<out_of_scope>
- Do NOT modify docs/contracts/auth-login.yaml (TechLead owns it)
- Do NOT change database migrations
- Do NOT touch the frontend
</out_of_scope>

<evidence>
- Modified files: pkg/auth/login.go, pkg/auth/login_test.go, cmd/server/routes.go
- Test output: write `go test -v ./pkg/auth/... > /tmp/auth-test.log` and reference the log path in your return
- Coverage: `go test -cover ./pkg/auth/...` output in the same log
</evidence>

<bailout>
- NEEDS_CONTEXT if AUTH-03 (rate-limiting) is ambiguous about per-IP vs per-account
- BLOCKED if 2 fix attempts on the JWT signing key rotation fail
</bailout>

<return_format>
Include: (1) status — DONE | DONE_WITH_CONCERNS | NEEDS_CONTEXT | BLOCKED; (2) evidence paths I can Read; (3) exit-criteria check (one line per criterion above: met | not met — reason). Any shape (prose, markdown, XML). Full conventions: rules/subagent-return-format.md.
</return_format>
```
