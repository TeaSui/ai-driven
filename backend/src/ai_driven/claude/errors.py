"""Claude-specific exceptions.

Currently exposes:
- ClaudeRefusalError: raised by the agent loop when Claude returns stop_reason="refusal".
  The skill documentation at ~/.claude/skills/building-with-the-claude-api/references/basics.md
  is explicit: on refusal, reset / remove the refused turn rather than retry — continuing
  keeps triggering refusals. We surface this as a typed exception so callers choose how to
  recover (fresh conversation, fallback model, user-visible error).
"""
from __future__ import annotations


class ClaudeRefusalError(RuntimeError):
    """Claude refused to continue generating (stop_reason="refusal").

    Not a retry-with-backoff condition — the streaming safety classifier rejected the
    output. Caller must rebuild the conversation or surface the failure to the user.
    """

    def __init__(
        self,
        message: str,
        *,
        model: str,
        refused_iteration: int,
        conversation_length: int,
    ) -> None:
        self.model = model
        self.refused_iteration = refused_iteration
        self.conversation_length = conversation_length
        super().__init__(
            f"Claude refusal at iteration {refused_iteration} "
            f"(model={model}, messages={conversation_length}): {message}"
        )
