# T23 — QA verdict → Task status (brief, v2 post-red-team)

**Changelog v1→v2:**
- B1: Replay-on-`awaiting_qa` — move the idempotency guard from `qa_completed_at` to the status. If `qa_completed_at` present BUT `status == "awaiting_qa"`, re-apply the gate from stored verdict. Guarantees SQS redelivery unsticks tasks whose first delivery crashed between artifact-commit and status-transition.
- B2: Explicit `not_implemented` branch → treat as lenient-error (`completed` + counter `qa.gate.not_implemented`); Phase 2 never uses this verdict currently, but closing the exhaustiveness gap is cheap.
- M1: Admin backend success-rate queries updated IN THIS TASK. Add `qa_failed`, `awaiting_qa`, `blocked` to the success/failure buckets correctly.
- M2: Fold the status-transition into `_persist_artifact_and_marker`'s existing session — one atomic commit, no race window.
- M3: Compare-and-set status guard. `qa_failed` transition only fires when current status is `awaiting_qa`; on mismatch, emit `qa.gate.status_mismatch` counter and leave the row for T24 reaper.



**Goal.** Bridge T22's `output_data.qa_verdict` to a terminal task status:
- verdict ∈ {`pass`, `pass_with_notes`} → `status="completed"`
- verdict == `block` → `status="qa_failed"` + persist `output_data.qa_failures`
- verdict == `error` → lenient-mode: `status="completed"` + counter (error never blocks)

**Depends on:** T22 (writes `qa_verdict` + `qa_artifact_id` to output_data; sets interim `awaiting_qa` status in live mode).

**Out of scope:** T24 (override endpoint), T25 (UI).

## Where the transition happens

In the `_QaPostCodingWrapper.run()` — immediately AFTER `_persist_artifact_and_marker` commits, BEFORE returning. Single owner (the QA wrapper) keeps state-machine logic in one place and avoids a separate worker round-trip.

**Mode-gating (preserves Phase 2 shadow contract):**
- Shadow mode: T23 writes ONLY `output_data.qa_failures` (if any) for observability; does NOT touch `status`. Status stays at `completed` (T22's shadow path).
- Live mode: T23 transitions `awaiting_qa` → `completed` | `qa_failed`.

## File-level plan

### 1. Alembic migration — add `qa_failed`

`alembic/versions/<rev>_add_qa_failed_status.py`:
- `down_revision = <T22's awaiting_qa rev>` (to be read from the latest T22 migration)
- DROPs + recreates `ck_tasks_status` CHECK with `qa_failed` added alongside existing `awaiting_qa`, `blocked`, etc.

### 2. QA gate module — `src/ai_driven/agents/orchestrator/qa_gate.py` (new)

Pure function, no DB side effects inline — returns the status transition the caller should perform. Keeps logic testable.

```python
from dataclasses import dataclass

@dataclass(frozen=True)
class QaGateDecision:
    new_status: str | None        # None = leave status alone (shadow mode)
    qa_failures: list[dict]       # payload for output_data.qa_failures
    counter_name: str | None      # e.g. "qa.gate.lenient_error"

def decide_qa_gate(
    verdict: str,
    payload: dict,                # full QaPayloadV2 dict
    stamped_mode: str,            # "shadow" | "live"
) -> QaGateDecision:
    # Shadow: never transition status
    if stamped_mode != "live":
        failures = _extract_failures(payload) if verdict == "block" else []
        return QaGateDecision(new_status=None, qa_failures=failures,
                              counter_name=f"qa.gate.shadow.{verdict}")
    # Live mode
    if verdict == "block":
        return QaGateDecision(
            new_status="qa_failed",
            qa_failures=_extract_failures(payload),
            counter_name="qa.gate.blocked",
        )
    if verdict in {"pass", "pass_with_notes"}:
        return QaGateDecision(new_status="completed", qa_failures=[],
                              counter_name=f"qa.gate.{verdict}")
    if verdict == "not_implemented":
        # B2 fix: explicit branch — current Phase 2 never emits this but the
        # Verdict literal includes it; treat like error (lenient pass).
        return QaGateDecision(new_status="completed", qa_failures=[],
                              counter_name="qa.gate.not_implemented")
    # error → lenient pass-through; Phase 2 plan line 50-51
    return QaGateDecision(new_status="completed", qa_failures=[],
                          counter_name="qa.gate.lenient_error")

def _extract_failures(payload: dict) -> list[dict]:
    """Combine unsatisfied ACs + unfollowed high/critical security rules."""
    failures: list[dict] = []
    for ac in payload.get("acceptance_criteria_verification", []):
        if not ac.get("satisfied", True):
            failures.append({
                "kind": "ac_unsatisfied",
                "criterion": ac.get("criterion", ""),
                "reason": ac.get("reason", ""),
                "evidence": ac.get("evidence", ""),
            })
    for rule in payload.get("security_rules_verification", []):
        if not rule.get("followed", True) and rule.get("severity") in {"high", "critical"}:
            failures.append({
                "kind": "security_rule_unfollowed",
                "rule": rule.get("rule", ""),
                "severity": rule.get("severity", ""),
                "evidence": rule.get("evidence", ""),
            })
    return failures
```

### 3. Wire into `_QaPostCodingWrapper` (consumer.py)

**M2 fix: fold status transition into `_persist_artifact_and_marker`'s existing session.** One atomic commit — no race window where a reaser/override sees inconsistent state.

T22 introduced `_persist_artifact_and_marker(task_uuid, artifact)` with a single call site inside `_QaPostCodingWrapper.run()`. T23 extends the signature to `(task_uuid, artifact, stamped_mode)` — update that one caller. Grep before commit to confirm no new T22 callers slipped in; if any exist, thread `stamped_mode` through.

```python
async def _persist_artifact_and_marker(task_uuid, artifact, stamped_mode):
    payload = dict(artifact.payload or {})
    decision = decide_qa_gate(artifact.verdict, payload, stamped_mode)

    async with async_session_factory() as session:
        art_repo = ArtifactRepository(session)
        task_repo = TaskRepository(session)
        artifact_id = await art_repo.upsert(task_uuid, artifact)  # returns row id post-T22

        # Load current status for M3 compare-and-set on qa_failed transition
        task_row = await task_repo.get_by_id(task_uuid)
        current_status = task_row.status if task_row else None

        out_patch = {
            "qa_completed_at": datetime.now(timezone.utc).isoformat(),
            "qa_verdict": artifact.verdict,
            "qa_artifact_id": str(artifact_id) if artifact_id else None,
        }
        if decision.qa_failures:
            out_patch["qa_failures"] = decision.qa_failures
        await task_repo.update_output_data(task_uuid, out_patch)

        if decision.new_status is not None:
            # M3: compare-and-set. qa_failed downgrade only allowed from awaiting_qa.
            if decision.new_status == "qa_failed" and current_status != "awaiting_qa":
                emit_counter("qa.gate.status_mismatch",
                             tags=[f"current:{current_status or 'unknown'}"])
                # Do NOT transition; leave row for T24 reaper / admin override
            else:
                await task_repo.update_status(task_uuid, decision.new_status)

        await session.commit()

    if decision.counter_name:
        emit_counter(decision.counter_name)

    return decision
```

**B1 fix — idempotent replay for stranded `awaiting_qa` tasks.** In `_QaPostCodingWrapper.run()`, change the idempotency short-circuit:

```python
# OLD: if existing_output.get("qa_completed_at"): return noop
# NEW:
if existing_output.get("qa_completed_at"):
    # Artifact was persisted on a prior delivery. Check if the status
    # transition ever completed; if status is still awaiting_qa the prior
    # run crashed after commit and SQS redelivery must complete the gate.
    async with async_session_factory() as s:
        task_row = await TaskRepository(s).get_by_id(task_uuid)
    if task_row and task_row.status == "awaiting_qa":
        # Reconstruct the gate from stored verdict. _extract_failures is
        # pure over the stored payload — cheap, no Claude retry.
        stored_verdict = existing_output.get("qa_verdict", "error")
        # Use the existing ArtifactRepository — no new helper.
        async with async_session_factory() as s:
            prior_artifacts_map = await ArtifactRepository(s).list_for_task(task_uuid)
        prior_artifact = prior_artifacts_map.get("qa")
        if prior_artifact is not None:
            decision = decide_qa_gate(stored_verdict,
                                      dict(prior_artifact.payload or {}),
                                      stamped_mode)
            async with async_session_factory() as s:
                task_repo = TaskRepository(s)
                if decision.new_status == "qa_failed" and task_row.status != "awaiting_qa":
                    emit_counter("qa.gate.status_mismatch", tags=[...])
                elif decision.new_status is not None:
                    await task_repo.update_status(task_uuid, decision.new_status)
                    await s.commit()
            emit_counter("qa.gate.replay_recovered")
    logger.info("qa_post_coding_idempotent_replay", extra={"task_id": task_id_str})
    return _synthesize_noop_result()
```

The replay branch is pure DB work — no Claude call, no new cost. Crash-recovery path is correct by construction.

Schema-drift case (T22 forces `verdict=error` when `schema_version != "v2"`): `decide_qa_gate` sees `verdict="error"` → lenient pass-through → `completed`. Correct.

### 3b. Admin backend query updates (M1 fix)

`src/ai_driven/api/admin/router.py` currently filters on `status == "completed"` at multiple points (lines 398, 619, 1360, 1914-1915, 1990). Post-T23 live-mode, orchestrated tasks pass through `awaiting_qa`, then either `completed` or `qa_failed`. Success-rate math at 1947-1948 would silently under-count.

Updates:
- **Success bucket:** `status == "completed"` (unchanged — qa_failed is NOT success).
- **Failure bucket:** `status IN {"failed", "blocked", "qa_failed"}` (add two).
- **In-flight bucket:** `status IN {"queued","running","awaiting_qa"}`.
- Any dashboard "total done" counter: `status IN {"completed","failed","blocked","qa_failed"}`.

Implementation: grep for `== "completed"` and `== "failed"` in admin/router.py; update each site. Add a shared constant `TERMINAL_STATUSES = frozenset({"completed","failed","blocked","qa_failed"})` + `IN_FLIGHT_STATUSES = frozenset({"queued","running","awaiting_qa"})` at module top; re-use.

### 4. Grace period for T22-landed-without-T23

If T22 is in `feature/orchestrator-phase1` but T23 isn't merged yet, `awaiting_qa` tasks accumulate. T23 ships ON THE SAME BRANCH before PR-to-main. Documented explicitly in the PR description and in T28's cleanup.

### 5. Tests

`tests/unit/test_qa_gate.py`:
- verdict=not_implemented + live → completed, counter=qa.gate.not_implemented (B2).
- verdict=pass + live → completed, no failures, counter=qa.gate.pass.
- verdict=pass_with_notes + live → completed, no failures.
- verdict=block + live → qa_failed + failures list populated from both AC + security.
- verdict=error + live → completed, counter=qa.gate.lenient_error.
- verdict=block + shadow → `new_status=None`, failures still extracted for obs.
- verdict=pass + shadow → `new_status=None`, no failures, counter=qa.gate.shadow.pass.
- `_extract_failures`: AC satisfied=true filtered out; security severity=low filtered out; severity=medium filtered out; severity=high + critical included.

`tests/unit/test_qa_post_coding_wrapper.py` (extend T22 tests):
- After verdict=block, status became qa_failed in DB (live mode).
- After verdict=pass, status became completed in DB.
- Shadow mode: status stays at `completed` (T22 path), qa_failures populated on block.
- **B1 replay**: set `qa_completed_at` + status=awaiting_qa + pre-stored verdict=block; redeliver; status becomes qa_failed without re-running QA, counter=qa.gate.replay_recovered.
- **M3 mismatch**: current status is `blocked` (not awaiting_qa), decision says qa_failed → status unchanged, counter=qa.gate.status_mismatch emitted.

`tests/unit/test_admin_router_status_buckets.py` (new):
- Success-rate query includes qa_failed in failure bucket, excludes awaiting_qa from terminal.

### 6. Observability

Counters: `qa.gate.{blocked, pass, pass_with_notes, lenient_error, shadow.pass, shadow.block, …}`.

Alert: `qa.gate.blocked` rate > 30% sustained 1h → P2 (false-positive rate too high; either model prompt regression or gating is too aggressive).

## Commit sequence

1. `feat(db): add qa_failed to ck_tasks_status`
2. `feat(qa-gate): decide_qa_gate pure function + extract_failures`
3. `feat(queue): _QaPostCodingWrapper applies qa-gate decision atomically`
4. `feat(queue): replay-recovery for stranded awaiting_qa tasks`
5. `feat(admin): status-bucket constants — include qa_failed + awaiting_qa`
6. `test(qa-gate): unit + wrapper integration coverage`

## Rollout

T23 is a no-op in shadow mode (counter-only). Live mode activation is T26. Safe to ship immediately after T22.

## Risks

1. **`qa_failures` schema vs consumer.** UI (T25) will render this. Keep the dict shape stable: `{kind, criterion|rule, reason, evidence, severity?}`.
2. **Duplicate counter emission on idempotent replay.** `_QaPostCodingWrapper` short-circuits on `qa_completed_at` — status transition code lives AFTER the idempotency check, so it runs only once. Counter-drift not a concern.
3. **What if `awaiting_qa` was never set (shadow path from T22)?** `update_status(task_id, "completed")` is a no-op (already completed) — safe. `update_status(task_id, "qa_failed")` in shadow is gated off by `stamped_mode != "live"` → `new_status=None`. Safe.
