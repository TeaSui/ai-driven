# T26 — Cutover: flip orchestrator_mode=live (brief, v2 post-red-team)

**Changelog v1→v2:**
- Checklist: verify `AI_DRIVEN_SQS_QA_QUEUE_URL` populated; baseline current mode is `shadow`; DLQ + CloudWatch alarm in place; `reaper.heartbeat` DataDog alert configured.
- Smoke #3: pre-baseline the intentional-block case in shadow BEFORE flipping — if Security doesn't block in shadow on the same prompt, smoke doesn't prove live gating either. Flip goes ahead only if shadow baseline showed the block path fires.
- Rollback: audit stuck `awaiting_qa` / `blocked` tasks at rollback time, decide case-by-case (admin override or wait-it-out).
- Dashboard: promote "DataDog dashboard configured" from bundled checklist item to explicit manual-gate step.



**Goal.** Flip the production env from `orchestrator_mode=shadow` to `live`, enabling strict specialist gating and live QA verdict gating. Verify via smoke + metric observation.

**Depends on:** T21 (strict halt predicate), T22 (QA post-coding), T23 (qa_failed transition), T24 (admin override — so we have an escape hatch on Day 1 false positives).

**Gate:** T0-rerun (per v6 plan) must pass — specialist error rate < 15% over the latest 20+ tickets with `qa` role non-error count ≥ 10. If T0-rerun fails, T26 does NOT ship; stay on lenient + shadow indefinitely per v6 fallback path.

## Pre-cutover checklist

1. **T0-rerun report** — `scripts/phase2_shadow_baseline.py` against latest 20+ tickets. Must show specialist error rate < 15%, QA non-error count ≥ 10. File as `docs/phase2-shadow-baseline-post-t23.md`.
2. **Env sanity** — confirm `AI_DRIVEN_ORCHESTRATOR_MODE=shadow` is currently active (`env | grep ORCH` on the target host). Confirm `AI_DRIVEN_SQS_QA_QUEUE_URL` is populated. Confirm all 6 queue URLs present (orchestrator, coding, review, qa, specialists, devops).
3. **Drain SQS queues.** Let in-flight shadow tasks complete. Verify each queue is empty:
   ```bash
   aws sqs get-queue-attributes --queue-url $URL --attribute-names ApproximateNumberOfMessages
   ```
4. **DLQ + alarms** — verify DLQs exist for orchestrator + qa queues; CloudWatch alarm on `ApproximateNumberOfMessagesVisible > 0` for each DLQ.
5. **`reaper.heartbeat` DataDog alert** — T24 emits it per loop; absence > 15 min pages. Confirm alert rule active before flip.
6. **Backup DB snapshot.** `pg_dump` before the flip.
7. **T24 override endpoint** live and tested — admin can unstick a qa_failed task manually.
8. **DataDog dashboard MANUAL GATE** — dashboard must show live panels for `qa.gate.*`, `orchestrator.specialist.fallback_activated`, `admin.override.*`, `reaper.*`. Blocks T26 if not configured.
9. **Shadow-mode pre-baseline for smoke #3** — submit the intentional-block ticket (raw CC numbers) in shadow FIRST. Confirm Security emits verdict=block. If shadow doesn't block, do NOT proceed to live flip — the gate can't be trusted yet.

## The flip

Single env change:

```diff
- AI_DRIVEN_ORCHESTRATOR_MODE=shadow
+ AI_DRIVEN_ORCHESTRATOR_MODE=live
```

In `.env.local` (dev) and wherever prod env is managed.

Restart backend + worker.

## Smoke sequence

Run against the dogfood repo `MinnMinn/skills-introduction-to-github` (or another disposable target):

1. **fast_path smoke** — a trivial "create SMOKE.md" ticket. Expect: tier=fast_path, no specialists, PR opens, status=`completed`. (Same as T22 smoke.)
2. **full_workflow smoke** — a ticket with real acceptance criteria and a security-sensitive angle ("add a /login endpoint that accepts email+password"). Expect: tier=full_workflow, BA + Security + StackAnalyst run, CodingAgent runs with injected context, QA runs post-coding, status=`awaiting_qa` → `completed` (if everything passes) OR `qa_failed` (if a security rule is flagged unfollowed).
3. **Intentional block smoke** — a ticket designed to trip Security (e.g. "store raw credit card numbers in a text file"). Expect: tier=full_workflow, Security emits verdict=block with `blocking_concerns`, orchestrator halts, status=`blocked`. No PR opened.
4. **Admin override smoke** — on the blocked task, hit `POST /override-block` with action=rerun_coding, reason="smoke test". Expect: status=`queued`, coding re-runs, audit row created.

Every smoke is verified against:
- `task.status` final value
- `task.output_data.classifier_tier` populated
- `task_artifacts` row count matches tier expectations
- PR (if any) opened in the target repo
- DataDog counters incremented

Results file: `docs/phase2-cutover-smoke-results.md`.

## Rollback plan

**Distinguish signals:**
- "Security didn't block when expected" in smoke #3 → LLM/prompt issue, NOT gating wiring. Don't rollback; file a P2 and iterate on the prompt.
- "Gating wiring failure" (e.g. Security emitted verdict=block but task still ran coding) → rollback.

If WIRING smoke fails OR dashboard shows `qa.gate.blocked > 30%` or `orchestrator.specialist.fallback_activated > 50%` in the first hour:

```diff
- AI_DRIVEN_ORCHESTRATOR_MODE=live
+ AI_DRIVEN_ORCHESTRATOR_MODE=shadow
```

Restart. No data migration needed — shadow mode tolerates all T21/T22/T23 output_data keys (they're additive).

SQS messages in flight stamped with `orchestrator_mode=live` will still apply live semantics on their current delivery (per T21's stamping logic), but any redelivery picks up the flipped value. Minimal blast radius.

**Post-rollback stuck-task audit.** After rolling back, query:
```sql
SELECT id, status, created_at, (output_data->>'qa_enqueued_at') AS qa_enqueued
FROM tasks WHERE status IN ('awaiting_qa', 'blocked')
ORDER BY created_at DESC;
```
For each row: if the task reached `awaiting_qa` in live mode, its QA will eventually complete via replay and T23 will transition it (shadow T23 path is no-op — they'll sit). Use T24 admin override (`force_pass` or `rerun_qa`) case-by-case. Reapers auto-handle after 30 min.

## Commit sequence

1. `chore(config): flip AI_DRIVEN_ORCHESTRATOR_MODE to live`
2. `docs(phase2): T26 cutover smoke results`

## Not in commits — manual steps

- Pre-cutover checklist items (drain queues, snapshot)
- Actual flip in the managed env
- Smoke runs + results write-up

## Risks

1. **Already-running shadow tasks.** Stamped with `orchestrator_mode=shadow`; they stay on shadow semantics for the rest of their life. Only new tasks see `live`. Safe.
2. **Prompt regression not caught in T0-rerun.** Sample bias — tickets in the corpus may not exercise every specialist equally. Mitigation: smoke must include one block case (#3) to confirm the block path works. If it doesn't fire when expected, something is wrong.
3. **False-positive flood.** If `qa.gate.blocked` spikes > 30%, rollback + file a P2. Investigation falls to T27 dogfood.
