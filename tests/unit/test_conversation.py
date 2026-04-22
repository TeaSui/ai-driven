"""Tests for conversation state management."""

from ai_driven.claude.conversation import Conversation


def test_add_messages():
    conv = Conversation()
    conv.add_user_message("Hello")
    conv.add_assistant_message("Hi there!")
    conv.add_user_message("How are you?")

    assert len(conv.messages) == 3
    assert conv.turn_count == 2


def test_get_messages_with_limit():
    conv = Conversation()
    for i in range(10):
        conv.add_user_message(f"User message {i}")
        conv.add_assistant_message(f"Assistant message {i}")

    messages = conv.get_messages(max_turns=3)
    assert len(messages) == 6  # 3 turns = 6 messages


def test_serialization():
    conv = Conversation()
    conv.system_prompt = "You are helpful."
    conv.add_user_message("Test")
    conv.total_input_tokens = 100
    conv.total_output_tokens = 50

    data = conv.to_dict()
    restored = Conversation.from_dict(data)

    assert restored.system_prompt == "You are helpful."
    assert len(restored.messages) == 1
    assert restored.total_input_tokens == 100
    assert restored.total_output_tokens == 50


def test_needs_summarization():
    conv = Conversation()
    assert not conv.needs_summarization()

    for i in range(25):
        conv.add_user_message(f"Message {i}")
        conv.add_assistant_message(f"Reply {i}")

    assert conv.needs_summarization()
