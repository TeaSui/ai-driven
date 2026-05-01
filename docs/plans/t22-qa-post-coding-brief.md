# T22 — QA post-coding specialist (brief, v4 post-red-team-round-3)

**Changelog v3→v4 (red-team round 3: 1 blocker + 3 majors — READY TO DISPATCH after):**
- B-v3-1: Discriminated union is not a `BaseModel` — use `TypeAdapter`. Updated snippet + `PAYLOAD_SCHEMAS` type.
- M-v3-1: Add `emit_counter(name, tags=None)` helper to `observability/metrics.py` — all brief call sites use that name.
- M-v3-2: Use inline `datetime.now(timezone.utc).isoformat()` uniformly. Drop the `datetime.now(timezone.utc).isoformat()` placeholder.
- M-v3-3: Add explicit `from typing import Annotated` + `from pydantic import Field, TypeAdapter` imports to `artifacts.py`.
- Minor clarification: `_enqueue_qa_best_effort` re-reads `output_data` from DB AFTER the `completed`-status commit (not the stale top-of-function snapshot).

**Changelog v2→v3 (red-team round 2: 2 blockers + 5 majors addressed):**
- B1: QA-enqueue exceptions MUST be swallowed inside the hook (never propagate to the outer `_process_message` try/except — that would flip a successful coding run to `failed` and clobber `pr_number`). Dedicated try/except around the whole hook, counter-only on failure.
- B2: Drop the PR-merged vs closed disambiguation — `SCMClient.get_pr()` doesn't exist. Treat all 404s on `get_pr_diff` uniformly as `verdict=error` (lenient mode passes through). Add `scm.get_pr()` later if dogfood shows we need it.
- M1: Concrete shared-session snippet for `_persist_artifact_and_marker`.
- M2: Gate `awaiting_qa` status transition on `stamped_orchestrator_mode == "live"`. Shadow mode enqueues QA + writes artifact, but status stays `completed` — preserves Phase 2 shadow contract AND unblocks admin-dashboard metrics.
- M3: `schema_version: Literal["v2"]` (no default) on the POST-coding QA path — missing key becomes a validation error → base class retry reinforces schema. Plus a `qa.post_coding.schema_drift` counter in the wrapper as belt-and-braces.
- M4: Add `pr_ref: dict | None = None` to `SpecialistContext`; stop abusing `team_settings`.
- M5: Reserved-key note — the wrapper MUST NOT write the four keys the generic consumer path patches (`output`, `tool_calls`, `iterations`, `error`).

**Changelog v1→v2 (14 red-team findings addressed):**
- Blocker #1: QA dispatch path does NOT go through `_process_task_inline` (hard-fail debug fallback; producer has an explicit early-reject).
- Blocker #2: `qa_enqueued_at` is written BEFORE `SendMessage`, cleared on failure.
- Blocker #3: Introduce interim status `awaiting_qa` via Alembic — `completed` is reserved for post-QA-pass. T22 adds the migration; T23 later adds `qa_failed`.
- Major #4: Missing-tier loud-log + counter.
- Major #5: Shadow mode DOES enqueue QA (telemetry); T23 is the gate, not T22's enqueue decision.
- Major #6: `QaPayload` gets a `schema_version: Literal["v1","v2"]="v1"` discriminator — T23 keys gating off `v2`.
- Major #7: Explicit grep + test touchlist.
- Major #8: artifact upsert + `qa_completed_at` marker in SAME session transaction.
- Minor #9: `_sum_specialist_cost_usd` extended to include `tasks.total_cost_usd` when seeding QA's initial_cost.
- Minor #10: TODO + dead-code test for `_should_halt_pipeline`'s qa exclusion.
- Minor #11: PR merged vs closed disambiguation in error path.
- Minor #12: Stuck-QA reaper note — deferred to T24 explicitly.
- Minor #13: IAM permission as a first-class step.
- Minor #14: `qa.post_coding.missing_pr_number` → Sev-1 alert threshold documented.

**Goal.** Move QA out of the pre-coding final_gate sequence. Run QA AFTER CodingAgent opens the PR, against the real diff + BA acceptance_criteria + Security implementation_rules. Ship lenient-on-errors (per v6 plan), advisory-only on test_coverage_gap, blocking in T23 on `security_rules_verification.followed=false AND severity in {high, critical}` OR any `acceptance_criteria_verification.satisfied=false`.

**Depends on:** T20 (orchestrator SQS worker), T21 (strict halt predicate + blocked status), T18/T19 (prior_artifacts + prompt_composer). None need changes.

**Out of scope:** T23 (qa_verdict → Task.status, including `qa_failed` migration). T24 (override endpoint for qa_failed + QA-stuck reaper). T22 covers: QA runs post-coding, validates against PR, persists artifact + qa_verdict + awaiting_qa interim status.

---

## Blocker-fix 1 — explicit rejection of `agent_type="qa"` on debug/inline path

Red-team blocker #1: `_process_task_inline` in `api/chat/router.py` only knows the legacy agent types. If producer falls back to inline (debug mode or no queue URL), an `agent_type="qa"` payload would crash.

**Fix:** in `TaskProducer.enqueue_task`, when `agent_type == "qa"` AND the inline-fallback path would trigger (debug mode OR missing `sqs_qa_queue_url`), skip the fallback entirely — raise a clear `RuntimeError("QA worker requires a configured sqs_qa_queue_url and non-debug mode")`. QA is post-coding, post-orchestrator; there is no scenario where it should run inline. The raise surfaces config errors loudly instead of silently no-op'ing.

```python
# producer.py, inside enqueue_task, after queue_url resolution
if agent_type == "qa" and (settings.debug or not queue_url):
    raise RuntimeError(
        "QA worker cannot run inline/debug — configure AI_DRIVEN_SQS_QA_QUEUE_URL and disable debug"
    )
```

## Blocker-fix 2 — `qa_enqueued_at` ordering

Red-team blocker #2: the hook must write `qa_enqueued_at` BEFORE `SendMessage`, clear on failure.

**Fix:** in the Coding worker's post-completion hook, use read-modify-write:
1. Read `output_data.qa_enqueued_at`; if present → skip (idempotent).
2. Set `qa_enqueued_at = now()` + commit.
3. Call `TaskProducer.enqueue_task`.
4. On `SendMessage` failure: set `qa_enqueued_at = None` + commit (so the next redelivery retries) + re-raise.

This orders the marker-write strictly before the side effect and makes retries safe.

## Blocker-fix 3 — interim status `awaiting_qa`

Red-team blocker #3: current consumer sets `status="completed"` on coding success; UI/webhooks fire on that. T23 flipping it to `qa_failed` later gaslights observers.

**Fix:** new Alembic migration in T22 — add `awaiting_qa` to `ck_tasks_status`. The Coding worker's hook, when it enqueues QA, does `update_status(task_id, "awaiting_qa")` INSTEAD of `completed`. When QA passes (T23), T23 flips to `completed`. When QA blocks (T23), T23 flips to `qa_failed` (after its own migration).

Status state machine after T22 (T23 adds `qa_failed`):
```
queued → running → completed         (fast_path or non-live mode)
queued → running → awaiting_qa → (T23: completed | qa_failed)   (full_workflow/multi_domain, live mode)
queued → running → blocked           (T21, specialist halt)
queued → running → failed            (exception path)
```

Webhook/Slack consumers get `awaiting_qa` as an explicit intermediate state — no silent downgrade. If a consumer currently hard-codes "completed = done", it'll keep working for fast_path and shadow mode; orchestrated-live tickets will emit awaiting_qa first, then completed.

Migration: `alembic/versions/<rev>_add_awaiting_qa_status.py`, `down_revision = c3d4e5f6a7b8` (the blocked-status migration).

---

## Design decision: new queue vs. reuse specialists queue

**Chosen: new queue `ai-driven-qa-tasks`** per v6 plan.

Rationale: separate DLQ tuning; independent pause; QA visibility timeout (1800s) is much longer than specialists' typical 900s. Marginal infra cost is trivial vs. operability win.

## Design decision: where does QA get enqueued?

**Chosen: Coding worker, after coding success + PR open, if tier ∈ {full_workflow, multi_domain}.** Shadow AND live mode both enqueue (shadow for telemetry). Gating happens in T23 by examining `qa_verdict` + mode. Fast_path skips QA entirely.

Rationale: the Coding worker owns the PR-open event and `pr_number` persistence. Any other place (orchestrator pre-enqueue with a polling loop) adds complexity for zero benefit.

---

## File-level plan

### 1. Config + SQS wiring

**`src/ai_driven/config.py`** line 146 area:
```python
sqs_qa_queue_url: str = ""
```

**`.env.local`**:
```
AI_DRIVEN_SQS_QA_QUEUE_URL=https://sqs.ap-southeast-1.amazonaws.com/281414548808/ai-driven-qa-tasks
```

**Real AWS setup (Minor #13 IAM):**
```bash
aws sqs create-queue --queue-name ai-driven-qa-tasks-dlq --region ap-southeast-1
aws sqs create-queue --queue-name ai-driven-qa-tasks --region ap-southeast-1 \
  --attributes VisibilityTimeout=1800,RedrivePolicy='{"deadLetterTargetArn":"<dlq_arn>","maxReceiveCount":"3"}'
```

Then verify the backend IAM user (`AKIAUDBM5HVENVLV6GV2` per .env.local) has `sqs:SendMessage,sqs:ReceiveMessage,sqs:DeleteMessage,sqs:ChangeMessageVisibility` on the new queue ARNs. Current IAM scope unknown — **manual verification step before live dogfood**. If scoped, extend the policy via `aws iam put-user-policy` (command in the T22 runbook section of the final commit).

### 2. Producer + consumer

**`src/ai_driven/queue/producer.py`:**
- Extend `_queue_map`: `"qa": settings.sqs_qa_queue_url`.
- Add the blocker-fix-1 hard-fail branch (debug/missing-url + qa → raise).
- Body-agent-type stamping is already orchestrator-queue-specific; QA goes directly (no reroute), so body `agent_type="qa"`. No new stamping logic.

**`src/ai_driven/queue/consumer.py`:**
- Include `sqs_qa_queue_url` in `_queue_urls` list (line 42 area).
- `_create_agent` factory: add `"qa": lambda: _QaPostCodingWrapper(scm_client=scm_client, jira_client=jira_client)`.

### 3. `_QaPostCodingWrapper` (new, in `consumer.py`)

Mirrors `_OrchestratorWrapper` layout:

```python
class _QaPostCodingWrapper:
    async def run(self, payload, **run_kwargs):
        task_id = ...
        task_uuid = ...

        # Idempotency: skip if qa_completed_at already set
        existing_output = await _load_output_data(task_uuid)
        if existing_output.get("qa_completed_at"):
            return _synthesize_noop_result()

        pr_number = existing_output.get("pr_number")
        if not pr_number:
            emit_counter("qa.post_coding.missing_pr_number")  # Sev-1 alert
            logger.error("qa_post_coding_missing_pr_number", ...)
            # Write a sentinel verdict so T23 treats this as an error (lenient)
            await _persist_artifact_and_marker(
                task_uuid,
                Artifact(role="qa", verdict="error", reasoning="pr_number missing on task; likely upstream bug",
                         payload={"schema_version": "v2", ...}, ...)
            )
            return _synthesize_noop_result()

        # Load prior artifacts (BA + Security) — same shape as CodingAgent T19
        prior = await ArtifactRepository(...).list_for_task(task_uuid)
        prior_map = {a.role: a for a in prior if a.role in {"business_analyst", "security"}}

        ctx = SpecialistContext(
            task_id=str(task_uuid),
            ticket=payload.get("ticket") or _reconstruct_ticket(payload),
            repo=payload.get("repo") or _reconstruct_repo(payload),
            tier=existing_output.get("classifier_tier"),
            prior_artifacts=prior_map,
            pr_ref={"pr_number": pr_number, "pr_url": existing_output.get("pr_url")},
            team_settings={},
        )

        specialist = QaSpecialist(scm_client=self._scm)
        artifact = await specialist.run_specialist(ctx)

        # Major-fix 8: upsert + marker in SAME session/transaction
        await _persist_artifact_and_marker(task_uuid, artifact)

        emit_counter(f"qa.post_coding.verdict.{artifact.verdict}")
        return _synthesize_result(artifact)
```

`_persist_artifact_and_marker` writes BOTH the artifact row AND `output_data.qa_completed_at` + `output_data.qa_verdict` + `output_data.qa_artifact_id` in one `session.commit()`. **Concrete snippet (M1 fix — must share ONE session):**

```python
async def _persist_artifact_and_marker(task_uuid, artifact):
    async with async_session_factory() as session:
        # Both repos accept an externally-managed session
        art_repo = ArtifactRepository(session)
        task_repo = TaskRepository(session)
        await art_repo.upsert(task_uuid, artifact)
        await task_repo.update_output_data(task_uuid, {
            "qa_completed_at": datetime.now(timezone.utc).isoformat(),
            "qa_verdict": artifact.verdict,
            # qa_artifact_id: set AFTER upsert returns the row id via art_repo.upsert_and_get_id
        })
        await session.commit()   # single commit — both writes atomic
```

If `ArtifactRepository.upsert` currently returns `None`, extend it to return the inserted/updated row id so we can stamp `qa_artifact_id` in the same commit. This is a one-line change — acceptable scope inside T22.

**Reserved-key avoidance (M5 fix):** the wrapper MUST NOT write keys `output`, `tool_calls`, `iterations`, `error` to `output_data` — the generic `_process_message` post-run patch (consumer.py:182–188) writes those four. Any overlap would clobber. Keys the wrapper owns: `qa_completed_at`, `qa_verdict`, `qa_artifact_id`. If we want diagnostic detail, write to `qa_reasoning` or similar namespace.

### 4. QA schema extension (`orchestrator/artifacts.py`)

```python
# Required imports to add at top of artifacts.py (M-v3-3 fix):
#   from typing import Annotated
#   from pydantic import BaseModel, ConfigDict, Field, TypeAdapter

class AcceptanceCriterionVerification(_Strict):
    criterion: str
    satisfied: bool
    evidence: str
    reason: str

class SecurityRuleVerification(_Strict):
    rule: str
    followed: bool
    evidence: str
    severity: Severity

# Single payload model supports BOTH pre-coding (legacy, schema_version='v1')
# and post-coding (schema_version='v2') — discriminator-based validation via a
# pair of subclasses so a missing key on a post-coding payload is a HARD error,
# not a silent default (M3 fix).

class QaPayloadV1(_Tolerant):
    schema_version: Literal["v1"] = "v1"
    checks_performed: list[QaCheck]
    test_coverage_gap: list[str]
    regression_risk: RegressionRisk

class QaPayloadV2(_Tolerant):
    schema_version: Literal["v2"]                # NO default — required
    checks_performed: list[QaCheck]
    acceptance_criteria_verification: list[AcceptanceCriterionVerification]
    security_rules_verification: list[SecurityRuleVerification]
    test_coverage_gap: list[str]
    regression_risk: RegressionRisk

# B-v3-1 fix: `Annotated[Union, Field(discriminator=...)]` is NOT a `BaseModel`
# subclass — it has no `.model_validate`. Wrap it in a `TypeAdapter`. The adapter
# IS stored in PAYLOAD_SCHEMAS; `validate_payload()` uses `.validate_python()`
# when the entry is a TypeAdapter, `.model_validate()` otherwise.
_QaPayloadUnion = Annotated[
    QaPayloadV1 | QaPayloadV2, Field(discriminator="schema_version")
]
QaPayload = TypeAdapter(_QaPayloadUnion)

# Update PAYLOAD_SCHEMAS type annotation:
#   PAYLOAD_SCHEMAS: dict[Role, type[BaseModel] | TypeAdapter[Any]] = {...}
# and the "qa" entry:
#   "qa": QaPayload,

# Update validate_payload:
#   def validate_payload(role, raw):
#       schema = PAYLOAD_SCHEMAS.get(role)
#       if schema is None: raise ValueError(...)
#       if isinstance(schema, TypeAdapter):
#           return schema.validate_python(raw)
#       return schema.model_validate(raw)
```

The `QaSpecialist` post-coding path MUST set `schema_version: "v2"`. If the LLM forgets, validation fails → base class retry fires with the schema-reminder reinforced.

**Belt-and-braces in `_QaPostCodingWrapper` (M3 fix):** after `run_specialist` returns, assert `artifact.payload.get("schema_version") == "v2"`. If not:
```python
emit_counter("qa.post_coding.schema_drift")
logger.error("qa_post_coding_schema_drift", extra={"task_id": task_id,
            "got_schema_version": artifact.payload.get("schema_version")})
# Force verdict=error so T23 treats it as lenient-pass, not a clean verdict
artifact = replace(artifact, verdict="error", reasoning="schema_version drift; post-coding QA must emit v2")
```

T23 gating will read ONLY v2 payloads — v1 (any legacy pre-coding QA row) is invisible to T23.

### 5. System prompt update (`claude/prompts/specialists/qa.py`)

Add a post-coding section to `QA_SYSTEM_PROMPT`:

> When invoked POST-CODING, you receive `pr_number` in the context's `team_settings`. Call `github.get_pr_diff` FIRST. For each acceptance_criterion from `<prior_artifacts>.business_analyst`, cite a `file.ext:line` in the diff that satisfies it OR set `satisfied=false` with a concrete reason. For each implementation_rule from `<prior_artifacts>.security`, cite evidence OR set `followed=false` + `severity`. `severity` values `high` or `critical` will block the PR in T23; `low` / `medium` → pass_with_notes. Set `schema_version: "v2"` on the payload. If `github.get_pr_diff` returns an error (PR closed/merged/404), explain in `reasoning` and emit `verdict: "error"` — the wrapper will persist and NOT halt the pipeline (lenient mode).

Schema reminder in `qa/agent.py::_SCHEMA_REMINDER` gets a parallel update.

### 5b. `SpecialistContext.pr_ref` (M4 fix)

Add to `src/ai_driven/agents/specialists/base.py`:

```python
@dataclass
class SpecialistContext:
    ...
    prior_artifacts: dict[str, Artifact] = field(default_factory=dict)
    team_settings: dict = field(default_factory=dict)
    pr_ref: dict | None = None   # {pr_number, pr_url} — set by post-coding path
```

Post-coding QA's prompt renderer reads `ctx.pr_ref["pr_number"]`. Pre-coding specialists leave it `None` — no behaviour change. Semantically honest: we don't abuse `team_settings`.

### 5c. Drop PR-merged disambiguation (B2 fix)

`SCMClient.get_pr()` does not exist (clients only expose `get_pr_diff`, `get_pr_reviewers`, `get_pr_checks`, `get_pr_commits`). Adding it is out of scope for T22. Therefore:
- `get_pr_diff` 404 on post-coding QA → `verdict="error"`, reason=`"PR diff unavailable (PR may be closed/merged)"`, lenient-mode pass-through.
- Dogfood data will show whether we actually need a merged-vs-closed distinction. If we do, add `scm.get_pr()` in a T22b follow-up.

### 6. Routing change (`orchestrator/routing.py`)

```python
"full_workflow": TierRule(
    mandatory=["business_analyst", "stack_analyst", "security"],
    candidates=["tech_lead", "ux_designer"],
    skip_to_pipeline=False,
    final_gate=None,         # was "qa"
),
"multi_domain": TierRule(
    mandatory=["business_analyst", "stack_analyst", "tech_lead", "security"],
    candidates=["ux_designer", "api_test"],
    skip_to_pipeline=False,
    final_gate=None,         # was "qa"
),
```

### 7. Coding worker hook (`consumer.py::_process_message`)

After the successful `update_status(..., status="completed", ...)` block, before SQS delete, ADD:

```python
# Isolated try/except — B1 fix: never let QA-enqueue nuke a completed coding run
async def _enqueue_qa_best_effort(task_id, agent_type, result, stamped_mode, payload, existing_output):
    if agent_type != "coding" or not result.success:
        return
    tier = existing_output.get("classifier_tier")
    pr_number = existing_output.get("pr_number")  # re-read AFTER the merge done above
    if tier is None:
        emit_counter("qa.post_coding.classifier_tier_missing")
        logger.warning("qa_enqueue_skipped_tier_missing", extra={"task_id": task_id})
        return
    if tier not in {"full_workflow", "multi_domain"}:
        return  # fast_path: no QA
    if not pr_number or not settings.sqs_qa_queue_url:
        return
    if existing_output.get("qa_enqueued_at"):
        return  # idempotent
    try:
        # Blocker-fix 2 ordering: write marker BEFORE SendMessage
        async with async_session_factory() as s:
            await TaskRepository(s).update_output_data(task_id, {"qa_enqueued_at": datetime.now(timezone.utc).isoformat()})
            await s.commit()
        await TaskProducer().enqueue_task(
            task_id=task_id, agent_type="qa", payload=payload,
            skip_orchestrator_routing=True,
        )
        # M2 fix: awaiting_qa ONLY in live mode; shadow keeps status='completed' for
        # dashboard-metric stability and Phase 2 shadow-contract compliance.
        if stamped_mode == "live":
            async with async_session_factory() as s:
                await TaskRepository(s).update_status(task_id, "awaiting_qa")
                await s.commit()
    except Exception:
        logger.error("qa_enqueue_failed", extra={"task_id": task_id}, exc_info=True)
        emit_counter("qa.post_coding.enqueue_failed")
        # Clear marker so the next redelivery retries
        try:
            async with async_session_factory() as s:
                await TaskRepository(s).update_output_data(task_id, {"qa_enqueued_at": None})
                await s.commit()
        except Exception:
            pass
        # Swallow — coding succeeded, do not taint the outer _process_message flow.
        # A stuck task-without-QA is recoverable via T24 reaper; a failed task with
        # clobbered pr_number is NOT.
```

Call site: inside the success branch of `_process_message`, AFTER the `update_output_data(run_result_patch)` + `update_status("completed", ...)` commits, but BEFORE `sqs.delete_message`. We do NOT await the helper inside the outer try/except — call it via `await _enqueue_qa_best_effort(...)` from a new `try/except Exception: logger.exception(...)` block in `_process_message` that is sibling to the existing catch, so any exception here is fully isolated.

**Shadow vs live (M2 fix):**
- Both modes enqueue QA when tier is full_workflow/multi_domain AND pr_number exists — shadow needs QA telemetry for T-A3 dogfood.
- Only live mode transitions status to `awaiting_qa`. Shadow mode leaves status at `completed`. Admin dashboard's `== "completed"` filters keep working through T22-only deployments; T23 will later add `qa_failed` transitions gated on `stamped_mode == "live"` too.

### 8. Cost-ceiling seeding (Minor-fix 9)

Extend `_sum_specialist_cost_usd` to also pull `tasks.total_cost_usd` so QA's `initial_cost` seed includes the coding run's cost:

```python
@staticmethod
async def _seed_initial_cost(task_id) -> float:
    artifact_sum = await _sum_specialist_cost_usd(task_id)
    tasks_cost = await _get_task_total_cost_usd(task_id)  # NEW
    return artifact_sum + tasks_cost
```

Call this ONLY for `agent_type == "qa"` — keeps non-QA seeding behaviour unchanged (since T-A2's rollup already covers the other paths).

### 9. Migration

`alembic/versions/<rev>_add_awaiting_qa_status.py`, `down_revision = c3d4e5f6a7b8`. Drops and re-creates `ck_tasks_status` CHECK with `awaiting_qa` added. Downgrade removes it.

### 10. Tests

- `tests/unit/test_qa_post_coding_wrapper.py`:
  - happy path: pr_number present, BA + Security artifacts exist → QA runs, artifact persisted, qa_verdict written, status unchanged (T23's job).
  - missing pr_number → counter emitted, error-artifact persisted.
  - idempotent replay: `qa_completed_at` present → short-circuit.
  - `get_pr_diff` 404 → verdict=error, lenient pass-through (B2 simplification).
  - Schema drift: specialist returns payload without `schema_version="v2"` → wrapper forces `verdict=error` + counter emitted (M3 belt-and-braces).
- `tests/unit/test_coding_worker_qa_enqueue.py`:
  - full_workflow + live mode → enqueue, status=awaiting_qa.
  - full_workflow + shadow mode → ALSO enqueue (telemetry); status STAYS `completed` (M2 fix).
  - fast_path → no enqueue; status=completed.
  - missing tier → counter emitted; no enqueue; status=completed.
  - double-enqueue guard: `qa_enqueued_at` present → no re-enqueue.
  - SendMessage failure → marker cleared, counter emitted, exception SWALLOWED (B1 fix); coding task remains `status=completed` with pr_number intact.
- `tests/integration/test_qa_post_coding_flow.py`:
  - orchestrator-task → coding-task → qa-task end-to-end with mocked SQS.
- `tests/unit/test_orchestrator_routing.py` (update):
  - `full_sequence()` for full_workflow/multi_domain no longer contains "qa".
  - Add dead-code invariant test (Minor-fix 10): assert no specialist emits `halted_on='qa'`.
- `tests/unit/test_orchestrator_artifacts.py` (extend):
  - `QaPayload(schema_version="v2", ...)` parses with nested verification sub-models.
  - `QaPayload(schema_version="v1", ...)` still parses (backward compat).
- `tests/unit/test_producer_qa_rejects_inline.py` (new):
  - `enqueue_task(agent_type="qa", ...)` in debug mode raises RuntimeError.
  - Same when `sqs_qa_queue_url=""`.

### 10b. `emit_counter` helper (M-v3-1 fix)

`src/ai_driven/observability/metrics.py` currently exposes `emit_agent_metrics`, `emit_specialist_fallback_activated`, etc., but no generic `emit_counter`. Add it:

```python
def emit_counter(name: str, tags: list[str] | None = None) -> None:
    """Increment a statsd counter by 1. No-op when DataDog is disabled."""
    try:
        _get_statsd().increment(name, 1, tags=tags or [])
    except Exception:
        logger.debug("emit_counter_failed name=%s", name, exc_info=True)
```

Every `emit_counter("qa.post_coding.…")` call site in this brief uses this helper.

### 10c. Helper call-site re-read (minor clarification)

`_enqueue_qa_best_effort` receives `task_id` + `agent_type` + `result` + `stamped_mode` + `payload`. It does NOT take `existing_output` as a parameter — it reads it fresh from DB inside the helper:

```python
async with async_session_factory() as s:
    task_row = await TaskRepository(s).get_by_id(task_id)
    existing_output = dict(task_row.output_data or {})
```

That way `pr_number` and `classifier_tier` reflect post-coding state, not the stale snapshot from the top of `_process_message`.

### 11. Observability

- Counters: `qa.post_coding.verdict.{pass|pass_with_notes|block|error}`, `qa.post_coding.missing_pr_number`, `qa.post_coding.classifier_tier_missing`, `qa.post_coding.enqueue_failed`, `qa.post_coding.schema_drift`.
- **Alert:** `qa.post_coding.missing_pr_number` → Sev-1 (signals CodingAgent T19b PR persistence regression).
- **Alert:** `qa.post_coding.schema_drift` sustained > 1/hr → P2 (prompt regression / model drift).

### 12. Grep + UI touchlist (Major-fix 7)

Files to grep for `"qa"` / `final_gate` / QA-last assumptions before finalizing:
```
src/ai_driven/agents/orchestrator/
src/ai_driven/agents/specialists/qa/
src/ai_driven/claude/prompts/specialists/
tests/unit/test_orchestrator*
tests/integration/
ai-driven-web-ui/src/   # T25 hasn't shipped yet but front-end may render specialist cards
docs/
```

### 13. Deferred to T24 (stuck-QA reaper — Minor-fix 12)

T22 does NOT ship a QA-stuck reaper. T24's override endpoint needs to accept `awaiting_qa` (older than 30 min) as an overrideable state. Noted here; implementation in T24.

---

## Commit sequence (serial)

1. `feat(db): add awaiting_qa to ck_tasks_status` — migration only.
2. `feat(qa): extend QaPayload with schema_version + ac + security verification`.
3. `feat(qa): drop pre-coding final_gate, add post-coding prompt section`.
4. `feat(queue): sqs_qa_queue_url + qa agent factory + inline-mode rejection`.
5. `feat(queue): _QaPostCodingWrapper runs QA against PR diff + prior artifacts`.
6. `feat(queue): coding worker enqueues qa-task on live+orchestrated tiers`.
7. `test(qa): unit + integration coverage for post-coding QA flow`.

## Rollout & safety

- T22 ships on `feature/orchestrator-phase1` branch. `orchestrator_mode=shadow` remains the default — tickets transition `running → awaiting_qa → ...` only when T23's status-transition hook lands AND mode is live.
- Until T23 lands, QA runs, writes `qa_verdict` to `output_data`, status sits at `awaiting_qa` forever. **That means T23 MUST follow T22 within the same feature cycle; we don't ship T22 alone to main.**
- T26 is live-mode cutover — gated on T0-rerun per the v6 plan.

## Risks / open questions (post-red-team)

1. **Very large PR diffs.** Out of scope; patch reactively after dogfood.
2. **PR-merged-mid-QA.** Distinguish `pr.merged_at` vs closed-unmerged in the error path; tests cover both.
3. **QA specialist cost variance.** Mitigated by Minor-fix 9 (cost ceiling includes coding spend). Real data from T-A3 dogfood will calibrate.
