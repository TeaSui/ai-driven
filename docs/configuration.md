# Configuration Reference

All settings are loaded from environment variables with the `AI_DRIVEN_` prefix via [pydantic-settings](https://docs.pydantic.dev/latest/concepts/pydantic_settings/).

Source: `src/ai_driven/config.py`

---

## Quick Start

**Local dev** (everything on localhost with mocks):
```bash
# 1. Prerequisites: PostgreSQL 16+, Redis
brew install postgresql@16 redis
brew services start postgresql@16
brew services start redis

# 2. Create database
createuser -s ai_driven 2>/dev/null
createdb -U ai_driven ai_driven 2>/dev/null

# 3. Configure environment
cp .env.example .env
# Leave AI_DRIVEN_ANTHROPIC_API_KEY=sk-placeholder for mock mode
# Or set a real key to use the actual Claude API

# 4. Install and run backend
python -m venv .venv && source .venv/bin/activate
pip install -e ".[dev]"
set -a && source .env && set +a
uvicorn ai_driven.main:app --host 0.0.0.0 --port 8888 --reload

# 5. Run frontend (separate terminal)
cd ai-driven-web-ui
npm install && npm run dev  # Starts on http://localhost:3000
```

**MockProvider** (automatic in debug mode):

When `AI_DRIVEN_DEBUG=true` and the API key is `sk-placeholder` (or empty), the backend uses `MockProvider` instead of calling the real Claude API. MockProvider returns scripted multi-step tool_use sequences per agent type so the full agent loop runs with realistic iterations, tool calls, events, and token counts. No external API calls are made.

**Production** (AWS — ECS/Fargate):
```bash
# All values injected via ECS task definition / Secrets Manager
# No .env file — no debug flag — no LocalStack endpoints
```

---

## Environment Modes

### What `debug=True` enables (local dev only)

| Behavior | debug=True | debug=False (production) |
|---|---|---|
| Auth bypass | Requests without `X-API-Key` pass through with a sentinel team ID | 401 Unauthorized |
| Swagger UI | Available at `/docs` | Disabled |
| CORS | `allow_origins=["*"]` | No origins allowed (configure explicitly) |
| Claude API | MockProvider when API key is `sk-placeholder` or empty | Real provider (Anthropic or Bedrock) |
| Task processing | Inline (`asyncio.create_task`) — no SQS worker needed | SQS → ECS Fargate worker |
| Sandbox | `MockSandboxExecutor` returns simulated results | `SandboxExecutor` runs Docker container |
| Sentinel team | `00000000-0000-0000-0000-000000000000` auto-created on startup | Not created |

> **Never set `AI_DRIVEN_DEBUG=true` in production.** It bypasses authentication entirely.

### MockProvider — Scripted Agent Loops

When MockProvider is active (debug + placeholder API key), it returns scripted `tool_use` responses per agent type so the full pipeline runs end-to-end without external API calls:

| Agent | Tool Steps | Iterations | Estimated Tokens |
|-------|-----------|------------|-----------------|
| Coding | 10 (get_jira_issue → list_files → read → search → read → write → sandbox → branch → PR → jira) | 11 | ~27,700 |
| Review | 7 (get_pr_diff → read → list_files → read → search → comment → approve) | 8 | ~17,800 |
| Workflow | 5 (dispatch_coding → check_status → dispatch_review → check_status → notify) | 6 | ~12,300 |
| DevOps | 6 (metrics → logs → deployments → health → incident → oncall) | 7 | ~14,900 |

Each step returns realistic token counts with simulated prompt caching. Tool handlers return `{"error": "...not configured"}` for Jira/SCM tools (since no real clients exist), but the agent loop continues through all scripted steps.

Source: `src/ai_driven/claude/provider.py` — `MockProvider._TOOL_SCRIPTS`

---

## All Settings — Local vs Production

### Core

| Env Variable | Local | Production | Notes |
|---|---|---|---|
| `AI_DRIVEN_ENVIRONMENT` | `local` | `production` | Used in logging, DataDog tags |
| `AI_DRIVEN_DEBUG` | `true` | **unset** (defaults to `false`) | See table above |
| `AI_DRIVEN_API_PORT` | `8888` | `8888` | Backend API port |
| `AI_DRIVEN_LOG_LEVEL` | `INFO` | `INFO` | |

### Claude / Bedrock

| Env Variable | Local | Production | Notes |
|---|---|---|---|
| `AI_DRIVEN_CLAUDE_PROVIDER` | `anthropic` | `bedrock` | `anthropic` for direct API, `bedrock` for AWS |
| `AI_DRIVEN_ANTHROPIC_API_KEY` | `sk-placeholder` | Real key (if provider=anthropic) | `sk-placeholder` triggers MockProvider in debug mode |
| `AI_DRIVEN_CLAUDE_DEFAULT_MODEL` | `claude-sonnet-4-6` | same | Primary model for most tasks |
| `AI_DRIVEN_CLAUDE_PLANNING_MODEL` | `claude-opus-4-6` | same | Extended thinking for planning |
| `AI_DRIVEN_CLAUDE_FAST_MODEL` | `claude-haiku-4-5` | same | Final fallback, also used for evaluation grading |
| `AI_DRIVEN_SANDBOX_MODE` | **`mock`** | **`docker`** | Mock returns simulated results; docker runs Claude Code CLI |

> **Bedrock Invoke API**: The platform uses `invoke_model` / `invoke_model_with_response_stream` with Anthropic Messages format payloads. IAM requires `bedrock:InvokeModel` and `bedrock:InvokeModelWithResponseStream` on `arn:aws:bedrock:{region}::foundation-model/anthropic.*`. Model access must also be enabled in the Bedrock console.

> **Bedrock model IDs**: `claude-sonnet-4-6` maps to `anthropic.claude-sonnet-4-6`, `claude-opus-4-6` maps to `anthropic.claude-opus-4-6-v1`, `claude-haiku-4-5` maps to `anthropic.claude-haiku-4-5-20251001-v1:0`. See `BedrockProvider._resolve_model_id()`.

### RAG (Semantic Code Search)

| Env Variable | Local | Production | Notes |
|---|---|---|---|
| `AI_DRIVEN_RAG_ENABLED` | `false` | `true` | Enables `semantic_search_code` tool in CodingAgent |
| `AI_DRIVEN_RAG_CHUNK_SIZE` | `1000` | `1000` | Max characters per code chunk |
| `AI_DRIVEN_VOYAGE_API_KEY` | empty | Real Voyage AI key | Required when RAG is enabled |

> **Database**: Requires `pgvector` extension. Migration `a1b2c3d4e5f6` creates the `code_embeddings` table with `vector(1024)` column and IVFFlat index.

### MCP (Model Context Protocol)

| Env Variable | Local | Production | Notes |
|---|---|---|---|
| `AI_DRIVEN_MCP_ENABLED` | `false` | `true` | Master switch for MCP tool registration |
| `AI_DRIVEN_MCP_GITHUB_ENABLED` | `false` | `true` | Enable GitHub MCP Server |
| `AI_DRIVEN_MCP_CONTEXT7_ENABLED` | `false` | `true` | Enable Context7 docs lookup |

### Evaluation (Post-Task Grading)

| Env Variable | Local | Production | Notes |
|---|---|---|---|
| `AI_DRIVEN_EVALUATION_ENABLED` | `false` | `true` | Auto-grade every completed task |

> Uses `claude-haiku-4-5` to grade on 4 dimensions: correctness (35%), completeness (25%), code quality (25%), security (15%). Results stored in `task_evaluations` table.

### AWS

| Env Variable | Local | Production | Notes |
|---|---|---|---|
| `AI_DRIVEN_AWS_REGION` | `ap-southeast-1` | `ap-southeast-1` | Must match Bedrock IAM policy Resource ARN |
| `AWS_ACCESS_KEY_ID` | IAM user key (`AKIA...`) | **unset** — use ECS task IAM role | Use `BedrockAPIKey-*` IAM user for local dev |
| `AWS_SECRET_ACCESS_KEY` | IAM user secret | **unset** | |
| `AWS_SESSION_TOKEN` | Only if using STS/SSO creds | **unset** | Not needed for IAM user keys |

### Database (PostgreSQL)

| Env Variable | Local | Production | Notes |
|---|---|---|---|
| `AI_DRIVEN_POSTGRES_HOST` | `localhost` | Aurora cluster endpoint | From CDK output |
| `AI_DRIVEN_POSTGRES_PORT` | `5432` | `5432` | |
| `AI_DRIVEN_POSTGRES_USER` | `ai_driven` | `ai_driven` | |
| `AI_DRIVEN_POSTGRES_PASSWORD` | `ai_driven` | Secrets Manager value | |
| `AI_DRIVEN_POSTGRES_DB` | `ai_driven` | `ai_driven` | |

### LocalStack / AWS Services

| Env Variable | Local | Production | Notes |
|---|---|---|---|
| `AI_DRIVEN_DYNAMODB_ENDPOINT_URL` | `http://localhost:4566` | **unset** (remove it) | When unset, boto3 talks to real AWS |
| `AI_DRIVEN_SQS_TASK_QUEUE_URL` | `http://localhost:4566/000000000000/ai-driven-coding-tasks` | Real SQS URL | From CDK output |
| `AI_DRIVEN_SQS_REVIEW_QUEUE_URL` | `http://localhost:4566/000000000000/ai-driven-review-tasks` | Real SQS URL | From CDK output |
| `AI_DRIVEN_SQS_DEVOPS_QUEUE_URL` | `http://localhost:4566/000000000000/ai-driven-devops-alerts` | Real SQS URL | From CDK output |

### Redis

| Env Variable | Local | Production | Notes |
|---|---|---|---|
| `AI_DRIVEN_REDIS_URL` | `redis://localhost:6379/0` | ElastiCache endpoint | |

### Observability (DataDog)

| Env Variable | Local | Production | Notes |
|---|---|---|---|
| `AI_DRIVEN_DATADOG_ENABLED` | `false` | **`true`** | Master switch for APM + metrics |
| `AI_DRIVEN_DD_ENV` | `local` | `production` | DataDog environment tag |
| `AI_DRIVEN_DD_API_KEY` | empty | Real key | |
| `AI_DRIVEN_DD_APP_KEY` | empty | Real key | |
| `AI_DRIVEN_DD_SERVICE` | `ai-driven` | `ai-driven` | |

### Integrations (optional for local dev)

| Env Variable | Local | Production | Notes |
|---|---|---|---|
| `AI_DRIVEN_JIRA_BASE_URL` | empty | `https://your-org.atlassian.net` | |
| `AI_DRIVEN_JIRA_EMAIL` | empty | Service account email | |
| `AI_DRIVEN_JIRA_API_TOKEN` | empty | Real token | |
| `AI_DRIVEN_GITHUB_TOKEN` | empty | Real PAT or App installation token | |
| `AI_DRIVEN_GITHUB_WEBHOOK_SECRET` | `test-secret` | Real HMAC secret | |
| `AI_DRIVEN_SLACK_BOT_TOKEN` | empty | Real bot token | |
| `AI_DRIVEN_SLACK_DEFAULT_CHANNEL` | empty | `#ai-driven-alerts` | |

---

## Switching Modes

### Local dev -> Production checklist

These **must** change when deploying to production:

| # | What | Why |
|---|---|---|
| 1 | Remove `AI_DRIVEN_DEBUG` (or set `false`) | Auth bypass, open CORS |
| 2 | Set `AI_DRIVEN_SANDBOX_MODE=docker` | Mock sandbox doesn't generate real code |
| 3 | Remove `AI_DRIVEN_DYNAMODB_ENDPOINT_URL` | Must talk to real DynamoDB, not LocalStack |
| 4 | Set real `AI_DRIVEN_SQS_*` URLs | From CDK stack outputs |
| 5 | Set real `AI_DRIVEN_POSTGRES_HOST` + `_PASSWORD` | Aurora endpoint + Secrets Manager |
| 6 | Set `AI_DRIVEN_DATADOG_ENABLED=true` + keys | Observability required in production |
| 7 | Remove `AWS_ACCESS_KEY_ID/SECRET/SESSION_TOKEN` | ECS task IAM role handles auth |
| 8 | Set real webhook secrets | `test-secret` is not acceptable |
| 9 | Set `AI_DRIVEN_RAG_ENABLED=true` + `VOYAGE_API_KEY` | Enable semantic code search for large repos |
| 10 | Set `AI_DRIVEN_EVALUATION_ENABLED=true` | Enable post-task quality grading |
| 11 | Set `AI_DRIVEN_MCP_ENABLED=true` | Enable MCP tool integration |
| 12 | Run `alembic upgrade head` | Apply pgvector + evaluation table migrations |

### Docker Compose profiles

```bash
# Infra only (run API on host for hot-reload):
docker compose -f docker/docker-compose.yml up -d postgres redis localstack

# Full stack (infra + API + worker in containers):
docker compose -f docker/docker-compose.yml --profile full up -d
```

### Bedrock vs Anthropic direct

```bash
# Bedrock Invoke API (default — uses IAM credentials):
AI_DRIVEN_CLAUDE_PROVIDER=bedrock
# IAM user needs: bedrock:InvokeModel, bedrock:InvokeModelWithResponseStream
# on arn:aws:bedrock:{region}::foundation-model/anthropic.*

# Anthropic direct (uses API key):
AI_DRIVEN_CLAUDE_PROVIDER=anthropic
AI_DRIVEN_ANTHROPIC_API_KEY=sk-ant-...
```

Both providers are wrapped in `ResilientProvider` with circuit breaker and automatic model fallback: opus -> sonnet -> haiku.

### Advanced Claude Features

| Feature | How It's Used |
|---------|--------------|
| **Extended thinking** | CodingAgent always enables thinking (`budget_tokens: 16000`) for deeper code analysis |
| **Structured JSON output** | ReviewAgent uses `stop_sequences=["```"]` to stop after JSON code fence |
| **Prompt caching** | System prompt + tool schemas get `cache_control: {"type": "ephemeral"}` when >1024 tokens |
| **XML tags** | All agent prompts use `<role>`, `<workflow>`, `<constraints>`, `<output_format>`, `<examples>` tags |
| **Few-shot examples** | Each agent prompt includes an `<examples>` section with ideal workflow demonstrations |
| **Cache metrics** | `cache_read_input_tokens` and `cache_creation_input_tokens` published in `iteration` events |

---

## CDK Infrastructure Notes

| CDK Context | Default | Production | Notes |
|---|---|---|---|
| `stage` | `dev` | `production` | `cdk deploy --context stage=production` |
| `region` | `eu-central-1` | `eu-central-1` | Override with `--context region=...` |
| API Gateway CORS `allow_origins` | `["*"]` | Restrict to frontend domain | Edit `api_stack.py` |
| DynamoDB `removal_policy` | `DESTROY` | Change to `RETAIN` | Edit `database_stack.py` |
