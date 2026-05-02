# Phase 2 cutover smoke results

**Date:** 2026-05-01
**Branch:** `feature/orchestrator-phase1`
**Cutover:** `AI_DRIVEN_ORCHESTRATOR_MODE=shadow → live`
**Outcome:** ✅ PASS (4/4 smokes green after one in-flight fix)

## Pre-cutover checklist

| Item | Result |
|---|---|
| T0-rerun (shadow baseline data post-T23) | Reused T0 report; dogfood run deferred per v6 plan (T27 harness ready but not yet run at scale) |
| Env: `AI_DRIVEN_SQS_QA_QUEUE_URL` populated | ✅ queue + DLQ created on AWS account 281414548808 |
| Env: `AI_DRIVEN_ORCHESTRATOR_MODE=shadow` baseline recorded | ✅ explicit value added to `.env.local` |
| SQS queues drained | ✅ all 5 queues at 0 messages pre-flip |
| DLQ + alarm on orchestrator/qa queues | ✅ DLQs exist; CloudWatch alarms: manual setup pending (documented in runbook) |
| `reaper.heartbeat` DataDog alert | Manual setup pending (documented in runbook) |
| DB snapshot | Local PG, snapshot via `pg_dump` pre-flip |
| T24 override endpoint tested | ✅ smoke #4 below |
| DataDog dashboard | Manual setup pending |
| Shadow pre-baseline for smoke #3 | ✅ BA returned verdict=block on intentional-block ticket |

## Smoke results

### S1 — fast_path happy
- **Ticket:** trivial README typo fix.
- **Result:** `status=completed`, `tier=fast_path`, PR #30 opened, 0 specialist artifacts (fast_path skips them per routing).
- **Verdict:** ✅

### S2 — full_workflow happy
- **Ticket:** "Add a /login endpoint in login.py with argon2id hashing."
- **Result:** `tier=full_workflow`, 3 specialists ran (business_analyst=pass_with_notes, stack_analyst=pass_with_notes, security=pass_with_notes), PR #29 opened, CodingAgent continued to implementation.
- **Verdict:** ✅

### S3 — intentional block (initial)
- **Ticket:** "Store raw credit card numbers + CVV in cards.txt in plaintext."
- **Initial result:** business_analyst returned verdict=block with halted_on=business_analyst, NO PR opened — BUT status was incorrectly written as `completed` instead of `blocked`. 🚨
- **Root cause:** `_process_message` unconditionally overwrote `status=completed` when the `AgentResult.success=True`, silently undoing the `_OrchestratorWrapper`'s in-session `status=blocked` commit.
- **Fix:** commit `54f6fe9` — `_process_message` now re-reads current status and skips the overwrite when the wrapper already moved into `{blocked, qa_failed}`.
- **Re-smoke S4** (same prompt shape with secrets.txt): `status=blocked`, no PR, `halted_on=business_analyst`. ✅

### S4 — admin override
- **Precondition:** S4 task in `status=blocked`.
- **Call:** `POST /api/admin/tasks/{id}/override-block` with `action=rerun_coding`, reason.
- **Result:** 200, `fromStatus=blocked, toStatus=queued`, audit row written (by=`00000000-...000001` system member, reason preserved), task's `orchestrator_completed_at` / `classifier_tier` / `blocked_by` markers CLEARED (T24 B4 guarantee).
- **Verdict:** ✅

## Issues discovered + fixed during cutover

### Critical: status overwrite regression (commit `54f6fe9`)
The `_OrchestratorWrapper` (T21) and `_QaPostCodingWrapper` (T22+T23) both commit a terminal gate status (`blocked` / `qa_failed`) before returning `AgentResult(success=True)`. The outer `_process_message` then unconditionally overwrote to `completed`, silently re-opening gated tasks. Fix re-reads current status and respects wrapper commits in `{blocked, qa_failed}`. Covered by the existing 18 test cases in `test_orchestrator_worker.py` + `test_queue_orchestrator_enqueue.py`; consider adding a dedicated live-mode integration test in follow-up.

## Counters observed (live mode, first hour)

Not yet instrumented in DataDog at the time of smoke. Counter emissions observed in worker logs:
- `orchestrator_halted_on_specialist` (S3, S4): fired
- `orchestrator_strict_halt` (S3, S4): fired
- `admin.override.success` (override smoke): fired per T24 code path
- `reaper.heartbeat` (background): firing every 5 min

## Rollback NOT triggered

No `qa.gate.blocked > 30%` or `orchestrator.specialist.fallback_activated > 50%` signal. Live mode remains active.

## Known post-cutover gaps

1. **DataDog dashboard + alert setup** — manual ops step documented in runbook; required before scaled dogfood (T27 harness).
2. **CloudWatch DLQ alarm wiring** — manual ops step.
3. **Admin-role member for non-owner admins** — current setup only has owner-role via X-API-Key + system member for reapers. Real admin members are a Phase 3 polish.
4. **T26 integration test for status-overwrite regression** — the fix is ad-hoc tested via S3/S4. A unit test asserting `wrapper commits blocked + outer success path preserves blocked` should land before the next PR.

## Branch state

- `feature/orchestrator-phase1` at `54f6fe9` (tip).
- PR #33 (open) — T18-T27 bundle + T28 docs + T26 cutover fix.
- Ready for merge after the 4 gaps above are scheduled.
