"""QA specialist — final GO/NO-GO gate across every prior artifact.

Fourth live specialist after :class:`BusinessAnalystSpecialist` (T8),
:class:`StackAnalystSpecialist` (T9), and :class:`SecuritySpecialist`
(T10). Follows the T8 template: XML-tagged system prompt,
``_build_user_prompt`` delegates to ``_build_initial_message`` + a short
role-specific trailer, class body stays minimal and declares class
attributes ONLY when they override a :class:`SpecialistBase` default.

QA uniqueness (vs BA / StackAnalyst / Security):

  * Reads EVERY prior artifact on the task (business_analyst acceptance
    criteria, stack_analyst stacks, security implementation_rules, plus
    any optional specialists that ran). QA is the first fan-in >2 and
    runs LAST in the dispatch sequence. Each AC and each
    implementation_rule becomes one QaCheck entry.
  * Owns THREE read-only tools (revision §5.1):
    ``github.get_pr_diff``, ``repo.read_file``, ``repo.overview``.
    ``repo.read_file`` and ``repo.overview`` mirror the CodingAgent
    shapes via the shared helpers in ``_shared/repo_tools.py`` (lifted
    there in commit ``deb45e0``). ``github.get_pr_diff`` mirrors the
    shape in :class:`ReviewerAgent`. In Phase 1 shadow mode the PR may
    not exist yet; the prompt tells QA to fall back to artifacts-only
    reasoning when a tool call fails or returns empty.
  * CAN return ``verdict='block'`` — but only for concrete failures:
    an unsatisfied AC, a violated security implementation_rule, or
    regression_risk='high' without a concrete mitigation. Style and
    refactoring opinions are ReviewerAgent's territory.

Not overridden (inherits base behaviour by design):

  * ``model_setting_attr`` — base default ``"claude_default_model"`` is
    correct for QA (Sonnet base default; gate reasoning across multiple
    prior artifacts is not a short classification). Per the T8
    convention, we do NOT restate a class attr when it matches the base
    default.
  * ``max_output_tokens`` — base default 16384 (post-T-A1) is plenty
    for ``QaPayload`` even on a long ticket with many ACs. Not restated.
  * ``_build_initial_message`` — base-class-only method; concrete
    specialists MUST NOT override it per revision §6.
"""
from __future__ import annotations

from typing import Any

from ai_driven.agents._shared.repo_tools import (
    build_repo_overview,
    read_files_batched,
)
from ai_driven.agents.specialists.base import SpecialistBase, SpecialistContext
from ai_driven.claude.prompts.specialists.qa import QA_SYSTEM_PROMPT


_SCHEMA_REMINDER = (
    "Re-emit ONLY the QA artifact JSON inside one ```json fence, with "
    "verdict in {pass, pass_with_notes, block}, reasoning, and a payload "
    "containing checks_performed (list of {check, passed, detail}), "
    "test_coverage_gap (list[str]), and regression_risk (one of "
    "low/medium/high). Block ONLY when a concrete AC or security rule is "
    "unmet, or regression_risk is high without mitigation."
)


class QaSpecialist(SpecialistBase):
    """QA — final GO/NO-GO gate across every prior artifact.

    Accepts an optional ``scm_client`` so the three registered read-only
    tools (``github.get_pr_diff``, ``repo.read_file``, ``repo.overview``)
    can actually talk to the backing SCM. When ``scm_client`` is ``None``
    (unit tests that mock ``chat`` entirely and never trigger tool
    execution) the tools are still registered for schema exposure but
    return a clear error sentinel if invoked — matching the StackAnalyst
    and CodingAgent patterns so the envelope test surface stays uniform.
    """

    agent_type = "specialist_qa"
    role = "qa"

    def __init__(
        self,
        scm_client: Any = None,
        **kwargs: Any,
    ) -> None:
        # Stash the SCM client BEFORE calling super().__init__, because
        # BaseAgent.__init__ invokes _register_tools() which references it
        # (same ordering invariant as StackAnalyst T9 and CodingAgent).
        self._scm = scm_client
        super().__init__(**kwargs)

    # --- tool registration ----------------------------------------------

    def _register_tools(self) -> None:
        """Register the three read-only tools QA is allowed (revision §5.1).

        Mirrors the ``repo.read_file`` / ``repo.overview`` shapes from
        :class:`ai_driven.agents.coding.agent.CodingAgent` via the shared
        helpers in ``_shared/repo_tools.py``, and the
        ``github.get_pr_diff`` shape from
        :class:`ai_driven.agents.reviewer.agent.ReviewerAgent`. Kept
        deliberately narrow — no ``repo.list_files``, no
        ``code.search``, no write tools. Phase 2 will add
        ``reviewer.get_comments`` if needed; that is out of scope for T11.
        """

        @self._registry.tool(
            name="github.get_pr_diff",
            description=(
                "Fetch the diff of a pull request so QA can verify that "
                "acceptance_criteria rows are implemented and "
                "implementation_rules are encoded in the patch. In Phase 1 "
                "shadow mode the PR may not exist yet; fall back to "
                "artifacts-only reasoning if this returns an error."
            ),
            input_schema={
                "type": "object",
                "properties": {
                    "repo_full_name": {"type": "string"},
                    "pr_number": {"type": "integer"},
                },
                "required": ["repo_full_name", "pr_number"],
            },
        )
        async def get_pr_diff(
            repo_full_name: str, pr_number: int,
        ) -> dict[str, Any]:
            if not self._scm:
                return {"error": "SCM client not configured"}
            diff = await self._scm.get_pr_diff(repo_full_name, pr_number)
            files = [
                {
                    "path": f.path,
                    "status": f.status,
                    "additions": f.additions,
                    "deletions": f.deletions,
                    "patch": f.patch,
                }
                for f in diff.files
            ]
            return {
                "files": files,
                "total_additions": diff.total_additions,
                "total_deletions": diff.total_deletions,
            }

        @self._registry.tool(
            name="repo.read_file",
            description=(
                "Read the contents of one or more files from the repository "
                "to spot-check that a Security implementation_rule was "
                "actually encoded (e.g. confirm argon2id is the hashing "
                "call). Accepts a single `path` OR `paths` (a list)."
            ),
            input_schema={
                "type": "object",
                "properties": {
                    "repo_full_name": {"type": "string"},
                    "path": {"type": "string"},
                    "paths": {
                        "type": "array",
                        "items": {"type": "string"},
                        "description": (
                            "List of file paths — alternative to `path`. "
                            "Exactly one of path/paths should be set."
                        ),
                    },
                    "ref": {"type": "string", "default": "main"},
                },
                "required": ["repo_full_name"],
            },
        )
        async def read_file(
            repo_full_name: str,
            path: str | None = None,
            paths: list[str] | None = None,
            ref: str = "main",
        ) -> dict[str, Any] | list[dict[str, Any]]:
            if not self._scm:
                return {"error": "SCM client not configured"}
            target_paths = (
                paths if paths is not None else ([path] if path else [])
            )
            if not target_paths:
                return {"error": "must provide `path` or non-empty `paths`"}
            results = await read_files_batched(
                self._scm, repo_full_name, target_paths, ref,
            )
            # Preserve single-path shape for backward compat with the
            # CodingAgent tool; return list for batch.
            if paths is None and len(results) == 1:
                return results[0]
            return results

        @self._registry.tool(
            name="repo.overview",
            description=(
                "Return a compact directory tree of the repo plus top-level "
                "manifests (package.json / pyproject.toml / go.mod / etc.). "
                "Use sparingly for orientation when the PR diff doesn't "
                "reveal enough context."
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
        # Order matches revision §5.1.
        return ["github.get_pr_diff", "repo.read_file", "repo.overview"]

    # --- subclass contract ----------------------------------------------

    def _system_prompt(self) -> str:
        return QA_SYSTEM_PROMPT

    def _schema_reminder(self) -> str:
        return _SCHEMA_REMINDER

    def _build_user_prompt(self, ctx: SpecialistContext) -> str:
        # Delegate envelope construction to the base class (revision §5,
        # §6). Every prior artifact (BA, stack_analyst, security, plus
        # any optional ones) is rendered inside <prior_artifacts> by
        # _build_initial_message as escaped JSON — do NOT re-serialise
        # them here, that would bypass the user-content escaping the
        # envelope tests lock against.
        base = self._build_initial_message(ctx)
        return (
            base
            + "\n\n<task>Run GO/NO-GO gate checks. Verify every "
            "acceptance_criteria entry from "
            "<prior_artifacts>.business_analyst has evidence of being "
            "satisfied, every implementation_rule from "
            "<prior_artifacts>.security has evidence of being followed, "
            "and assess regression_risk. If a PR diff or test results "
            "are available via tools, inspect them; otherwise flag "
            "missing evidence in checks_performed. Emit a single ```json "
            "fenced block per <output_schema>. Block ONLY when a "
            "concrete AC or Security rule is unmet, or regression_risk "
            "is high without justification.</task>"
        )
