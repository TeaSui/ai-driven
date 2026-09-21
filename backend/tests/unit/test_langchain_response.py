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


def test_bedrock_stop_reason_key_is_recognized():
    """ChatBedrockConverse reports stop reason under response_metadata["stopReason"]
    (AWS Bedrock Converse API's native camelCase key), not the "stop_reason"
    snake_case key ChatAnthropic uses. Before this fix, parse_ai_message only
    read "stop_reason", so every Bedrock response silently defaulted to
    stop_reason="end_turn" — has_tool_use (which is `stop_reason == "tool_use"`)
    was always False for Bedrock, so run_agent_loop always treated a real
    tool_use response as "no more tool calls" and returned without ever
    executing the tool, no matter how many iterations were allowed.
    """
    msg = AIMessage(
        content=[{"type": "tool_use", "id": "t1", "name": "git.create_branch",
                  "input": {"branch_name": "feature/x"}}],
        response_metadata={"stopReason": "tool_use", "model_name": "claude-sonnet-4-6"},
        usage_metadata={"input_tokens": 50, "output_tokens": 10, "total_tokens": 60},
    )
    r = parse_ai_message(msg, requested_model="claude-sonnet-4-6")
    assert r.stop_reason == "tool_use"
    assert r.has_tool_use
    assert r.tool_use_blocks == [ToolUseBlock(id="t1", name="git.create_branch",
                                              input={"branch_name": "feature/x"})]


def test_bedrock_streamed_tool_input_string_is_parsed_to_dict():
    """ChatBedrockConverse's streamed AIMessageChunk accumulation (via `+`)
    leaves content[].input as the raw accumulated JSON string — LangChain
    re-parses args into tool_calls[].args as a dict, but does not also
    re-parse content[].input. Anthropic direct and Bedrock's non-streaming
    ainvoke() already hand back a dict here. Without normalizing this,
    ToolRegistry.execute()'s `tool.handler(**input_data)` raises
    TypeError("argument of type 'str' is not a mapping") for every
    Bedrock streamed tool call.
    """
    msg = AIMessage(
        content=[{"type": "tool_use", "id": "t1", "name": "git.create_branch",
                  "input": '{"branch_name": "feature/x"}'}],
        response_metadata={"stopReason": "tool_use"},
    )
    r = parse_ai_message(msg, requested_model="m")
    assert r.tool_use_blocks == [ToolUseBlock(id="t1", name="git.create_branch",
                                              input={"branch_name": "feature/x"})]
    # raw_content feeds the next turn's assistant-message replay — the
    # provider API expects `input` as an object there too, not a string.
    assert r.raw_content[0]["input"] == {"branch_name": "feature/x"}


def test_malformed_tool_input_string_degrades_to_empty_dict():
    msg = AIMessage(
        content=[{"type": "tool_use", "id": "t1", "name": "git.create_branch",
                  "input": "not valid json"}],
        response_metadata={"stopReason": "tool_use"},
    )
    r = parse_ai_message(msg, requested_model="m")
    assert r.tool_use_blocks[0].input == {}


def test_anthropic_stop_reason_key_still_takes_precedence_when_both_present():
    """Belt-and-braces: if a future langchain-anthropic version ever added a
    "stopReason" key alongside "stop_reason", the existing snake_case key
    must still win so ChatAnthropic behavior is unchanged.
    """
    msg = AIMessage(
        content=[{"type": "text", "text": "hi"}],
        response_metadata={"stop_reason": "end_turn", "stopReason": "tool_use"},
    )
    r = parse_ai_message(msg, requested_model="m")
    assert r.stop_reason == "end_turn"
