"""Model-conditional `thinking` parameter resolver.

Anthropic's extended-thinking API has two shapes and which one is accepted depends
on the model:

- `{"type": "adaptive"}` — Opus 4.7 REQUIRES this; manual form returns 400. Also the
  recommended form going forward on all current thinking-capable models.
- `{"type": "enabled", "budget_tokens": N}` — legacy manual form. Still works on
  Sonnet 4.6 / Opus 4.6 but deprecated.
- `None` — no thinking. Use for Haiku 4.5 (doesn't support thinking) or when the
  caller passed budget_tokens=0.

Source: Anthropic docs bundled in the skill at
~/.claude/skills/building-with-the-claude-api/docs/2. Model capabilities/2. Adaptive thinking.md
"""
from __future__ import annotations

from typing import Any

_BEDROCK_PREFIX = "global.anthropic."

# Models that REQUIRE adaptive (manual form returns 400).
_ADAPTIVE_REQUIRED = frozenset({"claude-opus-4-7"})

# Models that still support the manual `enabled`-type form.
_LEGACY_MANUAL = frozenset({"claude-sonnet-4-6", "claude-opus-4-6"})

# Models that don't support thinking at all.
_NO_THINKING = frozenset({"claude-haiku-4-5", "claude-haiku-4-5-20251001"})


def resolve_thinking_config(model: str, *, budget_tokens: int) -> dict[str, Any] | None:
    """Return the correct `thinking` parameter shape for this model, or None to omit.

    Args:
        model: Model ID. Accepts both app-level (`claude-opus-4-7`) and Bedrock-prefixed
            (`global.anthropic.claude-opus-4-7`) forms.
        budget_tokens: Desired thinking budget when manual config is accepted. Ignored
            under adaptive. A value of 0 suppresses thinking entirely (returns None).

    Returns:
        dict suitable for `messages.create(thinking=...)`, or None if thinking should
        be omitted entirely.
    """
    if budget_tokens <= 0:
        return None

    normalized = model.removeprefix(_BEDROCK_PREFIX)

    if normalized in _NO_THINKING:
        return None
    if normalized in _LEGACY_MANUAL:
        return {"type": "enabled", "budget_tokens": budget_tokens}
    # Opus 4.7 and any model we don't recognize: default to adaptive.
    # Forward-compatible — new models almost certainly use adaptive too.
    return {"type": "adaptive"}
