"""Stub specialists short-circuit with verdict='not_implemented'."""
from __future__ import annotations

import pytest

from ai_driven.agents.specialists.api_test.agent import ApiTestSpecialist
from ai_driven.agents.specialists.base import SpecialistContext
from ai_driven.agents.specialists.tech_lead.agent import TechLeadSpecialist
from ai_driven.agents.specialists.ux_designer.agent import UxDesignerSpecialist


def _ctx() -> SpecialistContext:
    return SpecialistContext(
        task_id="t-1",
        ticket={"key": "K-1", "summary": "x", "description": "y", "labels": []},
        repo={"full_name": "org/repo", "scm_type": "github"},
        tier="multi_domain",
    )


@pytest.mark.asyncio
@pytest.mark.parametrize("klass,role", [
    (TechLeadSpecialist, "tech_lead"),
    (UxDesignerSpecialist, "ux_designer"),
    (ApiTestSpecialist, "api_test"),
])
async def test_stub_returns_not_implemented(klass, role):
    spec = klass()
    art = await spec.run_specialist(_ctx())
    assert art.role == role
    assert art.verdict == "not_implemented"
    assert art.input_tokens == 0
    assert art.output_tokens == 0
