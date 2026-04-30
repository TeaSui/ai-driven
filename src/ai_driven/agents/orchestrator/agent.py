"""OrchestratorAgent — Phase 1 (shadow mode).

Per task:
  1. classify tier via cheap fast-model call (settings.claude_fast_model; Sonnet post-H1)
  2. persist tasks.orchestration_tier
  3. plan specialist sequence per tier rules
  4. (full_workflow / multi_domain) optionally pick from candidates
  5. run specialists in mandatory order, then picked, then final gate
  6. fan-out prior artifacts per fan_out.py
  7. persist each Artifact via ArtifactRepository
  8. hard-stop on first verdict=='block'

In shadow mode (default) the orchestrator returns metadata but does NOT
gate the downstream coding+review pipeline. The producer/consumer wires
this so the existing pipeline runs in parallel — see Task 17.

Invariant: _persist_tier is the ONLY tasks-table write this class makes.
The orchestrator NEVER calls _update_task_status — the coding pipeline
owns that state. Keeping the write surface narrow preserves shadow-mode
safety (no risk of flipping a task into a status the existing pipeline
doesn't understand).
"""
from __future__ import annotations

import logging
import uuid
from typing import Any

from ai_driven.agents.base import BaseAgent
from ai_driven.agents.orchestrator.artifacts import Artifact
from ai_driven.agents.orchestrator.classifier import TierClassifier
from ai_driven.agents.orchestrator.fan_out import (
    relevant_artifacts_for,
    should_run_ux_designer,
)
from ai_driven.agents.orchestrator.repository import ArtifactRepository
from ai_driven.agents.orchestrator.routing import (
    SpecialistPlan,
    plan_specialist_sequence,
)
from ai_driven.agents.specialists.api_test.agent import ApiTestSpecialist
from ai_driven.agents.specialists.base import SpecialistContext
from ai_driven.agents.specialists.business_analyst.agent import (
    BusinessAnalystSpecialist,
)
from ai_driven.agents.specialists.qa.agent import QaSpecialist
from ai_driven.agents.specialists.security.agent import SecuritySpecialist
from ai_driven.agents.specialists.stack_analyst.agent import (
    StackAnalystSpecialist,
)
from ai_driven.agents.specialists.tech_lead.agent import TechLeadSpecialist
from ai_driven.agents.specialists.ux_designer.agent import UxDesignerSpecialist
from ai_driven.config import get_settings
from ai_driven.db.postgres.session import async_session_factory

logger = logging.getLogger(__name__)


_SPECIALIST_CLASSES = {
    "business_analyst": BusinessAnalystSpecialist,
    "stack_analyst":    StackAnalystSpecialist,
    "security":         SecuritySpecialist,
    "qa":               QaSpecialist,
    "tech_lead":        TechLeadSpecialist,
    "ux_designer":      UxDesignerSpecialist,
    "api_test":         ApiTestSpecialist,
}


class OrchestratorAgent(BaseAgent):
    """Top-level coordinator. Classifies tier, runs specialists, persists."""

    agent_type = "orchestrator"

    def __init__(
        self,
        claude_client: Any | None = None,
    ) -> None:
        super().__init__(claude_client=claude_client)
        # Accumulated Claude token usage across classifier + specialists.
        # Surfaced in the orchestrate() return dict so the consumer-side
        # _OrchestratorWrapper can synthesize a non-zero AgentResult.
        self._total_tokens: dict[str, int] = {"input": 0, "output": 0}

    def _register_tools(self) -> None:
        return None

    def _get_tool_names(self) -> list[str]:
        return []

    def _get_system_prompt(self, context: dict[str, Any]) -> str:
        return ""  # OrchestratorAgent doesn't use the agent loop

    def _is_shadow_mode(self) -> bool:
        return get_settings().orchestrator_mode != "live"

    # --- entry point --------------------------------------------------

    async def orchestrate(self, payload: dict[str, Any]) -> dict[str, Any]:
        task_id = payload["task_id"]
        ticket = payload.get("ticket", {})
        repo = payload.get("repo", {})

        self._task_id = task_id
        await self._publish_event("orchestrator.started",
                                  {"task_id": task_id})

        # 1. Classify tier
        tier = await self._classify_tier(ticket, repo)
        await self._persist_tier(task_id, tier)

        # 2. Plan
        plan = plan_specialist_sequence(tier, task_context={"ticket": ticket})

        if plan.skip_to_pipeline:
            await self._publish_event("orchestrator.completed", {
                "tier": tier, "specialists_run": [],
                "shadow": self._is_shadow_mode(),
            })
            return {
                "tier": tier, "specialists_run": [],
                "shadow": self._is_shadow_mode(),
                "halted_on": None, "artifacts": {},
                "total_tokens": dict(self._total_tokens),
            }

        # 3. Pick optional specialists (if any candidates)
        if plan.candidates:
            picked = await self._pick_optional_specialists(plan, ticket, repo)
            plan.picked = picked

        # 4. Run sequence, fanning out prior artifacts, halting on block
        artifacts: dict[str, Artifact] = {}
        halted_on: str | None = None

        for role in plan.full_sequence():
            # UX is conditional on stacks
            if role == "ux_designer":
                sa = artifacts.get("stack_analyst")
                if sa is None or not should_run_ux_designer(sa.payload):
                    continue

            ctx = SpecialistContext(
                task_id=task_id,
                ticket=ticket,
                repo=repo,
                tier=tier,
                prior_artifacts=relevant_artifacts_for(role, artifacts),
                team_settings={},
            )
            artifact = await self._dispatch_specialist(role, ctx)
            artifacts[role] = artifact
            # Accumulate token usage for wrapper AgentResult synthesis.
            self._total_tokens["input"] += int(getattr(artifact, "input_tokens", 0) or 0)
            self._total_tokens["output"] += int(getattr(artifact, "output_tokens", 0) or 0)
            await self._publish_event("specialist.artifact_persisted", {
                "role": role, "task_id": task_id,
                "verdict": artifact.verdict,
            })
            await self._persist_artifact(task_id, artifact)
            if artifact.verdict == "block":
                halted_on = role
                break

        await self._publish_event("orchestrator.completed", {
            "tier": tier,
            "specialists_run": list(artifacts.keys()),
            "halted_on": halted_on,
            "shadow": self._is_shadow_mode(),
        })
        return {
            "tier": tier,
            "specialists_run": list(artifacts.keys()),
            "halted_on": halted_on,
            "shadow": self._is_shadow_mode(),
            "artifacts": {r: a.verdict for r, a in artifacts.items()},
            "total_tokens": dict(self._total_tokens),
        }

    # --- helpers ------------------------------------------------------

    async def _classify_tier(self, ticket: dict, repo: dict) -> str:
        classifier = TierClassifier(claude_client=self._claude)
        return await classifier.classify(ticket, repo)

    async def _persist_tier(self, task_id: str, tier: str) -> None:
        from sqlalchemy import update
        from ai_driven.db.postgres.models import Task
        try:
            async with async_session_factory() as session:
                await session.execute(
                    update(Task).where(Task.id == uuid.UUID(task_id))
                    .values(orchestration_tier=tier)
                )
                await session.commit()
        except Exception:  # noqa: BLE001
            logger.exception("Failed to persist orchestration_tier; "
                             "continuing in shadow mode.")

    async def _persist_artifact(self, task_id: str, artifact: Artifact) -> None:
        try:
            async with async_session_factory() as session:
                repo = ArtifactRepository(session)
                await repo.upsert(uuid.UUID(task_id), artifact)
                await session.commit()
        except Exception:  # noqa: BLE001
            logger.exception("Failed to persist artifact for role=%s",
                             artifact.role)

    async def _dispatch_specialist(
        self, role: str, ctx: SpecialistContext,
    ) -> Artifact:
        klass = _SPECIALIST_CLASSES[role]
        specialist = klass()
        return await specialist.run_specialist(ctx)

    async def _pick_optional_specialists(
        self, plan: SpecialistPlan, ticket: dict, repo: dict,
    ) -> list[str]:
        """Layer 3 picker. Skipped in Phase 1 if all candidates are stubs.

        Stubs do no real work, so picking them adds zero value. When at least
        one non-stub candidate exists, this delegates to a fast-model call
        (settings.claude_fast_model; TBD in Phase 2). For Phase 1, returning
        [] is correct per revision §Open Questions #1.
        """
        all_stubs = all(c in {"tech_lead", "ux_designer", "api_test"}
                        for c in plan.candidates)
        if all_stubs:
            return []
        # Phase 2 will add the picker call here.
        return []
