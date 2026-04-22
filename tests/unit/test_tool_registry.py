"""Tests for the Claude tool registry."""

import pytest

from ai_driven.claude.tools.registry import ToolRegistry


@pytest.fixture
def registry():
    return ToolRegistry()


def test_register_tool(registry):
    async def handler(x: int) -> int:
        return x * 2

    registry.register(
        name="double",
        description="Doubles a number",
        input_schema={"type": "object", "properties": {"x": {"type": "integer"}}, "required": ["x"]},
        handler=handler,
    )

    assert "double" in registry.tool_names
    assert len(registry) == 1


def test_get_schemas(registry):
    async def handler(**kwargs):
        return {}

    registry.register("tool_a", "Tool A", {"type": "object", "properties": {}}, handler)
    registry.register("tool_b", "Tool B", {"type": "object", "properties": {}}, handler)

    schemas = registry.get_schemas()
    assert len(schemas) == 2
    assert schemas[0]["name"] == "tool_a"

    filtered = registry.get_schemas(["tool_b"])
    assert len(filtered) == 1
    assert filtered[0]["name"] == "tool_b"


@pytest.mark.asyncio
async def test_execute_tool(registry):
    async def add(a: int, b: int) -> int:
        return a + b

    registry.register(
        name="add",
        description="Adds two numbers",
        input_schema={
            "type": "object",
            "properties": {"a": {"type": "integer"}, "b": {"type": "integer"}},
            "required": ["a", "b"],
        },
        handler=add,
    )

    result = await registry.execute("add", {"a": 3, "b": 4})
    assert result == 7


@pytest.mark.asyncio
async def test_execute_unknown_tool(registry):
    with pytest.raises(ValueError, match="Unknown tool"):
        await registry.execute("nonexistent", {})


def test_decorator_registration(registry):
    @registry.tool(
        name="greet",
        description="Greets someone",
        input_schema={"type": "object", "properties": {"name": {"type": "string"}}, "required": ["name"]},
    )
    async def greet(name: str) -> str:
        return f"Hello, {name}!"

    assert "greet" in registry.tool_names
    tool = registry.get_tool("greet")
    assert tool is not None
    assert tool.description == "Greets someone"
