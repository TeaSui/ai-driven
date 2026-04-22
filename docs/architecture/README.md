# Architecture Documentation

Interactive diagrams for the AI-Driven Development Platform. Each file contains Mermaid diagrams that render on GitHub and can be opened in draw.io.

## Diagrams

| # | Document | What it covers |
|---|----------|----------------|
| 1 | [Database ER](01-database-er.md) | All 8 PostgreSQL tables with columns, types, relationships |
| 2 | [System Architecture](02-system-architecture.md) | Full platform topology — triggers, API, agents, Claude, infra, integrations, MCP |
| 3 | [Coding Agent Sequence](03-coding-agent-sequence.md) | Request flow: UI → API → agent loop → Redis → WebSocket → UI streaming |
| 4 | [Task State Machine](04-task-state-machine.md) | All states across 5 pipeline stages with transitions and error paths |
| 5 | [RAG, Caching & Evaluation](05-rag-caching-evaluation.md) | AST chunking, hybrid retrieval, prompt caching strategy, evaluation dimensions |
| 6 | [Workflow E2E](06-workflow-e2e.md) | Jira → Coding Agent → Review Agent → merge PR → Slack notify |
| 7 | [DevOps Incident](07-devops-incident.md) | DataDog alert → investigate → RCA → Jira ticket → Slack notify |

## How to view

- **GitHub**: Mermaid diagrams render natively in markdown preview
- **draw.io**: Copy the Mermaid block into draw.io (Extras → Edit Diagram → switch to Mermaid)
- **VS Code**: Install "Markdown Preview Mermaid Support" extension
