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
from decimal import Decimal
from typing import Any
from xml.sax.saxutils import escape as _saxutils_escape

from pydantic import ValidationError

from ai_driven.agents.base import AwaitingHumanInput, BaseAgent
from ai_driven.agents.orchestrator.artifacts import (
    Artifact,
    validate_payload,
)
from ai_driven.billing.tracker import get_model_pricing
from ai_driven.config import get_settings

logger = logging.getLogger(__name__)


@dataclass
class SpecialistContext:
    """Input envelope for a single specialist dispatch.

    ``prior_artifacts`` is keyed by role (e.g. ``"business_analyst"`` ->
    BA Artifact). The orchestrator builds this dict by reading from
    :class:`ArtifactRepository` before dispatch.

    ``pr_ref`` (optional) is populated by the T22 post-coding QA wrapper
    with ``{"pr_number": int, "pr_url": str}`` so the QA specialist can
    render the diff via ``github.get_pr_diff`` against a concrete PR.
    Pre-coding specialists (BA, StackAnalyst, Security, etc.) leave it
    ``None``; they never have a PR yet. Using a typed field keeps the
    post-coding path from smuggling pr_number through ``team_settings``
    (which is reserved for per-team orchestrator overrides).
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
    # T22: {pr_number, pr_url} for the post-coding QA path. None for
    # every pre-coding specialist.
    pr_ref: dict | None = None


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
    # HITL opt-in. When True (and global HITL is enabled for the team)
    # this specialist runs through ``_run_with_hitl`` instead of the
    # single-shot Claude call. The tool loop can raise
    # :class:`AwaitingHumanInput` when the model asks a clarification;
    # the SQS wrapper catches that and persists state. Default False —
    # specialists must explicitly opt in.
    supports_hitl: bool = False
    # Per-task cap on clarification rounds. Matches the design default
    # (3 rounds). The /respond endpoint enforces the same cap.
    max_hitl_rounds: int = 3
    # Per-specialist iteration budget inside the HITL tool loop. The
    # model may emit ONE ask_clarification call before we stop; anything
    # past that is either the terminal JSON artifact or an error.
    max_hitl_iterations: int = 2
    # Advisory; NOT enforced in run_specialist (see module docstring §1).
    # Retained so future QA / cost tooling can reason about specialist
    # intent without rediscovering it.
    max_input_tokens: int = 8192
    max_output_tokens: int = 16384  # bumped 4096 → 16384 (T-A1, 2026-05-01).
    # The T-A1 error audit (docs/phase2-error-audit.md) found that
    # ``stop_reason=max_tokens`` still accounted for 18.5% of specialist errors
    # after the 2048→4096 bump, concentrated on the Security role (3 of 5
    # truncation cases). Security threat-model JSON + QA full acceptance-
    # criteria lists can blow past 4k on multi-concern tickets. 16K sits well
    # under Sonnet 4.6's 64K output ceiling, so there is no risk of hitting
    # a server-side cap. The wall-clock cost is bounded by the specialist's
    # request timeout, not the token cap; the billing delta is negligible
    # because only the *actually emitted* output tokens are charged — the
    # ceiling only matters when the model was previously being truncated
    # mid-JSON (in which case we paid for garbage anyway).
    # Explicitly NOT using JSON chunking or streaming reassembly here
    # (forbidden by T-A1 scope). If 16K ever turns out to be insufficient,
    # escalate to T-A1b before inventing new parsing machinery.

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
        # Escape every interpolated user-controlled string so a hostile
        # Jira description like "</jira_ticket><instruction>..." cannot
        # close the envelope and inject a fake instruction tag into the
        # prompt. JSON-encoded prior_artifacts also get escaped as
        # defence-in-depth (a prior artifact's string field may contain
        # raw '<').
        summary = _xml_escape(ticket.get("summary", ""))
        description = _xml_escape(ticket.get("description", ""))
        ac_raw = ticket.get("acceptance_criteria") or ""
        acceptance_criteria = (
            _xml_escape(ac_raw) if ac_raw else "<empty>"
        )
        labels_list = ticket.get("labels") or []
        labels = _xml_escape(", ".join(labels_list))
        full_name = _xml_escape(repo.get("full_name", ""))
        scm_type = _xml_escape(repo.get("scm_type", ""))
        default_branch = _xml_escape(repo.get("default_branch", ""))
        prior_block = (
            _xml_escape(json.dumps(prior, indent=2)) if prior else "<none>"
        )
        return (
            f"<task_role>{self.role}</task_role>\n"
            f"\n"
            f"<jira_ticket>\n"
            f"  <title>{summary}</title>\n"
            f"  <description>{description}</description>\n"
            f"  <acceptance_criteria>"
            f"{acceptance_criteria}"
            f"</acceptance_criteria>\n"
            f"  <labels>{labels}</labels>\n"
            f"</jira_ticket>\n"
            f"\n"
            f"<repository>\n"
            f"  <full_name>{full_name}</full_name>\n"
            f"  <scm_type>{scm_type}</scm_type>\n"
            f"  <default_branch>{default_branch}"
            f"</default_branch>\n"
            f"</repository>\n"
            f"\n"
            f"<prior_artifacts>\n"
            f"{prior_block}\n"
            f"</prior_artifacts>\n"
            f"\n"
            f"<instruction>Produce the JSON object per <output_schema>. "
            f"Use prefill to start.</instruction>"
        )

    # --- cost computation -----------------------------------------------

    @staticmethod
    def _compute_cost_usd(
        model: str, input_tokens: int, output_tokens: int,
    ) -> Decimal:
        """Return the USD cost of a specialist Claude call as a ``Decimal``.

        Dispatches through ``billing.tracker.get_model_pricing`` (same helper
        BaseAgent's ``_iteration_cost_usd`` uses) so specialists and the
        coding agent bill against the same price table. Applies the Bedrock
        regional uplift when the active provider is Bedrock and the region
        is NOT us-east-1 — matches ``agents.base._bedrock_region_multiplier``.

        Uses ``Decimal`` throughout to avoid binary float drift; rounds to 6
        decimal places to match the ``task_artifacts.cost_usd NUMERIC(10,6)``
        column. Cache tiers are intentionally NOT applied — specialists
        currently make single-shot calls without prefix caching at the
        per-specialist scope; if that changes we'll propagate usage.cache_*
        from the response here.
        """
        pricing = get_model_pricing(model)
        settings = get_settings()
        regional_multiplier = (
            Decimal(str(settings.bedrock_price_multiplier))
            if getattr(settings, "claude_provider", None) == "bedrock"
            and getattr(settings, "aws_region", "") not in ("us-east-1", "")
            else Decimal("1")
        )
        # ``pricing`` values are USD per 1K tokens (see billing.tracker).
        input_cost = (
            Decimal(str(pricing["input"])) * Decimal(input_tokens) / Decimal("1000")
        )
        output_cost = (
            Decimal(str(pricing["output"])) * Decimal(output_tokens) / Decimal("1000")
        )
        cost = (input_cost + output_cost) * regional_multiplier
        return cost.quantize(Decimal("0.000001"))

    # --- HITL helpers ----------------------------------------------------

    def _hitl_active(self, ctx: SpecialistContext) -> bool:
        """Return True iff this specialist should run in HITL async mode
        for this specific dispatch.

        Conditions (ALL must hold):
          * subclass opted in (``supports_hitl = True``)
          * global + per-team switch is on
          * role appears in ``settings.hitl_specialists`` allowlist
          * this is NOT a Jira-webhook triggered run (no human listening)
        """
        if not self.supports_hitl:
            return False
        from ai_driven.config import get_settings as _get_settings
        settings = _get_settings()
        team_settings = ctx.team_settings or {}
        team_flag = team_settings.get("hitl_enabled")
        enabled = bool(team_flag) if team_flag is not None else bool(settings.hitl_enabled)
        if not enabled:
            return False
        allowed_roles = {r.strip() for r in (settings.hitl_specialists or "").split(",")}
        if self.role not in allowed_roles:
            return False
        # Jira-webhook flows have no human connected; suppress HITL.
        if (ctx.ticket or {}).get("triggered_by") == "jira_transition":
            return False
        return True

    # --- core entry point -----------------------------------------------

    async def run_specialist(self, ctx: SpecialistContext) -> Artifact:
        """Run the specialist and return a validated :class:`Artifact`.

        Every terminal return path emits ``specialist.completed`` via
        :meth:`_complete` so the orchestrator always sees the outcome.

        When :meth:`_hitl_active` is True the specialist runs through
        the tool-loop path (``_run_hitl_turn``) instead of the single-
        shot path. The tool-loop registers ``human.ask_clarification``
        so the model can pause for user input; if it does,
        :class:`AwaitingHumanInput` is raised and the SQS wrapper
        persists state + ACKs the message.

        Override in stubs to short-circuit with verdict='not_implemented'.
        """
        self._task_id = ctx.task_id
        model = self._resolve_model()

        await self._publish_event(
            "specialist.started",
            {"role": self.role, "task_id": ctx.task_id, "model": model},
        )

        # ── HITL async path ──
        if self._hitl_active(ctx):
            return await self._run_hitl(ctx, model)

        user_prompt = self._build_user_prompt(ctx)

        # Provider-aware request shape.
        #
        # Anthropic Direct supports the assistant-prefill pattern
        # ({"role":"assistant","content":"```json\n{"} + stop_sequences=["```"])
        # which strongly biases the model toward valid JSON on round 1.
        #
        # Bedrock-hosted Claude REJECTS prefill with ValidationException:
        # "This model does not support assistant message prefill. The
        # conversation must end with a user message."
        #
        # Drop the prefill when the active provider is Bedrock and rely on the
        # <output_schema> instruction + JSON repair loop instead. Slightly
        # higher chance of round-1 failure (repair loop picks up the slack),
        # but works on both providers.
        from ai_driven.config import get_settings as _get_settings
        provider_is_bedrock = _get_settings().claude_provider == "bedrock"

        if provider_is_bedrock:
            messages = [{"role": "user", "content": user_prompt}]
            stop_sequences = None
        else:
            messages = [
                {"role": "user", "content": user_prompt},
                {"role": "assistant", "content": "```json\n{"},
            ]
            stop_sequences = ["```"]

        try:
            response = await self._claude.chat(
                messages=messages,
                system=[{
                    "type": "text",
                    "text": self._system_prompt(),
                    "cache_control": {"type": "ephemeral"},
                }],
                model=model,
                max_tokens=self.max_output_tokens,
                temperature=0.0,
                stop_sequences=stop_sequences,
            )
        except Exception as exc:  # noqa: BLE001
            logger.exception("Specialist %s call failed", self.role)
            return await self._complete(ctx, Artifact(
                role=self.role, verdict="error",
                reasoning=f"claude call failed: {exc}", payload={},
                model_used=model, input_tokens=0, output_tokens=0,
                cost_usd=Decimal("0"),
            ))

        usage = getattr(response, "usage", None)
        input_tokens = int(getattr(usage, "input_tokens", 0) or 0)
        output_tokens = int(getattr(usage, "output_tokens", 0) or 0)
        cost_usd = self._compute_cost_usd(model, input_tokens, output_tokens)

        # Stop-reason guard (revision §3). Only meaningful for the
        # prefill+stop_sequence path (Anthropic Direct). For Bedrock we
        # accept end_turn as the natural terminator; max_tokens is the only
        # failure mode that indicates truncation.
        stop_reason = getattr(response, "stop_reason", None)
        if provider_is_bedrock:
            if stop_reason == "max_tokens":
                return await self._complete(ctx, Artifact(
                    role=self.role, verdict="error",
                    reasoning=(
                        f"output truncated (stop_reason=max_tokens); "
                        "refusing to parse partial JSON"
                    ),
                    payload={}, model_used=model,
                    input_tokens=input_tokens, output_tokens=output_tokens,
                    cost_usd=cost_usd,
                ))
        else:
            if stop_reason != "stop_sequence":
                return await self._complete(ctx, Artifact(
                    role=self.role, verdict="error",
                    reasoning=(
                        f"unexpected stop_reason={stop_reason!r} — output "
                        "likely truncated; refusing to parse JSON"
                    ),
                    payload={}, model_used=model,
                    input_tokens=input_tokens, output_tokens=output_tokens,
                    cost_usd=cost_usd,
                ))

        # Reconstruct the raw text that downstream JSON extraction expects.
        # Anthropic-Direct path had the "```json\n{" prefill stripped by the
        # API; prepend it back so _extract_json (which regex-matches
        # anywhere in the blob) still works. Bedrock-path output contains
        # the full ```json ... ``` fence inline — pass through unchanged.
        if provider_is_bedrock:
            raw_text = response.text or ""
        else:
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
                cost_usd=cost_usd,
            ))

        try:
            parsed = self._extract_json(result.output)
        except (json.JSONDecodeError, ValueError) as exc:
            return await self._complete(ctx, Artifact(
                role=self.role, verdict="error",
                reasoning=f"JSON parse failed post-repair: {exc}",
                payload={}, model_used=model,
                input_tokens=input_tokens, output_tokens=output_tokens,
                cost_usd=cost_usd,
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
                cost_usd=cost_usd,
            ))

        return await self._complete(ctx, Artifact(
            role=self.role,
            verdict=parsed.get("verdict", "error"),
            reasoning=parsed.get("reasoning", ""),
            payload=validated.model_dump(),
            model_used=model,
            input_tokens=input_tokens,
            output_tokens=output_tokens,
            cost_usd=cost_usd,
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

    # --- HITL tool-loop (async-continuation) ----------------------------

    async def _run_hitl(self, ctx: SpecialistContext, model: str) -> Artifact:
        """Run the specialist through a tool-augmented Claude loop.

        The ``human.ask_clarification`` tool is flipped to async mode so
        if the model calls it, :class:`AwaitingHumanInput` propagates
        out — caught by the SQS wrapper. If the model produces a JSON
        artifact directly, we parse it like the single-shot path.

        Security: any user-supplied content that flows back in from
        ``/respond`` is wrapped in ``<user_answer id=…>`` tags with XML
        escaping (see :meth:`resume_with_answer`). The system prompt
        carries a corresponding "untrusted data" clause.
        """
        from decimal import Decimal
        user_prompt = self._build_user_prompt(ctx)
        messages: list[dict[str, Any]] = [
            {"role": "user", "content": user_prompt},
        ]
        return await self._hitl_continue(ctx, model, messages)

    async def resume_with_answer(
        self,
        ctx: SpecialistContext,
        *,
        model: str | None = None,
        prior_messages: list[dict[str, Any]],
        tool_use_id: str,
        answers: dict[str, str],
        rounds_used: int,
        rounds_cap: int,
    ) -> Artifact:
        """Continue a specialist that was paused on ask_clarification.

        Called by the ``specialist_resume`` consumer path. Appends the
        user answer as a ``tool_result`` for the pending ``tool_use_id``
        and re-enters the tool loop. When ``rounds_used >= rounds_cap``
        the tool is hidden (the model MUST emit a terminal verdict).

        ``prior_messages`` comes straight from
        ``task_specialist_state.conversation`` — it already contains
        the original user prompt and the assistant's prior turn(s).
        """
        resolved_model = model or self._resolve_model()
        # Build the tool_result block. Every answer is XML-escaped and
        # wrapped so prompt-injection attempts from the user can't close
        # our envelope.
        escaped: list[str] = []
        for qid, text in (answers or {}).items():
            safe_qid = _xml_escape(str(qid))
            safe_text = _xml_escape(str(text))
            escaped.append(f'<user_answer id="{safe_qid}">{safe_text}</user_answer>')
        answer_block = "\n".join(escaped) or "<user_answer id=\"_empty\"></user_answer>"

        messages = list(prior_messages) + [{
            "role": "user",
            "content": [{
                "type": "tool_result",
                "tool_use_id": tool_use_id,
                "content": answer_block,
            }],
        }]
        # Hide the tool once the cap is reached — the model's next turn
        # has no choice but to emit a terminal verdict.
        allow_ask = rounds_used < rounds_cap
        if not allow_ask:
            # Nudge: tell the model explicitly that the clarification
            # budget is exhausted; combined with `tools=None` below this
            # forces a JSON artifact.
            messages.append({
                "role": "user",
                "content": (
                    "Clarification budget exhausted. Emit your final "
                    "BusinessAnalyst artifact JSON now, using the "
                    "answers above plus best-guess defaults for "
                    "anything still unclear. Note remaining "
                    "ambiguities in `ambiguities_resolved`."
                ),
            })
        return await self._hitl_continue(
            ctx, resolved_model, messages, allow_ask=allow_ask,
        )

    async def _hitl_continue(
        self,
        ctx: SpecialistContext,
        model: str,
        messages: list[dict[str, Any]],
        *,
        allow_ask: bool = True,
    ) -> Artifact:
        """Shared tool-loop body for both initial HITL run and resume.

        Flips ``self._hitl_mode = "async"`` for the duration so the
        registered ``human.ask_clarification`` tool raises on use. If
        ``allow_ask=False``, the tool is NOT passed to Claude — the
        model must emit a terminal JSON artifact.
        """
        from decimal import Decimal
        # Flip HITL mode. Caller runs this once per specialist dispatch
        # so resetting after is safe even on exception paths.
        self._hitl_mode = "async"
        prev_task = self._task_id
        self._task_id = ctx.task_id
        tools = ["human.ask_clarification"] if allow_ask else None
        input_tokens = 0
        output_tokens = 0
        raw_text = ""
        try:
            # Single-turn: we don't want the generic run_agent_loop
            # because HITL interruption would otherwise be caught by
            # _execute_tool_calls. Call provider directly.
            from ai_driven.claude.tools.registry import ToolRegistry
            # Provider call — same shape as run_agent_loop's internals.
            resp = await self._claude._provider.create_message(
                model=model,
                max_tokens=self.max_output_tokens,
                messages=messages,
                system=[{
                    "type": "text",
                    "text": self._system_prompt(),
                    "cache_control": {"type": "ephemeral"},
                }],
                tools=self._claude._tool_registry.get_schemas(tools) if tools else None,
                temperature=0.0,
            )
            usage = getattr(resp, "usage", None)
            input_tokens = int(getattr(usage, "input_tokens", 0) or 0)
            output_tokens = int(getattr(usage, "output_tokens", 0) or 0)

            if resp.has_tool_use and tools:
                for block in resp.tool_use_blocks:
                    if block.name != "human.ask_clarification":
                        continue
                    # Normalize batch shape from the block input.
                    raw_input = block.input or {}
                    batch: list[dict[str, Any]] = []
                    if isinstance(raw_input.get("questions"), list):
                        for q in raw_input["questions"]:
                            if not isinstance(q, dict):
                                continue
                            qtext = str(q.get("question") or "").strip()
                            if not qtext:
                                continue
                            batch.append({
                                "id": str(q.get("id") or f"q{len(batch)+1}"),
                                "question": qtext,
                                "options": list(q.get("options") or []),
                                "why": str(q.get("why") or "").strip(),
                            })
                    elif raw_input.get("question"):
                        batch.append({
                            "id": "q1",
                            "question": str(raw_input["question"]),
                            "options": list(raw_input.get("options") or []),
                            "why": str(raw_input.get("context") or ""),
                        })
                    if not batch:
                        # Malformed tool call — fall through to a JSON
                        # error artifact instead of another loop round.
                        break
                    import uuid as _uuid
                    round_id = _uuid.uuid4().hex[:12]
                    # Snapshot full conversation INCLUDING the assistant's
                    # latest tool_use turn so resume can attach a
                    # tool_result with the matching tool_use_id.
                    snapshot = list(messages) + [
                        {"role": "assistant", "content": resp.raw_content}
                    ]
                    raise AwaitingHumanInput(
                        questions=batch,
                        round_id=round_id,
                        messages=snapshot,
                        tool_use_id=block.id,
                    )

            # Not a tool call (or tools disabled) → parse artifact.
            raw_text = resp.text or ""
        finally:
            self._hitl_mode = "poll"
            self._task_id = prev_task

        cost_usd = self._compute_cost_usd(model, input_tokens, output_tokens)
        # Fall through to the same JSON-extraction logic as the single-
        # shot path. No prefill here — Bedrock and Direct agree on the
        # plain JSON fence that the BA system prompt instructs.
        from types import SimpleNamespace
        result = SimpleNamespace(output=raw_text, metadata={})
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
                reasoning="JSON invalid after 2 repair rounds (HITL path)",
                payload={}, model_used=model,
                input_tokens=input_tokens, output_tokens=output_tokens,
                cost_usd=cost_usd,
            ))
        try:
            parsed = self._extract_json(result.output)
        except (json.JSONDecodeError, ValueError) as exc:
            return await self._complete(ctx, Artifact(
                role=self.role, verdict="error",
                reasoning=f"JSON parse failed post-repair: {exc}",
                payload={}, model_used=model,
                input_tokens=input_tokens, output_tokens=output_tokens,
                cost_usd=cost_usd,
            ))
        try:
            validated = validate_payload(
                self.role, parsed.get("payload", parsed)
            )
        except (ValidationError, ValueError) as exc:
            err_detail = (
                exc.errors()[:3] if isinstance(exc, ValidationError) else str(exc)
            )
            return await self._complete(ctx, Artifact(
                role=self.role, verdict="error",
                reasoning=f"payload schema mismatch: {err_detail}",
                payload={}, model_used=model,
                input_tokens=input_tokens, output_tokens=output_tokens,
                cost_usd=cost_usd,
            ))
        return await self._complete(ctx, Artifact(
            role=self.role,
            verdict=parsed.get("verdict", "error"),
            reasoning=parsed.get("reasoning", ""),
            payload=validated.model_dump(),
            model_used=model,
            input_tokens=input_tokens,
            output_tokens=output_tokens,
            cost_usd=cost_usd,
        ))

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


def _xml_escape(text: str) -> str:
    """Escape XML metacharacters (``<``, ``>``, ``&``) in user content.

    Thin wrapper around :func:`xml.sax.saxutils.escape` so every
    interpolation in :meth:`SpecialistBase._build_initial_message` goes
    through a single audited choke point. The goal is prompt-injection
    hardening: a Jira description containing literal ``</jira_ticket>``
    must not be able to close the envelope and smuggle a fake
    ``<instruction>`` tag into the rendered prompt.
    """
    return _saxutils_escape(text)


def _artifact_to_dict(art: Any) -> dict[str, Any]:
    """Best-effort Artifact -> JSON-safe dict for inlining in the XML envelope.

    :class:`Artifact` is a dataclass today, so ``asdict`` handles it.
    Guard for duck-typed alternatives (e.g. test doubles) by falling
    back to ``__dict__``.

    ``Artifact.cost_usd`` is a ``Decimal`` (NUMERIC(10,6) on disk) and
    ``json.dumps`` cannot serialize ``Decimal``. Stringify here so the
    downstream ``json.dumps`` call in ``_build_initial_message`` succeeds
    — the envelope is informational context for the model; a string is
    indistinguishable from a number at the prompt level.
    """
    if is_dataclass(art) and not isinstance(art, type):
        data = asdict(art)
    elif hasattr(art, "__dict__"):
        data = dict(art.__dict__)
    else:
        return {"repr": repr(art)}
    for k, v in list(data.items()):
        if isinstance(v, Decimal):
            data[k] = str(v)
    return data
