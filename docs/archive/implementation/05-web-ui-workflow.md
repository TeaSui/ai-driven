# Sprint 5: Web UI & Workflow Orchestration

**Status: COMPLETE**

## Goal

Build the Next.js web dashboard with chat interface, real-time agent streaming, and the workflow state machine that ties everything together.

## Prerequisites

- Sprint 3 complete (Coding Agent functional)
- Sprint 4 complete (Review Bot functional)
- WebSocket streaming working in FastAPI

---

## Tasks

### 5.1 Next.js Project Setup

**Directory: `ai-driven-web-ui/`**

- [x] Initialize Next.js 14+ with App Router
- [x] Configure dependencies: swr, clsx, tailwind-merge
- [x] Configure API proxy to backend (FastAPI at :8888) via next.config.js rewrites
- [x] Set up Tailwind CSS + PostCSS
- [x] Create layout: sidebar nav + main content area

### 5.2 Dashboard — Task Overview Page

**File: `ai-driven-web-ui/src/app/dashboard/page.tsx`**

- [x] Fetch and display tasks from `GET /api/admin/tasks`
- [x] Table columns: Status, Agent Type, Title, Created, Duration
- [x] Status badges with color coding
- [x] Filter by agent type and status
- [x] Auto-refresh every 10 seconds (SWR with revalidateInterval)
- [x] Click task → detail view

### 5.3 Dashboard — Task Detail Page

**File: `ai-driven-web-ui/src/app/dashboard/tasks/[id]/page.tsx`**

- [x] Fetch task details from `GET /api/admin/tasks/{id}`
- [x] Show: status, agent type, title, Jira key, PR link
- [x] Show: Token usage, cost, duration, iterations
- [x] Show: Output and error details

### 5.4 Chat Interface — Send Messages

**File: `ai-driven-web-ui/src/app/chat/page.tsx`**

- [x] Chat input with agent type selector (coding, review, workflow, devops)
- [x] Send message via `POST /api/chat/send`
- [x] Display message history (client-side state)
- [x] Show agent responses

### 5.5 Chat Interface — Real-Time Streaming

**File: `ai-driven-web-ui/src/components/agent-stream.tsx`**

- [x] Connect to WebSocket `ws://api/ws/{task_id}`
- [x] Display streaming text as it arrives
- [x] Display tool call indicators
- [x] Display phase transitions
- [x] Handle connection drops (auto-reconnect)

### 5.6 WebSocket API — Backend Streaming

**File: `src/ai_driven/api/chat/router.py`**

- [x] Implement WebSocket streaming endpoint
- [x] Subscribe to task events from Redis pub/sub
- [x] Events: `text_delta`, `tool_call_start`, `tool_call_end`, `phase_change`, `complete`, `error`
- [x] Handle connection drops gracefully

**File: `src/ai_driven/agents/base.py`**

- [x] Add `_publish_event(event_type, data)` method
- [x] Publish events to Redis channel `task:{task_id}:events`
- [x] Publishing is optional — doesn't crash if Redis is unavailable

### 5.7 Admin — Team Management Page

**File: `ai-driven-web-ui/src/app/admin/page.tsx`**

- [x] Team listing and settings
- [x] Repository management (list, add, remove)
- [x] Add repository form

### 5.8 Admin — Repository Registration API

**File: `src/ai_driven/api/admin/router.py`**

- [x] `POST /api/admin/repos` — register a repository for a team
- [x] `GET /api/admin/repos` — list team's repositories
- [x] `DELETE /api/admin/repos/{id}` — soft-delete (set is_active=False)
- [x] `PUT /api/admin/repos/{id}` — update repo settings

### 5.9 Workflow State Machine

**New file: `src/ai_driven/agents/workflow/state_machine.py`**

- [x] Define states: TICKET_CREATED → READY_FOR_DEV → ... → COMPLETED, FAILED
- [x] Define valid transitions with guards
- [x] Store state transitions in DynamoDB `TaskStateMachineStore`
- [x] Emit events on transitions via listener callbacks
- [x] `set_state()` for restoring from persistent storage

### 5.10 Workflow Agent — Orchestration Logic

**File: `src/ai_driven/agents/workflow/agent.py`**

- [x] `dispatch_coding_agent` — create coding task and enqueue
- [x] `dispatch_review_agent` — create review task and enqueue
- [x] `check_task_status` — check dispatched task status via DynamoDB
- [x] `merge_pull_request` — merge PR via SCM client
- [x] `notify_team` — send Slack notification

### 5.11 Workflow — Event-Driven Triggers

**New file: `src/ai_driven/agents/workflow/triggers.py`**

- [x] On coding task complete → trigger review if PR created
- [x] On review complete (approved) → attempt auto-merge, notify team
- [x] On review complete (changes requested) → notify developer
- [x] On merge complete → notify team

### 5.12 Tests

- [x] `tests/unit/test_state_machine.py` — 13 tests: state transitions, guards, store, listeners
- [x] `tests/unit/test_workflow_agent.py` — 8 tests: tool registration, system prompt, messages
- [x] `tests/unit/test_workflow_agent_tools.py` — tool execution tests
- [x] `tests/unit/test_workflow_triggers.py` — trigger event handling
- [x] `tests/unit/test_workflow_state_machine.py` — additional state machine tests
- [x] `tests/unit/test_base_agent_events.py` — event publishing tests
- [x] `tests/unit/test_admin_repos.py` — admin API tests
- [x] `tests/unit/test_chat_websocket.py` — WebSocket streaming tests
- [x] `tests/integration/test_workflow_flow.py` — full pipeline: triggers + state machine

---

## Verification

```bash
# All tests: 317 passing
pytest tests/ -v

# Frontend dev server (after npm install)
cd web_ui && npm install && npm run dev
# Open http://localhost:3000
```

## Files Created/Modified This Sprint

| Action | File |
|--------|------|
| Create | `ai-driven-web-ui/` (entire Next.js project) |
| Modify | `src/ai_driven/api/chat/router.py` (WebSocket streaming) |
| Modify | `src/ai_driven/api/admin/router.py` (repo CRUD) |
| Modify | `src/ai_driven/agents/base.py` (Redis pub/sub events) |
| Modify | `src/ai_driven/agents/workflow/agent.py` (orchestration tools) |
| Create | `src/ai_driven/agents/workflow/state_machine.py` |
| Create | `src/ai_driven/agents/workflow/triggers.py` |
| Create | `tests/unit/test_state_machine.py` |
| Create | `tests/unit/test_workflow_agent.py` |
| Create | `tests/unit/test_workflow_agent_tools.py` |
| Create | `tests/unit/test_workflow_triggers.py` |
| Create | `tests/unit/test_workflow_state_machine.py` |
| Create | `tests/unit/test_base_agent_events.py` |
| Create | `tests/unit/test_admin_repos.py` |
| Create | `tests/unit/test_chat_websocket.py` |
| Create | `tests/integration/test_workflow_flow.py` |
