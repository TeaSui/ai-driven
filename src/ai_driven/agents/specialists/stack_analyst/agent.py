"""StackAnalyst specialist — multi-label classification of task stacks.

Second live specialist after :class:`BusinessAnalystSpecialist`. Follows
the T8 template: XML-tagged system prompt, ``_build_user_prompt``
delegates to ``_build_initial_message`` + a short role-specific trailer,
class body stays minimal and declares class attributes only when they
override a :class:`SpecialistBase` default.

StackAnalyst-specific overrides (diff against BA, intentional):

  * ``model_setting_attr = "claude_fast_model"`` — stack classification
    is a short fixed-set label pick, not open-ended reasoning; route it
    to the fast-model slot rather than the default one so the tier
    setting can diverge without touching specialists.
  * ``max_output_tokens = 1024`` — the StackAnalystPayload is tiny
    (<200 tokens in practice); 1024 is plenty of headroom while
    cutting the ceiling on runaway generations.
  * ``_register_tools`` / ``_get_tool_names`` are overridden to expose
    exactly one tool — ``repo.overview`` — so the specialist can peek
    at the target repo's tree + manifest files before classifying.
    This is the ONLY specialist that registers any tools in Phase 1
    (revision §5).

Not overridden (inherits base behaviour by design):

  * ``_build_initial_message`` — base-class-only method; concrete
    specialists MUST NOT override it per revision §6.
  * The verdict='block' forbiddance is enforced at the *prompt* level
    (see ``stack_analyst.py``'s ``<decision_rules>`` and
    ``<anti_patterns>``) rather than at the Python level. The
    :class:`StackAnalystPayload` schema allows any
    :data:`Verdict`, so a block would still parse; the prompt is the
    load-bearing guard. Rationale: adding a runtime verdict check here
    would duplicate constraints the base already validates for shape,
    and would silently rewrite the model's output instead of surfacing
    a prompt regression.
"""
from __future__ import annotations

from typing import Any

from ai_driven.agents._shared.repo_tools import build_repo_overview
from ai_driven.agents.specialists.base import SpecialistBase, SpecialistContext
from ai_driven.claude.prompts.specialists.stack_analyst import (
    STACK_ANALYST_SYSTEM_PROMPT,
)


_SCHEMA_REMINDER = (
    "Re-emit ONLY the StackAnalyst artifact JSON inside one ```json fence, "
    "with verdict in {pass, pass_with_notes}, reasoning, and a payload "
    "containing stacks (non-empty subset of "
    "backend/frontend/mobile/devops/data/ai/aws), primary_stack (member of "
    "stacks), cross_cutting_concerns, reasoning. NEVER return verdict='block'."
)


class StackAnalystSpecialist(SpecialistBase):
    """StackAnalyst — classifies which of the 7 stacks a task touches.

    Accepts an optional ``scm_client`` so the registered ``repo.overview``
    tool can actually list the target repo's tree and manifests. When
    ``scm_client`` is ``None`` (e.g. unit tests that mock out ``chat``
    entirely and never trigger tool execution), the tool is still
    registered for schema exposure but returns a clear error sentinel if
    invoked, matching the :class:`CodingAgent` pattern.
    """

    agent_type = "specialist_stack_analyst"
    role = "stack_analyst"
    # Fast-model slot (Sonnet post-H1). Short fixed-set classify — not a
    # reasoning task, so don't burn the default-model slot on it.
    model_setting_attr = "claude_fast_model"
    # StackAnalystPayload is small; cap generations tighter than the
    # base default 2048.
    max_output_tokens = 1024

    def __init__(
        self,
        scm_client: Any = None,
        **kwargs: Any,
    ) -> None:
        # Stash the SCM client BEFORE calling super().__init__, because
        # BaseAgent.__init__ invokes _register_tools() which references it.
        self._scm = scm_client
        super().__init__(**kwargs)

    # --- tool registration ----------------------------------------------

    def _register_tools(self) -> None:
        """Register the single read-only tool StackAnalyst is allowed.

        Mirrors the ``repo.overview`` shape from
        :class:`ai_driven.agents.coding.agent.CodingAgent` so specialists
        and the coding agent converge on one tool definition. Kept
        deliberately minimal — this specialist does NOT get
        ``repo.list_files`` or ``repo.read_file`` (revision §5).
        """
        @self._registry.tool(
            name="repo.overview",
            description=(
                "Return a compact directory tree of the repo plus top-level "
                "manifests (package.json / pyproject.toml / go.mod / "
                "Cargo.toml / etc.). Call this ONCE before emitting your "
                "JSON so stack classification is grounded in actual "
                "manifest files rather than ticket wording alone."
            ),
            input_schema={
                "type": "object",
                "properties": {
                    "repo_full_name": {"type": "string"},
                    "max_depth": {
                        "type": "integer",
                        "default": 3,
                        "description": (
                            "How deep to walk the tree. 3 is usually "
                            "enough; 5 max."
                        ),
                    },
                    "ref": {"type": "string", "default": "main"},
                },
                "required": ["repo_full_name"],
            },
        )
        async def repo_overview(
            repo_full_name: str, max_depth: int = 3, ref: str = "main",
        ) -> dict[str, Any]:
            if not self._scm:
                return {"error": "SCM client not configured"}
            return await build_repo_overview(
                self._scm, repo_full_name, max_depth, ref,
            )

    def _get_tool_names(self) -> list[str]:
        return ["repo.overview"]

    # --- subclass contract ----------------------------------------------

    def _system_prompt(self) -> str:
        return STACK_ANALYST_SYSTEM_PROMPT

    def _schema_reminder(self) -> str:
        return _SCHEMA_REMINDER

    def _build_user_prompt(self, ctx: SpecialistContext) -> str:
        # Delegate envelope construction to the base class (revision §5,
        # §6). Do NOT re-serialise ctx.ticket / ctx.repo / BA artifact
        # here — the base already renders all three inside the XML
        # envelope with user-content escaping, and inlining our own
        # json.dumps would bypass that escaping and reintroduce the
        # prompt-injection vector the envelope tests lock against.
        base = self._build_initial_message(ctx)
        return (
            base
            + "\n\n<task>Call repo.overview once to inspect the target "
            "repo's tree and manifest files, then classify which of "
            "{backend, frontend, mobile, devops, data, ai, aws} this task "
            "touches. Emit a single ```json fenced block per "
            "<output_schema>. NEVER return verdict='block'.</task>"
        )
