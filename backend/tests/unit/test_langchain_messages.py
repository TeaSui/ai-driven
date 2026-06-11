"""backend/tests/unit/test_langchain_messages.py"""
from langchain_core.messages import AIMessage, HumanMessage

from ai_driven.claude.langchain_messages import to_langchain_messages


def test_plain_user_string():
    out = to_langchain_messages([{"role": "user", "content": "hello"}])
    assert out == [HumanMessage(content="hello")]


def test_assistant_blocks_pass_through_verbatim():
    blocks = [
        {"type": "thinking", "thinking": "hmm", "signature": "sig-abc123"},
        {"type": "text", "text": "I'll check the repo."},
        {"type": "tool_use", "id": "toolu_1", "name": "jira.get_issue",
         "input": {"key": "KAN-1"}},
    ]
    out = to_langchain_messages([{"role": "assistant", "content": blocks}])
    assert isinstance(out[0], AIMessage)
    assert out[0].content == blocks          # verbatim — signature preserved


def test_tool_result_user_turn_passes_through():
    content = [{"type": "tool_result", "tool_use_id": "toolu_1",
                "content": "issue body"}]
    out = to_langchain_messages([{"role": "user", "content": content}])
    assert isinstance(out[0], HumanMessage)
    assert out[0].content == content


def test_mixed_conversation_order_preserved():
    msgs = [
        {"role": "user", "content": "do the task"},
        {"role": "assistant", "content": [{"type": "text", "text": "ok"}]},
        {"role": "user", "content": [{"type": "tool_result",
                                      "tool_use_id": "t1", "content": "r"}]},
    ]
    out = to_langchain_messages(msgs)
    assert [type(m) for m in out] == [HumanMessage, AIMessage, HumanMessage]
