# Feature 3: AI Dev Workflow Platform

## Purpose

Chain the full software development lifecycle from a Jira ticket to a
PR merged with confidence: classify → specialists gate → code → open PR
→ post-coding QA validates the diff → completed (or `blocked` /
`qa_failed`). Humans can intervene at every terminal. The platform
turns a Jira ticket into a reviewable PR with attributable evidence,
not a mystery.

Deployment and monitoring integrations (DataDog watch window, auto
rollback) are feature-flagged and covered by Feature 4 — they are not
part of the core chain described here.

## Who Uses It

- **Developers** — move a ticket to "Ready for Dev" and watch it flow
  through the pipeline.
- **Tech Leads** — monitor pipeline progress on the admin Timeline,
  intervene via `POST /api/admin/tasks/{id}/override-block` when needed.
- **Engineering Managers** — see throughput metrics: tickets/day,
  time-to-PR, specialist block rate, QA pass rate.

## Expected Result

Given ticket `PROJ-456` moved to "Ready for Dev":

1. Orchestrator classifies tier and runs specialists (BA, Security, …)
   — `task_artifacts` rows persisted.
2. All specialists pass → coding-task enqueued with BA + Security
   context injected into the prompt.
3. CodingAgent creates PR #78.
4. QA post-coding validates the diff against acceptance criteria and
   security rules.
5. QA pass → task `completed`; QA block → task `qa_failed` (admin
   override can rerun or force-pass).
6. Full audit trail persisted in `task_artifacts` and
   `task_override_audit`.

## Architecture — no dedicated state machine

Unlike the (removed) legacy `WorkflowAgent`, the platform no longer
ships its own `WorkflowStateMachine`. The authoritative state machine
is the **Task state machine** in
[`architecture/04-task-state-machine.md`](../architecture/04-task-state-machine.md)
with transitions written by exactly one worker:

| Worker | Writes |
|---|---|
| `_OrchestratorWrapper` (SQS `orchestrator` queue) | `queued → orchestrating → {coding, blocked, running}` |
| `CodingAgent` worker (SQS `task` queue) | `coding → awaiting_qa` (PR opened) / `coding → failed` (no PR) |
| `_QaPostCodingWrapper` (SQS `qa` queue) | `awaiting_qa → completed` / `awaiting_qa → qa_failed` |
| Admin override / reaper | Terminal overrides, `task_override_audit` row in same transaction |

See [`architecture/06-workflow-e2e.md`](../architecture/06-workflow-e2e.md)
for the sequence diagram.

## Event-Driven Triggers

All pipeline advancement is driven by SQS message hand-offs, not an
in-process trigger listener table:

| Hand-off | Producer | Consumer |
|---|---|---|
| Intake → orchestrator | `POST /api/chat/send` or webhook | `_OrchestratorWrapper` |
| Orchestrator → coding | `_OrchestratorWrapper` (on all specialists pass) | `CodingAgent` worker |
| Coding → QA | `CodingAgent` (on PR opened) | `_QaPostCodingWrapper` |
| Coding failure / no PR | `CodingAgent` wrapper | `status=failed` written in same commit |

No separate trigger registry exists. A reaper
(`background/reapers.py`) sweeps tasks stuck in `orchestrating`,
`running`, or `awaiting_qa` > 30 minutes and writes terminal `failed`
plus a `task_override_audit` row.

## Real-Time Visibility

### Dashboard (Web UI)

- Admin Timeline at `/dashboard/timeline` projects each Task's `status`
  onto `(stage, stageStatus)` pair via `_STATUS_TO_STAGE` +
  `_AGENT_COMPLETED_STAGE`; see
  [`architecture/04-task-state-machine.md`](../architecture/04-task-state-machine.md).
- Click into a task → see BA / Security / QA artifacts, coding tool
  log, PR link.
- Reviews tab at `/dashboard/reviews` shows inline AI review comments,
  reviewer list, commits, and checks — all live from the SCM provider.

### WebSocket Streaming

- Agents publish events to Redis `task:{id}:events`.
- Web UI connects via `/api/chat/stream/{taskId}` and renders
  `phase_change`, `tool_call_start / end`, `streaming_chunk`,
  `iteration`, `complete`, `error`.

### Slack Notifications

Sent by the CodingAgent's `notify_team` tool or by the DevOps agent for
incidents. Format: `"{jira_key}: {message}"` on the configured channel.

## Configuration

| Setting | Env var | Default | Purpose |
|---|---|---|---|
| Orchestrator mode | `AI_DRIVEN_ORCHESTRATOR_MODE` | `shadow` | `shadow` \| `live` \| `off`. Stamped per SQS message at enqueue, so mid-deploy flips are safe. |
| Treat specialist errors as pass | `AI_DRIVEN_ORCHESTRATOR_TREAT_ERRORS_AS_PASS` | `true` | When `true`, specialist Bedrock flakes are silently passed for gating. Flip to `false` for strict measurement. |
| Per-task cost ceiling | `claude_max_cost_per_task_usd` | `$3.00` | Agent loop halts when cumulative cost crosses this. Primary runaway-loop defense. |
| Max tool iterations | `claude_max_tool_iterations` | `50` | Secondary runaway-loop defense. |
| QA reaper interval | `background/reapers.py` | 5 min | Sweeps tasks stuck > 30 min in `orchestrating` / `running` / `awaiting_qa`. |

## Error Handling

| Failure | Terminal status | Recovery |
|---|---|---|
| Specialist `verdict=block` (live mode) | `blocked` | `POST /admin/tasks/{id}/override-block` with `action=rerun_coding` |
| Coding loop ends, no PR | `failed` | Reaper + admin override |
| Stuck > 30 min at `orchestrating` / `running` / `awaiting_qa` | `failed` via reaper | — |
| QA `verdict=block` | `qa_failed` | `action=rerun_qa` (cheap) or `action=force_pass` |
| User clicks Stop | `cancelled` | Distinct from `failed` so `successRate` stays honest |

## Code Mapping

| Component | Primary File |
|---|---|
| Orchestrator wrapper | `backend/src/ai_driven/queue/consumer.py::_OrchestratorWrapper` |
| Orchestrator logic | `backend/src/ai_driven/agents/orchestrator/agent.py` |
| Tier classifier | `backend/src/ai_driven/agents/orchestrator/classifier.py` |
| Tier rules | `backend/src/ai_driven/agents/orchestrator/routing.py::TIER_RULES` |
| QA post-coding wrapper | `backend/src/ai_driven/queue/consumer.py::_QaPostCodingWrapper` |
| Coding agent | `backend/src/ai_driven/agents/coding/agent.py` |
| Review agent | `backend/src/ai_driven/agents/reviewer/agent.py` |
| Task state machine (authoritative) | `docs/architecture/04-task-state-machine.md` |
| E2E sequence | `docs/architecture/06-workflow-e2e.md` |
| Admin override / reaper | `backend/src/ai_driven/api/admin/router.py` (override endpoint) + `background/reapers.py` |
| Timeline UI | `frontend/src/app/dashboard/timeline/page.tsx` |

## Removed: legacy WorkflowAgent

An earlier iteration of this feature shipped a dedicated
`WorkflowAgent` with its own 13-state `WorkflowStateMachine` and a
separate trigger registry. That agent was removed once the orchestrator
(classification + specialist gating) and the post-coding QA wrapper
(PR-diff validation) fully owned its responsibilities. The SQS
consumer never routed the `"workflow"` agent_type, so the legacy path
was only reachable via the debug/inline chat route. Historical
`tasks.agent_type='workflow'` rows were backfilled to `'coding'` by
migration `b3c4d5e6f7a8`.
