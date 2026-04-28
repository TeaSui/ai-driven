"""Manages MCP server connections and discovery of tools, resources, and prompts.

Resources and prompts are first-class MCP capabilities alongside tools (curriculum
lessons 51–54). They have different *control planes*:

  * Tools — model-driven. Claude emits ``tool_use`` and the loop runs them.
  * Resources — app-driven. Your code calls ``read_resource(uri)`` when context
    is needed (e.g. user @-mentions a doc, app pulls config at task start).
  * Prompts — user-driven. Your UI surfaces ``list_prompts()`` as a slash-menu;
    the server materialises the chosen prompt with caller-supplied arguments.

This module exposes all three. Failures in any one are isolated — a server
that exposes only tools still works, a server that fails to advertise prompts
still has its tools registered.
"""

from __future__ import annotations

import json
import logging
from dataclasses import dataclass, field
from typing import Any

from ai_driven.mcp.config import McpServerConfig, get_mcp_servers
from ai_driven.observability.trace_context import new_span

logger = logging.getLogger(__name__)


@dataclass(frozen=True)
class McpResource:
    """Server-published data resource. Read-side equivalent of a tool."""

    uri: str
    name: str
    description: str = ""
    mime_type: str = ""
    server: str = ""


@dataclass(frozen=True)
class McpPrompt:
    """Server-published prompt template the user can invoke."""

    name: str
    description: str = ""
    arguments: list[dict[str, Any]] = field(default_factory=list)
    server: str = ""


class McpClientManager:
    """Manages lifecycle of MCP server connections."""

    def __init__(self) -> None:
        self._sessions: dict[str, Any] = {}
        self._tools: dict[str, list[dict[str, Any]]] = {}
        self._server_for_tool: dict[str, str] = {}
        # Resources and prompts cached per server. Built once during connect;
        # callers can re-query with `refresh_*` if a server pushes updates.
        self._resources: dict[str, list[McpResource]] = {}
        self._prompts: dict[str, list[McpPrompt]] = {}

    async def connect_all(self) -> None:
        """Connect to all configured MCP servers."""
        servers = get_mcp_servers()
        for server_config in servers:
            try:
                await self._connect_server(server_config)
            except Exception:
                logger.warning("Failed to connect to MCP server: %s", server_config.name)

    async def _connect_server(self, config: McpServerConfig) -> None:
        """Connect to a single MCP server via stdio."""
        try:
            from mcp import ClientSession, StdioServerParameters
            from mcp.client.stdio import stdio_client
        except ImportError:
            logger.warning("mcp_package_missing", extra={"server": config.name})
            return

        server_params = StdioServerParameters(
            command=config.command[0],
            args=config.command[1:],
            env=config.env or None,
        )

        transport = stdio_client(server_params)
        read_stream, write_stream = await transport.__aenter__()

        session = ClientSession(read_stream, write_stream)
        await session.__aenter__()
        await session.initialize()

        # Discover tools
        tools_result = await session.list_tools()
        tool_schemas = []
        for tool in tools_result.tools:
            schema = {
                "name": f"mcp_{config.name}_{tool.name}",
                "description": tool.description or f"MCP tool: {tool.name}",
                "input_schema": tool.inputSchema if hasattr(tool, 'inputSchema') else {"type": "object", "properties": {}},
            }
            tool_schemas.append(schema)
            self._server_for_tool[schema["name"]] = config.name

        self._sessions[config.name] = session
        self._tools[config.name] = tool_schemas

        # Best-effort resource/prompt discovery. Servers may legitimately
        # expose only tools, so a failure here must not unregister the server.
        resources = await self._safe_list_resources(session, config.name)
        prompts = await self._safe_list_prompts(session, config.name)
        self._resources[config.name] = resources
        self._prompts[config.name] = prompts

        logger.info(
            "mcp_server_connected",
            extra={
                "server": config.name,
                "tools_discovered": len(tool_schemas),
                "resources_discovered": len(resources),
                "prompts_discovered": len(prompts),
            },
        )

    @staticmethod
    async def _safe_list_resources(session: Any, server_name: str) -> list[McpResource]:
        try:
            result = await session.list_resources()
        except Exception as exc:  # noqa: BLE001 — capability is optional
            logger.debug("mcp_list_resources_unavailable", extra={"server": server_name, "error": str(exc)})
            return []
        out: list[McpResource] = []
        for res in getattr(result, "resources", []) or []:
            out.append(
                McpResource(
                    uri=str(getattr(res, "uri", "")),
                    name=getattr(res, "name", "") or "",
                    description=getattr(res, "description", "") or "",
                    mime_type=getattr(res, "mimeType", "") or "",
                    server=server_name,
                )
            )
        return out

    @staticmethod
    async def _safe_list_prompts(session: Any, server_name: str) -> list[McpPrompt]:
        try:
            result = await session.list_prompts()
        except Exception as exc:  # noqa: BLE001
            logger.debug("mcp_list_prompts_unavailable", extra={"server": server_name, "error": str(exc)})
            return []
        out: list[McpPrompt] = []
        for prompt in getattr(result, "prompts", []) or []:
            args_raw = getattr(prompt, "arguments", []) or []
            args: list[dict[str, Any]] = []
            for arg in args_raw:
                args.append({
                    "name": getattr(arg, "name", "") or "",
                    "description": getattr(arg, "description", "") or "",
                    "required": bool(getattr(arg, "required", False)),
                })
            out.append(
                McpPrompt(
                    name=getattr(prompt, "name", "") or "",
                    description=getattr(prompt, "description", "") or "",
                    arguments=args,
                    server=server_name,
                )
            )
        return out

    def get_tool_schemas(self, server_name: str | None = None) -> list[dict[str, Any]]:
        """Get tool schemas, optionally filtered by server."""
        if server_name:
            return self._tools.get(server_name, [])
        return [tool for tools in self._tools.values() for tool in tools]

    async def call_tool(self, tool_name: str, arguments: dict[str, Any]) -> Any:
        """Call an MCP tool by its prefixed name."""
        server_name = self._server_for_tool.get(tool_name)
        if not server_name or server_name not in self._sessions:
            return {"error": f"No MCP server found for tool: {tool_name}"}

        # Strip the mcp_{server}_ prefix to get the original tool name
        prefix = f"mcp_{server_name}_"
        original_name = tool_name[len(prefix):] if tool_name.startswith(prefix) else tool_name

        session = self._sessions[server_name]
        with new_span(f"mcp:{server_name}:{original_name}"):
            logger.info("mcp_tool_call_start", extra={"server": server_name, "tool": original_name})
            result = await session.call_tool(original_name, arguments)
            logger.info("mcp_tool_call_end", extra={"server": server_name, "tool": original_name})

        # Extract text content from MCP result
        if hasattr(result, 'content') and result.content:
            texts = []
            for block in result.content:
                if hasattr(block, 'text'):
                    texts.append(block.text)
            return {"result": "\n".join(texts)} if texts else {"result": str(result)}
        return {"result": str(result)}

    # --- resources ----------------------------------------------------

    def list_resources(self, server_name: str | None = None) -> list[McpResource]:
        """Return all known resources, or just those from one server."""
        if server_name is not None:
            return list(self._resources.get(server_name, []))
        return [r for rs in self._resources.values() for r in rs]

    async def read_resource(
        self, uri: str, server_name: str | None = None
    ) -> dict[str, Any] | str:
        """Fetch a resource's contents.

        If `server_name` is omitted we route by URI scheme to the first server
        that advertised a resource with the same URI. Returns parsed JSON when
        the MIME type is application/json, otherwise the raw text.
        """
        target_server = server_name or self._server_for_resource(uri)
        if target_server is None or target_server not in self._sessions:
            return {"error": f"No MCP server found for resource: {uri}"}

        session = self._sessions[target_server]
        try:
            from pydantic import AnyUrl  # type: ignore[import-not-found]
        except ImportError:  # pragma: no cover — pydantic is a hard dep
            return {"error": "pydantic not available — cannot construct resource URI"}

        with new_span(f"mcp:{target_server}:resource"):
            logger.info("mcp_resource_read_start", extra={"server": target_server, "uri": uri})
            result = await session.read_resource(AnyUrl(uri))
            logger.info("mcp_resource_read_end", extra={"server": target_server, "uri": uri})

        contents = getattr(result, "contents", None) or []
        if not contents:
            return {"result": ""}
        first = contents[0]
        text = getattr(first, "text", None) or ""
        mime = (getattr(first, "mimeType", "") or "").lower()
        if mime == "application/json":
            try:
                return json.loads(text)
            except json.JSONDecodeError:
                logger.warning(
                    "mcp_resource_bad_json",
                    extra={"server": target_server, "uri": uri},
                )
        return text

    def _server_for_resource(self, uri: str) -> str | None:
        for server_name, resources in self._resources.items():
            if any(r.uri == uri for r in resources):
                return server_name
        # Fallback: route by URI scheme prefix advertised in any resource.
        for server_name, resources in self._resources.items():
            for r in resources:
                if "://" in uri and "://" in r.uri and r.uri.split("://", 1)[0] == uri.split("://", 1)[0]:
                    return server_name
        return None

    # --- prompts ------------------------------------------------------

    def list_prompts(self, server_name: str | None = None) -> list[McpPrompt]:
        if server_name is not None:
            return list(self._prompts.get(server_name, []))
        return [p for ps in self._prompts.values() for p in ps]

    async def get_prompt(
        self,
        name: str,
        arguments: dict[str, Any] | None = None,
        server_name: str | None = None,
    ) -> list[dict[str, Any]]:
        """Materialise a server-published prompt with caller-supplied args.

        Returns a list of ``{"role": ..., "content": ...}`` messages ready to
        feed straight into the Claude API.
        """
        target_server = server_name or self._server_for_prompt(name)
        if target_server is None or target_server not in self._sessions:
            return [{"role": "user", "content": f"[mcp error] no server provides prompt {name!r}"}]

        session = self._sessions[target_server]
        with new_span(f"mcp:{target_server}:prompt"):
            logger.info("mcp_prompt_get_start", extra={"server": target_server, "prompt": name})
            result = await session.get_prompt(name, arguments or {})
            logger.info("mcp_prompt_get_end", extra={"server": target_server, "prompt": name})

        messages: list[dict[str, Any]] = []
        for msg in getattr(result, "messages", []) or []:
            role = getattr(msg, "role", "user") or "user"
            content_obj = getattr(msg, "content", "")
            text = getattr(content_obj, "text", None)
            if text is None:
                text = str(content_obj)
            messages.append({"role": role, "content": text})
        return messages

    def _server_for_prompt(self, name: str) -> str | None:
        for server_name, prompts in self._prompts.items():
            if any(p.name == name for p in prompts):
                return server_name
        return None

    async def disconnect_all(self) -> None:
        """Disconnect from all MCP servers."""
        for name, session in self._sessions.items():
            try:
                await session.__aexit__(None, None, None)
            except Exception:
                logger.debug("Error disconnecting MCP server %s", name)
        self._sessions.clear()
        self._tools.clear()
        self._server_for_tool.clear()
        self._resources.clear()
        self._prompts.clear()


# Singleton instance
_manager: McpClientManager | None = None


async def get_mcp_manager() -> McpClientManager:
    """Get or create the singleton MCP client manager."""
    global _manager
    if _manager is None:
        _manager = McpClientManager()
        await _manager.connect_all()
    return _manager
