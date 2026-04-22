# Sprint 1: Foundation

**Status: COMPLETE**

## Goal

Project skeleton, Claude provider abstraction, database models, FastAPI app with health check and webhook stubs.

## Tasks

### 1.1 Project Scaffolding
- [x] Create `pyproject.toml` with all dependencies
- [x] Create directory structure (`src/ai_driven/`, `tests/`, `infrastructure/`, `docker/`)
- [x] Create `.env.example` with all config keys
- [x] Create `.gitignore` (Python, Node, CDK, IDE, secrets)
- [x] Create `Makefile` with dev commands
- [x] Create Python virtual environment (`.venv/`)

### 1.2 Configuration
- [x] `src/ai_driven/config.py` — Pydantic settings from env vars
  - Claude provider type (anthropic/bedrock), model IDs
  - PostgreSQL, DynamoDB, SQS, Redis connection strings
  - GitHub, Bitbucket, Jira, Slack, DataDog credentials
- [x] Verify: `python -c "from ai_driven.config import get_settings; print(get_settings())"`

### 1.3 Claude Provider Abstraction
- [x] `src/ai_driven/claude/provider.py` — Data models: `TokenUsage`, `TextBlock`, `ToolUseBlock`, `ThinkingBlock`, `MessageResponse`
- [x] `AnthropicProvider` — Direct API via `anthropic.AsyncAnthropic`
- [x] `BedrockProvider` — AWS Bedrock via `boto3` with model ID mapping
- [x] `create_provider()` factory function
- [x] Both providers parse responses into the same `MessageResponse` model

### 1.4 Claude Client
- [x] `src/ai_driven/claude/client.py` — `ClaudeClient` class
  - `chat()` — Single-turn message (no tool loop)
  - `run_agent_loop()` — Multi-turn tool use loop with max iterations
  - `run_agent_loop_streaming()` — Streaming variant for real-time UI
  - `_execute_tool_calls()` — Execute tools and build `tool_result` blocks

### 1.5 Tool Registry
- [x] `src/ai_driven/claude/tools/registry.py` — `ToolRegistry` class
  - `register()` + `@tool()` decorator
  - `get_schemas()` — Returns tool schemas for Claude
  - `execute()` — Dispatches tool calls by name
- [x] `src/ai_driven/claude/tools/schemas.py` — Schema definitions for all tools
- [x] Unit tests: register, get schemas, execute, unknown tool error

### 1.6 Conversation Management
- [x] `src/ai_driven/claude/conversation.py` — `Conversation` class
  - Message history management
  - Context window truncation (`get_messages(max_turns=N)`)
  - Serialization (`to_dict` / `from_dict`)
  - Summarization detection
- [x] Unit tests: add messages, truncation, serialization

### 1.7 System Prompts
- [x] `src/ai_driven/claude/prompts/coding_agent.py` — Coding agent prompt (understand → plan → implement → deliver)
- [x] `src/ai_driven/claude/prompts/review_agent.py` — Review bot prompt (security, bugs, perf, quality checklist)
- [x] `src/ai_driven/claude/prompts/workflow_agent.py` — Workflow orchestrator prompt
- [x] `src/ai_driven/claude/prompts/devops_agent.py` — DevOps assistant prompt (triage workflow)

### 1.8 SCM Abstraction
- [x] `src/ai_driven/scm/models.py` — Data models: `Repository`, `PullRequest`, `PRDiff`, `DiffFile`, `FileContent`, etc.
- [x] `src/ai_driven/scm/interface.py` — `SCMClient` Protocol (list_files, read_file, create_pr, get_pr_diff, post_comment, merge)
- [x] `src/ai_driven/scm/github_client.py` — Full GitHub API implementation
- [x] `src/ai_driven/scm/bitbucket_client.py` — Full Bitbucket Cloud API implementation

### 1.9 Jira Client
- [x] `src/ai_driven/jira/models.py` — `JiraIssue`, `JiraComment`, `JiraTransition`
- [x] `src/ai_driven/jira/client.py` — `JiraClient` (get_issue, get_transitions, transition_issue, add_comment)

### 1.10 Database Models
- [x] `src/ai_driven/db/postgres/models.py` — SQLAlchemy: `Team`, `Repository`, `Task`, `Review`, `ConversationRecord`
- [x] `src/ai_driven/db/postgres/repository.py` — Repository pattern: `TeamRepository`, `TaskRepository`, `ReviewRepository`
- [x] `src/ai_driven/db/postgres/session.py` — Async engine, session factory, `init_db()`
- [x] `src/ai_driven/db/dynamo/models.py` — DynamoDB: `AgentSessionStore`, `WebhookEventStore`, `TaskStateMachineStore`

### 1.11 Auth Middleware
- [x] `src/ai_driven/auth/middleware.py` — `APIKeyMiddleware` (X-API-Key header, SHA-256 hash, public path bypass)
- [x] Unit tests: hash determinism, different keys produce different hashes

### 1.12 Observability
- [x] `src/ai_driven/observability/metrics.py` — DogStatsD metrics: token counts, cost, agent duration, review stats
- [x] `src/ai_driven/observability/tracing.py` — DataDog APM auto-patching (FastAPI, httpx, SQLAlchemy, Redis, boto)
- [x] `src/ai_driven/observability/logging.py` — Structured JSON logging with python-json-logger

### 1.13 API Layer
- [x] `src/ai_driven/main.py` — FastAPI app with lifespan, CORS, middleware, routers
- [x] `src/ai_driven/api/health.py` — `GET /health`
- [x] `src/ai_driven/api/webhooks/github.py` — HMAC-SHA256 signature verification, PR event handling
- [x] `src/ai_driven/api/webhooks/bitbucket.py` — PR created/updated/comment events
- [x] `src/ai_driven/api/webhooks/jira.py` — Issue created/updated, status transition detection
- [x] `src/ai_driven/api/webhooks/datadog.py` — Alert webhook handler
- [x] `src/ai_driven/api/chat/router.py` — `POST /api/chat/send`, `WS /api/chat/stream/{task_id}`
- [x] `src/ai_driven/api/admin/router.py` — `POST /api/admin/teams`, `GET /api/admin/tasks`

### 1.14 Agent Base + Stubs
- [x] `src/ai_driven/agents/base.py` — `BaseAgent` (register tools, system prompt, run loop, metrics)
- [x] `src/ai_driven/agents/coding/agent.py` — `CodingAgent` with 7 registered tools
- [x] `src/ai_driven/agents/reviewer/agent.py` — `ReviewAgent` with 3 tools
- [x] `src/ai_driven/agents/workflow/agent.py` — `WorkflowAgent` stub
- [x] `src/ai_driven/agents/devops/agent.py` — `DevOpsAgent` stub

### 1.15 Queue
- [x] `src/ai_driven/queue/producer.py` — `TaskProducer` (SQS send_message)
- [x] `src/ai_driven/queue/consumer.py` — `TaskConsumer` (SQS polling, agent dispatch)

### 1.16 Docker + Infrastructure
- [x] `docker/Dockerfile.api` — FastAPI container
- [x] `docker/Dockerfile.worker` — Agent worker container
- [x] `docker/docker-compose.yml` — PostgreSQL, DynamoDB Local, Redis, API, Worker
- [x] `infrastructure/stacks/network_stack.py` — VPC
- [x] `infrastructure/stacks/database_stack.py` — Aurora + DynamoDB tables
- [x] `infrastructure/stacks/compute_stack.py` — ECS Fargate + SQS queues
- [x] `infrastructure/stacks/api_stack.py` — API Gateway
- [x] `infrastructure/stacks/observability_stack.py` — CloudWatch dashboard

## Verification

```bash
# All tests pass
source .venv/bin/activate && pytest tests/unit -v
# Expected: 18 passed

# App loads with all routes
python -c "from ai_driven.main import app; print(f'{len(app.routes)} routes loaded')"
# Expected: 10 routes loaded
```
