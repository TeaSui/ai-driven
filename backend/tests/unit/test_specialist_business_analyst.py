"""Unit tests for BusinessAnalystSpecialist with mocked Claude.

Three invariants locked here:

  1. Happy path — a valid Claude response with a schema-correct payload
     becomes an Artifact whose role/verdict/payload/tokens round-trip.
  2. Block path — verdict='block' with blocking_questions passes the
     Pydantic schema and surfaces verbatim in the artifact payload.
  3. User-prompt envelope — ``_build_user_prompt`` MUST route through
     ``_build_initial_message`` so revision §5's XML envelope mandate
     can't be silently bypassed by a subclass rewrite.

The ``_claude_returns`` helper simulates the exact response shape
:class:`SpecialistBase.run_specialist` expects: the prefill ``{`` is
dropped from ``text`` because the base re-attaches it before parsing,
and ``stop_reason='stop_sequence'`` clears the truncation guard (revision
§3). Any regression that forgets either detail will produce
``verdict='error'`` instead of the expected outcome.
"""
from __future__ import annotations

import json
from types import SimpleNamespace
from unittest.mock import AsyncMock

import pytest

from ai_driven.agents.specialists.base import SpecialistContext
from ai_driven.agents.specialists.business_analyst.agent import (
    BusinessAnalystSpecialist,
)


def _ctx(description: str = "Add a /login endpoint that returns a JWT.") -> SpecialistContext:
    return SpecialistContext(
        task_id="t-ba",
        ticket={
            "key": "KAN-1",
            "summary": "Login endpoint",
            "description": description,
            "labels": [],
            "acceptance_criteria": [],
        },
        repo={"full_name": "org/repo", "scm_type": "github", "default_branch": "main"},
        tier="full_workflow",
    )


def _claude_returns(
    payload: dict,
    verdict: str = "pass",
    stop_reason: str = "stop_sequence",
) -> AsyncMock:
    """Build an AsyncMock returning a MessageResponse-shaped namespace.

    The base class:
      * re-attaches the ``{`` prefill before parsing — so we strip the
        leading ``{`` off the serialised body here,
      * guards on ``stop_reason == "stop_sequence"`` — so we default to
        that; override in a negative-path test if needed.
    """
    body = {"verdict": verdict, "reasoning": "ok", "payload": payload}
    text = json.dumps(body)[1:]  # drop leading "{" — prefill provides it
    return AsyncMock(return_value=SimpleNamespace(
        text=text + "\n",
        stop_reason=stop_reason,
        usage=SimpleNamespace(input_tokens=120, output_tokens=80),
    ))


@pytest.mark.asyncio
async def test_business_analyst_returns_validated_artifact() -> None:
    agent = BusinessAnalystSpecialist()
    agent._publish_event = AsyncMock()  # type: ignore[method-assign]
    agent._claude.chat = _claude_returns({  # type: ignore[method-assign]
        "ambiguities_resolved": [
            {"question": "JWT lifetime?", "resolution": "1h default"}
        ],
        "acceptance_criteria": [
            {"given": "valid creds", "when": "POST /login", "then": "returns JWT"}
        ],
        "out_of_scope": ["password reset"],
        "blocking_questions": [],
    })

    art = await agent.run_specialist(_ctx())

    assert art.role == "business_analyst"
    assert art.verdict == "pass"
    assert art.payload["acceptance_criteria"][0]["given"] == "valid creds"
    assert art.payload["ambiguities_resolved"][0]["question"] == "JWT lifetime?"
    assert art.payload["out_of_scope"] == ["password reset"]
    assert art.input_tokens == 120
    assert art.output_tokens == 80


@pytest.mark.asyncio
async def test_business_analyst_block_on_ambiguity() -> None:
    agent = BusinessAnalystSpecialist()
    agent._publish_event = AsyncMock()  # type: ignore[method-assign]
    agent._claude.chat = _claude_returns(  # type: ignore[method-assign]
        {
            "ambiguities_resolved": [],
            "acceptance_criteria": [],
            "out_of_scope": [],
            "blocking_questions": ["Which OAuth provider?"],
        },
        verdict="block",
    )

    art = await agent.run_specialist(_ctx("Add OAuth login somehow."))

    assert art.verdict == "block"
    assert art.payload["blocking_questions"] == ["Which OAuth provider?"]


def test_business_analyst_user_prompt_contains_xml_envelope() -> None:
    """_build_user_prompt must route through _build_initial_message.

    Revision §5 mandates every specialist's user prompt start with the
    base-class XML envelope so user-content escaping isn't bypassed. A
    subclass that inlined markdown sections (the pre-revision shape
    from the plan) would fail this test by missing one or more of the
    envelope tags — which is exactly the regression we want to block.
    """
    agent = BusinessAnalystSpecialist()
    prompt = agent._build_user_prompt(_ctx())

    # Envelope tags produced by _build_initial_message.
    assert "<task_role>" in prompt
    assert "<jira_ticket>" in prompt
    assert "<repository>" in prompt
    assert "<prior_artifacts>" in prompt

    # Role-specific trailing instruction appended by this specialist.
    assert "<task>" in prompt
    assert "</task>" in prompt
    assert "BusinessAnalyst artifact JSON" in prompt
