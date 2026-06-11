"""backend/tests/unit/test_langchain_response.py"""
from langchain_core.messages import AIMessage

from ai_driven.claude.langchain_response import parse_ai_message
from ai_driven.claude.provider import TextBlock, ThinkingBlock, ToolUseBlock


def _ai_message(content, stop_reason="end_turn", usage=None):
    return AIMessage(
        content=content,
        response_metadata={"stop_reason": stop_reason, "model": "claude-sonnet-4-6"},
        usage_metadata=usage or {
            "input_tokens": 100, "output_tokens": 20, "total_tokens": 120,
            "input_token_details": {"cache_read": 80, "cache_creation": 10},
        },
    )


def test_text_only():
    r = parse_ai_message(_ai_message([{"type": "text", "text": "hi"}]),
                         requested_model="claude-sonnet-4-6")
    assert r.content == [TextBlock(text="hi")]
    assert r.stop_reason == "end_turn"
    assert r.usage.input_tokens == 100
    assert r.usage.output_tokens == 20
    assert r.usage.cache_read_input_tokens == 80
    assert r.usage.cache_creation_input_tokens == 10


def test_tool_use_sets_blocks_and_raw_content():
    blocks = [
        {"type": "thinking", "thinking": "...", "signature": "sig-1"},
        {"type": "tool_use", "id": "t1", "name": "jira.get_issue",
         "input": {"key": "KAN-1"}},
    ]
    r = parse_ai_message(_ai_message(blocks, stop_reason="tool_use"),
                         requested_model="claude-sonnet-4-6")
    assert r.has_tool_use
    assert r.tool_use_blocks == [ToolUseBlock(id="t1", name="jira.get_issue",
                                              input={"key": "KAN-1"})]
    assert any(isinstance(b, ThinkingBlock) for b in r.content)
    # raw_content is the replay carrier (design §5 Phase 1a): must be the
    # verbatim block dicts INCLUDING the thinking signature.
    assert r.raw_content == blocks


def test_string_content_normalized_to_text_block():
    r = parse_ai_message(_ai_message("plain string"), requested_model="m")
    assert r.content == [TextBlock(text="plain string")]
    assert r.raw_content == [{"type": "text", "text": "plain string"}]


def test_missing_usage_degrades_to_zero():
    msg = AIMessage(content="x", response_metadata={"stop_reason": "end_turn"},
                    usage_metadata=None)
    r = parse_ai_message(msg, requested_model="m")
    assert r.usage.input_tokens == 0
    assert r.usage.output_tokens == 0
