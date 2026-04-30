"""Security specialist — STRIDE threat model + implementation_rules for CodingAgent.

Third live specialist after :class:`BusinessAnalystSpecialist` (T8) and
:class:`StackAnalystSpecialist` (T9). Follows the T8 template: XML-tagged
system prompt, ``_build_user_prompt`` delegates to
``_build_initial_message`` + a short role-specific trailer, class body
stays minimal and declares class attributes ONLY when they override a
:class:`SpecialistBase` default.

Security uniqueness (vs BA / StackAnalyst):

  * Reads TWO prior artifacts — business_analyst (for acceptance criteria
    the threats must defend) AND stack_analyst (so the STRIDE enumeration
    is scoped to the actual stacks touched; mobile threats are skipped
    on a backend-only change). Both are rendered inside ``<prior_artifacts>``
    by the base-class envelope — the user-prompt trailer just points the
    model at ``.stack_analyst.stacks``.
  * CAN return ``verdict='block'`` (unlike StackAnalyst), but ONLY for
    flatly unsafe requests. The blocker triggers are enumerated in the
    ``<decision_rules>`` of ``security.py`` (plaintext credential storage,
    disabling auth, exposing secrets to logs/PR text, obvious broken
    crypto). Normal risk is expressed via ``implementation_rules`` and
    ``verdict='pass'`` / ``'pass_with_notes'``.
  * Produces ``implementation_rules`` consumed VERBATIM by the CodingAgent.
    The system prompt is explicit that rules are copy-paste-ready
    directives, not vague suggestions.

Not overridden (inherits base behaviour by design):

  * ``model_setting_attr`` — base default ``"claude_default_model"`` is
    correct for Security (Sonnet base default; real reasoning, not a
    short classification). Per the T8 convention, we do NOT restate a
    class attr when it matches the base default.
  * ``max_output_tokens`` — base default 2048 is plenty for
    ``SecurityPayload`` (typical output ~1-1.5k tokens). Not restated.
  * ``_register_tools`` / ``_get_tool_names`` — Security takes no tools
    in Phase 1. The base class already returns ``[]`` / no-op.
  * ``_build_initial_message`` — base-class-only method; concrete
    specialists MUST NOT override it per revision §6.
"""
from __future__ import annotations

from ai_driven.agents.specialists.base import SpecialistBase, SpecialistContext
from ai_driven.claude.prompts.specialists.security import (
    SECURITY_SYSTEM_PROMPT,
)


_SCHEMA_REMINDER = (
    "Re-emit ONLY the Security artifact JSON inside one ```json fence, with "
    "verdict in {pass, pass_with_notes, block}, reasoning, and a payload "
    "containing threat_model (list of {stride_category, threat, severity, "
    "mitigation}), implementation_rules, compliance_notes, blocking_concerns. "
    "Use verdict='block' ONLY for flatly unsafe requests; blocking_concerns "
    "MUST be non-empty in that case and empty otherwise."
)


class SecuritySpecialist(SpecialistBase):
    """Security — STRIDE threat model + copy-paste-ready implementation_rules.

    Convention: specialists declare class attributes ONLY when they
    override a :class:`SpecialistBase` default. Security matches the base
    defaults (``model_setting_attr = "claude_default_model"`` — a
    Settings-resolved reference, not a literal, so a team-level model
    flip propagates without touching specialists; ``max_output_tokens
    = 2048``), so neither is restated here. The only overrides are
    ``agent_type`` and ``role`` which are required for every specialist.
    """

    agent_type = "specialist_security"
    role = "security"

    def _system_prompt(self) -> str:
        return SECURITY_SYSTEM_PROMPT

    def _schema_reminder(self) -> str:
        return _SCHEMA_REMINDER

    def _build_user_prompt(self, ctx: SpecialistContext) -> str:
        # Delegate envelope construction to the base class (revision §5,
        # §6). Both the BA and stack_analyst prior artifacts are rendered
        # inside <prior_artifacts> by _build_initial_message as escaped
        # JSON — do NOT re-serialise them here, that would bypass the
        # user-content escaping the envelope tests lock against.
        base = self._build_initial_message(ctx)
        return (
            base
            + "\n\n<task>Apply STRIDE to the change described in "
            "<jira_ticket>. Focus threats on the stacks listed in "
            "<prior_artifacts>.stack_analyst.stacks. Produce "
            "copy-paste-ready implementation_rules the CodingAgent will "
            "follow verbatim. Emit a single ```json fenced block per "
            "<output_schema>. Return verdict='block' ONLY for flatly "
            "unsafe requests (plaintext credentials, disabling auth, "
            "broken crypto).</task>"
        )
