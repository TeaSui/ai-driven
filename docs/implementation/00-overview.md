# AI-Driven Development Platform — Implementation Guide

## What This Is

An AI-powered platform that automates the SDLC using Claude API. It integrates with Jira, GitHub, Bitbucket, AWS, and DataDog to provide:

1. **AI Coding Agent** — Takes Jira tickets, generates code, creates PRs
2. **AI Code Review Bot** — Auto-reviews PRs, posts inline comments
3. **AI Dev Workflow Platform** — Orchestrates Jira → Code → Review → Deploy → Monitor
4. **AI DevOps Assistant** — Triages DataDog alerts, creates incident tickets

## Tech Stack

| Component | Technology |
|-----------|-----------|
| Backend | Python 3.12+ / FastAPI (port 8888) |
| Claude API | Anthropic direct + AWS Bedrock Invoke API (configurable) |
| AWS Compute | Lambda (webhooks) + ECS Fargate (agents) |
| Database | PostgreSQL Aurora Serverless v2 + DynamoDB + pgvector |
| Queue | SQS with DLQs |
| Chat UI | Slack bot + Next.js 14 web app (GoTyme-X AI design system, port 3000) |
| Auth | API keys per team |
| Observability | DataDog APM + AI-specific metrics |
| SCM | GitHub + Bitbucket Cloud (unified abstraction) |
| RAG | pgvector + Voyage AI embeddings + BM25 hybrid search |
| MCP | GitHub MCP Server + Context7 (stdio transport) |
| Evaluation | Haiku-based multi-dimension grading |
| IaC | AWS CDK (Python) |

## Sprint Map

| Sprint | Doc | Focus | Status |
|--------|-----|-------|--------|
| 1 | [01-foundation.md](01-foundation.md) | Project skeleton, Claude provider, DB models, FastAPI app | COMPLETE |
| 2 | [02-scm-jira-integration.md](02-scm-jira-integration.md) | SCM clients, Jira client, webhook handlers, integration tests | COMPLETE |
| 3 | [03-coding-agent.md](03-coding-agent.md) | Coding agent tools, planning phase, sandbox, PR creation, SQS/ECS | COMPLETE |
| 4 | [04-code-review-bot.md](04-code-review-bot.md) | Review agent, diff analysis, inline comments, auto-review pipeline | COMPLETE |
| 5 | [05-web-ui-workflow.md](05-web-ui-workflow.md) | Next.js dashboard, chat WebSocket, workflow state machine | COMPLETE |
| 6 | [06-devops-polish.md](06-devops-polish.md) | DevOps agent, DataDog dashboards, multi-tenancy, rate limiting, CDK deploy | COMPLETE |
| 7 | — | Claude API integration: RAG, MCP, evaluation, extended thinking, prompt engineering, real-time visibility, Bedrock Invoke API | COMPLETE |
| 8 | [07-severity-taxonomy-migration.md](07-severity-taxonomy-migration.md) | Severity enum + DB CHECK + normalization (fixes cross-writer data-corruption risk) | PLANNED |
| 9 | [08-auth-and-ws-security.md](08-auth-and-ws-security.md) | Cognito login, `middleware.ts`, WebSocket team-scoped authz, `cancelled` terminal state, debug banner | PLANNED |

> **Launch blocker:** sprints 8 and 9 must ship before the second customer. See `docs/review/findings-final.md` and `docs/vision/01-roadmap.md`.
| 8 | — | GoTyme-X AI dashboard (Next.js 14), MockProvider with scripted tool_use sequences, full pipeline verification | COMPLETE |

## Project Structure

```
ai-driven/
├── src/ai_driven/            # Application source (backend)
│   ├── api/                  # FastAPI routers (webhooks, chat, admin)
│   │   ├── admin/router.py   # Dashboard, tasks, pipeline, incidents, reviews, insights
│   │   └── chat/router.py    # POST /send, WebSocket /stream/{task_id}
│   ├── agents/               # AI agents (coding, reviewer, workflow, devops)
│   │   └── base.py           # BaseAgent with Redis event publishing
│   ├── auth/                 # API key middleware (debug bypass)
│   ├── claude/               # Claude API layer
│   │   ├── provider.py       # AnthropicProvider, BedrockProvider, MockProvider
│   │   ├── client.py         # ClaudeClient.run_agent_loop() with tool execution
│   │   ├── tools/            # ToolRegistry for agent tool management
│   │   └── prompts/          # XML-structured system prompts per agent
│   ├── db/postgres/          # PostgreSQL models and repositories
│   ├── evaluation/           # Post-task grading (Haiku-based, 4 dimensions)
│   ├── jira/                 # Jira REST API client
│   ├── mcp/                  # MCP client manager + tool bridge
│   ├── observability/        # DataDog metrics, tracing, logging
│   ├── queue/                # SQS producer/consumer
│   ├── rag/                  # Semantic code search (pgvector + Voyage AI + BM25)
│   └── scm/                  # GitHub + Bitbucket abstraction
├── ai-driven-web-ui/         # Next.js 14 frontend (GoTyme-X AI design system)
│   ├── src/app/              # App Router pages
│   │   ├── dashboard/        # Overview, tasks/[id] detail
│   │   ├── timeline/         # Pipeline stage view
│   │   ├── reviews/          # Code review with inline comments
│   │   ├── incidents/        # DevOps incident triage + RCA
│   │   ├── insights/         # Engineering productivity metrics
│   │   └── chat/             # Agent chat interface
│   ├── src/components/ui/    # Shared design system components
│   ├── src/lib/api.ts        # Typed API client (SWR-based)
│   └── next.config.js        # API proxy to backend (localhost:8888)
├── tests/                    # Unit / integration / e2e tests
├── infrastructure/           # AWS CDK stacks
├── docker/                   # Dockerfiles + compose
├── slack_bot/                # Slack bot
└── alembic/                  # Database migrations
```

## How to Use These Docs

1. Work through sprints sequentially
2. Check off each task as you complete it
3. Run the verification steps at the end of each sprint before moving on
4. Each sprint doc is self-contained — it lists all files to create/modify, what they should do, and how to test
