"""BusinessAnalyst specialist — resolves ambiguity, writes Gherkin ACs.

First live specialist (template for stack_analyst, security, qa). The
subclass contract per :class:`SpecialistBase`:

  * ``_system_prompt`` returns the XML-tagged prompt string (>=1024 tokens
    so ``cache_control: ephemeral`` is attached automatically by the
    provider layer — revision §4).
  * ``_build_user_prompt`` MUST call ``self._build_initial_message(ctx)``
    as the first portion of the user message and append role-specific
    trailing instructions. The base renders the XML envelope with
    user-content escaping; duplicating the ticket/repo JSON here would
    bypass that escaping and reintroduce the prompt-injection vector
    test_specialist_base tests lock against.
  * Model routing goes through ``model_setting_attr`` — no hardcoded
    model literals (revision §1).
"""
from __future__ import annotations

from ai_driven.agents.specialists.base import SpecialistBase, SpecialistContext
from ai_driven.claude.prompts.specialists.business_analyst import (
    BUSINESS_ANALYST_SYSTEM_PROMPT,
)


_SCHEMA_REMINDER = (
    "Re-emit ONLY the BA artifact JSON inside one ```json fence, with "
    "verdict, reasoning, and a payload containing ambiguities_resolved, "
    "acceptance_criteria, out_of_scope, blocking_questions."
)


class BusinessAnalystSpecialist(SpecialistBase):
    """BusinessAnalyst — resolves ticket ambiguity, writes Gherkin ACs.

    Convention: specialists declare class attributes ONLY when they
    override a :class:`SpecialistBase` default. BA matches the base
    defaults (``model_setting_attr = "claude_default_model"`` — a
    Settings-resolved reference, not a literal, so a team-level model
    flip propagates without touching specialists; ``max_output_tokens
    = 16384`` post-T-A1), so neither is restated here. Subclasses that need a
    different model (e.g. ``stack_analyst`` on ``claude_fast_model``)
    set ``model_setting_attr`` explicitly — the diff against this
    template then clearly shows the override.
    """

    agent_type = "specialist_business_analyst"
    role = "business_analyst"

    def _system_prompt(self) -> str:
        return BUSINESS_ANALYST_SYSTEM_PROMPT

    def _schema_reminder(self) -> str:
        return _SCHEMA_REMINDER

    def _build_user_prompt(self, ctx: SpecialistContext) -> str:
        # Delegate envelope construction to the base class so every
        # specialist renders the same XML shape with the same user-content
        # escaping (revision §5, §6). Append only the role-specific
        # trailing instruction that disambiguates 'emit JSON now' from the
        # generic ``<instruction>`` already inside the envelope.
        base = self._build_initial_message(ctx)
        return (
            base
            + "\n\n<task>Produce the BusinessAnalyst artifact JSON per "
            "<output_schema>. Emit a single ```json fenced block and "
            "stop. Do NOT add prose around it.</task>"
        )
