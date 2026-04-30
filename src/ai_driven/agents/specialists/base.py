"""Common scaffolding for all specialists.

A specialist is a :class:`BaseAgent` subclass that:
  * accepts a :class:`SpecialistContext` (ticket, repo, prior artifacts),
  * calls Claude once with a role-specific prompt + prefill + stop sequence,
  * validates the output against a Pydantic payload schema from
    ``ai_driven.agents.orchestrator.artifacts.PAYLOAD_SCHEMAS``,
  * returns an :class:`Artifact` (role, verdict, reasoning, payload, tokens).

Stubs (see :class:`StubSpecialist`) override :meth:`run_specialist` to skip
Claude entirely and return ``verdict='not_implemented'``.

The orchestrator (Task 16+) persists the returned Artifact via
:class:`ArtifactRepository` and decides what to do with the verdict
(continue, hard-stop on ``'block'``, etc.).

Revision-driven invariants (docs/specs/2026-04-30-orchestrator-revision.md
and the red-team section of docs/plans/2026-04-29-orchestrator-build.md):

1. No char/4 pre-flight token guess. The SDK and server-side 413s handle
   oversized contexts; the cosmetic check was lying anyway. ``max_input_tokens``
   stays as a class attribute for documentation and future QA reuse.
2. ``specialist.completed`` is emitted on EVERY terminal return path so the
   orchestrator's per-specialist bookkeeping (and UI phase indicators) see
   errors, schema failures, escalations, and stub short-circuits alongside
   successes.
3. Claude responses are guarded on ``stop_reason == "stop_sequence"``. A
   ``max_tokens`` stop means the prefill+stop pattern truncated mid-output
   and JSON parsing may succeed on a plausible-looking but incomplete object;
   we refuse to trust it.
4. :meth:`SpecialistBase._build_initial_message` renders the XML-tagged task
   envelope prescribed by revision §6. Concrete specialists MUST call this
   from their ``_build_user_prompt`` and MUST NOT override it.
5. :meth:`SpecialistBase._resolve_model` routes through ``Settings`` so a
   future ``claude_default_model`` flip propagates automatically. Hardcoded
   model strings in specialists are forbidden.
"""
from __future__ import annotations

import json
import logging
import re
from abc import abstractmethod
from dataclasses import asdict, dataclass, field, is_dataclass
from typing import Any

from pydantic import ValidationError

from ai_driven.agents.base import BaseAgent
from ai_driven.agents.orchestrator.artifacts import (
    Artifact,
    validate_payload,
)
from ai_driven.config import get_settings

logger = logging.getLogger(__name__)


@dataclass
class SpecialistContext:
    """Input envelope for a single specialist dispatch.

    ``prior_artifacts`` is keyed by role (e.g. ``"business_analyst"`` ->
    BA Artifact). The orchestrator builds this dict by reading from
    :class:`ArtifactRepository` before dispatch.
    """

    task_id: str
    # {key, summary, description, labels, acceptance_criteria}
    ticket: dict
    # {full_name, scm_type, default_branch}
    repo: dict
    # 'fast_path' | 'full_workflow' | 'multi_domain'
    tier: str
    prior_artifacts: dict[str, Artifact] = field(default_factory=dict)
    team_settings: dict = field(default_factory=dict)


class SpecialistBase(BaseAgent):
    """Abstract base for live and stub specialists.

    Concrete specialists implement :meth:`_system_prompt`,
    :meth:`_build_user_prompt`, and :meth:`_schema_reminder`. They MAY
    override :attr:`model_setting_attr` to point at a non-default model
    key on :class:`Settings` (e.g. ``"claude_fast_model"``).
    """

    agent_type: str = "specialist"
    role: str = ""
    # Name of a Settings attribute — the resolved value at runtime is the
    # model string passed to Claude. Never hardcode a model literal in a
    # specialist; use an attribute name here so a single settings flip
    # propagates to every specialist.
    model_setting_attr: str = "claude_default_model"
    # Advisory; NOT enforced in run_specialist (see module docstring §1).
    # Retained so future QA / cost tooling can reason about specialist
    # intent without rediscovering it.
    max_input_tokens: int = 8192
    max_output_tokens: int = 2048

    # --- BaseAgent abstract hooks ---------------------------------------

    def _register_tools(self) -> None:
        # Specialists don't take side-effects by default. Subclasses that
        # need read tools (e.g. QA's github.get_pr_diff / repo.read_file)
        # override this.
        return None

    def _get_tool_names(self) -> list[str]:
        return []

    def _get_system_prompt(self, context: dict[str, Any]) -> str:
        return self._system_prompt()

    # --- subclass contract ----------------------------------------------

    @abstractmethod
    def _system_prompt(self) -> str:
        """Role-specific system prompt (cached, >=1024 tokens preferred)."""

    @abstractmethod
    def _build_user_prompt(self, ctx: SpecialistContext) -> str:
        """Render the per-task user message.

        Concrete specialists MUST call ``self._build_initial_message(ctx)``
        as the first portion of the returned string and append any
        role-specific instructions after. Do NOT replace or override
        ``_build_initial_message`` itself — it is a base-class-only method
        that produces the revision-mandated XML envelope.
        """

    @abstractmethod
    def _schema_reminder(self) -> str:
        """Schema-only reminder text used by the JSON repair loop."""

    # --- model resolution ----------------------------------------------

    def _resolve_model(self) -> str:
        """Look up the model string for this specialist via ``Settings``.

        Raises ``ValueError`` if :attr:`model_setting_attr` doesn't resolve
        to a non-empty string on ``Settings``. This is defensive — a silent
        ``None``/default would quietly dispatch every specialist to Sonnet
        regardless of the intended tier and mask a typo in the subclass.
        """
        settings = get_settings()
        attr = self.model_setting_attr
        value = getattr(settings, attr, None)
        if not isinstance(value, str) or not value:
            raise ValueError(
                f"Specialist {type(self).__name__} model_setting_attr="
                f"{attr!r} resolved to {value!r}; must be a non-empty "
                f"model string on Settings."
            )
        return value

    # --- base-class-only: XML envelope ---------------------------------

    def _build_initial_message(self, ctx: SpecialistContext) -> str:  # type: ignore[override]
        """Render the XML-tagged task envelope for this specialist call.

        This method is base-class-only. Concrete specialists MUST NOT
        override it; they call it from ``_build_user_prompt`` and append
        role-specific instructions afterwards. The envelope is the shape
        defined in docs/specs/2026-04-30-orchestrator-revision.md §6 so
        that every specialist sees the same structure regardless of role.
        """
        prior = {
            role: _artifact_to_dict(art)
            for role, art in ctx.prior_artifacts.items()
        }
        ticket = ctx.ticket or {}
        repo = ctx.repo or {}
        prior_block = (
            json.dumps(prior, indent=2) if prior else "<none>"
        )
        return (
            f"<task_role>{self.role}</task_role>\n"
            f"\n"
            f"<jira_ticket>\n"
            f"  <title>{ticket.get('summary', '')}</title>\n"
            f"  <description>{ticket.get('description', '')}</description>\n"
            f"  <acceptance_criteria>"
            f"{ticket.get('acceptance_criteria') or '<empty>'}"
            f"</acceptance_criteria>\n"
            f"  <labels>{ticket.get('labels', [])}</labels>\n"
            f"</jira_ticket>\n"
            f"\n"
            f"<repository>\n"
            f"  <full_name>{repo.get('full_name', '')}</full_name>\n"
            f"  <scm_type>{repo.get('scm_type', '')}</scm_type>\n"
            f"  <default_branch>{repo.get('default_branch', '')}"
            f"</default_branch>\n"
            f"</repository>\n"
            f"\n"
            f"<prior_artifacts>\n"
            f"{prior_block}\n"
            f"</prior_artifacts>\n"
            f"\n"
            f"<instruction>Produce the JSON object per &lt;output_schema&gt;. "
            f"Use prefill to start.</instruction>"
        )

    # --- core entry point -----------------------------------------------

    async def run_specialist(self, ctx: SpecialistContext) -> Artifact:
        """Run the specialist and return a validated :class:`Artifact`.

        Every terminal return path emits ``specialist.completed`` via
        :meth:`_complete` so the orchestrator always sees the outcome.

        Override in stubs to short-circuit with verdict='not_implemented'.
        """
        self._task_id = ctx.task_id
        model = self._resolve_model()

        await self._publish_event(
            "specialist.started",
            {"role": self.role, "task_id": ctx.task_id, "model": model},
        )

        user_prompt = self._build_user_prompt(ctx)

        # Call Claude with prefill + stop_sequence. NOTE: no char/4 pre-flight
        # guard — see module docstring §1.
        try:
            response = await self._claude.chat(
                messages=[
                    {"role": "user", "content": user_prompt},
                    {"role": "assistant", "content": "```json\n{"},
                ],
                system=[{
                    "type": "text",
                    "text": self._system_prompt(),
                    "cache_control": {"type": "ephemeral"},
                }],
                model=model,
                max_tokens=self.max_output_tokens,
                temperature=0.0,
                stop_sequences=["```"],
            )
        except Exception as exc:  # noqa: BLE001
            logger.exception("Specialist %s call failed", self.role)
            return await self._complete(ctx, Artifact(
                role=self.role, verdict="error",
                reasoning=f"claude call failed: {exc}", payload={},
                model_used=model, input_tokens=0, output_tokens=0,
            ))

        usage = getattr(response, "usage", None)
        input_tokens = int(getattr(usage, "input_tokens", 0) or 0)
        output_tokens = int(getattr(usage, "output_tokens", 0) or 0)

        # Stop-reason guard (revision §3). The prefill + stop_sequence pattern
        # can silently produce valid-looking but truncated JSON when the model
        # hits max_tokens mid-object; refuse to trust anything that didn't
        # cleanly terminate on the explicit stop sequence.
        stop_reason = getattr(response, "stop_reason", None)
        if stop_reason != "stop_sequence":
            return await self._complete(ctx, Artifact(
                role=self.role, verdict="error",
                reasoning=(
                    f"unexpected stop_reason={stop_reason!r} — output "
                    "likely truncated; refusing to parse JSON"
                ),
                payload={}, model_used=model,
                input_tokens=input_tokens, output_tokens=output_tokens,
            ))

        raw_text = "```json\n{" + (response.text or "")
        from types import SimpleNamespace
        result = SimpleNamespace(output=raw_text, metadata={})

        # Repair loop — uses the Pydantic schema for validation.
        await self._repair_or_escalate_json(
            result,
            validator=self._validate_text,
            schema_reminder=self._schema_reminder(),
            system_prompt=self._system_prompt(),
            model=model,
            task_id=ctx.task_id,
            event_prefix=f"specialist_{self.role}",
        )

        if (result.metadata or {}).get("escalated"):
            return await self._complete(ctx, Artifact(
                role=self.role, verdict="error",
                reasoning="JSON invalid after 2 repair rounds",
                payload={}, model_used=model,
                input_tokens=input_tokens, output_tokens=output_tokens,
            ))

        try:
            parsed = self._extract_json(result.output)
        except (json.JSONDecodeError, ValueError) as exc:
            return await self._complete(ctx, Artifact(
                role=self.role, verdict="error",
                reasoning=f"JSON parse failed post-repair: {exc}",
                payload={}, model_used=model,
                input_tokens=input_tokens, output_tokens=output_tokens,
            ))

        try:
            validated = validate_payload(
                self.role, parsed.get("payload", parsed)
            )
        except (ValidationError, ValueError) as exc:
            # ValidationError.errors() is verbose; take the first 3 so the
            # reasoning stays readable.
            err_detail = (
                exc.errors()[:3] if isinstance(exc, ValidationError) else str(exc)
            )
            return await self._complete(ctx, Artifact(
                role=self.role, verdict="error",
                reasoning=f"payload schema mismatch: {err_detail}",
                payload={}, model_used=model,
                input_tokens=input_tokens, output_tokens=output_tokens,
            ))

        return await self._complete(ctx, Artifact(
            role=self.role,
            verdict=parsed.get("verdict", "error"),
            reasoning=parsed.get("reasoning", ""),
            payload=validated.model_dump(),
            model_used=model,
            input_tokens=input_tokens,
            output_tokens=output_tokens,
        ))

    # --- completion helper ----------------------------------------------

    async def _complete(
        self, ctx: SpecialistContext, artifact: Artifact
    ) -> Artifact:
        """Emit ``specialist.completed`` then return the artifact.

        Centralises the terminal event so every return path in
        :meth:`run_specialist` (success, Claude-error, stop-reason guard,
        repair-escalation, schema mismatch) fires the same event shape
        rather than silently dropping it.
        """
        await self._publish_event(
            "specialist.completed",
            {
                "role": self.role,
                "task_id": ctx.task_id,
                "verdict": artifact.verdict,
                "input_tokens": artifact.input_tokens,
                "output_tokens": artifact.output_tokens,
            },
        )
        return artifact

    # --- helpers --------------------------------------------------------

    def _validate_text(self, text: str) -> bool:
        try:
            parsed = self._extract_json(text)
        except (json.JSONDecodeError, ValueError):
            return False
        if not isinstance(parsed, dict) or "verdict" not in parsed:
            return False
        try:
            validate_payload(self.role, parsed.get("payload", parsed))
        except (ValidationError, ValueError):
            return False
        return True

    @staticmethod
    def _extract_json(text: str) -> dict:
        match = re.search(r"\{.*\}", text, re.DOTALL)
        if not match:
            raise ValueError("no JSON object found in output")
        return json.loads(match.group(0))


class StubSpecialist(SpecialistBase):
    """Specialist that does no real work; emits verdict='not_implemented'.

    Concrete stubs (Task 7) subclass this and set :attr:`role` + optionally
    override :attr:`model_setting_attr` for when they're lit up later.
    """

    def _system_prompt(self) -> str:
        return f"stub specialist for {self.role}"

    def _build_user_prompt(self, ctx: SpecialistContext) -> str:
        return ""

    def _schema_reminder(self) -> str:
        return ""

    async def run_specialist(self, ctx: SpecialistContext) -> Artifact:
        self._task_id = ctx.task_id
        await self._publish_event(
            "specialist.started",
            {"role": self.role, "task_id": ctx.task_id, "model": "n/a"},
        )
        return await self._complete(ctx, Artifact(
            role=self.role,
            verdict="not_implemented",
            reasoning="stub — implementation deferred to a follow-up spec",
            payload={},
            model_used="",
            input_tokens=0,
            output_tokens=0,
        ))


# --- module-private helpers ----------------------------------------------


def _artifact_to_dict(art: Any) -> dict[str, Any]:
    """Best-effort Artifact -> dict for inlining in the XML envelope.

    :class:`Artifact` is a dataclass today, so ``asdict`` handles it.
    Guard for duck-typed alternatives (e.g. test doubles) by falling
    back to ``__dict__``.
    """
    if is_dataclass(art) and not isinstance(art, type):
        return asdict(art)
    if hasattr(art, "__dict__"):
        return dict(art.__dict__)
    return {"repr": repr(art)}
