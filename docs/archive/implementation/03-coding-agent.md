# Sprint 3: AI Coding Agent

**Status: COMPLETE**

## Goal

Make the AI Coding Agent fully functional: Jira ticket → plan → code → test → PR. Includes sandbox execution via Claude Code CLI, SQS worker, and Slack bot trigger.

## Prerequisites

- Sprint 2 complete (SCM + Jira clients hardened, webhooks wired, integration tests passing)
- Claude API key configured (Anthropic or Bedrock)
- Docker compose running (PostgreSQL, DynamoDB, Redis)

---

## Tasks

### 3.1 Coding Agent — Tool Handlers with Real Clients

**File: `src/ai_driven/agents/coding/agent.py`**

- [x] Inject `JiraClient` and `SCMClient` via constructor (not optional None)
- [x] Wire `get_jira_issue` tool to real `JiraClient.get_issue()`
- [x] Wire `add_jira_comment` tool to real `JiraClient.add_comment()`
- [x] Wire `update_jira_status` tool to real `JiraClient.transition_issue()`
- [x] Wire `list_repo_files` tool to real `SCMClient.list_files()`
- [x] Wire `read_file` tool to real `SCMClient.read_file()`
- [x] Wire `search_code` tool to real `SCMClient.search_code()`
- [x] Wire `create_pull_request` tool to real `SCMClient.create_pull_request()`
- [x] Add tool: `write_file` — commit a file change to a branch via SCM API
- [x] Add tool: `create_branch` — create a feature branch from default branch
- [x] Truncate large file contents (>10KB) in tool results to avoid context blowup
- [ ] Add token counting to tool results for cost awareness

### 3.2 Sandbox Execution — Claude Code CLI

**New file: `src/ai_driven/agents/coding/sandbox.py`**

- [x] Create `SandboxExecutor` class
- [x] Clone repository into a temporary directory
- [x] Create and checkout feature branch
- [x] Build Docker sandbox container with Claude Code CLI installed
- [x] Execute Claude Code CLI with task prompt inside the container
- [x] Stream stdout/stderr from container
- [x] Collect changed files after execution
- [x] Push branch to remote
- [x] Clean up temporary directory

```python
class SandboxExecutor:
    async def execute(
        self, repo_url: str, branch: str, task_prompt: str, timeout: int = 600,
    ) -> SandboxResult:
        work_dir = tempfile.mkdtemp(prefix="ai-driven-")
        try:
            # Clone repo
            await self._run(f"git clone --depth=1 {repo_url} {work_dir}")
            await self._run(f"git -C {work_dir} checkout -b {branch}")

            # Run Claude Code CLI in sandbox
            result = await self._run_in_docker(
                work_dir=work_dir,
                command=f"claude -p '{task_prompt}' --allowedTools Edit,Write,Bash",
                timeout=timeout,
            )

            # Collect changes
            diff = await self._run(f"git -C {work_dir} diff")
            changed_files = await self._run(f"git -C {work_dir} diff --name-only")

            # Commit and push
            await self._run(f"git -C {work_dir} add -A")
            await self._run(f'git -C {work_dir} commit -m "feat: AI-generated implementation"')
            await self._run(f"git -C {work_dir} push origin {branch}")

            return SandboxResult(success=True, diff=diff, changed_files=changed_files.split())
        finally:
            shutil.rmtree(work_dir, ignore_errors=True)
```

- [x] Wire `execute_in_sandbox` tool to `SandboxExecutor`
- [x] Add timeout and resource limits to Docker container (CPU, memory, no network except git push)
- [x] Docker sandbox image: `docker/Dockerfile.sandbox`

### 3.3 Coding Agent — Unified Agent Loop with Extended Thinking

**File: `src/ai_driven/agents/coding/agent.py`**

The CodingAgent uses a single `run_agent_loop()` with extended thinking instead of a separate planning phase. Claude decides the workflow (understand → plan → implement → deliver) autonomously via tool use.

- [x] CodingAgent always enables extended thinking (`budget_tokens: 16000`)
- [x] XML-structured system prompt with `<role>`, `<workflow>`, `<constraints>`, `<examples>` tags
- [x] 11 registered tools: `get_jira_issue`, `add_jira_comment`, `list_repo_files`, `read_file`, `search_code`, `write_file`, `create_branch`, `create_pull_request`, `update_jira_status`, `execute_in_sandbox`, `repo.overview`. `repo.list_files` now also accepts `paths: list[str]`, `recursive`, `max_depth`, and `pattern`; `repo.read_file` accepts batch `paths: list[str]`.
- [x] Optional: `semantic_search_code` (if RAG enabled), MCP tools (if MCP enabled)
- [x] Events published to Redis at each step for real-time UI visibility
- [x] Calculate and store total token usage and cost (cache-aware per-model pricing via billing/tracker.get_model_pricing())
- As of commit 42ddf91 the initial user message is XML-tagged: `<user_request>`, `<repository>`, `<jira_ticket>`, `<repo_map>` (pre-loaded tree + manifests), `<workflow>`, `<rules>`.
- [x] MockProvider returns scripted 10-step tool_use sequence for local dev

### 3.5 SQS Worker — Production-Ready Consumer

**File: `src/ai_driven/queue/consumer.py`**

- [x] Add graceful shutdown handling (SIGTERM, SIGINT)
- [x] Add heartbeat — extend SQS visibility timeout while task is running
- [ ] Add dead letter queue processing (log DLQ messages, alert on Slack)
- [x] Add concurrency control (max N concurrent tasks via semaphore)
- [x] Inject real clients (Jira, SCM) into agent creation
- [ ] Add DataDog metrics for queue depth, processing time, success rate

```python
async def _heartbeat(self, queue_url: str, receipt_handle: str, interval: int = 300):
    """Extend visibility timeout periodically for long-running tasks."""
    while True:
        await asyncio.sleep(interval)
        self._sqs.change_message_visibility(
            QueueUrl=queue_url, ReceiptHandle=receipt_handle,
            VisibilityTimeout=900,
        )
```

### 3.6 Slack Bot — Trigger Coding Agent

**New file: `slack_bot/app.py`**

- [x] Initialize Slack Bolt app with socket mode
- [x] Register `/ai-code` slash command handler
- [x] Parse: `/ai-code PROJ-123` or `/ai-code PROJ-123 in org/repo`
- [x] Validate Jira issue exists, find associated repo
- [x] Create task in DB, enqueue to SQS
- [x] Reply with task ID and status message
- [x] Register `/ai-status` slash command — check task status
- [ ] Add event listener for mentions (`@AI-Driven code PROJ-123`)

```python
from slack_bolt.async_app import AsyncApp
from slack_bolt.adapter.socket_mode.async_handler import AsyncSocketModeHandler

app = AsyncApp(token=settings.slack_bot_token, signing_secret=settings.slack_signing_secret)

@app.command("/ai-code")
async def handle_ai_code(ack, body, say):
    await ack()
    text = body.get("text", "").strip()
    # Parse: "PROJ-123" or "PROJ-123 in org/repo"
    parts = text.split(" in ")
    jira_key = parts[0].strip()
    repo = parts[1].strip() if len(parts) > 1 else None

    task = await create_coding_task(jira_key, repo, team_id=body["team_id"])
    await say(f"Coding task created: `{task.id}`\nJira: {jira_key}\nStatus: queued")
```

### 3.7 Slack Bot — Progress Notifications

**New file: `slack_bot/notifications.py`**

- [x] Send Slack message when task phase changes (planning → coding → testing → PR created)
- [x] Include: task ID, Jira link, current phase, elapsed time
- [x] Send PR link when created
- [x] Send error details if task fails
- [x] Find Slack channel from team settings

### 3.8 Unit Tests — Coding Agent

**New file: `tests/unit/test_coding_agent.py`**

- [x] Test tool registration (all expected tools registered)
- [x] Test `_build_initial_message` format
- [x] Test phase flow with mocked Claude client
- [x] Test failure handling (Claude returns error, tool execution fails)

### 3.9 Integration Tests — Full Flow

**New file: `tests/integration/test_coding_flow.py`**

- [x] Test: Jira webhook → task created → queued (mock SQS)
- [x] Test: Task consumed → agent runs with mocked Claude → tools called in order
- [x] Test: PR created → Jira comment posted → status updated

### 3.10 E2E Test — Real Coding Agent

**New file: `tests/e2e/test_coding_e2e.py`**

- [ ] Test with real Claude API (test account, small repo)
- [ ] Create a simple Jira ticket (e.g., "Add a hello world endpoint")
- [ ] Run coding agent
- [ ] Verify: PR created, Jira comment posted, tests pass
- [ ] Mark as slow test (separate pytest mark)

---

## Verification

```bash
# Unit tests
pytest tests/unit -v
# Expected: All pass (including new coding agent tests)

# Integration tests
pytest tests/integration -v
# Expected: All pass

# Manual E2E test
# 1. Create a Jira ticket with clear acceptance criteria
# 2. Run: python -c "
#    from ai_driven.agents.coding.agent import CodingAgent
#    from ai_driven.jira.client import JiraClient
#    from ai_driven.scm.github_client import GitHubClient
#    import asyncio
#    agent = CodingAgent(jira_client=JiraClient(), scm_client=GitHubClient(token='...'))
#    result = asyncio.run(agent.run({'jira_key': 'PROJ-123', 'repo_full_name': 'org/repo'}))
#    print(f'Success: {result.success}, Tokens: {result.total_tokens}')
#    "
# Expected: PR created in the repo

# Slack bot
# 1. Start slack bot: python slack_bot/app.py
# 2. In Slack: /ai-code PROJ-123 in org/repo
# Expected: Task created message, then progress updates
```

## Files Created/Modified This Sprint

| Action | File |
|--------|------|
| Modify | `src/ai_driven/agents/coding/agent.py` |
| Modify | `src/ai_driven/claude/provider.py` (MockProvider with scripted tool_use) |
| Create | `src/ai_driven/agents/coding/sandbox.py` |
| Modify | `src/ai_driven/queue/consumer.py` |
| Create | `docker/Dockerfile.sandbox` |
| Create | `slack_bot/app.py` |
| Create | `slack_bot/notifications.py` |
| Create | `tests/unit/test_coding_agent.py` |
| Create | `tests/integration/test_coding_flow.py` |
| Create | `tests/e2e/test_coding_e2e.py` |
