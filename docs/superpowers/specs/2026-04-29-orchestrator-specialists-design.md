# Orchestrator + Specialist Agents — Design

**Status:** Draft — pending user review
**Author:** brainstorming session, 2026-04-29
**Scope:** First of three specs that port `~/.claude/` discipline into the platform. Subsequent specs: (B) verification gates + review-loop limits, (C) skills + rules per-team.

## Summary

Replace `WorkflowAgent` with a new `OrchestratorAgent` that sequences pre-implementation specialists (`BusinessAnalyst`, `Security`, `QA`) before the existing coding/review pipeline runs. Designs three additional specialists as stubs (`TechLead`, `UXDesigner`, `APITest`) so the routing machinery can exercise all 6 roles in tests while only 3 do real work in MVP. `CodingAgent` stays unified (stack-agnostic) — no separate backend/frontend/etc. agents.

## Goals

- Platform gains the pre-implementation discipline that `~/.claude/` has today: structured requirements (BA), threat modeling (Security), gate-based sign-off (QA).
- Trust-boundary changes (auth, payments, PII, external API surface) cannot reach the coding agent without Security producing implementation rules first.
- Ambiguous tickets get clarified by BA before CodingAgent burns tokens on the wrong thing.
- Final QA gate blocks tasks from transitioning to `COMPLETED` when acceptance criteria aren't met.
- Cost overhead on trivial (fast-path) tasks is bounded to a single Haiku classifier call (~$0.0001).

## Non-goals (explicit — out of scope for this spec)

- Per-team routing customization (`teams.settings_json.orchestration_overrides`) — follow-up spec C.
- Verification gates and review-loop-limits enforcement — follow-up spec B.
- Splitting `CodingAgent` into per-stack specialists (backend/frontend/mobile/etc.) — deliberately rejected; the current stack-agnostic agent is the right abstraction for a multi-tenant platform.
- Replacing `ReviewerAgent` or `DevOpsAgent` — they stay as is, with only a minor read-from-artifacts change to reviewer.

## Core decisions (locked)

| # | Decision | Choice | Rationale |
|---|----------|--------|-----------|
| 1 | First spec scope | Orchestrator + specialists | Most visible platform differentiator; directly maps `~/.claude` discipline into product |
| 2 | Routing mechanism | Hybrid: rules classify tier, LLM picks specialists within tier | Matches `workflow-routing.md`; predictable cost/latency budget + flexibility where judgment is needed |
| 3 | Specialist roster | Design 6 roles, implement 3 (BA/Security/QA) in MVP | Design once, implement in phases; reduces redesign risk |
| 4 | Routing rules location | Code-level (`routing.py`) | PR-reviewed, diffable, testable without API calls. Per-team config deferred to spec C |
| 5 | Specialist comms | DB artifacts + orchestrator fan-out | Persistent audit trail + controlled context; orchestrator decides relevance |
| 6 | Failure handling | Tiered: 2-round repair for parse, 3× backoff for infra, blocker verdicts are sacred | Matches existing reviewer pattern; preserves gate integrity |
| 7 | Orchestrator timing | Cheap Haiku classifier on every task, then branch | `workflow-routing.md`'s "Ask one question" made programmatic; classifier cost negligible |

## Architecture

### System diagram

```
┌─────────────────────────────────────────────────────────────────┐
│                       Task Intake                                │
│           (webhooks, admin UI, Slack, /api/admin/tasks)          │
└───────────────────────────┬─────────────────────────────────────┘
                            │ submit_task(ticket, repo, context)
                            ▼
┌─────────────────────────────────────────────────────────────────┐
│            OrchestratorAgent (new — agent_type="orchestrator")  │
│  1. Classify (cheap Haiku call → fast_path|full|multi_domain)   │
│  2. If fast_path → delegate straight to coding pipeline         │
│  3. Else consult routing rules + LLM pick → sequence specialists│
│  4. Dispatch specialists in order, fan out prior artifacts       │
│  5. Collect verdicts, hard-stop on blockers, write artifacts    │
│  6. Once gates pass → hand off to coding→review pipeline        │
└────┬──────────────┬────────────────┬──────────────┬─────────────┘
     │              │                │              │
     ▼              ▼                ▼              ▼
┌─────────┐  ┌──────────────┐  ┌────────┐  ┌───────────────┐
│   BA    │  │  Security    │  │   QA   │  │ Stubs: UX,    │
│ (new)   │  │   (new)      │  │ (new)  │  │ TechLead, API │
└─────────┘  └──────────────┘  └────────┘  └───────────────┘
     │              │                │
     └──────┬───────┴────────────────┘
            │ each writes: task_artifacts row keyed by (task_id, role)
            ▼
┌─────────────────────────────────────────────────────────────────┐
│  Existing pipeline: CodingAgent → ReviewerAgent                 │
│            (reads task_artifacts for security rules +           │
│             acceptance criteria before/during work)              │
└─────────────────────────────────────────────────────────────────┘
```

### What changes in the codebase

| Change | File(s) | Nature |
|--------|---------|--------|
| New agent class | `src/ai_driven/agents/orchestrator/agent.py` | new |
| Routing rules module | `src/ai_driven/agents/orchestrator/routing.py` | new, pure Python |
| Fan-out rules module | `src/ai_driven/agents/orchestrator/fan_out.py` | new, pure Python |
| Artifact Pydantic schemas | `src/ai_driven/agents/orchestrator/artifacts.py` | new |
| Specialist classes (3 live + 3 stubs) | `src/ai_driven/agents/specialists/{role}/agent.py` × 6 | new |
| Specialist system prompts | `src/ai_driven/claude/prompts/specialists/{role}.py` × 3 (stubs have no prompt) | new |
| Orchestrator + classifier prompts | `src/ai_driven/claude/prompts/orchestrator.py` | new |
| Repair-loop helper lifted from reviewer | `src/ai_driven/agents/base.py` (new `_repair_or_escalate_json` method) | refactor |
| Reviewer uses the lifted helper | `src/ai_driven/agents/reviewer/agent.py` | refactor |
| CodingAgent reads `task_artifacts` | `src/ai_driven/agents/coding/agent.py` | extend |
| Reviewer reads `task_artifacts` (security only) | `src/ai_driven/agents/reviewer/agent.py` | extend |
| State machine moves out of `agents/workflow/` | `src/ai_driven/workflow/state_machine.py` (relocated) | move |
| Triggers move out of `agents/workflow/` | `src/ai_driven/workflow/triggers.py` (relocated) | move |
| `WorkflowAgent` class + prompt deleted | `src/ai_driven/agents/workflow/agent.py`, `claude/prompts/workflow_agent.py` | DELETE (last in rollout sequence) |
| New SQS queues | `ai-driven-orchestrator-tasks`, `ai-driven-specialists-tasks` | infra |
| Obsolete queue deleted | `ai-driven-workflow-tasks` | infra (after rollout) |

**All specialists share one queue** (`ai-driven-specialists-tasks`) keyed by `role` in the message body. Per-role queues rejected as over-engineering for MVP — one shared consumer with role dispatch is simpler.

## Data model

### New table: `task_artifacts`

```sql
CREATE TABLE task_artifacts (
  id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  task_id         UUID NOT NULL REFERENCES tasks(id) ON DELETE CASCADE,
  role            VARCHAR(32) NOT NULL,
  payload         JSONB NOT NULL,
  verdict         VARCHAR(16) NOT NULL,
  reasoning       TEXT,
  model_used      VARCHAR(100),
  input_tokens    INTEGER DEFAULT 0,
  output_tokens   INTEGER DEFAULT 0,
  created_at      TIMESTAMP WITH TIME ZONE DEFAULT now(),
  CONSTRAINT task_artifacts_role_check CHECK (role IN (
    'business_analyst','security','qa','tech_lead','ux_designer','api_test'
  )),
  CONSTRAINT task_artifacts_verdict_check CHECK (verdict IN (
    'pass','pass_with_notes','block','error','not_implemented'
  ))
);
CREATE INDEX idx_task_artifacts_task ON task_artifacts(task_id);
CREATE UNIQUE INDEX idx_task_artifacts_task_role ON task_artifacts(task_id, role);
```

One artifact per (task_id, role). Re-runs UPDATE rather than INSERT — repair/retry history lives in the event stream, not the artifacts table.

### Schema extension: `tasks.orchestration_tier`

```sql
ALTER TABLE tasks ADD COLUMN orchestration_tier VARCHAR(16);
-- values: 'fast_path' | 'full_workflow' | 'multi_domain' | NULL (not yet classified)
```

NULL on pre-orchestrator tasks. Classifier writes one of the three values.

### Artifact payload schemas (Pydantic, validated at write)

```python
# business_analyst
{
  "ambiguities_resolved": [{"question": str, "resolution": str}],
  "acceptance_criteria": [{"given": str, "when": str, "then": str}],
  "out_of_scope": [str],
  "blocking_questions": [str]    # if non-empty, verdict='block'
}

# security
{
  "threat_model": [{
      "stride_category": str,
      "threat": str,
      "severity": Literal["low","medium","high","critical"],
      "mitigation": str
  }],
  "implementation_rules": [str],  # injected verbatim into CodingAgent prompt
  "compliance_notes": [str],
  "blocking_concerns": [str]     # if non-empty, verdict='block'
}

# qa (final gate)
{
  "checks_performed": [{"check": str, "passed": bool, "detail": str}],
  "test_coverage_gap": [str],
  "regression_risk": Literal["low","medium","high"]
  # No go_no_go — the top-level `verdict` column is the single source of truth
}

# tech_lead (stub in MVP — schema shown is the planned v1 shape, NOT implemented
# in this spec's work. Fields may be revised when implementation begins in spec follow-up.)
{
  "adr_summary": str,
  "api_contracts": [dict],
  "selected_tech": [{"choice": str, "rationale": str}],
  "delegation_plan": [str]
}

# ux_designer (stub)
{
  "wireframe_refs": [str],
  "user_flows": [{"step": int, "action": str, "screen": str}],
  "accessibility_notes": [str]
}

# api_test (stub)
{
  "endpoints_tested": [{"path": str, "method": str, "status": int, "p95_ms": float}],
  "security_smoke_results": [dict],
  "failures": [str]
}
```

## Routing & classification

### Layer 1 — tier classifier

Single Haiku call in `OrchestratorAgent._classify_tier()`. Prompt asks the question from `~/.claude/rules/workflow-routing.md`:
> Could a mistake here cause a security incident, data breach, or financial loss? Does this touch auth/payments/PII/external API? Multi-domain?

Output: strictly one of `{fast_path, full_workflow, multi_domain}`. Temperature 0. On parse failure, default to `full_workflow`. On infra failure, 3× exponential backoff, then `full_workflow` fallback. Result written to `tasks.orchestration_tier`. Cost: ~$0.0001/task on Haiku.

### Layer 2 — routing rules (pure Python)

```python
@dataclass
class TierRule:
    mandatory: list[str]         # always run, in listed order
    pick_from: list[str]         # LLM picker may include any subset
    skip_to_pipeline: bool       # true = no specialists, go straight to coding+review
    final_gate: str | None = None  # specialist that runs after code+review complete

TIER_RULES: dict[str, TierRule] = {
    "fast_path": TierRule(
        mandatory=[], pick_from=[],
        skip_to_pipeline=True,
    ),
    "full_workflow": TierRule(
        mandatory=["business_analyst", "security"],
        pick_from=["tech_lead", "ux_designer"],  # stubs in MVP
        skip_to_pipeline=False,
        final_gate="qa",
    ),
    "multi_domain": TierRule(
        mandatory=["business_analyst", "tech_lead", "security"],
        pick_from=["ux_designer", "api_test"],
        skip_to_pipeline=False,
        final_gate="qa",
    ),
}

def plan_specialist_sequence(tier: str, task_context: dict) -> SpecialistPlan:
    """Returns mandatory roles to run, plus candidates for LLM to pick from."""
```

Mandatory specialists always run. `pick_from` requires Layer 3. `final_gate` runs after code+review.

### Layer 3 — specialist picker

Only runs when `pick_from` is non-empty AND tier ≠ `fast_path`. One Haiku call: "given this task and these candidates, which are needed?" Output is a subset of `pick_from`. Bounded — model can't invent roles. If all `pick_from` entries are stubs (MVP), Layer 3 is skipped.

### Fan-out — which prior artifacts each specialist sees

| Specialist | Reads |
|---|---|
| `business_analyst` | nothing (runs first) |
| `security` | `business_analyst` |
| `tech_lead` (stub) | `business_analyst`, `security` |
| `ux_designer` (stub) | `business_analyst` |
| `api_test` (stub) | `tech_lead` (if present), `security` |
| `qa` (final gate) | ALL prior artifacts + CodingAgent output + ReviewerAgent output |
| `CodingAgent` (extended) | `business_analyst`, `security` |
| `ReviewerAgent` (extended) | `security` only |

Hard-coded in `fan_out.py` — pure function `relevant_artifacts_for(role, all_artifacts) -> dict`.

## Specialist contract

### Shared types

```python
@dataclass
class SpecialistContext:
    task_id: str
    ticket: JiraTicket           # title, description, labels, acceptance criteria
    repo: Repository             # full_name, scm_type, default_branch
    tier: str
    prior_artifacts: dict[str, Artifact]
    team_settings: dict          # empty dict in MVP

@dataclass
class Artifact:
    role: str
    verdict: Literal['pass', 'pass_with_notes', 'block', 'error']
    reasoning: str
    payload: dict                # validated against role's Pydantic model
    model_used: str
    input_tokens: int
    output_tokens: int
```

### Prompt template (all specialists)

```
# ROLE
You are a {role} agent. [one-line mandate]

# WHAT YOU READ
Jira ticket, target repository, prior specialist outputs (JSON).

# WHAT YOU PRODUCE
Single JSON object per schema:
{schema}
- verdict: 'pass' | 'pass_with_notes' | 'block' — use 'block' only when [criteria]
- reasoning: 1-2 sentences
- payload: {role schema}

# DECISION RULES
[role-specific]

# EXAMPLES
<positive_example>...</positive_example>
<blocker_example>...</blocker_example>

# ANTI-PATTERNS — THINGS YOU DO NOT DO
[explicit list pointing at adjacent specialists]
```

Anti-patterns section is the key anti-drift mechanism — every specialist's prompt explicitly says what OTHER specialists do so it doesn't encroach.

### Live specialist mandates

**`business_analyst`:** Resolve ticket ambiguity using context + repo conventions, OR return `verdict='block'` with `blocking_questions`. Always produce Gherkin acceptance criteria. Always list out-of-scope.

**`security`:** STRIDE against the described change. Produce `implementation_rules` (injected verbatim into CodingAgent prompt). Return `verdict='block'` only for flatly unsafe requests (plaintext credentials, disabling auth). Soft concerns → `compliance_notes` + `verdict='pass_with_notes'`.

**`qa` (final gate):** Read ALL prior artifacts + PR diff + reviewer comments. Produce `checks_performed` covering acceptance criteria, security rules, test coverage, regression risk. Explicitly forbidden from re-running reviewer work — it's a gate, not a second reviewer.

### Stub specialists

`tech_lead`, `ux_designer`, `api_test` — `BaseAgent` subclasses whose `run()` immediately writes `Artifact(verdict='not_implemented', ...)` and returns. No Claude calls, zero cost. Orchestrator treats `not_implemented` the same as `pass`.

### Output validation & repair

Reuse reviewer pattern, lifted into `BaseAgent._repair_or_escalate_json(schema, output)`:
- Round 1: re-prompt with error message, ask for corrected JSON
- Round 2: same
- After round 2: `verdict='error'`, publish `specialist_failed`, orchestrator halts → human escalation

## Failure handling (tiered per Q6)

| Failure type | Handling |
|---|---|
| Specialist JSON parse failure | 2-round repair loop, then escalate |
| Specialist infra error (rate-limit/timeout) | 3× exponential backoff via existing `claude.client` wrapper, then escalate |
| Specialist returns `verdict='block'` | Hard-stop. Task moves to `AWAITING_HUMAN`, Slack notification, orchestrator does NOT retry or re-prompt the specialist |
| Classifier parse failure | Default to `full_workflow`. No retry. |
| Classifier infra error | 3× backoff, then default to `full_workflow` |
| Routing rules bug (unknown tier) | Hard crash — code defect, caught by tests |
| Specialist picker parse failure | Fall back to "run all candidates in `pick_from`" |

Blocker verdicts are **sacred** — the orchestrator never re-prompts a Security `block` with different phrasing. That undermines the whole point of having gates.

## Claude API integration

### Model selection per role

| Role | Model | Why |
|---|---|---|
| Tier classifier | `claude-haiku-4-5` | 3-way classification, short input, temp=0 |
| Specialist picker | `claude-haiku-4-5` | Subset pick from known list |
| `business_analyst` | `claude-sonnet-4-6` | Ambiguity reasoning + Gherkin generation |
| `security` | `claude-sonnet-4-6` | STRIDE depth; under-calling severity is a real risk with Haiku |
| `qa` | `claude-sonnet-4-6` | Context-heavy (all artifacts + diff) |
| Stubs | n/a | No Claude calls |
| `CodingAgent` | unchanged (existing `settings.claude_provider`) | |
| `ReviewerAgent` | unchanged | |

Model IDs pulled from `src/ai_driven/config.py` constants, not hardcoded.

### Structured output — prefill + stop_sequences

Every specialist:
```python
messages=[
    {"role": "user", "content": prompt},
    {"role": "assistant", "content": "```json\n{"},
],
stop_sequences=["```"],
```
Parse with `json.loads`, validate against Pydantic schema. Matches existing `reviewer/agent.py` pattern. Lower token overhead than forced `tool_use`.

### Prompt caching

```python
response = client.messages.create(
    model=model, max_tokens=2048,
    system=[{
        "type": "text",
        "text": SPECIALIST_SYSTEM_PROMPT,  # 1500–2500 tokens
        "cache_control": {"type": "ephemeral"},
    }],
    messages=[...],
)
```

System prompt must exceed 1024 tokens to be cacheable — planned prompts clear this. Expected cache hit rate ~95% within 1h TTL. Cost math: ~87% input-token reduction on orchestration hot path (3 specialists × ~2000 cached system tokens each).

**Not cached:** tier classifier prompt (too short, below 1024 minimum), `prior_artifacts` (change per task, go in `messages`).

### Tools per specialist

| Specialist | Tools |
|---|---|
| `business_analyst` | none |
| `security` | none |
| `qa` | `scm.read_diff`, `scm.read_file`, `tests.list_run_results` (read-only) |
| Stubs | none |

Reuse existing tool implementations from `CodingAgent` for QA's tools — same handlers, registered in QA's `ToolRegistry`. Specialists never take write side-effects (no PR creation, comment posting, state changes) — that's coding/reviewer/devops territory.

### Streaming

**No streaming for specialists in MVP.** They produce single structured JSON objects; streaming adds failure modes (`_final_message` fallback) with no user-visible benefit. Coding/reviewer keep streaming.

### Rate limits & retries

Every Claude call routes through existing `claude.client.Client` (tenacity retries, token budget, observability hooks). Orchestrator tiered-failure rules kick in AFTER the wrapper exhausts retries.

### Cost ceilings per specialist

```python
class SecurityAgent(BaseAgent):
    MAX_OUTPUT_TOKENS = 2048
    MAX_INPUT_TOKENS = 8192  # refuse to run if exceeded → verdict='error' + reasoning="context too large"
```

### Event emission

Every specialist emits to the existing `agent_events` Redis pub/sub channel, same event schema as coding/reviewer:
1. `specialist.started` — `{role, task_id, model}`
2. `specialist.completed` — `{role, task_id, verdict, tokens_in, tokens_out, cost_usd}`
3. `specialist.artifact_persisted` — `{role, task_id, artifact_id}`

UI consumes these automatically — events surface in the existing task timeline without frontend changes.

## Testing

### Unit tests (`tests/unit/`)

- `test_routing_rules.py` — pure function tests, ~20 cases
- `test_fan_out.py` — per-role allowed-reads assertions
- `test_specialist_{role}.py` × 6 — mocked Claude, asserted artifact fields
- `test_specialist_repair_loop.py` — malformed JSON handling
- `test_classifier.py` — tier assignment + fallback behavior
- `test_orchestrator_agent.py` — full orchestrator flow with mocked specialists (order, persistence, blocker hard-stop, stub handling)

Target: 100% routing/fan-out coverage, ≥90% orchestrator state transitions.

### Integration tests (`tests/integration/`) — real Postgres/Redis, mocked Claude

- `test_orchestrator_flow.py` — SQS-driven: enqueue → consume → specialists → artifacts → coding → complete (happy path, blocker path, stub path)
- `test_artifact_injection.py` — CodingAgent sees security `implementation_rules` in its prompt
- `test_workflow_agent_removal.py` — tasks route through Orchestrator not WorkflowAgent

### Evaluation suite (`tests/evals/specialists/`) — real Claude, nightly CI

Following `tests/evals/reviewer/` pattern. ~15 real Jira tickets per role with expected verdicts. Haiku-based grader scores verdict correctness, reasoning quality, payload completeness. Thresholds: ≥90% verdict correctness, ≥7/10 grader scores. Failures on main alert Slack — no PR block, no nightly block (costs real API money; hard-gating nightly eval would wake people for non-actionable failures).

### E2E (Playwright)

One new test in `full-workflow.spec.ts`: submit security-labeled ticket via admin UI → verify specialist events in timeline → verify artifacts display on task detail page.

## UI changes

Minimal, ship last:
1. Task detail page: new "Artifacts" tab listing each specialist's verdict + collapsible payload JSON
2. Dashboard: new KPI — `% tasks by orchestration_tier`

Specialist events appear in the existing task timeline automatically (same event schema).

## Rollout strategy

### Phase 1 — dark launch / shadow (1 week)

Setting `ai_driven.orchestrator.mode = shadow` (default). For every task: orchestrator classifies and logs proposed routing, but existing pipeline still runs. No specialist calls, no artifacts written. Validates classifier against real traffic.

Metrics to watch:
- Tier distribution (expected ~70% fast_path, ~25% full_workflow, ~5% multi_domain)
- Classifier p95 latency (< 500ms)
- Classifier error rate (< 0.1%)

### Phase 2 — live, per-team opt-in (2 weeks)

Flip `orchestrator.mode = live` globally. Add `teams.orchestration_enabled` boolean column (default `false`). Enable for one internal team first, monitor 1 week, then 1–2 beta teams. Full rollout when feedback loop is clean.

Teams with `orchestration_enabled=false` route straight to the existing coding+review pipeline — escape hatch preserved 30 days post-rollout.

### WorkflowAgent deletion

Separate PR, scheduled last:
1. Build orchestrator + specialists alongside WorkflowAgent
2. Phase 1 shadow — WorkflowAgent still running
3. Phase 2 live per-team — new path bypasses WorkflowAgent for opted-in teams only
4. After 2 weeks of all-teams stable operation → delete `WorkflowAgent` class, its queue, its consumer, its prompts
5. Relocate `state_machine.py` and `triggers.py` out of `agents/workflow/` into `src/ai_driven/workflow/`

Deletion in its own PR makes rollback trivial through week 3.

### Migration safety

Alembic migration is additive (new table + new column with NULL default) — zero-downtime. Existing tasks keep `orchestration_tier=NULL` (treated as "not classified" — correct for pre-orchestrator tasks).

Rollback: set `mode=off`, redeploy. Alembic down-migration drops table + column.

## Observability

Three new DataDog dashboards (same dir as existing AI-specific dashboards):

1. **Orchestration health** — classifier latency, tier distribution, specialist latency p50/p95/p99, specialist verdict distribution, blocker rate per role (day 1)
2. **Cost attribution** — specialist cost per task (sum of token costs from `task_artifacts`), compared to fast-path baseline (day 1)
3. **Gate effectiveness** — % of Security `block` verdicts justified in hindsight (requires human feedback — 2-month fill-in, not day 1)

## Documentation updates

Edits to existing files (no new doc tree created):
- `docs/implementation/00-overview.md` — add orchestrator to agent list, update tech-stack table
- `docs/architecture/02-system-architecture.md` — new section on orchestration layer, updated sequence diagram
- `docs/features/06-orchestration.md` — new file, explains tiers and specialist roles to end users

## Open risks

- **Classifier drift.** The tier classifier is a single model call on short input; prompt changes could silently re-distribute tiers. Mitigation: nightly eval on fixed dataset, threshold alert on distribution shift > 10% vs baseline.
- **Prompt cache invalidation.** Any edit to a specialist system prompt invalidates that role's cache for 1h, temporarily spiking costs. Mitigation: batch prompt edits, document in release notes.
- **Specialist output context bloat.** As more artifacts are written, QA's prompt grows (reads all). Mitigation: cost ceiling (`MAX_INPUT_TOKENS`) triggers `verdict='error'` for oversized tasks with "needs decomposition" reasoning.
- **WorkflowAgent deletion coordination.** External code (tests, admin UI buttons, Slack commands) may reference WorkflowAgent directly. Mitigation: grep-based inventory before deletion PR, explicit migration step for each caller.
- **Per-team rollout slowness.** Two-week opt-in may frustrate stakeholders wanting immediate full cutover. Mitigation: metrics dashboard makes the case for the phased approach. Phase 2 can be shortened (from 2 weeks → 1 week) when Phase 1 meets *all* of: classifier error rate < 0.1% over ≥500 tasks, p95 latency < 500ms sustained, tier distribution within ±15% of the ~70/25/5 baseline, and zero orchestrator consumer crashes during the shadow window.

## Follow-up specs (explicit sequence)

- **Spec B:** Verification gates + review-loop limits enforcement. Hard-stop discipline for coding/reviewer agents. Builds on this spec's artifact infrastructure.
- **Spec C:** Skills + rules per-team. Per-team `orchestration_overrides` in `teams.settings_json`, skill library the specialists can opt into. Builds on A and B.

Each gets its own brainstorming → spec → plan cycle.
