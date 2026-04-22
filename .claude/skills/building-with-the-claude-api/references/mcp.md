# MCP (Model Context Protocol)

MCP clients and servers: architecture, transports, implementing a client, defining tools and resources, and the inspector.

## Contents

- [What MCP is](#what-mcp-is)
- [Architecture: client vs server](#architecture-client-vs-server)
- [Transports](#transports)
- [Message types](#message-types)
- [End-to-end request flow](#end-to-end-request-flow)
- [Implementing a client](#implementing-a-client)
- [Defining resources (server)](#defining-resources-server)
- [The MCP Inspector](#the-mcp-inspector)
- [Gotchas](#gotchas)
- [Sources](#sources)

## What MCP is

Model Context Protocol is a standardized way for Claude (and other LLMs) to get **tools**, **prompts**, and **resources** from external servers without the application developer writing custom integrations for every service.

The value: instead of you writing 40 GitHub tool schemas + implementations, you connect an MCP client to an off-the-shelf MCP server for GitHub. The MCP server ships the tool schemas and implementations; you just consume them.

MCP is complementary to, not a replacement for, tool use. Claude still receives a tool schema and emits `tool_use` blocks — MCP just moves the authoring of those tools to a separate server process.

## Architecture: client vs server

Two components. In most real projects you build **one or the other**, not both:

- **MCP Server** — exposes tools, prompts, and resources. Authored once by a service provider (e.g., GitHub MCP server), consumed by many apps.
- **MCP Client** — connects to one or more MCP servers, forwards tool lists to Claude, and executes tool calls on the server when Claude requests them.

Inside a typical app server:
1. Your app logic receives a user message.
2. Your MCP client asks the MCP server "what tools do you have?" (`ListToolsRequest`).
3. Your app sends those tools to Claude along with the user message.
4. Claude responds with a `tool_use`.
5. Your MCP client forwards that to the MCP server (`CallToolRequest`).
6. The MCP server executes and returns a `CallToolResult`.
7. Your app sends the result back to Claude, which answers the user.

## Transports

MCP is transport-agnostic. Common setups:
- **stdio** — server runs as a subprocess; messages pass over stdin/stdout. Default for local dev and CLI tools.
- **HTTP** / SSE — server runs as a remote service; client connects over HTTP or server-sent events.
- **WebSocket** — bidirectional, persistent connection.

Your client code is largely identical across transports — only the session-construction arguments change.

## Message types

The two you'll touch most:
- `ListToolsRequest` → `ListToolsResult` — "what tools do you provide?"
- `CallToolRequest` → `CallToolResult` — "run tool X with arguments Y."

Also common: `ListResourcesRequest`, `ReadResourceRequest`, `ListPromptsRequest`, `GetPromptRequest`.

## End-to-end request flow

User asks "What repositories do I have?" in a chat app wired to a GitHub MCP server:

1. App → MCP client: "get tools."
2. MCP client → MCP server: `ListToolsRequest`.
3. MCP server → MCP client: `ListToolsResult` (tool list).
4. App → Claude API: user message + tool list.
5. Claude → App: `tool_use` block naming `list_repos` with args.
6. App → MCP client: "execute `list_repos`."
7. MCP client → MCP server: `CallToolRequest`.
8. MCP server → GitHub API: (actual HTTP call).
9. GitHub → MCP server: repo data.
10. MCP server → MCP client: `CallToolResult`.
11. App → Claude API: `tool_result` block.
12. Claude → App: final text answer.
13. App → User: rendered answer.

Many steps, one clear responsibility per component. The MCP client abstracts the server-communication plumbing from your app.

## Implementing a client

Using the Python MCP SDK. The typical pattern: wrap the `ClientSession` in a custom class that handles lifecycle (`async with`) and exposes convenience methods.

```python
from mcp import ClientSession, types
from mcp.client.stdio import StdioServerParameters, stdio_client

class MCPClient:
    def __init__(self, command, args):
        self.command = command
        self.args = args
        self._session = None

    async def __aenter__(self):
        self._transport_cm = stdio_client(
            StdioServerParameters(command=self.command, args=self.args)
        )
        read, write = await self._transport_cm.__aenter__()
        self._session_cm = ClientSession(read, write)
        self._session = await self._session_cm.__aenter__()
        await self._session.initialize()
        return self

    async def __aexit__(self, exc_type, exc, tb):
        await self._session_cm.__aexit__(exc_type, exc, tb)
        await self._transport_cm.__aexit__(exc_type, exc, tb)

    def session(self):
        return self._session

    async def list_tools(self) -> list[types.Tool]:
        result = await self.session().list_tools()
        return result.tools

    async def call_tool(
        self, tool_name: str, tool_input: dict
    ) -> types.CallToolResult | None:
        return await self.session().call_tool(tool_name, tool_input)
```

Usage:

```python
async with MCPClient(command="uv", args=["run", "mcp_server.py"]) as client:
    tools = await client.list_tools()
    print(tools)
    result = await client.call_tool("read_doc_contents", {"doc_id": "report.pdf"})
    print(result)
```

The `async with` block is important — it ensures the stdio subprocess and the session are torn down cleanly.

## Defining resources (server)

Resources expose **data** (like GET handlers), while tools expose **actions** (like POST handlers). Clients read resources via a URI.

Two kinds:
- **Direct resources** — static URIs (`docs://documents`).
- **Templated resources** — URIs with parameters (`docs://documents/{doc_id}`). The SDK parses parameters and passes them as kwargs to your function.

```python
from mcp.server.fastmcp import FastMCP

mcp = FastMCP("docs-server")

docs = {
    "deposition.md": "Testimony from Jane Doe...",
    "report.pdf": "Financial report Q4...",
}

@mcp.resource("docs://documents", mime_type="application/json")
def list_docs() -> list[str]:
    return list(docs.keys())

@mcp.resource("docs://documents/{doc_id}", mime_type="text/plain")
def fetch_doc(doc_id: str) -> str:
    if doc_id not in docs:
        raise ValueError(f"Doc with id {doc_id} not found")
    return docs[doc_id]
```

MIME types are hints to clients about the payload format:
- `application/json` — structured data (the SDK serializes return values automatically).
- `text/plain` — raw text.
- Any valid MIME type works.

Resources are ideal for "@mention" style features: list to populate an autocomplete, fetch to pull content when a user submits.

## The MCP Inspector

Run a local inspector that lets you test tools and resources in a browser without wiring up a client.

```bash
uv run mcp dev mcp_server.py
```

Starts a dev server on port 6277 and prints a local URL. In the browser:
1. Click **Connect** to start the MCP server.
2. Navigate to **Tools** → **List Tools** → pick one → fill parameters → **Run Tool**.
3. Navigate to **Resources** / **Resource Templates** to test resource URIs.

Use the inspector as your dev feedback loop — edit the server, test the tool, verify the result — before wiring it into an actual client.

## Gotchas

- **Build one side, not both.** Unless you're specifically teaching/learning, you either build a server (for others to consume) or a client (to consume existing servers). Building both for production is rare.
- **`async with` is mandatory for clients.** Forgetting it leaks the stdio subprocess and leaves the session unclosed — you'll see zombie processes.
- **Tool descriptions still matter.** MCP tools are forwarded to Claude as tool schemas — Claude reads the description to decide when to invoke. Vague descriptions cause tool confusion.
- **`ReadResourceRequest` ≠ `CallToolRequest`.** Resources are for fetching data; tools are for taking actions. Don't model GET-shaped operations as tools.
- **MIME type is advisory, not enforced.** The SDK serializes return values based on Python type (list/dict → JSON, str → text). Set `mime_type` so clients render correctly.
- **Templated URI params become function kwargs.** Name the `{param}` in the URI the same as the function argument — the SDK uses the name to bind.
- **The inspector port may change.** Check the CLI output — don't hard-code 6277.
- **MCP ≠ tool use.** Common misconception. MCP is "someone else wrote the tool"; tool use is the API-level mechanism. You still get `tool_use` blocks from Claude either way.

## Sources

- `docs/building-with-the-claude-api/46-introducing-MCP.md`
- `docs/building-with-the-claude-api/47-MCP-clients.md`
- `docs/building-with-the-claude-api/48-project-setup.md`
- `docs/building-with-the-claude-api/49-the-server-inspector.md`
- `docs/building-with-the-claude-api/50-implementing-a-client.md`
- `docs/building-with-the-claude-api/51-defining-resources.md`
