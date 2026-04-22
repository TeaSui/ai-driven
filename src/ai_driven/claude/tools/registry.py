"""Tool registry — register, lookup, and execute tools for Claude."""

from __future__ import annotations

import logging
from typing import Any, Callable, Awaitable

logger = logging.getLogger(__name__)

ToolHandler = Callable[..., Awaitable[Any]]


class ToolDefinition:
    """A registered tool with its schema and handler."""

    def __init__(
        self,
        name: str,
        description: str,
        input_schema: dict[str, Any],
        handler: ToolHandler,
    ) -> None:
        self.name = name
        self.description = description
        self.input_schema = input_schema
        self.handler = handler

    @property
    def schema(self) -> dict[str, Any]:
        return {
            "name": self.name,
            "description": self.description,
            "input_schema": self.input_schema,
        }


class ToolRegistry:
    """Registry for tools available to Claude agents."""

    def __init__(self) -> None:
        self._tools: dict[str, ToolDefinition] = {}

    def register(
        self,
        name: str,
        description: str,
        input_schema: dict[str, Any],
        handler: ToolHandler,
    ) -> None:
        if name in self._tools:
            logger.warning(f"Overwriting existing tool: {name}")
        self._tools[name] = ToolDefinition(name, description, input_schema, handler)

    def tool(
        self,
        name: str,
        description: str,
        input_schema: dict[str, Any],
    ) -> Callable[[ToolHandler], ToolHandler]:
        """Decorator to register a tool handler."""
        def decorator(func: ToolHandler) -> ToolHandler:
            self.register(name, description, input_schema, func)
            return func
        return decorator

    def get_schemas(self, tool_names: list[str] | None = None) -> list[dict[str, Any]]:
        if tool_names is None:
            return [t.schema for t in self._tools.values()]
        return [self._tools[n].schema for n in tool_names if n in self._tools]

    def get_tool(self, name: str) -> ToolDefinition | None:
        return self._tools.get(name)

    async def execute(self, name: str, input_data: dict[str, Any]) -> Any:
        tool = self._tools.get(name)
        if tool is None:
            raise ValueError(f"Unknown tool: {name}")

        logger.debug(f"Executing tool {name} with input: {input_data}")
        return await tool.handler(**input_data)

    @property
    def tool_names(self) -> list[str]:
        return list(self._tools.keys())

    def __len__(self) -> int:
        return len(self._tools)
