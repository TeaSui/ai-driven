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
- [Accessing resources (client)](#accessing-resources-client)
- [Designing prompts (server)](#designing-prompts-server)
- [Using prompts (client)](#using-prompts-client)
- [Tools vs resources vs prompts](#tools-vs-resources-vs-prompts)
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

## Accessing resources (client)

Servers expose resources; clients fetch them with `read_resource(uri)`. Unlike tool calls (Claude-driven) or prompts (user-driven), reading a resource is **app-driven** — your code decides when context needs to be pulled in.

```python
import json
from pydantic import AnyUrl

class MCPClient:
    # ... __aenter__/__aexit__ omitted, see "Implementing a client" above

    async def list_resources(self) -> list:
        result = await self.session().list_resources()
        return result.resources

    async def list_resource_templates(self) -> list:
        result = await self.session().list_resource_templates()
        return result.resourceTemplates

    async def read_resource(self, uri: str) -> dict | str:
        result = await self.session().read_resource(AnyUrl(uri))
        resource = result.contents[0]
        if resource.mimeType == "application/json":
            return json.loads(resource.text)
        return resource.text  # text/plain or other
```

Typical "@mention" UX: autocomplete from `list_resources()`; on submit, `read_resource(uri)` and inject the content into the user's message before sending it to Claude.

```python
async def chat_with_mention(client, mention_uri: str, user_text: str) -> str:
    doc = await client.read_resource(mention_uri)
    user_message = f"Reference document:\n{doc}\n\nUser question: {user_text}"
    return await call_claude(user=user_message)
```

## Designing prompts (server)

Prompts are pre-built, parameter-driven instruction templates the server publishes for users to invoke (slash commands, menu items). Treat them as the server's curated "best ways to use my tools."

```python
from mcp.server.fastmcp import FastMCP
from mcp.server.fastmcp.prompts import base
from pydantic import Field

mcp = FastMCP("docs-server")

@mcp.prompt(
    name="summarize_doc",
    description="Summarize a document by id with a target length.",
)
def summarize_doc(
    doc_id: str = Field(description="ID from docs://documents"),
    length: str  = Field(default="short", description="short | medium | long"),
) -> list[base.Message]:
    return [
        base.UserMessage(
            f"Summarize the document with id '{doc_id}' at {length} length. "
            f"Use the read_doc tool to fetch it first."
        )
    ]
```

Design notes:
- Prompts can return one or more messages and can pre-stage tool guidance, system context, and few-shot examples.
- Parameters become kwargs — name them clearly, give descriptions, default optional ones.
- Aim prompts at *your server's* tasks. Don't try to be a general assistant — that's the host app's job.
- Test in the MCP Inspector before shipping; verify variable interpolation.

## Using prompts (client)

The client lists prompts, asks the user to fill arguments, fetches the materialised prompt, then sends it to Claude.

```python
class MCPClient:
    async def list_prompts(self) -> list:
        result = await self.session().list_prompts()
        return result.prompts

    async def get_prompt(self, name: str, arguments: dict) -> list[dict]:
        result = await self.session().get_prompt(name, arguments)
        # result.messages is a list of base.Message (UserMessage / AssistantMessage)
        return [
            {"role": m.role, "content": m.content.text if hasattr(m.content, "text") else m.content}
            for m in result.messages
        ]
```

Wire-up sketch:
1. User types `/summarize_doc` (or picks from a menu populated by `list_prompts`).
2. App reads each argument's description, prompts the user to fill them.
3. Client calls `get_prompt("summarize_doc", {"doc_id": "report.pdf", "length": "short"})`.
4. App passes those messages to Claude (with the server's tools attached, since the prompt may reference them).

## Tools vs resources vs prompts

Same MCP plumbing, different control planes:

| Capability | Who triggers it | Typical UX |
|---|---|---|
| **Tools** | Claude (model-controlled) | Claude emits `tool_use`; the loop runs it |
| **Resources** | The app (app-controlled) | App reads when context is needed (e.g., user @-mentions a doc) |
| **Prompts** | The user (user-controlled) | User picks a slash command; app fills params; sends to Claude |

A well-designed MCP server typically ships **all three**: tools to act, resources to read context, prompts to teach users the canonical recipes.

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
- **Don't model resources as tools.** A `read_doc` tool that takes a `doc_id` is a smell — Claude has to know it can call it. Resources are pulled by the *app* in response to a UI signal (an @-mention) and injected into context.
- **Prompts collapse "how do I use this server?" UX.** Servers without prompts force users to figure out the tool/resource recipes themselves. If a workflow takes more than two tool calls, ship it as a prompt.
- **`get_prompt` returns templated `Message` objects, not strings.** Strip them into `{role, content}` dicts before handing to the API.
- **Resource MIME type drives parsing.** `application/json` deserialises automatically (in your client wrapper); `text/plain` stays raw. Mismatched MIME types are silent corruption.

## Sources

- `docs/building-with-the-claude-api/46-introducing-MCP.md`
- `docs/building-with-the-claude-api/47-MCP-clients.md`
- `docs/building-with-the-claude-api/48-project-setup.md`
- `docs/building-with-the-claude-api/49-the-server-inspector.md`
- `docs/building-with-the-claude-api/50-implementing-a-client.md`
- `docs/building-with-the-claude-api/51-defining-resources.md`
- `docs/building-with-the-claude-api/52-accessing-resources.md`
- `docs/building-with-the-claude-api/53-designing-prompts.md`
- `docs/building-with-the-claude-api/54-prompts-in-the-client.md`
- `docs/building-with-the-claude-api/55-MCP-review.md`
