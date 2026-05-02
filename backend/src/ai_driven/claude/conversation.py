"""Conversation state management for Claude agents.

Handles message history, context window management, and conversation persistence.
"""

from __future__ import annotations

import json
import logging
from dataclasses import dataclass, field
from typing import Any

logger = logging.getLogger(__name__)

MAX_CONVERSATION_TURNS = 50
SUMMARIZE_AFTER_TURNS = 20


@dataclass
class Conversation:
    """Manages a conversation's message history with context window optimization."""

    messages: list[dict[str, Any]] = field(default_factory=list)
    system_prompt: str | None = None
    total_input_tokens: int = 0
    total_output_tokens: int = 0

    def add_user_message(self, content: str | list[dict[str, Any]]) -> None:
        self.messages.append({"role": "user", "content": content})

    def add_assistant_message(self, content: str | list[Any]) -> None:
        self.messages.append({"role": "assistant", "content": content})

    def add_tool_results(self, results: list[dict[str, Any]]) -> None:
        self.messages.append({"role": "user", "content": results})

    @property
    def turn_count(self) -> int:
        return len([m for m in self.messages if m["role"] == "user"])

    def get_messages(self, max_turns: int | None = None) -> list[dict[str, Any]]:
        """Return messages, optionally truncated to the last N turns."""
        if max_turns is None or len(self.messages) <= max_turns * 2:
            return self.messages
        return self.messages[-(max_turns * 2):]

    def needs_summarization(self) -> bool:
        return self.turn_count > SUMMARIZE_AFTER_TURNS

    def to_dict(self) -> dict[str, Any]:
        return {
            "messages": self.messages,
            "system_prompt": self.system_prompt,
            "total_input_tokens": self.total_input_tokens,
            "total_output_tokens": self.total_output_tokens,
        }

    @classmethod
    def from_dict(cls, data: dict[str, Any]) -> Conversation:
        conv = cls()
        conv.messages = data.get("messages", [])
        conv.system_prompt = data.get("system_prompt")
        conv.total_input_tokens = data.get("total_input_tokens", 0)
        conv.total_output_tokens = data.get("total_output_tokens", 0)
        return conv
