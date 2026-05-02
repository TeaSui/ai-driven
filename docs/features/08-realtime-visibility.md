# Feature 8: Real-Time Prompt/Response Visibility

## Purpose

Provide full transparency into what the AI agents are doing: the system prompt sent, each tool call with inputs/outputs, Claude's response text, token usage, and cache metrics — all streamed in real-time to the task detail page.

## Architecture

```
Agent (BaseAgent.run())
  │
  ├── prompt_sent ──────────────┐
  ├── tool_call_start ──────────┤
  ├── tool_call_end ────────────┤
  ├── response_chunk ───────────┤  Redis pub/sub
  ├── iteration ────────────────┤  channel: task:{id}:events
  ├── complete / error ─────────┘
  │                              │
  │                              ▼
  │                    WebSocket endpoint
  │                    /api/chat/stream/{task_id}
  │                              │
  │                              ▼
  │                    Task Detail Page
  │                    (React, expandable panels)
```

## Event Types

| Event | Published When | Data Fields |
|-------|---------------|-------------|
| `phase_change` | Agent starts | `phase`, `agent_type` |
| `prompt_sent` | Before first Claude call | `system_prompt` (5KB), `user_message` (3KB), `model` |
| `tool_call_start` | Each tool invocation | `tool`, `call_number`, `input_data` (2KB) |
| `tool_call_end` | After tool returns | `tool`, `duration_ms`, `success`, `result_data` (2KB) |
| `response_chunk` | Each Claude response | `content` (3KB), `iteration` |
| `iteration` | Each loop iteration | Token counts, `cache_read_tokens`, `cache_creation_tokens`, `stop_reason` |
| `text_delta` | Final response text | `content` |
| `complete` | Agent finishes | `tool_calls`, `iterations`, `duration_seconds` |
| `error` | On failure | `message` (user-friendly) |

## Backend Implementation

**File: `backend/src/ai_driven/agents/base.py`**

Events are published via Redis pub/sub in callback hooks:
- `on_tool_call` → publishes `tool_call_start` with truncated `input_data`
- `on_tool_result` → publishes `tool_call_end` with truncated `result_data`
- `on_response` → publishes `iteration` (token counts) + `response_chunk` (text)

Events are also stored in Redis list `task:{task_id}:event_log` with 24h TTL for late-joining clients.

**File: `backend/src/ai_driven/api/chat/router.py`**

WebSocket endpoint `/api/chat/stream/{task_id}`:
- Subscribes to Redis channel `task:{task_id}:events`
- Forwards allowed event types to the connected client
- Allowed types: `text_delta`, `tool_call_start`, `tool_call_end`, `phase_change`, `complete`, `error`, `prompt_sent`, `response_chunk`, `iteration`

## Frontend Implementation

**File: `frontend/src/app/dashboard/tasks/[id]/page.tsx`**

The task detail page renders events as an interactive timeline:

| Event | UI Rendering |
|-------|-------------|
| `prompt_sent` | Expandable card showing system prompt + user message (monospace) |
| `tool_call_start` + `tool_call_end` | Collapsible sections showing tool name, input JSON, output JSON, duration |
| `response_chunk` | Accumulated into a running "Claude Response" panel below the event stream |
| `iteration` | Token usage badges, cache hit/miss indicators |
| `complete` | Summary card with total stats |
| `error` | Red error card with user-friendly message |

Filter buttons allow showing/hiding event types.

## Prompt Caching Visibility

Each `iteration` event includes cache metrics from the Bedrock/Anthropic response:

```json
{
  "type": "iteration",
  "data": {
    "iteration": 3,
    "input_tokens": 2500,
    "output_tokens": 800,
    "cache_read_tokens": 1800,
    "cache_creation_tokens": 0,
    "stop_reason": "tool_use"
  }
}
```

This lets you verify that prompt caching is working (cache reads should be >0 on subsequent iterations).
