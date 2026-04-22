# Feature Workflow Documentation

Each document defines a feature end-to-end: what triggers it, what happens step by step, what the user sees, and how it maps to code.

## Core Features

| # | Feature | Trigger | Result |
|---|---------|---------|--------|
| 1 | [AI Coding Agent](01-ai-coding-agent.md) | Jira ticket → "Ready for Dev" / Slack `/ai-code` / Web UI Chat | Working PR with tests |
| 2 | [AI Code Review Bot](02-ai-code-review-bot.md) | PR opened / updated / `/ai-review` comment | Inline comments + verdict |
| 3 | [AI Dev Workflow Platform](03-ai-dev-workflow-platform.md) | Jira ticket (chains all features) | Deployed, monitored code |
| 4 | [AI DevOps Assistant](04-ai-devops-assistant.md) | DataDog alert webhook | Incident ticket + root cause + Slack notification |

## Platform Capabilities

| # | Feature | Description | Doc |
|---|---------|-------------|-----|
| 5 | [RAG — Semantic Code Search](05-rag-semantic-search.md) | pgvector + Voyage AI embeddings + BM25 hybrid search for large repos | This file |
| 6 | [Post-Task Evaluation](06-evaluation.md) | Haiku-based grading on correctness, completeness, code quality, security | This file |
| 7 | [MCP Integration](07-mcp-integration.md) | GitHub MCP Server + Context7 for enriched tool capabilities | This file |
| 8 | [Real-Time Visibility](08-realtime-visibility.md) | Prompt/response streaming, tool I/O, cache metrics in task detail | This file |

## How Features Connect

```
┌──────────────────────────────────────────────────────────────┐
│                    Feature 3: Workflow                        │
│                    (Orchestrates all)                         │
│                                                              │
│  Jira Ticket ──→ Feature 1 ──→ Feature 2 ──→ Merge          │
│  "Ready for Dev"  (Coding)     (Review)     (Auto)           │
│       │              │            │                           │
│       │         RAG search    Local repo                     │
│       │         (Feature 5)   (shared via Redis)             │
│       │              │            │                           │
│  Deploy ──→ Feature 4 ──→ Incident (if needed)               │
│             (DevOps)                                         │
│                                                              │
│  ┌─────────────────────────────────────────────────────┐     │
│  │ Cross-cutting: Evaluation (6), MCP (7), Events (8)  │     │
│  └─────────────────────────────────────────────────────┘     │
└──────────────────────────────────────────────────────────────┘
```

Features 1, 2, and 4 can also run independently (triggered manually or via webhooks).
Feature 3 chains them into an automated pipeline.
Features 5-8 are cross-cutting capabilities available to all agents.
