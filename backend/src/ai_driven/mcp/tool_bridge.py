"""Bridge MCP tools into the agent ToolRegistry."""

from __future__ import annotations

import logging
from typing import Any

from ai_driven.claude.tools.registry import ToolRegistry
from ai_driven.mcp.client_manager import get_mcp_manager

logger = logging.getLogger(__name__)


async def register_mcp_tools(
    registry: ToolRegistry,
    server_names: list[str] | None = None,
) -> int:
    """Register MCP tools into a ToolRegistry.

    Args:
        registry: The tool registry to add MCP tools to.
        server_names: Optional list of MCP server names to include.
            If None, all connected servers' tools are registered.

    Returns:
        Number of tools registered.
    """
    try:
        manager = await get_mcp_manager()
    except Exception:
        logger.warning("MCP manager unavailable — no MCP tools registered")
        return 0

    schemas = manager.get_tool_schemas()
    registered = 0

    for schema in schemas:
        tool_name = schema["name"]

        # Filter by server name if specified
        if server_names:
            server = tool_name.split("_")[1] if "_" in tool_name else ""
            if server not in server_names:
                continue

        # Create a closure for this specific tool
        async def _call_mcp_tool(_name: str = tool_name, **kwargs: Any) -> Any:
            return await manager.call_tool(_name, kwargs)

        registry.tool(
            name=tool_name,
            description=schema["description"],
            input_schema=schema.get("input_schema", {"type": "object", "properties": {}}),
        )(_call_mcp_tool)
        registered += 1

    if registered > 0:
        logger.info("Registered %d MCP tools", registered)
    return registered
