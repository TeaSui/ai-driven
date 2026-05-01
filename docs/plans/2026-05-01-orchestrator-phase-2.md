# Orchestrator Phase 2 — Live mode, context injection, QA gating

**Status:** v6 — T0 returned ABORT-STRICT; pivoted to Path A+B hybrid per 5th red-team round. Ready to dispatch T-A2.

## Rollout reality (post-T0)

T0 shadow-baseline (`docs/phase2-shadow-baseline.md`) found:
- 45-73% specialist error rate from Bedrock `max_tokens` truncation and AWS credit exhaustion
- Every `task_artifacts.cost_usd = 0.00` (attribution hook never wired)
- Sample too small (11-13 per role) for meaningful FP math
- QA block rate 75% on n=4 is untrustworthy

Flipping straight to strict gating would halt a majority of pipelines for infrastructure reasons. Red-team of the repair plan (round 5) rejected a pure "pause + grow sample" approach and recommended a hybrid:

### Path A+B hybrid (what we're actually shipping)

| # | Task | Days |
|---|---|---|
| T-A2 | Cost attribution end-to-end + cost-ceiling compounding fix | 1 |
| T-A1 | Audit + fix top 3 specialist error modes, define <15% fallback | 1-1.5 |
| T19 | prior_artifacts handoff (from original Phase 2 — moved forward so dogfood reflects Phase-2 topology) | 0.5 |
| T18 + T19b | prompt_composer + PR persist + **ship LENIENT gating** (halt only on non-empty `blocking_concerns`/`blocking_questions`; errors pass through) | 1.5 |
| T-A3 | Dogfood 50+ tickets over 2 weeks on `feature/orchestrator-phase1`, per-role non-error targets (≥15 BA, ≥15 security, ≥10 qa). Lenient gating runs LIVE during this time so we collect real block-decision data, not dry-run artifacts | ~2 weeks wall, 1 day work |
| T0-rerun | Re-run shadow baseline against fresh data. If exit criteria met → flip to strict (T-B1). If not → stay lenient indefinitely | 0.5 |
| T20 | Orchestrator as SQS worker (from original Phase 2 plan) | 1.5 |
| T21 | Strict gating + status migration (only runs if T0-rerun passes) | 1 |
| T22 | QA post-coding | 1.5 |
| T23 | QA verdict → Task status | 0.5 |
| T24 | Admin override + stuck-orchestrator reaper | 0.5 |
| T25 | Specialists UI tab | 1 |
| T26 | Cutover | 0.25 |
| T27 | Dogfood harness + results report | 1 |
| T28 | Cleanup + runbook | 0.5 |

### What changed vs v5

0. **T18 runs before T19 (reorder)**. v5/v6 draft put T19 first so "dogfood corpus reflects Phase 2 topology" — but a stub composer (T19 without T18) produces identical output to the current static prompt, so the topology signal is zero. Building T18 first as a pure function is independently testable and gives T19 a real signature to wire against. Red-team round 6 confirmed.
1. **Lenient gating ships first** (as the T18+T19+T19b bundle). Strict gating is contingent on T0-rerun passing.
2. **T-A2 cost-attribution scope expanded** to end-to-end fix (dataclass field + base class compute + repo writer + reader + Decimal precision + cost-ceiling rollup). 1 day, not 0.5.
3. **T-A1 has a fallback spec** — if error rate can't drop <15% after max_tokens tuning + retry-on-ValidationException, we stay on lenient gating permanently and document.
4. **T19 moved forward** so dogfood corpus reflects actual Phase 2 topology.
5. **T-A3 target raised** to 50+ tickets / ≥15 BA / ≥15 security / ≥10 qa per-role non-error counts (not a flat 30).
6. **T-A3 overlaps with T18** — dogfood runs while lenient gating is live, producing real override/block decisions for the strict-gating decision.

### Fallback path (if errors stay high)

If T-A1 can't drive error rate below 15%, the plan stays at lenient gating permanently:
- T20 (SQS worker) still ships.
- T21 (strict migration) does NOT ship.
- T22 QA ships but runs only advisory (never blocks via `security_rules_verification.followed=false`).
- T24 override endpoint still ships for the cases where lenient-gating false-positives.
- T27 dogfood still measures "did specialist output influence the PR diff" as the primary value metric.

Lenient gating + observability is the minimum viable Phase 2. Strict gating is an upside if the data supports it.
**Author:** Session 2026-05-01
**Depends on:** Phase 1 (T1–T17 + T5b) on `feature/orchestrator-phase1`.

## Why Phase 2

Phase 1 specialists are write-only: output goes into `task_artifacts` and nothing reads it. Paying ~$0.05–$0.10 orchestration overhead per task with zero behavior change. Phase 2 makes specialists shape what gets built:

1. **Gating** — `verdict=block` halts the pipeline BEFORE CodingAgent runs, saving a $2–3 wasted run.
2. **Context injection** — Security `implementation_rules` and BA `acceptance_criteria` paste VERBATIM into CodingAgent's system prompt.
3. **Post-coding QA** — QA specialist fetches the PR diff, validates every BA AC + every Security rule was followed. Failures block the PR.

## User-locked decisions

| Decision | Chosen |
|---|---|
| Rollout | Global (one env-var flip) |
| Gating strictness | Strict on `implementation_rules` + `blocking_concerns` |
| TechLead in Phase 2 | **Dropped** (red-team #11 — narrow scope has no downstream consumer in P2) |
| Stack guides | Skip, add later |
| Dogfood target | `feature/orchestrator-phase1` branch of this repo (self-hosting) |
| Status model | First-class `blocked` + `qa_failed` via Alembic migration |
| Shadow baseline | Approved as T0 (half day) |
| Override auth | Admin-role only |

## Red-team blockers (v1 → v2 deltas)

v1 was rejected. The corrections rolled into this v2:

1. **BLOCKER — invented Redis handoff.** CodingAgent now reads `prior_artifacts` directly from the `task_artifacts` table via `ArtifactRepository`. No new infra. T18/T19/T20 rewritten.

2. **BLOCKER — synchronous orchestration can't live in the HTTP path.** OrchestratorAgent runs as an SQS worker on its own queue (`ai-driven-orchestrator-tasks` already created). Chain becomes:
   ```
   POST /api/chat/send
     → enqueue orchestrator-task
     → return status="orchestrating" in ~200ms
   Orchestrator worker
     → runs specialists serially
     → if block → status=blocked, stop
     → if pass → write artifacts, enqueue coding-task
   Coding worker
     → CodingAgent runs with injected context
     → opens PR, writes pr_number to Task.output_data
     → enqueue qa-task
   QA worker (new)
     → fetches PR diff, validates
     → verdict=block → status=qa_failed
     → verdict=pass → status=completed
   ```
   T21 rewritten around this.

3. **BLOCKER — `blocked` + `qa_failed` violate `ck_tasks_status`.** T21 now includes a proper Alembic migration adding both values to the CHECK constraint.

Plus these MAJOR fixes inline:

4. QA removed from Phase 1's pre-coding `final_gate` in routing.py; added as post-coding invocation (T22).
5. PR number persisted to `Task.output_data.pr_number` by CodingAgent (T19b sub-task).
6. Concurrent-counter decremented on blocked-task termination (solved by Fix #2 — orchestrator worker's `finally` covers it for free).
7. Admin-role override endpoint `POST /api/admin/tasks/{id}/override-block` (T24).
8. T0 shadow-baseline spike runs before committing to strict gating.
9. Dogfood (T27) repos: this ai-driven repo itself, on `feature/orchestrator-phase1`.
10. In-flight SQS messages stamp `orchestrator_mode` on enqueue; worker reads stamped value (handles deploy-time transition).

## Out of scope (explicit)

- TechLead Phase-2 specialist (stays stub; Phase 3 with mobile+aws specialists that can consume API contracts)
- Stack guides library
- Per-team orchestrator_mode column (global flip is enough)
- Multi-repo CodingAgent support
- Actual test execution during QA (Phase 2.1 — CI integration)
- UX Designer / API Test specialists (stubs stay)

## High-level flow (post-Phase 2)

```
User submits ticket
  ↓
POST /api/chat/send enqueues "orchestrator" task
  ↓ (returns status=orchestrating in ~200ms)
Orchestrator worker dequeues
  ↓
Classifier → tier
  ├─ fast_path → enqueue coding-task directly, no specialists
  └─ full_workflow / multi_domain:
      ↓
      Run specialists: BA → StackAnalyst → Security (no pre-coding QA)
      ↓
      any verdict=block with severity=high?
        yes → Task.status="blocked"
              Task.output_data = { blocked_by, blocking_reasons[] }
              END
        no  → write_artifacts_persisted
              enqueue coding-task
      ↓
  Coding worker dequeues
  ↓
  CodingAgent.run()
    - reads Task.output_data.prior_artifacts via ArtifactRepository
    - prompt_composer injects BA acceptance_criteria + Security rules
    - agent loop, opens PR via github.create_pr
    - persists pr_number, pr_url to Task.output_data
  ↓
  enqueue qa-task (only if Task.output_data.pr_number present)
  ↓
  QA worker dequeues
  ↓
  QaSpecialist.run_specialist_post_coding(task_id, pr_number)
    - fetches PR diff via github.get_pr_diff
    - validates each BA.acceptance_criteria has evidence
    - validates each Security.implementation_rules followed
    - emits verdict
  ↓
  verdict=block? → Task.status="qa_failed"
                   Task.output_data.qa_failures = [ {rule|AC, evidence}... ]
  verdict=pass or pass_with_notes? → Task.status="completed"
```

## Task list

### T0 — Shadow baseline spike (0.5 day)

**Files:** `docs/phase2-shadow-baseline.md`

Query the 60+ shadow-mode tasks already in `task_artifacts`. Compute:
- Per-role block rate (`verdict="block"` / total) for BA, Security.
- Per-role error rate (`verdict="error"`) — so the baseline doesn't confuse "AWS credit exhausted" with "block".
- Approximate false-positive rate: of the `block` verdicts, how many tickets had their CodingAgent run succeed anyway (human-merged PR exists)?

**Exit criteria:** if any role's block rate is >40% OR its FP rate is >25%, escalate — strict gating is a bad idea and we go lenient (pass_with_notes only halts the pipeline). If rates look sane (<20% block, <10% FP), proceed with strict as planned.

**Commit:** `docs(orchestrator): shadow-baseline metrics before Phase 2 strict gating`

---

### T18 — `prompt_composer` module (1 day)

**Files:** `src/ai_driven/agents/coding/prompt_composer.py` (new), `tests/unit/test_prompt_composer.py` (new)

Pure function: `compose_coding_prompt(base_system_prompt, prior_artifacts: dict[str, Artifact]) -> str`.

- Empty prior_artifacts → return base unchanged.
- BA.acceptance_criteria present → append `<acceptance_criteria>` XML block, each AC as a bullet with `Given/When/Then`.
- Security.implementation_rules present → append `<security_rules>` XML block, numbered list, verbatim.
- Malformed artifact (missing keys, wrong shape) → skip that role with a warning log, include the rest.

Tests: empty / BA-only / BA+Security / both-malformed / graceful-degrade-on-Pydantic-error.

**Commit:** `feat(coding): prompt_composer injects specialist artifacts into system prompt`

---

### T19 — CodingAgent reads prior_artifacts from `task_artifacts` + wires composer (0.5 day)

**Files:** `src/ai_driven/agents/coding/agent.py`, `tests/unit/test_coding_agent_prompt_composition.py` (new)

At `CodingAgent.run()` start:
1. If `task_id` present, load prior artifacts via `ArtifactRepository.list_for_task(task_id)`.
2. Pass to `prompt_composer.compose_coding_prompt(self.SYSTEM_PROMPT, prior_artifacts)`.
3. Use composed prompt for the agent loop.
4. If ArtifactRepository returns empty (fast_path, or orchestrator off), use base prompt unchanged.

Graceful-degrade tests: DB outage during load → log warning, use base prompt, don't crash.

**Commit:** `feat(coding): wire prompt_composer into CodingAgent.run via task_artifacts`

---

### T19b — CodingAgent persists pr_number + pr_url after PR creation (0.5 day)

**Files:** `src/ai_driven/agents/coding/agent.py`, `tests/unit/test_coding_agent_pr_persist.py` (new)

When CodingAgent's `github.create_pull_request` tool succeeds, persist the resulting PR number + URL to `Task.output_data.pr_number` / `.pr_url`. QA (T22) reads these to know which PR to validate.

**Idempotency on retry (crash-safe):** BEFORE calling `github.create_pull_request`, read `Task.output_data.pr_number`. If already set (SQS redelivery after worker crash), skip the PR-create call, return the existing PR reference, and proceed to "agent loop complete". Prevents duplicate PRs when SQS redrives a coding task that previously opened a PR but crashed before acking.

**Crash window — PR created but pr_number not persisted:** if the worker opens the PR successfully but dies before the DB write lands, SQS redelivery would not find `pr_number` in output_data and would attempt to create a second PR. GitHub's branch-name uniqueness prevents a literal duplicate (the retry's `create_pull_request` call returns 422 "A pull request already exists for <branch>"). CodingAgent MUST catch this specific 422, parse the existing PR's number/URL from the error response (GitHub's error body includes the PR reference), and persist it — treat as success. Document this path with an explicit test: simulate branch-already-has-PR by calling create twice back-to-back in a unit test.

**No-PR terminal state:** if CodingAgent's agent loop ends without opening a PR (out-of-iterations, Claude returned no-PR, etc.), the coding-worker MUST set `Task.status="failed"` with `Task.output_data.failure_reason="no_pr_created"` (user-locked decision — reuse `failed` rather than adding a third new status). Do not enqueue QA in this case. Today the task would sit at `status=coding` forever; this task closes that hole.

**Commit:** `feat(coding): persist pr_number + pr_url to Task.output_data`

---

### T20 — Orchestrator runs as SQS worker (1.5 days)

**Files:**
- `src/ai_driven/queue/consumer.py` — `_OrchestratorWrapper` at lines 309, 317–368 already exists and is production-ready (returns AgentResult with token accounting). DO NOT rewrite. Extend it to: (a) persist `Task.status` transitions explicitly, (b) enqueue the downstream coding-task on orchestrator success, (c) verify `task_id` passthrough.
- `src/ai_driven/agents/orchestrator/agent.py` — ensure `orchestrate()` accepts the consumer wrapper's payload shape; no new `.run()` method needed (wrapper handles the adaptation).
- `src/ai_driven/queue/producer.py` — `_schedule_shadow_orchestrator` deleted; in live mode, enqueue to `ai-driven-orchestrator-tasks` SQS queue; shadow mode remains fire-and-forget via `create_task`.
- `src/ai_driven/api/chat/router.py` — live mode sends to orchestrator queue, returns immediately.
- New `tests/integration/test_orchestrator_worker.py`.

**Critical invariants:**
1. SQS message body stamps `orchestrator_mode` at enqueue time so in-flight messages during deploy behave predictably.
2. **Idempotency marker for crash-safe retries:** the orchestrator's terminal action MUST persist an idempotency flag in the same DB transaction as artifact upsert — use `Task.output_data.orchestrator_completed_at = <iso timestamp>`. On SQS redelivery (worker crashed mid-run before message acked), the orchestrator worker checks this marker first; if present, skip specialist re-runs and go straight to enqueue-coding-task. Prevents duplicate Claude spend on retry.
3. **DLQ + stuck-state reaper:** `ai-driven-orchestrator-tasks` queue MUST have a DLQ configured (redrive after 3 failures). After DLQ, the Task stays at `status="orchestrating"` with no recovery — T24's override endpoint handles this by accepting `orchestrating` as an overrideable state when `started_at` is older than 30 minutes (see T24 change).

**Commit:** `feat(orchestrator): run as SQS worker with crash-safe idempotency — orchestrator-tasks queue`

---

### T21 — Live-mode gating + status migration (1 day)

**Files:**
- New Alembic migration adding `blocked` + `qa_failed` to `ck_tasks_status`. Record its revision id; T24's migration will set `down_revision` to this one.
- `src/ai_driven/agents/orchestrator/agent.py` — on any specialist returning `verdict=block`:
  - persist Artifact as usual
  - set `Task.status="blocked"`, `Task.output_data={blocked_by, blocking_reasons, tier}`
  - do NOT enqueue coding-task
  - `finally` path still runs, decrementing rate-limit counter (free from being an SQS worker)
- Remove `final_gate="qa"` from `TIER_RULES` in `routing.py` (QA moves to post-coding)
- **Explicit test file updates:**
  - `tests/unit/test_orchestrator_routing.py` lines 22, 28, 50 — remove/update assertions that `final_gate == "qa"` and `full[-1] == "qa"`
  - `tests/unit/test_orchestrator_agent.py` line 71 — update the expected role-set (QA no longer in pre-coding sequence). Add a NEW assertion that QA is invoked via the post-coding path in T22 — use `spec.call_args` on the coding-enqueue side.

**Integration test:** submit ticket whose description intentionally triggers a BA block (ambiguous), assert:
- Task.status transitions queued → orchestrating → blocked (never coding)
- Zero messages ever land on the coding SQS queue
- Rate-limit counter is zero after

**Commit:** `feat(orchestrator): live-mode strict gating + blocked status migration`

---

### T22 — QA post-coding specialist (1.5 days)

**Files / config / infra:**
- `src/ai_driven/agents/specialists/qa/agent.py` — add `run_post_coding(task_id, pr_number)` that fetches PR diff and validates
- `src/ai_driven/claude/prompts/specialists/qa.py` — extend system prompt: "You're verifying PR compliance against BA ACs and Security rules. For each, cite a line/file as evidence."
- `src/ai_driven/agents/orchestrator/artifacts.py` — extend `QaPayload` with `acceptance_criteria_verification`, `security_rules_verification`
- **New Settings field:** `src/ai_driven/config.py` — add `sqs_qa_queue_url: str = ""` alongside existing SQS fields (line 141+).
- **Producer routing:** `src/ai_driven/queue/producer.py` — add `"qa"` to whatever agent-type → queue map exists, pointing at the new `sqs_qa_queue_url`.
- **Consumer URL wiring:** `src/ai_driven/queue/consumer.py` line 42-style — include `sqs_qa_queue_url` in the list of URLs the consumer polls. Consumer registers `"qa"` agent type in `_create_agent`.
- **Env files:** `.env.local` adds `AI_DRIVEN_SQS_QA_QUEUE_URL=https://sqs.ap-southeast-1.amazonaws.com/281414548808/ai-driven-qa-tasks`.
- **Localstack setup:** `scripts/create-local-aws-resources.sh` (or equivalent) adds `awslocal sqs create-queue --queue-name ai-driven-qa-tasks`.
- **Real AWS:** create the queue via `aws sqs create-queue` in the target account. If we have IAM-scoped worker credentials, verify the IAM policy allows SendMessage/ReceiveMessage/DeleteMessage on this queue (piggyback on the existing wildcard or add to the scoped list).
- Tests: unit + integration covering full post-coding flow.

QA output schema:
```json
{
  "verdict": "pass" | "pass_with_notes" | "block",
  "payload": {
    "acceptance_criteria_verification": [
      {"criterion": "...", "satisfied": true, "evidence": "file.py:45", "reason": "..."}
    ],
    "security_rules_verification": [
      {"rule": "...", "followed": false, "evidence": "file.py:89 uses HS256 not RS256", "severity": "high"}
    ],
    "test_coverage_gap": ["AC3 has no test"],
    "regression_risk": "low"
  }
}
```

Phase 2 strict-gating: any `security_rules_verification.followed=false AND severity=high` OR any `acceptance_criteria_verification.satisfied=false` → QA sets verdict=block.

Test-coverage is advisory only (red-team MINOR #13) — flagged as pass_with_notes, never blocks.

**Commit:** `feat(qa): post-coding PR validation against BA ACs + Security rules`

---

### T23 — QA verdict → Task status (0.5 day)

**Files:** `src/ai_driven/agents/orchestrator/agent.py` or a new `qa_gate.py`

Called from QA worker after specialist returns:
- verdict=block → Task.status="qa_failed", persist `output_data.qa_failures=[rule/AC verifications that failed]`
- verdict=pass / pass_with_notes → Task.status="completed"

**Commit:** `feat(orchestrator): QA verdict gates Task.status (completed | qa_failed)`

---

### T24 — Admin override endpoint + audit log + stuck-orchestrator reaper (0.5 day)

**Files:**
- `src/ai_driven/api/admin/router.py` — new `POST /api/admin/tasks/{task_id}/override-block`, admin-only
- New table `task_override_audit(id, task_id, overridden_by_member_id, from_status, reason, overrode_at)` + Alembic migration. **`down_revision` must be set to T21's migration revision id** to serialize correctly.
- Request body: `{reason: str}` — required, non-empty, stored in audit.
- **Overrideable states:**
  - `blocked` — specialist-gated, override re-enqueues coding-task.
  - `qa_failed` — QA-gated, override either re-enqueues QA (retry) OR marks as `completed` — user-selectable via body field `{action: "rerun_qa" | "force_pass"}`. Default `rerun_qa`.
  - `orchestrating` BUT only when `started_at` is older than 30 minutes (stuck-orchestrator case — hit DLQ, no recovery). Override sets status to `failed` with `failure_reason="orchestrator_stuck_reaped"`. Prevents tasks stuck in limbo indefinitely.
- Any other status → 400 with a clear error.
- New DataDog counter `orchestrator.block_override{role,action}` so false-positive rate and reap rate are separately measurable.
- Tests: admin can override each of the 3 states; non-admin gets 403; audit row written; non-overrideable state returns 400; orchestrating-but-fresh (< 30 min) is rejected.

**Commit:** `feat(admin): POST /override-block — admin escape hatch + stuck-orchestrator reaper`

---

### T25 — Specialists UI tab (1 day)

**Files:**
- `src/ai_driven/api/admin/router.py` — new `GET /api/admin/tasks/{task_id}/artifacts` returning `[{role, verdict, reasoning, payload, costUsd, createdAt}]`
- `ai-driven-web-ui/src/lib/api.ts` — `getTaskArtifacts(id)` helper
- `ai-driven-web-ui/src/app/dashboard/tasks/[id]/page.tsx` — "Specialists" tab
- Override button on blocked/qa_failed tasks (admin-only — backend enforces)

UI per artifact card:
- Role name + verdict pill
- Cost + duration
- Reasoning text
- Payload JSON (collapsed, click to expand)

**Commit:** `feat(ui): Specialists tab + admin override button`

---

### T26 — Deploy Phase 2, flip orchestrator_mode=live globally (0.25 day)

Actual production cutover:
1. Confirm T0 metrics still look sane.
2. Drain SQS queues or confirm stamping works.
3. Set `AI_DRIVEN_ORCHESTRATOR_MODE=live` in `.env.local`.
4. Restart backend + worker.
5. Smoke test: submit one fast_path + one full_workflow ticket, confirm status transitions happen correctly.

**Commit:** `chore(config): enable live mode for Phase 2 dogfood`

---

### T27 — Dogfood with 10 real tickets on this repo (1 day)

**Files:** `tests/integration/test_phase2_dogfood.py`, `docs/phase2-dogfood-results.md`

10 real tickets targeting `feature/orchestrator-phase1` of this ai-driven repo. Each ticket describes a real improvement. **At least 3 of the 10 MUST target security-sensitive surfaces** so the "verbatim Security.implementation_rules in diff" criterion is reliably measurable. Candidate ticket surfaces:
- session-token auth in `auth/middleware.py` / `api/auth/router.py` (rate-limiting, session TTL)
- github_token handling in `scm/github_client.py` (no-log-leak, redaction)
- admin endpoint authorization tightening
- API key hashing upgrade
- webhook signature verification hardening

Non-security tickets can target orchestrator plumbing, prompt files, docs, UI — i.e. things the three security-focused tickets wouldn't already cover.

Measurable success criteria (red-team #10 fix, refined in v4):
- ≥2 tickets where BA's `verdict` alone matches the team's subjective "is this ticket clear enough" judgment (measured by: does the BA reasoning text contain a question the human reviewer would have asked?)
- ≥1 ticket where `git diff` of the resulting PR contains a verbatim string from `Security.implementation_rules` (e.g. "argon2id", "RS256"). **This criterion applies to the 3 security-focused tickets; if none of them produce such evidence, Phase 2 context injection is not working as designed.**
- ≥1 ticket where QA `verdict=block` caught a real issue (manually reviewed: the block was correct)
- Zero tickets where override was needed due to a false-positive block

Results compiled into `docs/phase2-dogfood-results.md`.

**Commit:** `test(phase2): dogfood harness — 10 real tickets, measured quality metrics`

---

### T28 — Cleanup + runbook (0.5 day)

**Files:**
- `docs/runbooks/orchestrator-live-mode.md` (new) — how to flip back to shadow in emergency, query artifacts, exercise override API. Document BOTH env sources: `.env.local` for local dev; real deployment (whenever it happens) should use platform secrets (Secrets Manager / env vars per ECS task def).
- `docs/architecture/03-coding-agent-sequence.md` — update diagram for the new flow
- `docs/features/01-ai-coding-agent.md` — reflect live-mode gating
- Delete any lingering "shadow-mode only" comments in the orchestrator source

**Commit:** `docs(orchestrator): Phase 2 live-mode runbook + architecture updates`

## Total estimate

| # | Task | Days |
|---|---|---|
| T0 | Shadow baseline | 0.5 |
| T18 | prompt_composer | 1 |
| T19 | Wire composer + ArtifactRepository read | 0.5 |
| T19b | Persist pr_number | 0.5 |
| T20 | Orchestrator as SQS worker | 1.5 |
| T21 | Live-mode gating + migration | 1 |
| T22 | QA post-coding validation | 1.5 |
| T23 | QA verdict → Task status | 0.5 |
| T24 | Admin override endpoint | 0.5 |
| T25 | Specialists UI tab | 1 |
| T26 | Cutover | 0.25 |
| T27 | Dogfood 10 tickets | 1 |
| T28 | Runbook + docs | 0.5 |
| **Total** | | **~9.75 working days** |

## Dispatch strategy

Sequential via `subagent-driven-development`. Explicit ordering:

1. **T0 (shadow baseline)** — solo, first.
2. **T18 (prompt_composer)** — solo, before T19 (T19 imports it).
3. **T19 + T19b (CodingAgent reads artifacts + persists pr_number)** — solo serial pair, both touch `coding/agent.py`.
4. **T20 (orchestrator as SQS worker)** — solo, touches `consumer.py` + `producer.py` + `orchestrator/agent.py`.
5. **T21 (gating + status migration)** — solo, **must complete before T22, T24, T25** because (a) T22 expects the blocked-path to exist for its integration test, (b) T24's migration has `down_revision=T21_revision`, (c) T25's UI renders the new statuses.
6. **T22 + T23 (QA post-coding + verdict→status)** — solo serial pair, both touch QA specialist + orchestrator status-transition logic.
7. **T24 + T25 (admin override + UI) — may run PARALLEL with each other** after T21 lands, because they touch disjoint files (admin router + migration vs. UI components).
8. **T26 (cutover)** — solo, manual.
9. **T27 (dogfood)** — solo, after T26.
10. **T28 (docs)** — solo, last.

Parallelisable batches that actually reduce wall-clock: step 7 only (saves 0.5 day). Everything else is sequentially constrained by shared files or logical dependencies.

## Checkpoints requiring user go/no-go

1. **After T0** — baseline metrics. If block rate >40% on any role, abort strict gating plan.
2. **After T21** — blocked tasks visible in UI. Smoke-test a BA block, confirm no CodingAgent run, confirm counter decrements.
3. **After T23** — end-to-end flow. Submit a ticket that should pass, confirm Task.status reaches "completed" through orchestrator → coding → QA.
4. **After T27** — dogfood results. Decide whether Phase 2 paid off.
