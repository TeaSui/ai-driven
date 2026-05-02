# Feature 1: AI Coding Agent

## Purpose

Automate code implementation from Jira tickets. A developer assigns a ticket, and the AI agent reads the requirements, plans the implementation, writes code in a sandboxed environment, runs tests, and creates a pull request — all without human intervention.

## Who Uses It

- **Developers** — trigger via Slack `/ai-code PROJ-123` or move Jira ticket to "Ready for Dev"
- **Tech Leads** — review the generated PRs, approve/reject
- **Product Managers** — see ticket status auto-update in Jira

## Expected Result

Given a Jira ticket `PROJ-123: Add user profile endpoint`, the agent produces:
- A feature branch `feature/PROJ-123` with working code
- Tests that pass
- A pull request with Claude-generated description linking back to the Jira ticket
- Jira ticket transitioned to "In Review"
- Slack notification: "PR #42 created for PROJ-123"

---

## Full Workflow (Step by Step)

### Step 1: Trigger

**Three entry points, same outcome:**

| Trigger | How | Handler |
|---------|-----|---------|
| Slack command | `/ai-code PROJ-123` | `slack_bot/app.py` → validates Jira key → creates task → SQS |
| Jira webhook | Ticket moved to "Ready for Dev" | `api/webhooks/jira.py` → dedup → creates task → SQS |
| Web UI | Send message in Chat tab | `api/chat/router.py` → creates task → inline (debug) or SQS (production) |

**What happens:**
1. Validate the Jira key format (`[A-Z]+-\d+`)
2. Look up the repository (from Jira project mapping, ticket label `repo:org/name`, or default repo)
3. Create a `Task` record in PostgreSQL (status: `QUEUED`, agent_type: `coding`)
4. Publish SQS message with `task_id`
5. Respond immediately: "Task queued, tracking ID: abc-123"

### Step 2: Worker Picks Up Task

**File: `backend/src/ai_driven/queue/consumer.py`**

1. ECS Fargate worker polls SQS queue
2. Receives message, extracts `task_id`
3. Updates task status → `RUNNING`
4. Extends SQS visibility timeout (heartbeat every 5 min)
5. Initializes `CodingAgent` with injected clients (Jira, SCM, Claude)

### Step 3: Agent Loop (Claude API + Extended Thinking + Tool Use)

**File: `backend/src/ai_driven/agents/coding/agent.py`**

The CodingAgent uses a single multi-turn loop that handles both planning and implementation:

```
ClaudeClient.run_agent_loop()
├── Model: claude-sonnet-4-6 (configurable)
├── Extended thinking: enabled (budget_tokens: 16000)
├── System prompt: XML-structured coding agent instructions (<role>, <workflow>, <constraints>, <examples>)
├── Tools: get_jira_issue, list_repo_files, read_file, search_code, write_file,
│          create_branch, create_pull_request, execute_in_sandbox, update_jira_status,
│          add_jira_comment, semantic_search_code (if RAG enabled)
└── Max tool iterations: 50 (claude_max_tool_iterations)
```

**Tool calls the agent typically makes:**

1. `get_jira_issue(key="PROJ-123")` → reads title, description, acceptance criteria, subtasks
2. `list_repo_files(path="src/")` → understands project structure (capped at 200 lines)
3. `search_code(query="class UserService")` → finds related implementations
4. `read_file(path="src/models/user.py")` → reads existing code (truncated at 10KB)
- `repo.list_files` also accepts `paths: list[str]` for batch listing, `recursive: bool` + `max_depth: int` for tree walks, and `pattern: str` for fnmatch filtering.
- `repo.read_file` also accepts `paths: list[str]` for batch reads (up to 20 files per call, parallel).
- `repo.overview` returns `{tree, manifests}` in one call — preferred at the EXPLORE phase to skip directory-by-directory walks.
- On startup, `CodingAgent.run()` pre-loads a `<repo_map>` block (directory tree + top-level manifests) into the first user message so the model sees the whole layout without burning a tool call.
- The initial user message is XML-tagged with `<user_request>`, `<repository>`, `<jira_ticket>`, `<repo_map>`, `<workflow>`, `<rules>`.

**Output:** `ImplementationPlan` with:
- Summary of what needs to change
- List of files to create/modify
- Approach and key decisions
- Test strategy
- Estimated complexity

### Step 4: Coding Phase (Sandbox Execution)

**File: `backend/src/ai_driven/agents/coding/sandbox.py`**

1. Clone repository into a Docker container (`ops/docker/sandbox/Dockerfile`)
2. Create feature branch: `feature/PROJ-123`
3. Run Claude Code CLI inside the sandbox with the plan
4. Claude Code reads files, writes code, runs tests iteratively
5. Container has: no network access, memory/CPU limits, 15-min timeout
6. Collect the git diff of all changes

**Safety constraints:**
- `--network=none` — no external calls from sandbox
- Memory limit, CPU limit
- Max 15 minutes execution
- Non-root user inside container

### Step 5: PR Creation

**File: `backend/src/ai_driven/agents/coding/agent.py`**

Using the agent's tool loop:

1. `create_branch(branch="feature/PROJ-123", from_ref="main")`
2. For each changed file: `write_file(path, content, commit_message, branch)`
3. `create_pull_request(title="PROJ-123: Add user profile endpoint", body=<claude-generated>, branch="feature/PROJ-123")`
4. `update_jira_status(key="PROJ-123", status="In Review")`
5. `add_jira_comment(key="PROJ-123", body="PR created: https://github.com/org/repo/pull/42")`

### Step 6: Notification

1. Slack message to configured channel: "PR #42 created for PROJ-123"
2. WebSocket event pushed to Web UI (via Redis pub/sub)
3. Task status updated → `PR_CREATED`

---

## Code Mapping

| Step | Primary File | Key Class/Function |
|------|-------------|-------------------|
| Trigger (Slack) | `slack_bot/app.py` | `handle_ai_code()` |
| Trigger (Jira) | `backend/src/ai_driven/api/webhooks/jira.py` | `handle_jira_webhook()` |
| Trigger (Web UI) | `backend/src/ai_driven/api/chat/router.py` | `send_message()` → `_process_task_inline()` |
| Queue (production) | `backend/src/ai_driven/queue/consumer.py` | `SQSConsumer.poll()` |
| Sandbox | `backend/src/ai_driven/agents/coding/sandbox.py` | `SandboxExecutor.execute()` / `MockSandboxExecutor` |
| Agent loop | `backend/src/ai_driven/agents/coding/agent.py` | `CodingAgent.run()` |
| Claude API | `backend/src/ai_driven/claude/client.py` | `ClaudeClient.run_agent_loop()` |
| Claude Provider | `backend/src/ai_driven/claude/provider.py` | `AnthropicProvider` / `BedrockProvider` / `MockProvider` |
| Tool execution | `backend/src/ai_driven/claude/tools/registry.py` | `ToolRegistry.execute()` |
| SCM operations | `backend/src/ai_driven/scm/github_client.py` | `GitHubClient.create_pull_request()` |
| Jira operations | `backend/src/ai_driven/jira/client.py` | `JiraClient.get_issue()` |
| Event streaming | `backend/src/ai_driven/agents/base.py` | `BaseAgent._publish_event()` → Redis pub/sub |

## Claude API Usage

| Aspect | Value |
|--------|-------|
| Model | `claude-sonnet-4-6` (default), configurable per call |
| Extended thinking | Always enabled for CodingAgent (`budget_tokens: 16000`) |
| Max tool iterations | 50 (claude_max_tool_iterations) |
| Per-task cost ceiling | $3.00 USD (claude_max_cost_per_task_usd) — agent loop halts when cumulative cost crosses this. Primary runaway-loop defense. |
| Prompt caching | System prompt + tool schemas cached (>1024 tokens, `cache_control: ephemeral`) |
| Conversation management | Full history — messages list resent each iteration (stateless API) |
| Few-shot examples | `<examples>` XML section in system prompt with ideal workflow |
| Provider | `AnthropicProvider` (direct), `BedrockProvider` (AWS), or `MockProvider` (local dev) |
| Resilience | ResilientProvider retries failed calls against the same configured model (all slots currently pinned to claude-sonnet-4-6); fall-through to cheaper tiers is disabled — see config.py commit 36a8780 for rationale. |
| Local dev | `MockProvider` returns scripted tool_use sequences (10 steps, 11 iterations) |
| Cost tracking | Cost tracking: cache-aware per-model dispatch via billing.tracker.get_model_pricing (input/output/cache_write/cache_read). Bedrock regions other than us-east-1 apply bedrock_price_multiplier (default 1.10). |

### RAG — Semantic Code Search

When `AI_DRIVEN_RAG_ENABLED=true`, the CodingAgent gets a `semantic_search_code` tool:

| Component | Technology |
|-----------|-----------|
| Embeddings | Voyage AI `voyage-code-3` (1024-dim) |
| Vector store | pgvector (PostgreSQL extension) |
| Keyword search | PostgreSQL `ts_rank` full-text search |
| Ranking | Reciprocal Rank Fusion (RRF, k=60) — 60% vector + 40% keyword |
| Chunking | Python AST-based (functions/classes) + fixed-size fallback (1000 chars) |

**Files:** `backend/src/ai_driven/rag/` — `chunker.py`, `embeddings.py`, `indexer.py`, `retriever.py`, `models.py`

The agent uses this for large repos where GitHub keyword search is insufficient. Chunks are indexed once per repo and stored in the `code_embeddings` table.

### MCP Integration

When `AI_DRIVEN_MCP_ENABLED=true`, the CodingAgent registers tools from external MCP servers:

| MCP Server | Tools Provided |
|------------|---------------|
| GitHub MCP | Enhanced GitHub operations beyond the built-in SCM tools |
| Context7 | Library/framework documentation lookup |

**Files:** `backend/src/ai_driven/mcp/` — `config.py`, `client_manager.py`, `tool_bridge.py`

MCP tools are bridged into the agent's `ToolRegistry` at runtime via `register_mcp_tools()`.

### Post-Task Evaluation

When `AI_DRIVEN_EVALUATION_ENABLED=true`, every completed task is automatically graded:

| Dimension | Weight | What It Measures |
|-----------|--------|-----------------|
| Correctness | 35% | Does the output address the task requirements? |
| Completeness | 25% | Are all aspects of the request covered? |
| Code Quality | 25% | Clean code, patterns, maintainability |
| Security | 15% | No vulnerabilities introduced |

Grading uses `claude-sonnet-4-6 (all model slots pinned to Sonnet; see config.py)` with structured JSON output. Results are stored in `task_evaluations` and visible in the task detail UI.

**Files:** `backend/src/ai_driven/evaluation/` — `grader.py`, `runner.py`, `models.py`

### Real-Time Event Streaming

The agent publishes events to Redis `task:{task_id}:events` throughout execution:

| Event | Data | When |
|-------|------|------|
| `prompt_sent` | System prompt (5KB), user message (3KB), model | Before first Claude call |
| `tool_call_start` | Tool name, input data (2KB) | Each tool invocation |
| `tool_call_end` | Tool name, duration, success, result data (2KB) | After tool returns |
| `response_chunk` | Claude response text (3KB), iteration number | Each Claude response |
| `iteration` | Token counts, cache hits, stop reason | Each loop iteration |
| `complete` | Total tool calls, iterations, duration | Agent finishes |
| `error` | User-friendly error message | On failure |

The task detail page (`frontend/src/app/dashboard/tasks/[id]/page.tsx`) subscribes via WebSocket and renders these events with expandable panels for prompts, responses, and tool I/O.

## Error Handling

| Failure | Recovery |
|---------|----------|
| Jira ticket not found | Task fails, Slack notification with error |
| Repository not found | Task fails, 404 in response |
| Claude API timeout | Circuit breaker → retry → fallback model (Opus → Sonnet → Haiku) |
| Sandbox timeout (>15 min) | Task fails, partial diff discarded |
| Tests don't pass | Retry up to 3 times, then fail with test output |
| SQS visibility timeout | Heartbeat extends every 5 min |
| Worker crash | SQS redelivers after visibility timeout, DLQ after 3 retries |

## Metrics Emitted

- `ai.agent.task.duration` — total time from queue to PR
- `ai.agent.task.tool_calls` — number of tool calls per task
- `ai.agent.task.iterations` — Claude conversation turns
- `ai.coding.lines_changed` — lines of code in the diff
- `ai.coding.tests_passed` — test pass/fail count
- `ai.claude.tokens.input` / `ai.claude.tokens.output` — token usage
- `ai.claude.cost.usd` — estimated cost per task
