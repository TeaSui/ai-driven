# System Architecture Overview

Full platform topology: triggers, API, agents, Claude providers, infrastructure, integrations, and MCP servers.

```mermaid
flowchart TD
    subgraph Triggers["Trigger Sources"]
        UI["Web UI\nNew Run Modal"]
        JIRA["Jira Webhook\n/webhooks/jira/issue"]
        GH["GitHub Webhook\n/webhooks/github/pull_request"]
        DD["DataDog Webhook\n/webhooks/datadog/alert"]
        SLACK["Slack Command"]
    end

    subgraph API["FastAPI Backend :8888"]
        CHAT["POST /api/chat/send\nCreate Task + Queue"]
        WS["WS /api/chat/stream/{id}\nReal-time Events"]
        ADMIN["GET /api/admin/*\nDashboard + Analytics"]
    end

    subgraph Agents["Agent Execution"]
        BASE["BaseAgent\nTool Registry + Event Loop"]
        CODING["Coding Agent\n10 core tools + conditional RAG + MCP"]
        REVIEW["Review Agent\n8 tools + shared RAG"]
        DEVOPS["DevOps Agent\n7 tools"]
        WORKFLOW["Workflow Agent\n5 tools\nOrchestrates others"]
    end

    subgraph Claude["Claude AI"]
        ANTHROPIC["Anthropic API\nDirect + Cache"]
        BEDROCK["AWS Bedrock\nIAM Auth + Cache"]
        MOCK["Mock Provider\nDebug Mode"]
        CB["Circuit Breaker\n+ Resilient Fallback"]
    end

    subgraph Infra["Infrastructure"]
        PG[("PostgreSQL\n+ pgvector\n8 tables")]
        REDIS[("Redis\nPub/Sub + Cost\n+ Event Log")]
        SQS["SQS Queues\nTask / Review / DevOps"]
    end

    subgraph Integrations["External Integrations"]
        GITHUB["GitHub API\nPR + Code"]
        BITBUCKET["Bitbucket API"]
        JIRAAPI["Jira API\nTickets"]
        DDAPI["DataDog API\nMetrics + Logs"]
        SLACKAPI["Slack API\nNotifications"]
        VOYAGE["Voyage AI\nEmbeddings"]
    end

    subgraph MCP["MCP Servers (stdio)"]
        MCP_GH["GitHub MCP"]
        MCP_JIRA["Jira MCP"]
        MCP_SLACK["Slack MCP"]
        MCP_DD["DataDog MCP"]
        MCP_CTX["Context7 MCP"]
    end

    UI --> CHAT
    JIRA --> CHAT
    GH --> CHAT
    DD --> CHAT
    SLACK --> CHAT

    CHAT --> PG
    CHAT -->|"Debug: inline"| BASE
    CHAT -->|"Prod: enqueue"| SQS
    SQS -->|"Worker pulls"| BASE

    BASE --> CODING
    BASE --> REVIEW
    BASE --> DEVOPS
    BASE --> WORKFLOW

    WORKFLOW -->|"dispatch"| CODING
    WORKFLOW -->|"dispatch"| REVIEW

    CODING --> CB
    REVIEW --> CB
    DEVOPS --> CB
    WORKFLOW --> CB

    CB --> ANTHROPIC
    CB --> BEDROCK
    CB --> MOCK

    CODING --> GITHUB
    CODING --> JIRAAPI
    REVIEW --> GITHUB
    DEVOPS --> DDAPI
    DEVOPS --> SLACKAPI
    DEVOPS --> JIRAAPI

    CODING --> MCP_GH
    CODING --> MCP_JIRA
    CODING --> MCP_CTX
    DEVOPS --> MCP_DD
    WORKFLOW --> MCP_GH
    WORKFLOW --> MCP_JIRA
    WORKFLOW --> MCP_SLACK

    BASE -->|"PUBLISH events"| REDIS
    REDIS -->|"SUBSCRIBE"| WS
    WS -->|"WebSocket"| UI

    BASE --> PG
    CODING -->|"RAG index/query"| PG
    CODING -->|"Embed chunks"| VOYAGE

    ADMIN --> PG
    ADMIN --> REDIS

    style Triggers fill:#e8f5e9,stroke:#4caf50
    style API fill:#e3f2fd,stroke:#2196f3
    style Agents fill:#fff3e0,stroke:#ff9800
    style Claude fill:#f3e5f5,stroke:#9c27b0
    style Infra fill:#fce4ec,stroke:#e91e63
    style Integrations fill:#e0f7fa,stroke:#00bcd4
    style MCP fill:#f1f8e9,stroke:#8bc34a
```

## Key Data Flows

| Flow | Path |
|------|------|
| **Task submission** | UI/Webhook → POST /api/chat/send → PostgreSQL → SQS (prod) or inline (debug) |
| **Real-time streaming** | Agent → Redis PUBLISH → WebSocket SUBSCRIBE → Browser |
| **Tool execution** | Agent → Tool Registry → External API (GitHub/Jira/DataDog) |
| **RAG search** | Agent → Voyage embed query → pgvector cosine + ts_rank → RRF merge |
| **Cost tracking** | Agent iteration → Redis INCR cost:{team}:{date} |
| **Evaluation** | Task complete → fire-and-forget → Haiku grades → PostgreSQL |
