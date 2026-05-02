# T24 — Admin override + stuck-task reapers (brief, v2 post-red-team)

**Changelog v1→v2:**
- **B1+B2:** Migration adds `last_heartbeat_at TIMESTAMPTZ` column. Consumer's `_heartbeat` loop writes it every 5 min. Reaper keys on this (NOT `updated_at`, which doesn't exist).
- **B3:** `qa_enqueued_at` / `qa_completed_at` / `orchestrator_completed_at` / `started_at` live in `output_data` JSONB. Reaper queries use `(output_data->>'X')::timestamptz`. Add an expression index when query cost becomes visible (defer until dogfood shows > 1000 tasks; too early to pre-optimize).
- **B4:** `rerun_coding` CLEARS `orchestrator_completed_at`, `orchestrator_halted_on`, `classifier_tier`, `blocked_by`, `blocking_reasons`, `blocker_artifact_id` from `output_data` before re-enqueueing. Task_artifacts rows left in place (audit trail).
- **B5:** `rerun_qa` CLEARS `qa_completed_at`, `qa_verdict`, `qa_artifact_id`, `qa_enqueued_at`, `qa_failures` from `output_data` before re-enqueueing. QA artifact row left as history (next run upserts).
- **M1:** Create `require_admin` dependency in `auth/middleware.py` — reads session's team_member and asserts `role in {"admin","owner"}`. Reuse across T24 + T25.
- **M2:** Migration seeds BOTH a `system` team (fixed UUID `00000000-0000-0000-0000-000000000000`) AND a `system` team_member (fixed UUID `00000000-0000-0000-0000-000000000001`), idempotent via `ON CONFLICT DO NOTHING`.
- **M3:** Concurrent override → CAS via `UPDATE tasks SET status=:new WHERE id=:id AND status=:expected`. `rowcount == 0` → 409. No version column.
- **M4:** Reaper death → emit per-loop `reaper.heartbeat` counter; alert on absence for > 15 min. Runbook (T28) notes promote-to-separate-process as post-dogfood improvement.
- **M5:** Explicit heartbeat column removes the ambiguity — any status/stream write that happens during running is irrelevant; reaper keys only on `last_heartbeat_at`.



**Goal.** Two escape hatches for tasks stuck in a non-terminal state:
1. **Admin override endpoint** (`POST /api/admin/tasks/{task_id}/override-block`) — admin transitions a `blocked` / `qa_failed` / stuck-`orchestrating` / stuck-`awaiting_qa` task to a recoverable state with an audit row.
2. **Background reapers** — sweep periodically, auto-fail tasks stuck > 30 min in `orchestrating` / `awaiting_qa` / `running` with no heartbeat.

**Depends on:** T21 (blocked status), T22 (awaiting_qa), T23 (qa_failed).

**Out of scope:** T25 (UI surfacing the button — backend enforcement here).

## File-level plan

### 1. Alembic migration — audit table + heartbeat column + system seeds

`alembic/versions/<rev>_add_task_override_audit_plus_heartbeat.py`, `down_revision = <T23 qa_failed rev>`.

```sql
-- B1+B2: explicit heartbeat column
ALTER TABLE tasks ADD COLUMN last_heartbeat_at TIMESTAMPTZ;
CREATE INDEX ix_tasks_last_heartbeat_at ON tasks(last_heartbeat_at)
    WHERE status IN ('running', 'orchestrating', 'awaiting_qa');

-- Audit table
CREATE TABLE task_override_audit (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    task_id UUID NOT NULL REFERENCES tasks(id) ON DELETE CASCADE,
    overridden_by_member_id UUID NOT NULL REFERENCES team_members(id),
    from_status TEXT NOT NULL,
    to_status TEXT NOT NULL,
    action TEXT NOT NULL,           -- 'rerun_coding' | 'rerun_qa' | 'force_pass' | 'reap'
    reason TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX ix_task_override_audit_task_id ON task_override_audit(task_id);

-- M2: system team + member seeds (idempotent)
INSERT INTO teams (id, name, api_key_hash, created_at)
VALUES ('00000000-0000-0000-0000-000000000000', 'system', '__system__', now())
ON CONFLICT (id) DO NOTHING;

INSERT INTO team_members (id, team_id, email, role, created_at)
VALUES ('00000000-0000-0000-0000-000000000001',
        '00000000-0000-0000-0000-000000000000',
        'system@ai-driven.local', 'admin', now())
ON CONFLICT (id) DO NOTHING;
```

Downgrade: drop the audit table + heartbeat index + column. Do NOT delete the system seeds (they may be referenced by audit rows from future re-upgrade).

### 2. Override endpoint

`src/ai_driven/api/admin/router.py` — `POST /api/admin/tasks/{task_id}/override-block`.

Request body (pydantic CamelModel):
```python
class OverrideRequest(CamelModel):
    action: Literal["rerun_coding", "rerun_qa", "force_pass", "reap"]
    reason: str   # non-empty, max 500 chars
```

Behaviour matrix:

| current status | allowed actions | outcome |
|---|---|---|
| `blocked` | `rerun_coding` | CLEAR `output_data.{orchestrator_completed_at, orchestrator_halted_on, classifier_tier, blocked_by, blocking_reasons, blocker_artifact_id}` (B4); requeue coding-task with original payload; status → `queued` |
| `qa_failed` | `rerun_qa` | CLEAR `output_data.{qa_completed_at, qa_verdict, qa_artifact_id, qa_enqueued_at, qa_failures}` (B5); enqueue qa-task; status → `awaiting_qa` |
| `qa_failed` | `force_pass` | status → `completed` (audit records admin accepted risk) |
| `orchestrating` (only if `started_at` in output_data or `created_at` > 30min ago) | `reap` | status → `failed`, `output_data.failure_reason = "orchestrator_stuck_reaped"` |
| `awaiting_qa` (only if `output_data.qa_enqueued_at` > 30min ago) | `reap` | status → `failed`, `output_data.failure_reason = "qa_stuck_reaped"` |
| `running` (only if `last_heartbeat_at` > 30min ago OR NULL AND `created_at` > 30min) | `reap` | status → `failed`, `output_data.failure_reason = "worker_stuck_reaped"` |
| any other | — | 400 with clear error message |

**Concurrent-override guard (M3):** UPDATE statement `UPDATE tasks SET status=:new WHERE id=:id AND status=:expected RETURNING id`. If `rowcount == 0`, return 409 Conflict.

Every success writes a `task_override_audit` row in the SAME session as the status change.

**Auth (M1):** create a new `require_admin` FastAPI dependency in `src/ai_driven/auth/middleware.py`:
```python
async def require_admin(request: Request) -> TeamMemberInfo:
    member = await _get_authenticated_member(request)  # existing or derive
    if member.role not in {"admin", "owner"}:
        raise HTTPException(status_code=403, detail="Admin role required")
    return member
```
Reusable across T24 (override endpoint) and T25 (artifacts + override button backend).

### 3. Reapers

New module `src/ai_driven/background/reapers.py`. Three reapers, all key on the new `last_heartbeat_at` column or output_data JSON fields:

```python
STUCK_THRESHOLD_MINUTES = 30

async def reap_stuck_orchestrators() -> int:
    """SELECT tasks WHERE status='orchestrating'
       AND (output_data->>'started_at')::timestamptz < now() - interval '30 min'
       OR (created_at < now() - interval '30 min' AND output_data->>'started_at' IS NULL)
    Transition to 'failed', reason='orchestrator_stuck_auto_reaped'.
    Audit row with action='reap', overridden_by_member_id=SYSTEM_MEMBER_UUID.
    Emit counter 'reaper.orchestrator.reaped'.
    """

async def reap_stuck_qa() -> int:
    """SELECT tasks WHERE status='awaiting_qa'
       AND (output_data->>'qa_enqueued_at')::timestamptz < now() - interval '30 min'.
    Transition to 'failed', reason='qa_stuck_auto_reaped'.
    Emit counter 'reaper.qa.reaped'.
    """

async def reap_stuck_running() -> int:
    """SELECT tasks WHERE status='running'
       AND (last_heartbeat_at < now() - interval '30 min'
            OR (last_heartbeat_at IS NULL AND created_at < now() - interval '30 min')).
    Transition to 'failed', reason='worker_stuck_auto_reaped'.
    Emit counter 'reaper.running.reaped'.
    """
```

**Heartbeat writer (B2 fix).** In `src/ai_driven/queue/consumer.py::_heartbeat` (currently only calls `change_message_visibility`), ADD a DB write every tick:
```python
async with async_session_factory() as s:
    await TaskRepository(s).touch_heartbeat(task_id)  # UPDATE tasks SET last_heartbeat_at = now() WHERE id = :id
    await s.commit()
```
New `TaskRepository.touch_heartbeat(task_id)` method — one-line helper. Interval stays at 300s (every 5 min).

**Scheduler integration.** In `src/ai_driven/worker.py::main`, add a periodic asyncio task that runs all 3 reapers every 5 minutes. One loop, three awaits, per-reaper try/except. Emit `reaper.heartbeat` counter each loop iteration (M4 alert on absence).

Audit-row written automatically with `action='reap'`, `overridden_by_member_id=SYSTEM_MEMBER_UUID` ('00000000-0000-0000-0000-000000000001' per migration).

### 4. Error handling

Override endpoint response schemas:
- 200: `{data: {taskId, fromStatus, toStatus, action, auditId}, meta: {timestamp}}`
- 400: unrecognized action OR illegal transition OR task not stuck long enough
- 403: non-admin
- 404: task not found
- 409: concurrent override (compare-and-set fails — another admin beat us)

### 5. Tests

`tests/unit/test_admin_override_endpoint.py`:
- blocked + rerun_coding → status=queued, audit row written, SQS enqueue called.
- qa_failed + rerun_qa → status=awaiting_qa, audit row written.
- qa_failed + force_pass → status=completed, audit row written.
- orchestrating + reap (stuck > 30min) → status=failed, reason set.
- orchestrating + reap (fresh < 30min) → 400.
- awaiting_qa + reap (stuck) → status=failed.
- completed + any action → 400 (not overrideable).
- non-admin → 403.
- missing reason → 422.
- concurrent-override simulation (current status changed between read and write) → 409.

`tests/unit/test_reapers.py`:
- Seeds stuck orchestrator task (started_at = now - 31min, status=orchestrating). `reap_stuck_orchestrators()` transitions to failed, writes audit, emits counter.
- Fresh orchestrating task (15min) → untouched.
- Stuck awaiting_qa → reaped.
- Stuck running → reaped.
- Reaper handles DB error without crashing the loop.

`tests/integration/test_reaper_lifecycle.py`:
- Worker startup registers reaper task; running 15 minutes of fake time reaps one stuck task.

### 6. Observability

Counters:
- `admin.override.{blocked,qa_failed,orchestrating,awaiting_qa}.{rerun_coding,rerun_qa,force_pass,reap}` — dimensionality intentional for auditing override patterns.
- `reaper.{orchestrator,qa,running}.reaped` — sustained > 10/hr should page.

Alerts:
- `reaper.orchestrator.reaped > 5 within 1h` → Sev-2 (systemic orchestrator failure).
- `admin.override.qa_failed.force_pass` ANY emission → audit log entry automatically; P3 weekly review.

## Commit sequence

1. `feat(db): task_override_audit + last_heartbeat_at + system seeds`
2. `feat(auth): require_admin dependency`
3. `feat(consumer): write last_heartbeat_at every heartbeat tick`
4. `feat(admin): POST /override-block endpoint with CAS + marker-clearing`
5. `feat(reapers): orchestrator/qa/running stuck-task reapers`
6. `feat(worker): register reaper loop at startup`
7. `test(admin+reapers): unit + integration coverage`

## Risks / notes

1. **SYSTEM_MEMBER_ID seed** — make it idempotent in the migration (`INSERT ... ON CONFLICT DO NOTHING`). Reuse across environments via a fixed UUID constant.
2. **Re-enqueue on rerun_coding** — uses `TaskProducer().enqueue_task(skip_orchestrator_routing=False)` so it re-runs through the orchestrator. If the admin wants to bypass the orchestrator (e.g. the block was a false positive), add a later endpoint variant. Not in T24 scope.
3. **Reaper races with SQS heartbeat** — if the worker is alive and mid-task, its heartbeat updates `updated_at`. `reap_stuck_running` keys off `updated_at < now - 30min` so active tasks aren't reaped. Confirmed: heartbeat in consumer.py:257-277 doesn't touch `updated_at`; verify that + add if missing (or key reaper on a different field like `last_heartbeat_at`).
