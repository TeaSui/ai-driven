"""backend/tests/unit/test_langchain_provider.py"""
from unittest.mock import AsyncMock, MagicMock, patch

import pytest
from langchain_core.messages import AIMessage

from ai_driven.claude.langchain_provider import LangChainProvider
from ai_driven.config import ClaudeProviderType


def _fake_chat_model(reply: AIMessage):
    model = MagicMock()
    model.ainvoke = AsyncMock(return_value=reply)
    bound = MagicMock()
    bound.ainvoke = AsyncMock(return_value=reply)
    model.bind_tools = MagicMock(return_value=bound)
    return model


@pytest.mark.asyncio
async def test_create_message_returns_parsed_response():
    reply = AIMessage(
        content=[{"type": "text", "text": "done"}],
        response_metadata={"stop_reason": "end_turn", "model": "claude-sonnet-4-6"},
        usage_metadata={"input_tokens": 5, "output_tokens": 2, "total_tokens": 7},
    )
    provider = LangChainProvider(ClaudeProviderType.ANTHROPIC)
    with patch.object(provider, "_build_chat_model",
                      return_value=_fake_chat_model(reply)) as build:
        resp = await provider.create_message(
            model="claude-sonnet-4-6", max_tokens=1024,
            messages=[{"role": "user", "content": "go"}],
            system="sys", temperature=0.5,
        )
    assert resp.text == "done"
    assert resp.usage.input_tokens == 5
    build.assert_called_once()
    kwargs = build.call_args.kwargs
    assert kwargs["model"] == "claude-sonnet-4-6"
    assert kwargs["max_tokens"] == 1024
    assert kwargs["temperature"] == 0.5


@pytest.mark.asyncio
async def test_tools_are_bound_with_anthropic_schemas():
    reply = AIMessage(content="ok", response_metadata={}, usage_metadata=None)
    fake = _fake_chat_model(reply)
    provider = LangChainProvider(ClaudeProviderType.ANTHROPIC)
    tools = [{"name": "jira.get_issue", "description": "d",
              "input_schema": {"type": "object", "properties": {}}}]
    with patch.object(provider, "_build_chat_model", return_value=fake):
        await provider.create_message(
            model="m", max_tokens=10,
            messages=[{"role": "user", "content": "go"}], tools=tools,
        )
    fake.bind_tools.assert_called_once()
    bound_tools = fake.bind_tools.call_args.args[0]
    assert bound_tools[0]["name"] == "jira.get_issue"


@pytest.mark.asyncio
async def test_system_prompt_prepended_as_system_message():
    reply = AIMessage(content="ok", response_metadata={}, usage_metadata=None)
    fake = _fake_chat_model(reply)
    provider = LangChainProvider(ClaudeProviderType.ANTHROPIC)
    with patch.object(provider, "_build_chat_model", return_value=fake):
        await provider.create_message(
            model="m", max_tokens=10, system="be terse",
            messages=[{"role": "user", "content": "go"}],
        )
    sent = fake.ainvoke.call_args.args[0]
    from langchain_core.messages import SystemMessage
    assert isinstance(sent[0], SystemMessage)
