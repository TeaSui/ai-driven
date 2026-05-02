# Feature 3: AI Dev Workflow Platform

## Purpose

Orchestrate the full software development lifecycle automatically. When a Jira ticket is ready, the platform chains: plan → code → test → PR → review → merge → deploy → monitor. Each step triggers the next. Humans can intervene at any checkpoint. The platform turns a Jira ticket into deployed, monitored code with minimal manual work.

## Who Uses It

- **Developers** — move a ticket to "Ready for Dev" and watch it flow through the pipeline
- **Tech Leads** — monitor workflow progress on the dashboard, intervene when needed
- **Engineering Managers** — see throughput metrics: tickets/day, time-to-PR, time-to-deploy

## Expected Result

Given ticket `PROJ-456` moved to "Ready for Dev":
1. Coding agent creates PR #78 (5 minutes)
2. Review bot posts inline comments (2 minutes after PR)
3. If review passes → auto-merge PR
4. CI/CD pipeline triggers → deployment
5. DevOps agent monitors deployment health
6. Jira ticket auto-transitions: Ready for Dev → In Progress → In Review → Done
7. Full audit trail visible on dashboard and in Jira comments

---

## Full Workflow (Step by Step)

### Step 1: Workflow Initiation

**Trigger: Jira ticket transitions to "Ready for Dev"**

```
Jira Webhook → api/webhooks/jira.py
  ├── Validate: status changed to "Ready for Dev" or "Ready for Development"
  ├── Deduplicate via WebhookEventStore
  ├── Look up repository (project mapping / label / default)
  ├── Create Task (agent_type: "coding")
  ├── Initialize WorkflowStateMachine (state: TICKET_CREATED)
  └── Transition: TICKET_CREATED → READY_FOR_DEV → PLANNING
```

**State machine tracks the full lifecycle in DynamoDB.**

### Step 2: Planning + Coding Phase

**State: PLANNING → CODING**

The `WorkflowAgent` dispatches the `CodingAgent`:

1. `dispatch_coding_agent(jira_key="PROJ-456", repo="org/repo")`
2. CodingAgent uses a single `run_agent_loop()` with extended thinking (`budget_tokens: 16000`)
3. Claude autonomously decides the workflow: understand → plan → implement → deliver
4. Agent gathers context (Jira + repo) via tool calls, then writes code — no separate planning phase
5. Plan optionally shown to developer for approval (configurable checkpoint)

**State transition:** PLANNING → CODING → TESTING

### Step 3: Coding Phase

**State: CODING**

1. SandboxExecutor clones repo, creates branch, runs Claude Code CLI
2. Code is written, tests are run (up to 3 retry attempts)
3. Changes committed to feature branch

**State transition:** CODING → TESTING

### Step 4: Testing Phase

**State: TESTING**

1. Full test suite runs in sandbox
2. If tests fail after 3 attempts → state → FAILED, notify team
3. If tests pass → proceed

**State transition:** TESTING → PR_CREATED

### Step 5: PR Creation

**State: PR_CREATED**

1. Push branch to remote
2. Create PR with Claude-generated description
3. Link PR to Jira ticket
4. Jira status → "In Review"

**This triggers the next step automatically via WorkflowTriggers.**

### Step 6: Automated Code Review

**State: PR_CREATED → IN_REVIEW**

**Trigger: `WorkflowTriggers.on_coding_task_completed()`**

1. Detects PR was created
2. Enqueues a review task to SQS
3. Review bot picks it up (see Feature 2)
4. Posts inline comments + summary

**State transition:** IN_REVIEW → REVIEW_PASSED or back to CODING (if changes requested)

### Step 7: Review Verdict

**State: REVIEW_PASSED or loop back**

**Trigger: `WorkflowTriggers.on_review_completed()`**

| Verdict | Action |
|---------|--------|
| `approved` | Auto-merge PR via SCM API |
| `changes_requested` | Notify developer on Slack, optionally re-trigger coding agent |
| `comment_only` | Notify developer, wait for manual action |

### Step 8: Merge

**State: MERGING**

1. `merge_pull_request(repo="org/repo", pr_number=78, method="squash")`
2. Delete feature branch
3. Jira status → "Done"

**Trigger: `WorkflowTriggers.on_merge_completed()`** → notify team

**State transition:** MERGING → DEPLOYED

### Step 9: Deployment Monitoring

**State: DEPLOYED → MONITORING**

1. CI/CD pipeline triggers automatically on merge to main
2. DevOps agent monitors DataDog for deployment anomalies
3. If anomaly detected → create incident (see Feature 4)
4. After monitoring window (configurable, e.g., 30 minutes) with no issues:

**State transition:** MONITORING → COMPLETED

### Step 10: Completion

**State: COMPLETED**

1. Final Slack notification: "PROJ-456 is deployed and healthy"
2. Metrics recorded: total duration, cost, lines changed
3. Workflow audit trail persisted in DynamoDB

---

## State Machine

```
TICKET_CREATED → READY_FOR_DEV → PLANNING → CODING → TESTING
    ↓                                                    ↓
  FAILED ←──────────── (any non-terminal) ──────── PR_CREATED
                                                         ↓
                                                    IN_REVIEW
                                                    ↓       ↓
                                          REVIEW_PASSED   (back to CODING)
                                                ↓
                                             MERGING
                                                ↓
                                            DEPLOYED
                                                ↓
                                           MONITORING
                                                ↓
                                           COMPLETED
```

**13 states total.** FAILED is reachable from any non-terminal state.

Each transition is persisted in DynamoDB with timestamp, metadata, and optional guard conditions.

## Event-Driven Triggers

| Event | Source | Trigger Handler | Action |
|-------|--------|----------------|--------|
| Coding task completed | SQS consumer | `on_coding_task_completed()` | Enqueue review if PR created |
| Review completed | Review agent | `on_review_completed()` | Merge if approved, notify if not |
| Merge completed | SCM API | `on_merge_completed()` | Notify team, update Jira |

Triggers are registered as listeners on the state machine. They fire after each successful transition.

## Real-Time Visibility

### Dashboard (Web UI)
- Task table with status badges, auto-refresh every 10 seconds
- Click into task → see full conversation log, tool calls, phases
- Jira and PR links on each task

### WebSocket Streaming
- Agent publishes events to Redis pub/sub: `task:{id}:events`
- Web UI connects via WebSocket, receives real-time updates
- Event types: `phase_change`, `tool_call_start`, `tool_call_end`, `text_delta`, `complete`, `error`

### Slack Notifications
- Phase changes: "PROJ-456: Planning complete, starting coding..."
- Completion: "PR #78 created for PROJ-456"
- Failures: "PROJ-456 failed during testing: 3 tests failed"

## Code Mapping

| Component | Primary File | Key Class/Function |
|-----------|-------------|-------------------|
| State machine | `backend/src/ai_driven/agents/workflow/state_machine.py` | `WorkflowStateMachine` |
| Workflow agent | `backend/src/ai_driven/agents/workflow/agent.py` | `WorkflowAgent` |
| Event triggers | `backend/src/ai_driven/agents/workflow/triggers.py` | `WorkflowTriggers` |
| WebSocket streaming | `backend/src/ai_driven/api/chat/router.py` | WebSocket endpoint |
| Event publishing | `backend/src/ai_driven/agents/base.py` | `BaseAgent._publish_event()` |
| Dashboard | `frontend/src/app/dashboard/page.tsx` | Task table |
| Task detail | `frontend/src/app/dashboard/tasks/[id]/page.tsx` | Conversation log |
| Agent stream | `frontend/src/components/agent-stream.tsx` | WebSocket client |

## Configuration

| Setting | Default | Purpose |
|---------|---------|---------|
| Auto-merge on approve | `false` | Merge without human confirmation |
| Re-trigger coding on changes_requested | `false` | Let AI fix review feedback |
| Monitoring window | 30 min | How long to watch after deploy |
| Approval checkpoint after planning | `false` | Require human approval of plan |

## Error Handling

| Failure | Recovery |
|---------|----------|
| Any step fails | State → FAILED, Slack notification, Jira comment |
| Review requests changes | Notify developer, optionally re-trigger coding |
| Merge conflict | Notify developer, require manual resolution |
| Deployment anomaly | DevOps agent creates incident (Feature 4) |
| State machine store failure | Transition still succeeds (store is best-effort) |
| Listener error | Does not block transition (logged, not thrown) |
