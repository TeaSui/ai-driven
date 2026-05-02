"""Unit tests for StackAnalystSpecialist with mocked Claude.

Three invariants locked here:

  1. Happy path — a valid Claude response classifying a backend-only
     change round-trips through schema validation and produces an
     Artifact with the expected stacks / primary_stack.
  2. Thin-signal path — verdict='pass_with_notes' is accepted; this
     test also serves as the 'does not block' smoke: the prompt
     forbids verdict='block', so the success path for ambiguous
     tickets must be pass_with_notes (not block).
  3. Tool registration — StackAnalyst exposes EXACTLY ``repo.overview``
     (not ``repo.list_files`` / ``repo.read_file`` / ``code.search``).
     Revision §5 carves this narrower surface on purpose; a regression
     that widened it would quietly hand the specialist more power than
     the spec sanctions.

The ``_claude_returns`` helper mirrors T8's pattern: the prefill ``{``
is dropped from ``text`` because the base re-attaches it before parsing,
and ``stop_reason='stop_sequence'`` clears the truncation guard (revision
§3). Any regression that forgets either detail will produce
``verdict='error'`` instead of the expected outcome.
"""
from __future__ import annotations

import json
from types import SimpleNamespace
from unittest.mock import AsyncMock

import pytest

from ai_driven.agents.orchestrator.artifacts import Artifact
from ai_driven.agents.specialists.base import SpecialistContext
from ai_driven.agents.specialists.stack_analyst.agent import (
    StackAnalystSpecialist,
)


def _ctx_with_ba() -> SpecialistContext:
    ba = Artifact(
        role="business_analyst",
        verdict="pass",
        reasoning="",
        payload={
            "ambiguities_resolved": [],
            "acceptance_criteria": [
                {"given": "x", "when": "POST /api/orders", "then": "200"},
            ],
            "out_of_scope": [],
            "blocking_questions": [],
        },
        model_used="m",
        input_tokens=10,
        output_tokens=10,
    )
    return SpecialistContext(
        task_id="t-sa",
        ticket={
            "key": "KAN-2",
            "summary": "Add /api/orders POST",
            "description": "FastAPI route + Postgres insert",
            "labels": [],
            "acceptance_criteria": [],
        },
        repo={
            "full_name": "org/api",
            "scm_type": "github",
            "default_branch": "main",
        },
        tier="full_workflow",
        prior_artifacts={"business_analyst": ba},
    )


def _claude_returns(
    payload: dict,
    verdict: str = "pass",
    reasoning: str = "ok",
    stop_reason: str = "stop_sequence",
) -> AsyncMock:
    """Build an AsyncMock returning a MessageResponse-shaped namespace.

    The base class:
      * re-attaches the ``{`` prefill before parsing — so we strip the
        leading ``{`` off the serialised body here,
      * guards on ``stop_reason == "stop_sequence"`` — so we default to
        that; override in a negative-path test if needed.
    """
    body = {"verdict": verdict, "reasoning": reasoning, "payload": payload}
    text = json.dumps(body)[1:]  # drop leading "{" — prefill provides it
    return AsyncMock(return_value=SimpleNamespace(
        text=text + "\n",
        stop_reason=stop_reason,
        usage=SimpleNamespace(input_tokens=80, output_tokens=40),
    ))


@pytest.mark.asyncio
async def test_stack_analyst_classifies_backend_only() -> None:
    agent = StackAnalystSpecialist()
    agent._publish_event = AsyncMock()  # type: ignore[method-assign]
    agent._claude.chat = _claude_returns(  # type: ignore[method-assign]
        {
            "stacks": ["backend"],
            "primary_stack": "backend",
            "cross_cutting_concerns": ["database migration"],
            "reasoning": "FastAPI handler + Postgres write.",
        },
        reasoning="Backend-only change.",
    )

    art = await agent.run_specialist(_ctx_with_ba())

    assert art.role == "stack_analyst"
    assert art.verdict == "pass"
    assert art.payload["stacks"] == ["backend"]
    assert art.payload["primary_stack"] == "backend"
    assert art.payload["cross_cutting_concerns"] == ["database migration"]
    assert art.input_tokens == 80
    assert art.output_tokens == 40


@pytest.mark.asyncio
async def test_stack_analyst_does_not_block() -> None:
    """StackAnalyst is forbidden from returning verdict='block'.

    Even with sparse signal, verdict must be pass or pass_with_notes.
    This test exercises the thin-signal fallback path the prompt's
    <decision_rules> and <anti_patterns> mandate.
    """
    agent = StackAnalystSpecialist()
    agent._publish_event = AsyncMock()  # type: ignore[method-assign]
    agent._claude.chat = _claude_returns(  # type: ignore[method-assign]
        {
            "stacks": ["backend"],
            "primary_stack": "backend",
            "cross_cutting_concerns": [],
            "reasoning": "No clear stack indicators in description.",
        },
        verdict="pass_with_notes",
        reasoning="Low signal; defaulting to backend.",
    )

    art = await agent.run_specialist(_ctx_with_ba())

    assert art.verdict in ("pass", "pass_with_notes")
    assert art.verdict != "block"


def test_stack_analyst_registers_repo_overview_tool() -> None:
    """Locks revision §5: stack_analyst exposes ONLY repo.overview.

    StackAnalyst is the only Phase-1 specialist that registers any
    tools, and it gets a narrower surface than the coding agent on
    purpose — no repo.list_files, no repo.read_file, no code.search.
    A regression that widened the list would quietly hand the
    specialist more power than the spec sanctions; this test fails
    loudly in that case.
    """
    agent = StackAnalystSpecialist()
    assert agent._get_tool_names() == ["repo.overview"]


def test_stack_analyst_user_prompt_contains_xml_envelope() -> None:
    """_build_user_prompt must route through _build_initial_message.

    Revision §5 mandates every specialist's user prompt start with the
    base-class XML envelope so user-content escaping isn't bypassed. A
    subclass that inlined ``json.dumps(ctx.ticket, ...)`` (the pre-
    revision shape from the plan) would fail this test by missing one
    or more of the envelope tags — which is exactly the regression we
    want to block.
    """
    agent = StackAnalystSpecialist()
    prompt = agent._build_user_prompt(_ctx_with_ba())

    # Envelope tags produced by _build_initial_message.
    assert "<task_role>" in prompt
    assert "<jira_ticket>" in prompt
    assert "<repository>" in prompt
    assert "<prior_artifacts>" in prompt

    # Role-specific trailing instruction appended by this specialist.
    assert "<task>" in prompt
    assert "</task>" in prompt
    assert "repo.overview" in prompt
    assert "NEVER return verdict='block'" in prompt


def test_stack_analyst_uses_fast_model_setting() -> None:
    """StackAnalyst routes to ``claude_fast_model``, not the default.

    This is the one class attribute override that materially changes
    dispatch behaviour; locking it here catches a copy-paste from BA
    that would silently route every stack-classify call through the
    default-model slot.
    """
    agent = StackAnalystSpecialist()
    assert agent.model_setting_attr == "claude_fast_model"
    assert agent.max_output_tokens == 1024
