# Feature 7: MCP Integration

## Purpose

Connect AI agents to external tool providers via the Model Context Protocol (MCP). Extends agent capabilities beyond built-in tools by bridging MCP server tools into the agent's tool registry at runtime.

## When It Activates

- `AI_DRIVEN_MCP_ENABLED=true` in config
- CodingAgent calls `_register_mcp_tools()` before each `run()`
- MCP tools appear alongside built-in tools with prefix `mcp_{server}_{tool}`

## Supported MCP Servers

| Server | Config Flag | Tools Provided |
|--------|------------|---------------|
| GitHub MCP Server | `AI_DRIVEN_MCP_GITHUB_ENABLED` | Enhanced GitHub operations (issues, PRs, code search) |
| Context7 | `AI_DRIVEN_MCP_CONTEXT7_ENABLED` | Library/framework documentation lookup |

## Architecture

```
CodingAgent.run()
        │
        ▼
_register_mcp_tools()
        │
        ▼
┌─────────────────────────────────┐
│      McpClientManager           │
│  (singleton, stdio transport)   │
│                                 │
│  ┌───────────┐  ┌────────────┐  │
│  │  GitHub    │  │  Context7  │  │
│  │  Server    │  │  Server    │  │
│  └─────┬─────┘  └─────┬──────┘  │
│        │              │          │
│        ▼              ▼          │
│  ┌──────────────────────────┐   │
│  │     tool_bridge.py       │   │
│  │  Wraps MCP tools as      │   │
│  │  ToolRegistry handlers   │   │
│  └──────────────────────────┘   │
└─────────────────────────────────┘
        │
        ▼
Agent ToolRegistry (built-in + MCP tools)
```

## Components

| File | Purpose |
|------|---------|
| `src/ai_driven/mcp/__init__.py` | Package marker |
| `src/ai_driven/mcp/config.py` | `McpServerConfig` and `get_mcp_servers()` — server configurations |
| `src/ai_driven/mcp/client_manager.py` | `McpClientManager` singleton — manages stdio connections |
| `src/ai_driven/mcp/tool_bridge.py` | `register_mcp_tools()` — bridges MCP tools into ToolRegistry |

## How Tool Bridging Works

1. `McpClientManager` connects to each configured MCP server via stdio transport
2. Fetches tool schemas from each server (`tools/list`)
3. `register_mcp_tools()` wraps each MCP tool as a `ToolRegistry` handler
4. Tool names are prefixed: `mcp_{server_name}_{tool_name}`
5. When the agent calls an MCP tool, the bridge forwards the call to the MCP server

## Configuration

| Variable | Default | Notes |
|----------|---------|-------|
| `AI_DRIVEN_MCP_ENABLED` | `false` | Master switch |
| `AI_DRIVEN_MCP_GITHUB_ENABLED` | `false` | Enable GitHub MCP |
| `AI_DRIVEN_MCP_CONTEXT7_ENABLED` | `false` | Enable Context7 |

## Dependencies

- `mcp>=1.0.0` — Model Context Protocol SDK
