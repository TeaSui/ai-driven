"""Unit tests for MCP resources & prompts (curriculum lessons 51–54).

The MCP package is optional in the dev env, so we hand the manager
hand-built fakes that satisfy the duck-typed interface
(`list_resources`, `read_resource`, `list_prompts`, `get_prompt`).
"""

from __future__ import annotations

from types import SimpleNamespace
from typing import Any
from unittest.mock import AsyncMock

import pytest

from ai_driven.mcp.client_manager import McpClientManager, McpPrompt, McpResource


# --- fakes that quack like the mcp.ClientSession ----------------------

def _resource(uri: str, name: str, mime: str = "application/json") -> SimpleNamespace:
    return SimpleNamespace(uri=uri, name=name, description="", mimeType=mime)


def _prompt(name: str, args: list[dict[str, Any]] | None = None) -> SimpleNamespace:
    arg_objs = [
        SimpleNamespace(
            name=a["name"],
            description=a.get("description", ""),
            required=a.get("required", False),
        )
        for a in (args or [])
    ]
    return SimpleNamespace(name=name, description="", arguments=arg_objs)


def _resource_contents(text: str, mime: str = "application/json") -> SimpleNamespace:
    return SimpleNamespace(contents=[SimpleNamespace(text=text, mimeType=mime)])


def _prompt_messages(role_text_pairs: list[tuple[str, str]]) -> SimpleNamespace:
    msgs = [
        SimpleNamespace(role=role, content=SimpleNamespace(text=text))
        for role, text in role_text_pairs
    ]
    return SimpleNamespace(messages=msgs)


def _make_manager_with_resources_and_prompts() -> tuple[McpClientManager, AsyncMock]:
    """Manager pre-loaded as if it had connected to a 'docs-server'."""
    session = AsyncMock()
    mgr = McpClientManager()
    mgr._sessions["docs-server"] = session  # noqa: SLF001 — test injection
    mgr._resources["docs-server"] = [
        McpResource(
            uri="docs://documents/report.pdf",
            name="report.pdf",
            description="Q4 financial report",
            mime_type="application/json",
            server="docs-server",
        ),
        McpResource(
            uri="docs://documents/runbook.md",
            name="runbook.md",
            description="On-call runbook",
            mime_type="text/plain",
            server="docs-server",
        ),
    ]
    mgr._prompts["docs-server"] = [
        McpPrompt(
            name="summarize_doc",
            description="Summarize a document by id",
            arguments=[
                {"name": "doc_id", "description": "Document id", "required": True},
                {"name": "length", "description": "short|medium|long", "required": False},
            ],
            server="docs-server",
        ),
    ]
    return mgr, session


# --- listing -----------------------------------------------------------

def test_list_resources_unfiltered_returns_all() -> None:
    mgr, _ = _make_manager_with_resources_and_prompts()
    assert {r.uri for r in mgr.list_resources()} == {
        "docs://documents/report.pdf",
        "docs://documents/runbook.md",
    }


def test_list_resources_filtered_by_server() -> None:
    mgr, _ = _make_manager_with_resources_and_prompts()
    assert len(mgr.list_resources(server_name="docs-server")) == 2
    assert mgr.list_resources(server_name="missing") == []


def test_list_prompts() -> None:
    mgr, _ = _make_manager_with_resources_and_prompts()
    prompts = mgr.list_prompts()
    assert len(prompts) == 1
    assert prompts[0].name == "summarize_doc"
    assert prompts[0].arguments[0]["required"] is True


# --- read_resource -----------------------------------------------------

async def test_read_resource_parses_json() -> None:
    mgr, session = _make_manager_with_resources_and_prompts()
    session.read_resource = AsyncMock(
        return_value=_resource_contents('{"k": "v"}', "application/json")
    )
    result = await mgr.read_resource("docs://documents/report.pdf")
    assert result == {"k": "v"}


async def test_read_resource_returns_raw_text_for_non_json() -> None:
    mgr, session = _make_manager_with_resources_and_prompts()
    session.read_resource = AsyncMock(
        return_value=_resource_contents("Page-by-page on-call steps", "text/plain")
    )
    result = await mgr.read_resource("docs://documents/runbook.md")
    assert result == "Page-by-page on-call steps"


async def test_read_resource_unknown_uri_returns_error() -> None:
    mgr, _ = _make_manager_with_resources_and_prompts()
    result = await mgr.read_resource("nope://whatever")
    assert isinstance(result, dict) and "error" in result


async def test_read_resource_routes_by_uri_scheme_when_uri_unknown() -> None:
    """A URI we never listed but with a scheme our server uses still routes."""
    mgr, session = _make_manager_with_resources_and_prompts()
    session.read_resource = AsyncMock(
        return_value=_resource_contents("hello", "text/plain")
    )
    result = await mgr.read_resource("docs://documents/new-doc.md")
    assert result == "hello"


async def test_read_resource_handles_bad_json_gracefully() -> None:
    mgr, session = _make_manager_with_resources_and_prompts()
    session.read_resource = AsyncMock(
        return_value=_resource_contents("not { valid", "application/json")
    )
    # Falls back to raw text rather than crashing.
    result = await mgr.read_resource("docs://documents/report.pdf")
    assert result == "not { valid"


# --- get_prompt --------------------------------------------------------

async def test_get_prompt_returns_role_content_dicts() -> None:
    mgr, session = _make_manager_with_resources_and_prompts()
    session.get_prompt = AsyncMock(
        return_value=_prompt_messages(
            [
                ("user", "Summarize document with id 'report.pdf' at short length."),
            ]
        )
    )
    messages = await mgr.get_prompt("summarize_doc", {"doc_id": "report.pdf", "length": "short"})
    assert messages == [
        {"role": "user", "content": "Summarize document with id 'report.pdf' at short length."}
    ]


async def test_get_prompt_unknown_name_returns_friendly_message() -> None:
    mgr, _ = _make_manager_with_resources_and_prompts()
    messages = await mgr.get_prompt("nonexistent", {})
    assert messages and "no server provides prompt" in messages[0]["content"]


async def test_get_prompt_supports_explicit_server_name() -> None:
    mgr, session = _make_manager_with_resources_and_prompts()
    session.get_prompt = AsyncMock(
        return_value=_prompt_messages([("user", "ok")])
    )
    messages = await mgr.get_prompt("summarize_doc", {"doc_id": "x"}, server_name="docs-server")
    assert messages == [{"role": "user", "content": "ok"}]
    session.get_prompt.assert_awaited_once_with("summarize_doc", {"doc_id": "x"})


# --- discovery is best-effort ------------------------------------------

async def test_safe_list_resources_swallows_unsupported() -> None:
    """Server that doesn't implement resources returns []  rather than raising."""
    session = AsyncMock()
    session.list_resources = AsyncMock(side_effect=AttributeError("not supported"))
    out = await McpClientManager._safe_list_resources(session, "minimal-server")
    assert out == []


async def test_safe_list_prompts_swallows_unsupported() -> None:
    session = AsyncMock()
    session.list_prompts = AsyncMock(side_effect=Exception("nope"))
    out = await McpClientManager._safe_list_prompts(session, "minimal-server")
    assert out == []
