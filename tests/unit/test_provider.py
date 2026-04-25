"""Tests for Claude provider abstraction."""

from ai_driven.claude.provider import (
    ContentBlock,
    MessageResponse,
    TextBlock,
    TokenUsage,
    ToolUseBlock,
    ThinkingBlock,
)


def test_message_response_text():
    response = MessageResponse(
        content=[TextBlock(text="Hello"), TextBlock(text="World")],
        stop_reason="end_turn",
        usage=TokenUsage(input_tokens=10, output_tokens=5),
        model="claude-sonnet-4-6",
    )
    assert response.text == "Hello\nWorld"
    assert not response.has_tool_use


def test_message_response_tool_use():
    response = MessageResponse(
        content=[
            TextBlock(text="Let me check that"),
            ToolUseBlock(id="tool_1", name="get_issue", input={"key": "PROJ-1"}),
        ],
        stop_reason="tool_use",
        usage=TokenUsage(input_tokens=100, output_tokens=50),
        model="claude-sonnet-4-6",
    )
    assert response.has_tool_use
    assert len(response.tool_use_blocks) == 1
    assert response.tool_use_blocks[0].name == "get_issue"


def test_token_usage():
    usage = TokenUsage(input_tokens=1000, output_tokens=500, cache_read_input_tokens=200)
    assert usage.total_tokens == 1500


def test_message_response_with_thinking():
    response = MessageResponse(
        content=[
            ThinkingBlock(thinking="Let me think about this..."),
            TextBlock(text="The answer is 42"),
        ],
        stop_reason="end_turn",
        usage=TokenUsage(input_tokens=50, output_tokens=30),
        model="claude-opus-4-7",
    )
    assert response.text == "The answer is 42"
    assert not response.has_tool_use
