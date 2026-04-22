"""Manages MCP server connections and tool discovery."""

from __future__ import annotations

import logging
from typing import Any

from ai_driven.mcp.config import McpServerConfig, get_mcp_servers
from ai_driven.observability.trace_context import new_span

logger = logging.getLogger(__name__)


class McpClientManager:
    """Manages lifecycle of MCP server connections."""

    def __init__(self) -> None:
        self._sessions: dict[str, Any] = {}
        self._tools: dict[str, list[dict[str, Any]]] = {}
        self._server_for_tool: dict[str, str] = {}

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
        logger.info("mcp_server_connected", extra={"server": config.name, "tools_discovered": len(tool_schemas)})

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


# Singleton instance
_manager: McpClientManager | None = None


async def get_mcp_manager() -> McpClientManager:
    """Get or create the singleton MCP client manager."""
    global _manager
    if _manager is None:
        _manager = McpClientManager()
        await _manager.connect_all()
    return _manager
