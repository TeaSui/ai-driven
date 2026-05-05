# MCP (Model Context Protocol)

Two distinct paths:

1. **MCP connector** — use remote MCP servers directly from the Messages API (no client to build). Easiest path for consuming hosted MCP servers.
2. **MCP client / server development** — build your own with the Python MCP SDK (`FastMCP`). Required when you need resources, prompts, sampling, or local stdio transport.

## Contents

- [MCP connector (recommended for consumers)](#mcp-connector)
- [Remote MCP servers (catalog)](#remote-mcp-servers)
- [Building your own: architecture](#architecture-client-vs-server)
- [Transports](#transports)
- [Implementing a client](#implementing-a-client)
- [Defining resources (server)](#defining-resources-server)
- [Accessing resources (client)](#accessing-resources-client)
- [Designing prompts (server)](#designing-prompts-server)
- [Using prompts (client)](#using-prompts-client)
- [Tools vs resources vs prompts](#tools-vs-resources-vs-prompts)
- [The MCP Inspector](#the-mcp-inspector)
- [Gotchas](#gotchas)
- [Sources](#sources)

## MCP connector

Beta `mcp-client-2025-11-20` (supersedes `mcp-client-2025-04-04`). Connect remote MCP servers straight from the Messages API — Anthropic handles the MCP protocol. **Only `tool_use` / `tool_result` is supported; no resources, prompts, or sampling.**

```python
response = client.beta.messages.create(
    model="claude-opus-4-7", max_tokens=2048,
    betas=["mcp-client-2025-11-20"],
    mcp_servers=[{
        "type": "url",
        "url": "https://example.modelcontextprotocol.io/sse",
        "name": "example-mcp",            # unique per toolset
        "authorization_token": "YOUR_TOKEN",
    }],
    tools=[{
        "type": "mcp_toolset",
        "mcp_server_name": "example-mcp", # exactly one toolset per server name
        "default_config": {"enabled": True, "defer_loading": False},
        "configs": {"noisy_tool": {"enabled": False}},  # per-tool overrides
        "cache_control": {"type": "ephemeral"},
    }],
    messages=[{"role": "user", "content": "What tools do you have?"}],
)
```

Gotchas:
- HTTPS / SSE only — no stdio for remote servers.
- **Not on Bedrock / Vertex.**
- NOT ZDR-eligible.
- Each `mcp_server_name` referenced by exactly one toolset.
- `defer_loading: True` on the toolset works with tool search to avoid loading the whole tool list upfront.
- MCP tools cannot be called programmatically (`allowed_callers` doesn't support sandbox callers).
- OAuth token rotation is your responsibility — the API doesn't refresh.

## Remote MCP servers

A catalog of vetted public MCP servers is maintained at `modelcontextprotocol/servers` on GitHub — GitHub, Slack, Linear, Sentry, etc. These are third-party hosted. Review each provider's security and terms before pointing the connector at them.

## Architecture: client vs server

If the MCP connector isn't enough (you need resources, prompts, stdio, or private on-host servers), build one side of the protocol yourself.

- **MCP Server** — exposes tools, prompts, and resources. Authored once by a service provider, consumed by many apps.
- **MCP Client** — connects to one or more servers, forwards tool lists to Claude, executes tool calls against the server when Claude asks.

Request flow inside a typical app:
1. Your app receives a user message.
2. Your MCP client asks the server `ListToolsRequest`.
3. App sends tools to Claude with the user message.
4. Claude returns `tool_use`.
5. Your MCP client sends `CallToolRequest` to the server.
6. Server returns `CallToolResult`.
7. App sends `tool_result` back to Claude.
8. Claude answers.

**You typically build the server OR the client, not both.** Building both for production is rare.

## Transports

- **stdio** — server as a subprocess; stdin/stdout pipes. Default for local dev and CLI tools.
- **HTTP / SSE** — remote service over HTTP or server-sent events.
- **WebSocket** — persistent bidirectional.

Client code is largely transport-agnostic; only session construction changes.

## Implementing a client

Using the Python MCP SDK:

```python
from mcp import ClientSession, types
from mcp.client.stdio import StdioServerParameters, stdio_client

class MCPClient:
    def __init__(self, command, args):
        self.command, self.args = command, args

    async def __aenter__(self):
        self._transport_cm = stdio_client(
            StdioServerParameters(command=self.command, args=self.args))
        read, write = await self._transport_cm.__aenter__()
        self._session_cm = ClientSession(read, write)
        self._session = await self._session_cm.__aenter__()
        await self._session.initialize()
        return self

    async def __aexit__(self, exc_type, exc, tb):
        await self._session_cm.__aexit__(exc_type, exc, tb)
        await self._transport_cm.__aexit__(exc_type, exc, tb)

    async def list_tools(self):
        return (await self._session.list_tools()).tools

    async def call_tool(self, name: str, args: dict):
        return await self._session.call_tool(name, args)

# Usage
async with MCPClient(command="uv", args=["run", "mcp_server.py"]) as c:
    tools = await c.list_tools()
    result = await c.call_tool("read_doc_contents", {"doc_id": "report.pdf"})
```

`async with` is mandatory — otherwise the stdio subprocess leaks.

## Defining resources (server)

Resources = data (GET-shaped). Tools = actions (POST-shaped). Clients read via URI.

```python
from mcp.server.fastmcp import FastMCP

mcp = FastMCP("docs-server")
docs = {"deposition.md": "Testimony from Jane Doe...", "report.pdf": "Financial report Q4..."}

@mcp.resource("docs://documents", mime_type="application/json")
def list_docs() -> list[str]:
    return list(docs.keys())

@mcp.resource("docs://documents/{doc_id}", mime_type="text/plain")
def fetch_doc(doc_id: str) -> str:
    if doc_id not in docs:
        raise ValueError(f"Doc {doc_id} not found")
    return docs[doc_id]
```

Two kinds of URIs: **direct** (`docs://documents`) and **templated** (`docs://documents/{doc_id}`). Templated params become kwargs — name them the same in the URI and function signature.

## Accessing resources (client)

Tool calls are model-driven; resource reads are **app-driven** — your code decides when context needs injecting (e.g., user @-mention).

```python
import json
from pydantic import AnyUrl

async def read_resource(session, uri: str):
    result = await session.read_resource(AnyUrl(uri))
    r = result.contents[0]
    if r.mimeType == "application/json":
        return json.loads(r.text)
    return r.text
```

Typical UX: autocomplete from `list_resources()` → on submit, `read_resource(uri)` → inject content into the user message before sending to Claude.

## Designing prompts (server)

Prompts = curated, parameterized instruction templates ("best ways to use this server"). Triggered by the user (slash command / menu).

```python
from mcp.server.fastmcp import FastMCP
from mcp.server.fastmcp.prompts import base
from pydantic import Field

mcp = FastMCP("docs-server")

@mcp.prompt(name="summarize_doc",
            description="Summarize a document by id at a given length.")
def summarize_doc(
    doc_id: str = Field(description="ID from docs://documents"),
    length: str  = Field(default="short", description="short | medium | long"),
) -> list[base.Message]:
    return [base.UserMessage(
        f"Summarize document '{doc_id}' at {length} length. Use read_doc first.")]
```

## Using prompts (client)

```python
async def get_prompt(session, name, args):
    result = await session.get_prompt(name, args)
    return [{"role": m.role,
             "content": m.content.text if hasattr(m.content, "text") else m.content}
            for m in result.messages]
```

Wire-up: user picks slash command → app reads arg descriptions → prompts user → `get_prompt(...)` → pass messages to Claude with the server's tools attached.

## Tools vs resources vs prompts

Same MCP plumbing; different control planes.

| | Who triggers | Typical UX |
|---|---|---|
| **Tools** | Claude (model) | Model emits `tool_use`; loop executes |
| **Resources** | App (code) | App reads on @-mention; injects into context |
| **Prompts** | User | Slash command / menu; app fills params |

A mature server ships all three: tools to act, resources to read context, prompts to teach users canonical recipes.

## The MCP Inspector

```bash
uv run mcp dev mcp_server.py
```

Starts a dev server on a local port and opens a browser harness. Test Tools → Resources → Prompts without wiring a client.

## Gotchas

- **Prefer the MCP connector** unless you need resources / prompts / stdio — dramatically less code.
- **The connector only supports tool_use.** Resources, prompts, and sampling require building your own client.
- **Build one side, not both.** Unless you're specifically learning, pick server or client.
- **`async with` is mandatory for custom clients** — stdio subprocess leaks otherwise.
- **Tool descriptions still drive selection.** MCP tools are forwarded to Claude as normal tool schemas.
- **Don't model GET-shaped operations as tools.** A `read_doc(doc_id)` tool is a code smell — make it a resource.
- **MIME type is advisory** (serializer uses return type); set it so clients render correctly.
- **Templated URI params = function kwargs.** Names must match.
- **Prompts collapse "how do I use this server?" UX.** If a workflow takes >2 tool calls, ship it as a prompt.
- **`get_prompt` returns templated Message objects**, not strings. Convert to `{role, content}` dicts.

## Sources

- `../docs/mcp/remote-mcp-servers.md`
- `../docs/mcp/mcp-connector.md`
- MCP SDK reference (official modelcontextprotocol docs — FastMCP patterns)
