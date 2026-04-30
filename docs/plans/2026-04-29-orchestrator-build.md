# Orchestrator + Specialists — Phase 1 (Build / Shadow Mode) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Stand up `OrchestratorAgent` + four live specialists (BA, StackAnalyst, Security, QA) and three stubs (TechLead, UXDesigner, APITest) running in **shadow mode** — classifying every task, dispatching specialists, persisting `task_artifacts` rows, but with **zero downstream effect** on the existing CodingAgent → ReviewerAgent pipeline. Phase 2 (per-team live) and Phase 3 (WorkflowAgent deletion) are out of scope.

**Architecture:** A new `OrchestratorAgent` (BaseAgent subclass) is the entry point for every task. It classifies tier with a `settings.claude_fast_model` call (currently Sonnet 4.6 post-H1 — see revision doc), runs specialists sequentially, fans out prior artifacts via `fan_out.py`, and persists artifacts to a new `task_artifacts` table. Specialists are subclasses of a new `SpecialistBase` that produces `Artifact` objects via prefill + `stop_sequences=["```"]` + Pydantic-validated JSON. The reviewer's existing JSON-repair loop is lifted into `BaseAgent` as `_repair_or_escalate_json` and reused by every specialist. In Phase 1, the orchestrator's mode is `shadow` by default — it writes artifacts + `tasks.orchestration_tier` but does NOT update `tasks.status`, does NOT gate the existing pipeline, and runs as a fire-and-forget sibling to the coding pipeline (via `asyncio.create_task`, bypassing the SQS queue entirely). CodingAgent and ReviewerAgent are unchanged.

**Shadow-mode invariant (hard rule):** While `orchestrator_mode == "shadow"`, the orchestrator and specialists MUST NOT update `tasks.status`, MUST NOT write to any row outside `task_artifacts` and `tasks.orchestration_tier`, and MUST NOT raise exceptions out of the fire-and-forget task. The coding pipeline owns task status; the orchestrator is a silent observer.

**Tech Stack:** Python 3.11, FastAPI, SQLAlchemy 2.x async, Alembic, Pydantic v2, boto3 (SQS), redis-py async, Anthropic SDK (all model slots currently resolve to Sonnet 4.6 post-H1; fast-model slot kept as indirection for future Haiku re-introduction), pytest + pytest-asyncio.

**Reference spec:** [docs/specs/2026-04-29-orchestrator-specialists-design.md](../specs/2026-04-29-orchestrator-specialists-design.md)
**Spec revision (authoritative):** [docs/specs/2026-04-30-orchestrator-revision.md](../specs/2026-04-30-orchestrator-revision.md) — read this BEFORE starting any task. Reconciles H1–H5 master landings; supersedes original-spec assumptions about Haiku, cost accounting, repo tools, and prompt structure.

---

## Red-team revisions applied to this plan (2026-04-30)

A red-team pass over the first draft surfaced 3 BLOCKERs, 6 MAJORs, 6 MINORs. Fixes baked into the plan text:

**Applied to Tasks 1–5 (the checkpoint batch):**
- Task 1: Migration creates `pgcrypto` extension before calling `gen_random_uuid()`.
- Task 2: ORM `TaskArtifact.payload` uses `JSONB` (not `JSON`) to match the migration; `from sqlalchemy.dialects.postgresql import JSONB` added.
- Task 3: Top-level payload schemas inherit `_Tolerant` (`extra="ignore"`) so live LLM output with stray fields like `confidence` still validates; nested sub-models keep `extra="forbid"` to catch structural drift. Two new tests assert this.
- Task 5: `_repair_or_escalate_json` in `BaseAgent` NEVER writes `tasks.status`. The pre-existing `_update_task_status("needs_human_review")` line in the reviewer was silently failing the `ck_tasks_status` CHECK constraint and is removed with the refactor. A new test asserts `_update_task_status` is never called during escalation.

**To be applied in Tasks 6–17 (second checkpoint batch):**
- Task 6 (`SpecialistBase`): emit `specialist.completed` event on every terminal return path (success + error). Strip the char/4 token-estimate pre-flight (cosmetic; real token counts come from the SDK). Assert `response.stop_reason == "stop_sequence"` after prefill+stop chats so mid-output truncation is fail-loud.
- Task 7 (stubs): `StubSpecialist.run_specialist` emits `specialist.completed` before returning.
- Task 12 (classifier): bump `max_tokens` from 8 to 16. Expand `parse_tier` strip set to include `"\"'.,` and publish an `orchestrator.tier_fallback` event whenever the safe default fires so metrics surface silent misclassification.
- Task 16 (`OrchestratorAgent`): `_persist_tier` is the ONLY `tasks` row write the orchestrator makes; it must NOT call `_update_task_status`. Remove the unreachable `if role == "ux_designer"` branch in `orchestrate()` (Phase 1 never runs UX — filter lives at the picker stage for Phase 2). Accumulate classifier + specialist token counts so the `_OrchestratorWrapper` returns a non-zero `AgentResult`.
- Task 17 (producer/consumer wiring): DO NOT enqueue `"orchestrator"` through `enqueue_task`. Instead, `_enqueue_shadow_orchestrator` fires `asyncio.create_task(OrchestratorAgent().orchestrate(...))` directly — the queue/inline-fallback path doesn't know about the orchestrator and would silently drop the call. Integration test: replace the linear `iter([...])` chat mock with a role-router that inspects the system prompt so repair calls don't desync specialist responses.

Issues reviewed but intentionally skipped: char/4 token estimator (removed, not replaced — server-side 413 is good enough), JSONB round-trip None handling (non-issue), fenced-JSON regex (works; cleanup deferred to spec C).

---

## Spec-revision updates applied to this plan (2026-04-30, post-H1..H5)

Five hotfixes (H1–H5) and one prompt refactor (42ddf91) landed on master after this plan was drafted. See `docs/specs/2026-04-30-orchestrator-revision.md` for full context. Plan edits that MUST be applied during execution of each affected task are summarized here so no executor misses them:

**Cross-cutting rules:**
- Specialists MUST resolve models via `get_settings().claude_default_model` or `.claude_fast_model` — never hardcode `"claude-haiku-4-5"` or any model string literal. A unit test in Task 6 locks this.
- Specialist system prompts and first user messages MUST use XML-tagged structure per revision §3 and §6 — not markdown headers. The coding agent's `_build_initial_message` at `src/ai_driven/agents/coding/agent.py` is the reference pattern.

**Per-task overrides (revision-driven):**

- **Task 1 (migration):** Also add `cost_usd NUMERIC(10,6) DEFAULT 0` column to `task_artifacts` per revision §2.2 (shadow-mode cost observability). The commit cherry-picked on `feature/orchestrator-phase1` predates this revision, so land an additive ALTER migration as the final step of Task 1 rather than amending the existing migration file.
- **Task 2 (ORM):** Mirror the new `cost_usd` column as `TaskArtifact.cost_usd: Mapped[Decimal]` in the ORM.
- **Task 5 (repair loop lift):** Expanded — see the "Task 5 additions" section below; requires TWO new items beyond the cherry-picked commit.
- **Task 6 (SpecialistBase):** Model IDs resolve via `get_settings()`. `_build_initial_message()` uses the XML template from revision §6. Add a unit test asserting no specialist hardcodes a model string.
- **Task 8 (BA):** System prompt uses XML template from revision §3.
- **Task 9 (stack_analyst):** Tools list = `["repo.overview"]` only (NOT `repo.list_files`, which the original spec suggested). System prompt uses XML template. Rename task heading: "StackAnalyst specialist (live, Sonnet)" (not Haiku). Model = `settings.claude_fast_model`.
- **Task 10 (security):** System prompt uses XML template.
- **Task 11 (QA):** System prompt uses XML template. Tools list = `["github.get_pr_diff", "repo.read_file", "repo.overview"]` (corrects original spec's invalid `scm.read_diff`, `tests.list_run_results`).
- **Task 12 (classifier):** Model = `settings.claude_fast_model`, NOT `"claude-haiku-4-5"`. `max_tokens=16` (already in red-team block, reaffirmed).
- **Task 13 (routing rules):** No changes — this is the pure-Python routing module. Do NOT confuse with specialist picker (which lives inside Task 16 and is stubbed for Phase 2).
- **Task 16 (OrchestratorAgent):** Threads `running_cost_usd` through every specialist via `initial_cost` kwarg (new in Task 5). On any specialist's `budget_exceeded` result, write `Artifact(verdict='error', reasoning='budget_exceeded after <role>', payload={})` and halt. Picker stays stubbed to `return []` per revision §Open Questions #1 — if/when implemented, use `settings.claude_fast_model` and `max_tokens=64`.
- **Task 17 (wiring):** No change — shadow-mode fire-and-forget path is unaffected by the revision.

### Task 5 additions (beyond the cherry-picked commit 0aae9b9)

The cherry-picked commit lifts the JSON repair loop AND removes the invalid `_update_task_status("needs_human_review")` call. Two further changes required before closing Task 5:

1. **Add `initial_cost: float = 0.0` kwarg to `BaseAgent.run()`** with a pre-iteration short-circuit. Spec rationale: revision §2. Behavior: if `initial_cost >= settings.claude_max_cost_per_task_usd` on entry, return `AgentResult(success=False, reason='budget_exceeded_on_entry', cost_usd=initial_cost)` WITHOUT invoking `run_agent_loop`. The existing `_should_continue` callback only fires BETWEEN iterations (see `claude/client.py:108`), so an entry check is needed to prevent one wasted Claude call.

2. **Unit test for the pre-iteration short-circuit.** Use a `MockProvider` with a recording `create_message` and assert zero invocations when `BaseAgent.run(initial_cost=settings.claude_max_cost_per_task_usd + 0.01)` is called.

Both land in the same commit or a follow-up (Task 5b). They do NOT block cherry-picking the existing T5 commit; treat as an amendment.

---

## File Structure

### New files

```
src/ai_driven/
  agents/
    orchestrator/
      __init__.py
      agent.py                     # OrchestratorAgent (BaseAgent subclass)
      artifacts.py                 # Pydantic payload schemas + Artifact dataclass
      classifier.py                # Tier classifier (fast-model call + parser + fallback)
      routing.py                   # TIER_RULES + plan_specialist_sequence()
      fan_out.py                   # relevant_artifacts_for(role, all_artifacts)
      repository.py                # ArtifactRepository (read/write task_artifacts)
    specialists/
      __init__.py
      base.py                      # SpecialistBase + SpecialistContext
      business_analyst/__init__.py
      business_analyst/agent.py
      stack_analyst/__init__.py
      stack_analyst/agent.py
      security/__init__.py
      security/agent.py
      qa/__init__.py
      qa/agent.py
      tech_lead/__init__.py
      tech_lead/agent.py           # stub — verdict='not_implemented'
      ux_designer/__init__.py
      ux_designer/agent.py         # stub
      api_test/__init__.py
      api_test/agent.py            # stub
  claude/prompts/
    orchestrator.py                # TIER_CLASSIFIER_PROMPT + PICKER_PROMPT
    specialists/
      __init__.py
      business_analyst.py
      stack_analyst.py
      security.py
      qa.py

alembic/versions/
  a1b2c3d4e5fa_orchestration_artifacts.py

tests/unit/
  test_orchestrator_artifacts.py
  test_orchestrator_routing.py
  test_orchestrator_fan_out.py
  test_orchestrator_classifier.py
  test_orchestrator_agent.py
  test_specialist_business_analyst.py
  test_specialist_stack_analyst.py
  test_specialist_security.py
  test_specialist_qa.py
  test_specialist_stubs.py
  test_base_agent_repair_loop.py

tests/integration/
  test_orchestrator_shadow_mode.py
```

### Modified files

```
src/ai_driven/
  config.py                        # + orchestrator_mode, sqs_orchestrator_queue_url, sqs_specialists_queue_url
  agents/base.py                   # + _repair_or_escalate_json generic helper
  agents/reviewer/agent.py         # refactor _repair_or_escalate_output to call generic helper
  db/postgres/models.py            # + TaskArtifact, + Task.orchestration_tier
  queue/producer.py                # + 'orchestrator' & 'specialist' entries in queue map
  queue/consumer.py                # + orchestrator queue + specialist queue dispatch
```

### Out of scope for this plan (deferred to Phase 2 / 3)

- `prompt_composer.py`, `stack_guides/`, CodingAgent reading artifacts — Phase 2.
- `teams.orchestration_enabled` column, UI Artifacts tab, dashboards — Phase 2.
- WorkflowAgent deletion, state_machine.py / triggers.py relocation — Phase 3.
- Eval suite (`tests/evals/specialists/`), Playwright e2e — deferred.
- Doc updates to `docs/implementation/00-overview.md` etc. — Phase 2.

---

## Naming conventions used throughout this plan (locked)

| Symbol | Definition |
|---|---|
| `Artifact` | dataclass in `orchestrator/artifacts.py`: `role`, `verdict`, `reasoning`, `payload`, `model_used`, `input_tokens`, `output_tokens` |
| `TaskArtifact` | SQLAlchemy ORM model in `db/postgres/models.py` mirroring the `task_artifacts` table |
| `SpecialistContext` | dataclass in `specialists/base.py`: `task_id`, `ticket`, `repo`, `tier`, `prior_artifacts: dict[str, Artifact]`, `team_settings: dict` |
| Roles (string literals) | `business_analyst`, `stack_analyst`, `security`, `qa`, `tech_lead`, `ux_designer`, `api_test` |
| Verdicts | `pass`, `pass_with_notes`, `block`, `error`, `not_implemented` |
| Tiers | `fast_path`, `full_workflow`, `multi_domain` |
| Orchestrator modes | `shadow` (default), `live`, `off` |

---

## Task 1: Alembic migration — `task_artifacts` table + `tasks.orchestration_tier`

**Files:**
- Create: `alembic/versions/a1b2c3d4e5fa_orchestration_artifacts.py`

- [ ] **Step 1: Confirm latest revision is `a1b2c3d4e5f9`**

```bash
ls alembic/versions/ | sort
```
Expected: `a1b2c3d4e5f9_task_status_check.py` is the latest before any new files.

- [ ] **Step 2: Create the migration file**

```python
"""Add task_artifacts table and tasks.orchestration_tier column.

Revision ID: a1b2c3d4e5fa
Revises: a1b2c3d4e5f9
Create Date: 2026-04-29

Phase 1 of the orchestrator + specialists rollout. Adds:
  * task_artifacts: one row per (task_id, role) holding the structured
    output of a specialist run (BA, stack_analyst, security, qa, etc.).
  * tasks.orchestration_tier: classifier output (fast_path|full_workflow|
    multi_domain). NULL for pre-orchestrator tasks.

Both changes are additive and zero-downtime. See:
docs/specs/2026-04-29-orchestrator-specialists-design.md
"""
from typing import Sequence, Union

import sqlalchemy as sa
from alembic import op

revision: str = "a1b2c3d4e5fa"
down_revision: Union[str, None] = "a1b2c3d4e5f9"
branch_labels: Union[str, Sequence[str], None] = None
depends_on: Union[str, Sequence[str], None] = None


_VALID_ROLES = (
    "business_analyst",
    "stack_analyst",
    "security",
    "qa",
    "tech_lead",
    "ux_designer",
    "api_test",
)
_VALID_VERDICTS = (
    "pass",
    "pass_with_notes",
    "block",
    "error",
    "not_implemented",
)
_VALID_TIERS = ("fast_path", "full_workflow", "multi_domain")


def upgrade() -> None:
    # pgcrypto provides gen_random_uuid(). It is NOT guaranteed to be
    # pre-installed on the target Postgres (depends on image/version).
    op.execute("CREATE EXTENSION IF NOT EXISTS pgcrypto;")

    op.create_table(
        "task_artifacts",
        sa.Column(
            "id",
            sa.dialects.postgresql.UUID(as_uuid=True),
            primary_key=True,
            server_default=sa.text("gen_random_uuid()"),
        ),
        sa.Column(
            "task_id",
            sa.dialects.postgresql.UUID(as_uuid=True),
            sa.ForeignKey("tasks.id", ondelete="CASCADE"),
            nullable=False,
        ),
        sa.Column("role", sa.String(32), nullable=False),
        sa.Column("payload", sa.dialects.postgresql.JSONB, nullable=False),
        sa.Column("verdict", sa.String(16), nullable=False),
        sa.Column("reasoning", sa.Text, nullable=True),
        sa.Column("model_used", sa.String(100), nullable=True),
        sa.Column("input_tokens", sa.Integer, server_default="0"),
        sa.Column("output_tokens", sa.Integer, server_default="0"),
        sa.Column(
            "created_at",
            sa.DateTime(timezone=True),
            server_default=sa.func.now(),
            nullable=False,
        ),
        sa.CheckConstraint(
            "role IN (" + ",".join(f"'{r}'" for r in _VALID_ROLES) + ")",
            name="ck_task_artifacts_role",
        ),
        sa.CheckConstraint(
            "verdict IN (" + ",".join(f"'{v}'" for v in _VALID_VERDICTS) + ")",
            name="ck_task_artifacts_verdict",
        ),
    )
    op.create_index(
        "idx_task_artifacts_task", "task_artifacts", ["task_id"], unique=False,
    )
    op.create_index(
        "idx_task_artifacts_task_role",
        "task_artifacts",
        ["task_id", "role"],
        unique=True,
    )

    op.add_column(
        "tasks",
        sa.Column("orchestration_tier", sa.String(16), nullable=True),
    )
    op.create_check_constraint(
        "ck_tasks_orchestration_tier",
        "tasks",
        "orchestration_tier IS NULL OR orchestration_tier IN ("
        + ",".join(f"'{t}'" for t in _VALID_TIERS)
        + ")",
    )


def downgrade() -> None:
    op.drop_constraint("ck_tasks_orchestration_tier", "tasks", type_="check")
    op.drop_column("tasks", "orchestration_tier")
    op.drop_index("idx_task_artifacts_task_role", table_name="task_artifacts")
    op.drop_index("idx_task_artifacts_task", table_name="task_artifacts")
    op.drop_table("task_artifacts")
```

- [ ] **Step 3: Run migration up against local Postgres**

```bash
alembic upgrade head
```
Expected output: `Running upgrade a1b2c3d4e5f9 -> a1b2c3d4e5fa, Add task_artifacts table and tasks.orchestration_tier column`

- [ ] **Step 4: Verify schema**

```bash
psql "postgresql://ai_driven:ai_driven@localhost:5432/ai_driven" -c "\d task_artifacts"
psql "postgresql://ai_driven:ai_driven@localhost:5432/ai_driven" -c "\d tasks" | grep orchestration_tier
```
Expected: `task_artifacts` table listed with all columns + the two indexes; `orchestration_tier | character varying(16)` row in `tasks` description.

- [ ] **Step 5: Verify down-migration is clean**

```bash
alembic downgrade -1 && alembic upgrade head
```
Expected: down then up succeeds with no errors.

- [ ] **Step 6: Commit**

```bash
git add alembic/versions/a1b2c3d4e5fa_orchestration_artifacts.py
git commit -m "feat(db): add task_artifacts table and tasks.orchestration_tier"
```

---

## Task 2: SQLAlchemy `TaskArtifact` model + `Task.orchestration_tier` column

**Files:**
- Modify: `src/ai_driven/db/postgres/models.py` (add new model + column at end of file)
- Test: `tests/unit/test_orchestrator_artifacts.py` (db model section only)

- [ ] **Step 1: Write the failing test**

Create `tests/unit/test_orchestrator_artifacts.py`:

```python
"""Unit tests for TaskArtifact ORM model and orchestration_tier column."""
from __future__ import annotations

import uuid

import pytest
from sqlalchemy import select

from ai_driven.db.postgres.models import Task, TaskArtifact


def test_task_artifact_model_columns() -> None:
    cols = {c.name for c in TaskArtifact.__table__.columns}
    assert cols == {
        "id", "task_id", "role", "payload", "verdict", "reasoning",
        "model_used", "input_tokens", "output_tokens", "created_at",
    }


def test_task_artifact_unique_constraint_on_task_role() -> None:
    indexes = {idx.name: list(idx.columns.keys()) for idx in TaskArtifact.__table__.indexes}
    assert "idx_task_artifacts_task_role" in indexes
    assert indexes["idx_task_artifacts_task_role"] == ["task_id", "role"]


def test_task_has_orchestration_tier_column() -> None:
    cols = {c.name for c in Task.__table__.columns}
    assert "orchestration_tier" in cols
```

- [ ] **Step 2: Run test to verify it fails**

```bash
pytest tests/unit/test_orchestrator_artifacts.py -v
```
Expected: ImportError or AttributeError on `TaskArtifact` and `Task.orchestration_tier`.

- [ ] **Step 3: Add the model + column**

Append to `src/ai_driven/db/postgres/models.py` (after the last existing model).

Use `JSONB` (not `JSON`) to match the migration and to enable future containment/GIN queries:

```python
class TaskArtifact(Base):
    """Structured output of a specialist run, one row per (task_id, role)."""

    __tablename__ = "task_artifacts"

    id: Mapped[uuid.UUID] = mapped_column(
        UUID(as_uuid=True), primary_key=True, default=uuid.uuid4
    )
    task_id: Mapped[uuid.UUID] = mapped_column(
        UUID(as_uuid=True),
        ForeignKey("tasks.id", ondelete="CASCADE"),
        nullable=False,
    )
    role: Mapped[str] = mapped_column(String(32), nullable=False)
    payload: Mapped[dict] = mapped_column(JSONB, nullable=False)
    verdict: Mapped[str] = mapped_column(String(16), nullable=False)
    reasoning: Mapped[str | None] = mapped_column(Text, nullable=True)
    model_used: Mapped[str | None] = mapped_column(String(100), nullable=True)
    input_tokens: Mapped[int] = mapped_column(Integer, default=0)
    output_tokens: Mapped[int] = mapped_column(Integer, default=0)
    created_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True), default=utcnow
    )

    __table_args__ = (
        sa.Index("idx_task_artifacts_task", "task_id"),
        sa.Index("idx_task_artifacts_task_role", "task_id", "role", unique=True),
    )
```

Add these imports near the top of `models.py` if not already present:

```python
import sqlalchemy as sa
from sqlalchemy.dialects.postgresql import JSONB
```

Then in the existing `Task` class, add this column (place near `agent_type`):

```python
    orchestration_tier: Mapped[str | None] = mapped_column(String(16), nullable=True)
```

- [ ] **Step 4: Run test to verify it passes**

```bash
pytest tests/unit/test_orchestrator_artifacts.py -v
```
Expected: 3 passed.

- [ ] **Step 5: Commit**

```bash
git add src/ai_driven/db/postgres/models.py tests/unit/test_orchestrator_artifacts.py
git commit -m "feat(db): add TaskArtifact ORM model and Task.orchestration_tier"
```

---

## Task 3: Pydantic artifact payload schemas + `Artifact` dataclass

**Files:**
- Create: `src/ai_driven/agents/orchestrator/__init__.py` (empty)
- Create: `src/ai_driven/agents/orchestrator/artifacts.py`
- Test: `tests/unit/test_orchestrator_artifacts.py` (extend existing file)

- [ ] **Step 1: Write the failing test**

Append to `tests/unit/test_orchestrator_artifacts.py`:

```python
from pydantic import ValidationError

from ai_driven.agents.orchestrator.artifacts import (
    Artifact,
    BusinessAnalystPayload,
    StackAnalystPayload,
    SecurityPayload,
    QaPayload,
    TechLeadPayload,
    UxDesignerPayload,
    ApiTestPayload,
    PAYLOAD_SCHEMAS,
    validate_payload,
)


def test_business_analyst_payload_accepts_full_shape() -> None:
    p = BusinessAnalystPayload(
        ambiguities_resolved=[{"question": "Q?", "resolution": "R."}],
        acceptance_criteria=[{"given": "g", "when": "w", "then": "t"}],
        out_of_scope=["x"],
        blocking_questions=[],
    )
    assert p.acceptance_criteria[0].given == "g"


def test_stack_analyst_payload_rejects_unknown_stack() -> None:
    with pytest.raises(ValidationError):
        StackAnalystPayload(
            stacks=["backend", "spaceship"],  # not in fixed set
            primary_stack="backend",
            cross_cutting_concerns=[],
            reasoning="r",
        )


def test_security_payload_severity_enum() -> None:
    with pytest.raises(ValidationError):
        SecurityPayload(
            threat_model=[{
                "stride_category": "S",
                "threat": "t",
                "severity": "ULTRA",  # invalid
                "mitigation": "m",
            }],
            implementation_rules=[],
            compliance_notes=[],
            blocking_concerns=[],
        )


def test_qa_payload_regression_risk_enum() -> None:
    QaPayload(
        checks_performed=[{"check": "c", "passed": True, "detail": "d"}],
        test_coverage_gap=[],
        regression_risk="low",
    )
    with pytest.raises(ValidationError):
        QaPayload(
            checks_performed=[],
            test_coverage_gap=[],
            regression_risk="apocalyptic",
        )


def test_payload_schemas_registry_has_seven_roles() -> None:
    assert set(PAYLOAD_SCHEMAS) == {
        "business_analyst", "stack_analyst", "security", "qa",
        "tech_lead", "ux_designer", "api_test",
    }


def test_validate_payload_dispatches_by_role() -> None:
    out = validate_payload("business_analyst", {
        "ambiguities_resolved": [],
        "acceptance_criteria": [],
        "out_of_scope": [],
        "blocking_questions": [],
    })
    assert isinstance(out, BusinessAnalystPayload)


def test_payload_ignores_unknown_top_level_keys() -> None:
    """Live LLM output may decorate the payload with 'confidence' etc.
    These must be ignored, not rejected — validating the required shape
    is what matters; stray keys are dropped on model_dump()."""
    out = validate_payload("business_analyst", {
        "ambiguities_resolved": [],
        "acceptance_criteria": [],
        "out_of_scope": [],
        "blocking_questions": [],
        "confidence": 0.92,
        "notes": "extra field the model added",
    })
    dumped = out.model_dump()
    assert "confidence" not in dumped
    assert "notes" not in dumped


def test_nested_submodels_still_forbid_extras() -> None:
    """Structural drift in a nested entry IS a real bug — catch it."""
    with pytest.raises(ValidationError):
        validate_payload("security", {
            "threat_model": [{
                "stride_category": "Spoofing",
                "threat": "t", "severity": "high", "mitigation": "m",
                "bogus_extra": True,
            }],
            "implementation_rules": [],
            "compliance_notes": [],
            "blocking_concerns": [],
        })


def test_artifact_dataclass_carries_all_fields() -> None:
    a = Artifact(
        role="business_analyst",
        verdict="pass",
        reasoning="ok",
        payload={"ambiguities_resolved": [], "acceptance_criteria": [],
                 "out_of_scope": [], "blocking_questions": []},
        model_used="claude-sonnet-4-6",
        input_tokens=100,
        output_tokens=50,
    )
    assert a.role == "business_analyst" and a.input_tokens == 100
```

- [ ] **Step 2: Run test to verify it fails**

```bash
pytest tests/unit/test_orchestrator_artifacts.py -v
```
Expected: ImportError on `ai_driven.agents.orchestrator.artifacts`.

- [ ] **Step 3: Implement artifacts module**

Create `src/ai_driven/agents/orchestrator/__init__.py` (empty).

Create `src/ai_driven/agents/orchestrator/artifacts.py`:

```python
"""Pydantic payload schemas + Artifact dataclass for specialist outputs.

Every specialist returns an Artifact whose `payload` is validated against
the role-specific Pydantic model from PAYLOAD_SCHEMAS. The orchestrator
persists Artifacts to the task_artifacts table after validation.

Schemas mirror the shapes in docs/specs/2026-04-29-orchestrator-specialists-design.md.
"""
from __future__ import annotations

from dataclasses import dataclass
from typing import Any, Literal

from pydantic import BaseModel, ConfigDict


Role = Literal[
    "business_analyst", "stack_analyst", "security",
    "qa", "tech_lead", "ux_designer", "api_test",
]
Verdict = Literal["pass", "pass_with_notes", "block", "error", "not_implemented"]
Stack = Literal["backend", "frontend", "mobile", "devops", "data", "ai", "aws"]
Severity = Literal["low", "medium", "high", "critical"]
RegressionRisk = Literal["low", "medium", "high"]


# --- Sub-models -------------------------------------------------------------

class _Strict(BaseModel):
    """Base for nested sub-models — forbid extras to catch structural drift."""
    model_config = ConfigDict(extra="forbid")


class _Tolerant(BaseModel):
    """Base for top-level payloads — IGNORE unknown top-level keys.

    Real LLM output occasionally decorates the payload with extra fields
    like 'confidence' or 'notes'. We validate the required shape but
    accept additional keys (they won't round-trip into Artifact.payload
    via model_dump() — extras are dropped). This keeps live specialists
    from failing validation on cosmetic noise.
    """
    model_config = ConfigDict(extra="ignore")


class AmbiguityResolution(_Strict):
    question: str
    resolution: str


class AcceptanceCriterion(_Strict):
    given: str
    when: str
    then: str


class ThreatEntry(_Strict):
    stride_category: str
    threat: str
    severity: Severity
    mitigation: str


class QaCheck(_Strict):
    check: str
    passed: bool
    detail: str


class SelectedTech(_Strict):
    choice: str
    rationale: str


class UserFlowStep(_Strict):
    step: int
    action: str
    screen: str


class EndpointTested(_Strict):
    path: str
    method: str
    status: int
    p95_ms: float


# --- Per-role payload schemas ----------------------------------------------
# Top-level schemas use _Tolerant: we validate required shape but ignore
# stray extras. Nested sub-models use _Strict: structural drift in a nested
# ThreatEntry or AcceptanceCriterion is a real bug we want to surface.

class BusinessAnalystPayload(_Tolerant):
    ambiguities_resolved: list[AmbiguityResolution]
    acceptance_criteria: list[AcceptanceCriterion]
    out_of_scope: list[str]
    blocking_questions: list[str]


class StackAnalystPayload(_Tolerant):
    stacks: list[Stack]
    primary_stack: Stack
    cross_cutting_concerns: list[str]
    reasoning: str


class SecurityPayload(_Tolerant):
    threat_model: list[ThreatEntry]
    implementation_rules: list[str]
    compliance_notes: list[str]
    blocking_concerns: list[str]


class QaPayload(_Tolerant):
    checks_performed: list[QaCheck]
    test_coverage_gap: list[str]
    regression_risk: RegressionRisk


class TechLeadPayload(_Tolerant):
    adr_summary: str
    api_contracts: list[dict]
    selected_tech: list[SelectedTech]
    delegation_plan: list[str]


class UxDesignerPayload(_Tolerant):
    wireframe_refs: list[str]
    user_flows: list[UserFlowStep]
    accessibility_notes: list[str]


class ApiTestPayload(_Tolerant):
    endpoints_tested: list[EndpointTested]
    security_smoke_results: list[dict]
    failures: list[str]


PAYLOAD_SCHEMAS: dict[str, type[BaseModel]] = {
    "business_analyst": BusinessAnalystPayload,
    "stack_analyst": StackAnalystPayload,
    "security": SecurityPayload,
    "qa": QaPayload,
    "tech_lead": TechLeadPayload,
    "ux_designer": UxDesignerPayload,
    "api_test": ApiTestPayload,
}


def validate_payload(role: str, raw: dict[str, Any]) -> BaseModel:
    """Validate `raw` against the schema for `role`. Raises ValidationError."""
    schema = PAYLOAD_SCHEMAS.get(role)
    if schema is None:
        raise ValueError(f"Unknown specialist role: {role}")
    return schema.model_validate(raw)


# --- Artifact dataclass ----------------------------------------------------

@dataclass
class Artifact:
    """Persistence-ready record of one specialist run."""

    role: str
    verdict: str
    reasoning: str
    payload: dict[str, Any]   # validated dict (post-Pydantic round-trip)
    model_used: str
    input_tokens: int
    output_tokens: int
```

- [ ] **Step 4: Run test to verify it passes**

```bash
pytest tests/unit/test_orchestrator_artifacts.py -v
```
Expected: 10 passed.

- [ ] **Step 5: Commit**

```bash
git add src/ai_driven/agents/orchestrator/__init__.py src/ai_driven/agents/orchestrator/artifacts.py tests/unit/test_orchestrator_artifacts.py
git commit -m "feat(orchestrator): add Pydantic payload schemas and Artifact dataclass"
```

---

## Task 4: `ArtifactRepository` — DB read/write for `task_artifacts`

**Files:**
- Create: `src/ai_driven/agents/orchestrator/repository.py`
- Test: `tests/unit/test_orchestrator_artifacts.py` (extend)

- [ ] **Step 1: Write the failing test**

Append to `tests/unit/test_orchestrator_artifacts.py`:

```python
from unittest.mock import AsyncMock, MagicMock

from ai_driven.agents.orchestrator.repository import ArtifactRepository


@pytest.mark.asyncio
async def test_artifact_repository_upsert_inserts_new_row() -> None:
    session = AsyncMock()
    session.execute = AsyncMock(return_value=MagicMock(scalar_one_or_none=MagicMock(return_value=None)))
    session.add = MagicMock()
    session.flush = AsyncMock()
    session.commit = AsyncMock()

    repo = ArtifactRepository(session)
    art = Artifact(
        role="security",
        verdict="pass",
        reasoning="no issues",
        payload={"threat_model": [], "implementation_rules": [],
                 "compliance_notes": [], "blocking_concerns": []},
        model_used="claude-sonnet-4-6",
        input_tokens=120,
        output_tokens=80,
    )
    task_id = uuid.uuid4()
    await repo.upsert(task_id, art)
    session.add.assert_called_once()


@pytest.mark.asyncio
async def test_artifact_repository_upsert_updates_existing_row() -> None:
    existing = MagicMock()
    session = AsyncMock()
    session.execute = AsyncMock(return_value=MagicMock(scalar_one_or_none=MagicMock(return_value=existing)))
    session.commit = AsyncMock()

    repo = ArtifactRepository(session)
    art = Artifact(role="security", verdict="block", reasoning="r",
                   payload={"threat_model": [], "implementation_rules": [],
                            "compliance_notes": [], "blocking_concerns": ["x"]},
                   model_used="m", input_tokens=1, output_tokens=1)
    await repo.upsert(uuid.uuid4(), art)
    assert existing.verdict == "block"
    assert existing.reasoning == "r"
```

- [ ] **Step 2: Run test to verify it fails**

```bash
pytest tests/unit/test_orchestrator_artifacts.py -v
```
Expected: ImportError on `ArtifactRepository`.

- [ ] **Step 3: Implement repository**

Create `src/ai_driven/agents/orchestrator/repository.py`:

```python
"""Persistence layer for task_artifacts.

UPSERT semantics: re-runs of the same (task_id, role) pair UPDATE the
existing row rather than inserting. Repair/retry history is tracked in
the agent_events stream, not here.
"""
from __future__ import annotations

import uuid
from typing import TYPE_CHECKING

from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession

from ai_driven.agents.orchestrator.artifacts import Artifact
from ai_driven.db.postgres.models import TaskArtifact

if TYPE_CHECKING:  # pragma: no cover
    pass


class ArtifactRepository:
    def __init__(self, session: AsyncSession) -> None:
        self._session = session

    async def upsert(self, task_id: uuid.UUID, artifact: Artifact) -> None:
        existing = await self._session.execute(
            select(TaskArtifact).where(
                TaskArtifact.task_id == task_id,
                TaskArtifact.role == artifact.role,
            )
        )
        row = existing.scalar_one_or_none()
        if row is None:
            self._session.add(
                TaskArtifact(
                    task_id=task_id,
                    role=artifact.role,
                    payload=artifact.payload,
                    verdict=artifact.verdict,
                    reasoning=artifact.reasoning,
                    model_used=artifact.model_used,
                    input_tokens=artifact.input_tokens,
                    output_tokens=artifact.output_tokens,
                )
            )
        else:
            row.payload = artifact.payload
            row.verdict = artifact.verdict
            row.reasoning = artifact.reasoning
            row.model_used = artifact.model_used
            row.input_tokens = artifact.input_tokens
            row.output_tokens = artifact.output_tokens
        await self._session.commit()

    async def list_for_task(self, task_id: uuid.UUID) -> dict[str, Artifact]:
        """Return all artifacts for a task, keyed by role."""
        result = await self._session.execute(
            select(TaskArtifact).where(TaskArtifact.task_id == task_id)
        )
        out: dict[str, Artifact] = {}
        for row in result.scalars():
            out[row.role] = Artifact(
                role=row.role,
                verdict=row.verdict,
                reasoning=row.reasoning or "",
                payload=row.payload,
                model_used=row.model_used or "",
                input_tokens=row.input_tokens or 0,
                output_tokens=row.output_tokens or 0,
            )
        return out
```

- [ ] **Step 4: Run test to verify it passes**

```bash
pytest tests/unit/test_orchestrator_artifacts.py -v
```
Expected: all tests passed (12 total).

- [ ] **Step 5: Commit**

```bash
git add src/ai_driven/agents/orchestrator/repository.py tests/unit/test_orchestrator_artifacts.py
git commit -m "feat(orchestrator): add ArtifactRepository with upsert semantics"
```

---

## Task 5: Lift `_repair_or_escalate_json` into `BaseAgent`

**Files:**
- Modify: `src/ai_driven/agents/base.py` (add new method)
- Modify: `src/ai_driven/agents/reviewer/agent.py` (refactor `_repair_or_escalate_output` to delegate)
- Test: `tests/unit/test_base_agent_repair_loop.py` (new)

This generalizes the reviewer's two-round JSON repair loop so every specialist (and any future agent) can reuse it. The reviewer is migrated to call the new helper to prove parity.

- [ ] **Step 1: Write the failing test**

Create `tests/unit/test_base_agent_repair_loop.py`:

```python
"""Unit tests for BaseAgent._repair_or_escalate_json (lifted from reviewer)."""
from __future__ import annotations

import json
from types import SimpleNamespace
from unittest.mock import AsyncMock

import pytest

from ai_driven.agents.base import BaseAgent


class _StubAgent(BaseAgent):
    agent_type = "stub"

    def _register_tools(self) -> None:
        return None

    def _get_system_prompt(self, context):
        return "you are a stub"

    def _get_tool_names(self):
        return []


def _make_agent() -> _StubAgent:
    a = _StubAgent()
    a._publish_event = AsyncMock()  # type: ignore[method-assign]
    a._update_task_status = AsyncMock()  # type: ignore[method-assign]
    return a


def _validator(text: str) -> bool:
    """Accept anything that parses as a dict with key 'verdict'."""
    try:
        obj = json.loads(text.strip("` \njson"))
        return isinstance(obj, dict) and "verdict" in obj
    except Exception:
        return False


@pytest.mark.asyncio
async def test_valid_output_short_circuits() -> None:
    agent = _make_agent()
    agent._claude.chat = AsyncMock()
    res = SimpleNamespace(output='```json\n{"verdict":"pass"}\n```', metadata={})
    await agent._repair_or_escalate_json(
        res, validator=_validator, schema_reminder="...",
        system_prompt="sys", model="m", task_id=None,
    )
    agent._claude.chat.assert_not_called()
    assert res.metadata.get("escalated") is not True


@pytest.mark.asyncio
async def test_repair_succeeds_on_first_round() -> None:
    agent = _make_agent()
    agent._claude.chat = AsyncMock(
        return_value=SimpleNamespace(text='"verdict":"pass"}')
    )
    res = SimpleNamespace(output="garbage", metadata={})
    await agent._repair_or_escalate_json(
        res, validator=_validator, schema_reminder="...",
        system_prompt="sys", model="m", task_id="t",
    )
    agent._claude.chat.assert_awaited_once()
    assert res.metadata.get("escalated") is not True


@pytest.mark.asyncio
async def test_persistent_invalidity_escalates_after_two_rounds() -> None:
    agent = _make_agent()
    agent._claude.chat = AsyncMock(return_value=SimpleNamespace(text="still bad"))
    res = SimpleNamespace(output="garbage", metadata={})
    await agent._repair_or_escalate_json(
        res, validator=_validator, schema_reminder="...",
        system_prompt="sys", model="m", task_id="t",
    )
    assert agent._claude.chat.await_count == 2
    assert res.metadata.get("escalated") is True
    assert "2 repair rounds" in res.metadata.get("escalation_reason", "")


@pytest.mark.asyncio
async def test_escalation_does_not_touch_task_status() -> None:
    """Shadow-mode invariant: the generic repair helper NEVER writes task.status.

    'needs_human_review' isn't in the tasks.status CHECK constraint; and
    specialists running alongside the coding pipeline must stay side-effect-free.
    """
    agent = _make_agent()
    agent._claude.chat = AsyncMock(return_value=SimpleNamespace(text="still bad"))
    res = SimpleNamespace(output="garbage", metadata={})
    await agent._repair_or_escalate_json(
        res, validator=_validator, schema_reminder="...",
        system_prompt="sys", model="m", task_id="t",
    )
    agent._update_task_status.assert_not_called()
```

- [ ] **Step 2: Run test to verify it fails**

```bash
pytest tests/unit/test_base_agent_repair_loop.py -v
```
Expected: AttributeError — `_repair_or_escalate_json` does not exist on BaseAgent.

- [ ] **Step 3: Add the helper to BaseAgent**

In `src/ai_driven/agents/base.py`, add this method to the `BaseAgent` class (before `_build_initial_message`):

```python
    # Hard cap on repair rounds — matches ~/.claude/rules/review-loop-limits.md.
    MAX_JSON_REPAIR_ROUNDS = 2

    async def _repair_or_escalate_json(
        self,
        result: Any,
        *,
        validator: "Callable[[str], bool]",
        schema_reminder: str,
        system_prompt: str,
        model: str,
        task_id: str | None,
        event_prefix: str = "agent",
    ) -> None:
        """Bounded JSON-repair loop usable by any agent emitting structured JSON.

        Mutates ``result.output`` in place when repair succeeds. After
        ``MAX_JSON_REPAIR_ROUNDS`` (2) failed attempts, sets
        ``result.metadata['escalated'] = True`` and publishes an escalation
        event. Callers decide what to do with an escalated result; this helper
        never raises, never silently truncates, and NEVER updates task status
        (the reviewer's caller handles that itself — the helper stays side-effect-free
        so specialists running in shadow mode don't touch tasks.status).
        """
        from ai_driven.config import get_settings  # local: avoid cycles

        output_text = getattr(result, "output", "") or ""
        if validator(output_text):
            return

        settings = get_settings()
        for attempt in range(1, self.MAX_JSON_REPAIR_ROUNDS + 1):
            logger.warning(
                "%s: structured JSON output unparseable; repair attempt %d/%d",
                self.agent_type, attempt, self.MAX_JSON_REPAIR_ROUNDS,
            )
            await self._publish_event(
                f"{event_prefix}_output_repair",
                {"attempt": attempt, "max_attempts": self.MAX_JSON_REPAIR_ROUNDS},
            )
            try:
                repair = await self._claude.chat(
                    messages=[
                        {"role": "user", "content": schema_reminder},
                        {"role": "user", "content": (
                            "Here is your previous reply for reference; "
                            "transform it into the required JSON without losing "
                            "any details:\n\n"
                            + (output_text[:6000] or "<previous output empty>")
                        )},
                        {"role": "assistant", "content": "```json\n{"},
                    ],
                    system=system_prompt,
                    model=model,
                    max_tokens=settings.claude_max_tokens,
                    temperature=0.0,
                    stop_sequences=["```"],
                )
            except Exception as exc:  # noqa: BLE001
                logger.warning("%s: repair call failed: %s", self.agent_type, exc)
                continue

            repaired_text = "```json\n{" + (repair.text or "")
            if validator(repaired_text):
                result.output = repaired_text
                return
            output_text = repaired_text

        # 2 rounds exhausted — escalate.
        if not hasattr(result, "metadata") or result.metadata is None:
            result.metadata = {}
        result.metadata["escalated"] = True
        result.metadata["escalation_reason"] = (
            f"{self.agent_type} JSON output invalid after "
            f"{self.MAX_JSON_REPAIR_ROUNDS} repair rounds"
        )
        await self._publish_event(
            f"{event_prefix}_escalation",
            {"reason": "invalid_structured_output",
             "rounds_attempted": self.MAX_JSON_REPAIR_ROUNDS},
        )
        # NOTE: we deliberately do NOT call `_update_task_status(...)` here.
        # "needs_human_review" is not in the tasks.status CHECK constraint
        # (see alembic/versions/a1b2c3d4e5f9_task_status_check.py), and
        # specialists running in shadow mode must never mutate tasks.status.
        # The reviewer's wrapper code may update status externally if
        # appropriate for its flow.
```

Add at top of file (next to existing typing imports):

```python
from collections.abc import Callable
```

- [ ] **Step 4: Run new test to verify it passes**

```bash
pytest tests/unit/test_base_agent_repair_loop.py -v
```
Expected: 3 passed.

- [ ] **Step 5: Refactor reviewer to delegate to the new helper**

The pre-existing reviewer call `await self._update_task_status("needs_human_review")` has been silently failing against the `ck_tasks_status` CHECK constraint — `needs_human_review` is not in the valid set. The refactor takes this opportunity to drop that call. Escalation is now signalled purely via `result.metadata["escalated"] = True` and the `review_escalation` pub/sub event. Downstream code that cares (e.g. consumer summary logic) can inspect metadata.

In `src/ai_driven/agents/reviewer/agent.py`, replace the body of `_repair_or_escalate_output` with a call to the lifted helper:

```python
    async def _repair_or_escalate_output(
        self,
        result: Any,
        *,
        model: str,
        task_id: str | None,
    ) -> None:
        """Delegate to BaseAgent._repair_or_escalate_json for the lifted loop."""
        await self._repair_or_escalate_json(
            result,
            validator=self._output_is_valid_review_json,
            schema_reminder=_REVIEW_JSON_SCHEMA_REMINDER,
            system_prompt=self._get_system_prompt({}),
            model=model,
            task_id=task_id,
            event_prefix="review",
        )
```

Delete the old loop body. Keep `_MAX_JSON_REPAIR_ROUNDS` and `_REVIEW_JSON_SCHEMA_REMINDER` module constants (the helper class-level cap is the same value — 2).

- [ ] **Step 6: Update existing reviewer repair-loop tests for the dropped status-update**

Open `tests/unit/test_reviewer_json_repair.py`. The test named `test_persistent_invalidity_escalates_after_cap` (or its current equivalent) asserts `_update_task_status` was called on escalation. That behavior is intentionally gone — escalation now only flips metadata and publishes an event. If the test contains an assertion like `agent._update_task_status.assert_awaited_with("needs_human_review")` or `_update_task_status.assert_called()`, replace it with `agent._update_task_status.assert_not_called()` and update the docstring to note that escalation is signal-only.

- [ ] **Step 7: Run reviewer regression suite**

```bash
pytest tests/unit/test_reviewer_json_repair.py tests/unit/test_review_agent.py -v
```
Expected: all existing reviewer JSON-repair tests still pass after the Step 6 update.

- [ ] **Step 8: Commit**

```bash
git add src/ai_driven/agents/base.py src/ai_driven/agents/reviewer/agent.py tests/unit/test_base_agent_repair_loop.py tests/unit/test_reviewer_json_repair.py
git commit -m "refactor(agents): lift JSON repair loop into BaseAgent; drop invalid needs_human_review status write"
```

---

## Task 6: `SpecialistBase` + `SpecialistContext`

**Files:**
- Create: `src/ai_driven/agents/specialists/__init__.py` (empty)
- Create: `src/ai_driven/agents/specialists/base.py`
- Test: included in Task 7's `test_specialist_business_analyst.py` indirectly; we exercise `SpecialistBase` via concrete subclasses.

This is the parent class every specialist (live or stub) inherits from. It owns the prefill+stop_sequences chat invocation, the `Artifact` construction, and the integration with `_repair_or_escalate_json`. Stubs override one method to short-circuit.

- [ ] **Step 1: Implement SpecialistContext + SpecialistBase**

Create `src/ai_driven/agents/specialists/__init__.py` (empty).

Create `src/ai_driven/agents/specialists/base.py`:

```python
"""Common scaffolding for all specialists.

A specialist is a BaseAgent subclass that:
  * accepts a SpecialistContext (ticket, repo, prior artifacts),
  * calls Claude once with a role-specific prompt + prefill,
  * validates output against a Pydantic payload schema,
  * returns an Artifact (role, verdict, reasoning, payload, tokens).

Stubs override `run_specialist()` to skip Claude entirely and return
verdict='not_implemented'.

The orchestrator persists the returned Artifact via ArtifactRepository
and decides what to do with the verdict (continue, hard-stop on 'block', etc.).
"""
from __future__ import annotations

import json
import logging
import re
from abc import abstractmethod
from dataclasses import dataclass, field
from typing import Any

from pydantic import ValidationError

from ai_driven.agents.base import BaseAgent
from ai_driven.agents.orchestrator.artifacts import (
    Artifact,
    PAYLOAD_SCHEMAS,
    validate_payload,
)
from ai_driven.config import get_settings

logger = logging.getLogger(__name__)


@dataclass
class SpecialistContext:
    task_id: str
    ticket: dict        # {key, summary, description, labels, acceptance_criteria}
    repo: dict          # {full_name, scm_type, default_branch}
    tier: str           # 'fast_path' | 'full_workflow' | 'multi_domain'
    prior_artifacts: dict[str, Artifact] = field(default_factory=dict)
    team_settings: dict = field(default_factory=dict)


class SpecialistBase(BaseAgent):
    """Abstract base for live and stub specialists."""

    role: str = ""
    model_setting_attr: str = "claude_default_model"  # override per specialist
    max_input_tokens: int = 8192
    max_output_tokens: int = 2048

    def _register_tools(self) -> None:
        # Specialists don't take side-effects by default. Subclasses that
        # need read tools (e.g. QA's github.get_pr_diff / repo.read_file) override this.
        return None

    def _get_tool_names(self) -> list[str]:
        return []

    def _get_system_prompt(self, context: dict[str, Any]) -> str:
        return self._system_prompt()

    @abstractmethod
    def _system_prompt(self) -> str:
        """Role-specific system prompt (cached, ≥1024 tokens preferred)."""

    @abstractmethod
    def _build_user_prompt(self, ctx: SpecialistContext) -> str:
        """Render the per-task user message, including prior_artifacts JSON."""

    @abstractmethod
    def _schema_reminder(self) -> str:
        """Schema-only reminder text used by the JSON repair loop."""

    def _resolve_model(self) -> str:
        return getattr(get_settings(), self.model_setting_attr)

    # --- core entry point -------------------------------------------------

    async def run_specialist(self, ctx: SpecialistContext) -> Artifact:
        """Run the specialist and return a validated Artifact.

        Override in stubs to short-circuit with verdict='not_implemented'.
        """
        self._task_id = ctx.task_id
        await self._publish_event(
            "specialist.started",
            {"role": self.role, "task_id": ctx.task_id, "model": self._resolve_model()},
        )

        user_prompt = self._build_user_prompt(ctx)
        model = self._resolve_model()

        # Refuse oversized contexts — verdict='error', no Claude call.
        # Approximate token count by character length / 4 — good enough as a
        # cheap pre-flight check; real token accounting happens in the SDK.
        approx_tokens = (len(self._system_prompt()) + len(user_prompt)) // 4
        if approx_tokens > self.max_input_tokens:
            return Artifact(
                role=self.role, verdict="error",
                reasoning=f"context too large ({approx_tokens} approx tokens > "
                          f"{self.max_input_tokens})",
                payload={}, model_used=model, input_tokens=0, output_tokens=0,
            )

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
            return Artifact(
                role=self.role, verdict="error",
                reasoning=f"claude call failed: {exc}", payload={},
                model_used=model, input_tokens=0, output_tokens=0,
            )

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
            return Artifact(
                role=self.role, verdict="error",
                reasoning="JSON invalid after 2 repair rounds",
                payload={}, model_used=model,
                input_tokens=getattr(response.usage, "input_tokens", 0),
                output_tokens=getattr(response.usage, "output_tokens", 0),
            )

        parsed = self._extract_json(result.output)
        try:
            validated = validate_payload(self.role, parsed.get("payload", parsed))
        except ValidationError as exc:
            return Artifact(
                role=self.role, verdict="error",
                reasoning=f"payload schema mismatch: {exc.errors()[:3]}",
                payload={}, model_used=model,
                input_tokens=getattr(response.usage, "input_tokens", 0),
                output_tokens=getattr(response.usage, "output_tokens", 0),
            )

        return Artifact(
            role=self.role,
            verdict=parsed.get("verdict", "error"),
            reasoning=parsed.get("reasoning", ""),
            payload=validated.model_dump(),
            model_used=model,
            input_tokens=getattr(response.usage, "input_tokens", 0),
            output_tokens=getattr(response.usage, "output_tokens", 0),
        )

    # --- helpers ---------------------------------------------------------

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
    """Specialist that does no real work; emits verdict='not_implemented'."""

    def _system_prompt(self) -> str:
        return f"stub specialist for {self.role}"

    def _build_user_prompt(self, ctx: SpecialistContext) -> str:
        return ""

    def _schema_reminder(self) -> str:
        return ""

    async def run_specialist(self, ctx: SpecialistContext) -> Artifact:
        await self._publish_event(
            "specialist.started",
            {"role": self.role, "task_id": ctx.task_id, "model": "n/a"},
        )
        return Artifact(
            role=self.role,
            verdict="not_implemented",
            reasoning="stub — implementation deferred to a follow-up spec",
            payload={},
            model_used="",
            input_tokens=0,
            output_tokens=0,
        )
```

- [ ] **Step 2: No tests yet — they come with concrete subclasses.**
- [ ] **Step 3: Commit**

```bash
git add src/ai_driven/agents/specialists/__init__.py src/ai_driven/agents/specialists/base.py
git commit -m "feat(specialists): add SpecialistBase and SpecialistContext scaffolding"
```

---

## Task 7: Stub specialists — `tech_lead`, `ux_designer`, `api_test`

**Files:**
- Create: `src/ai_driven/agents/specialists/tech_lead/__init__.py` (empty)
- Create: `src/ai_driven/agents/specialists/tech_lead/agent.py`
- Create: `src/ai_driven/agents/specialists/ux_designer/__init__.py` (empty)
- Create: `src/ai_driven/agents/specialists/ux_designer/agent.py`
- Create: `src/ai_driven/agents/specialists/api_test/__init__.py` (empty)
- Create: `src/ai_driven/agents/specialists/api_test/agent.py`
- Test: `tests/unit/test_specialist_stubs.py`

- [ ] **Step 1: Write the failing test**

Create `tests/unit/test_specialist_stubs.py`:

```python
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
```

- [ ] **Step 2: Run test to verify it fails**

```bash
pytest tests/unit/test_specialist_stubs.py -v
```
Expected: ImportError on the three stub modules.

- [ ] **Step 3: Implement the three stubs**

Create the three files. Each has the same shape:

`src/ai_driven/agents/specialists/tech_lead/agent.py`:

```python
from ai_driven.agents.specialists.base import StubSpecialist


class TechLeadSpecialist(StubSpecialist):
    agent_type = "specialist_tech_lead"
    role = "tech_lead"
```

`src/ai_driven/agents/specialists/ux_designer/agent.py`:

```python
from ai_driven.agents.specialists.base import StubSpecialist


class UxDesignerSpecialist(StubSpecialist):
    agent_type = "specialist_ux_designer"
    role = "ux_designer"
```

`src/ai_driven/agents/specialists/api_test/agent.py`:

```python
from ai_driven.agents.specialists.base import StubSpecialist


class ApiTestSpecialist(StubSpecialist):
    agent_type = "specialist_api_test"
    role = "api_test"
```

Each `__init__.py` is empty.

- [ ] **Step 4: Run test to verify it passes**

```bash
pytest tests/unit/test_specialist_stubs.py -v
```
Expected: 3 passed (parametrized).

- [ ] **Step 5: Commit**

```bash
git add src/ai_driven/agents/specialists/tech_lead src/ai_driven/agents/specialists/ux_designer src/ai_driven/agents/specialists/api_test tests/unit/test_specialist_stubs.py
git commit -m "feat(specialists): add tech_lead, ux_designer, api_test stubs"
```

---

## Task 8: BusinessAnalyst specialist (live, Sonnet)

**Files:**
- Create: `src/ai_driven/claude/prompts/specialists/__init__.py` (empty)
- Create: `src/ai_driven/claude/prompts/specialists/business_analyst.py`
- Create: `src/ai_driven/agents/specialists/business_analyst/agent.py`
- Test: `tests/unit/test_specialist_business_analyst.py`

- [ ] **Step 1: Write the failing test**

Create `tests/unit/test_specialist_business_analyst.py`:

```python
"""Unit tests for BusinessAnalystSpecialist with mocked Claude."""
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


def _claude_returns(payload: dict, verdict: str = "pass") -> AsyncMock:
    body = {"verdict": verdict, "reasoning": "ok", "payload": payload}
    text = json.dumps(body)[1:]  # drop leading "{" — prefill provides it
    return AsyncMock(return_value=SimpleNamespace(
        text=text + "\n",
        usage=SimpleNamespace(input_tokens=120, output_tokens=80),
    ))


@pytest.mark.asyncio
async def test_business_analyst_returns_validated_artifact() -> None:
    agent = BusinessAnalystSpecialist()
    agent._publish_event = AsyncMock()  # noqa
    agent._claude.chat = _claude_returns({
        "ambiguities_resolved": [{"question": "JWT lifetime?", "resolution": "1h default"}],
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
    assert art.input_tokens == 120


@pytest.mark.asyncio
async def test_business_analyst_block_on_ambiguity() -> None:
    agent = BusinessAnalystSpecialist()
    agent._publish_event = AsyncMock()
    agent._claude.chat = _claude_returns({
        "ambiguities_resolved": [],
        "acceptance_criteria": [],
        "out_of_scope": [],
        "blocking_questions": ["Which OAuth provider?"],
    }, verdict="block")
    art = await agent.run_specialist(_ctx("Add OAuth login somehow."))
    assert art.verdict == "block"
    assert art.payload["blocking_questions"] == ["Which OAuth provider?"]
```

- [ ] **Step 2: Run test to verify it fails**

```bash
pytest tests/unit/test_specialist_business_analyst.py -v
```
Expected: ImportError on the BA modules.

- [ ] **Step 3: Implement system prompt**

Create `src/ai_driven/claude/prompts/specialists/__init__.py` (empty).

Create `src/ai_driven/claude/prompts/specialists/business_analyst.py`:

```python
"""System prompt for the BusinessAnalyst specialist."""

BUSINESS_ANALYST_SYSTEM_PROMPT = """\
# ROLE
You are the BusinessAnalyst specialist agent. Your job is to resolve
ambiguity in a Jira ticket, produce Gherkin acceptance criteria, and
list explicit out-of-scope items — OR return verdict='block' with the
unresolved questions if the ticket cannot be made actionable from
context alone.

# WHAT YOU READ
- Jira ticket (key, summary, description, labels, existing acceptance criteria)
- Target repository metadata (full_name, scm_type, default_branch)
- No prior artifacts — you run first.

# WHAT YOU PRODUCE
A single JSON object inside one ```json fenced block, no prose around it:

{
  "verdict": "pass" | "pass_with_notes" | "block",
  "reasoning": "<1-2 sentences>",
  "payload": {
    "ambiguities_resolved": [{"question": "...", "resolution": "..."}],
    "acceptance_criteria":  [{"given": "...", "when": "...", "then": "..."}],
    "out_of_scope":         ["..."],
    "blocking_questions":   ["..."]
  }
}

# DECISION RULES
- 'pass'              when the ticket is actionable as-is OR you resolved
                      every ambiguity using the description + repo conventions.
- 'pass_with_notes'   when you made strong assumptions worth flagging.
- 'block'             ONLY when proceeding would clearly waste implementation
                      effort (missing API contract, missing data shape, etc.).
                      blocking_questions MUST be non-empty.

# ANTI-PATTERNS — THINGS YOU DO NOT DO
- You do NOT design APIs or pick technologies — that is TechLead's job.
- You do NOT model threats — that is Security's job.
- You do NOT classify which stacks the task touches — that is StackAnalyst's job.
- You do NOT verify acceptance — that is QA's job (after implementation).
- You do NOT block to avoid making a judgment call. Block only when the
  question would otherwise force CodingAgent to guess at user-facing behavior
  or contracts.

# EXAMPLES
<positive_example>
Ticket: "Add /users/me endpoint returning the logged-in user's profile."
Output verdict: pass; payload.acceptance_criteria includes
  {"given":"a valid session","when":"GET /users/me","then":"200 with {id,email,name}"}
</positive_example>

<blocker_example>
Ticket: "Add payments support."
Output verdict: block; payload.blocking_questions includes
  "Which payment provider (Stripe/Adyen/internal)?",
  "What currencies and minimum amount?",
  "Subscription, one-off, or both?"
</blocker_example>
"""
```

- [ ] **Step 4: Implement the agent class**

Create `src/ai_driven/agents/specialists/business_analyst/__init__.py` (empty).

Create `src/ai_driven/agents/specialists/business_analyst/agent.py`:

```python
"""BusinessAnalyst specialist — resolves ambiguity, writes Gherkin ACs."""
from __future__ import annotations

import json

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
    agent_type = "specialist_business_analyst"
    role = "business_analyst"
    model_setting_attr = "claude_default_model"   # Sonnet
    max_output_tokens = 2048

    def _system_prompt(self) -> str:
        return BUSINESS_ANALYST_SYSTEM_PROMPT

    def _schema_reminder(self) -> str:
        return _SCHEMA_REMINDER

    def _build_user_prompt(self, ctx: SpecialistContext) -> str:
        return (
            "## Jira ticket\n"
            f"{json.dumps(ctx.ticket, indent=2)}\n\n"
            "## Repository\n"
            f"{json.dumps(ctx.repo, indent=2)}\n\n"
            "Produce the BA artifact JSON."
        )
```

- [ ] **Step 5: Run test to verify it passes**

```bash
pytest tests/unit/test_specialist_business_analyst.py -v
```
Expected: 2 passed.

- [ ] **Step 6: Commit**

```bash
git add src/ai_driven/claude/prompts/specialists src/ai_driven/agents/specialists/business_analyst tests/unit/test_specialist_business_analyst.py
git commit -m "feat(specialists): add BusinessAnalyst specialist (live, Sonnet)"
```

---

## Task 9: StackAnalyst specialist (live, Sonnet via `claude_fast_model`)

> **Revision note (2026-04-30):** Heading was "(live, Haiku)" in original. Haiku slot removed in H1; `settings.claude_fast_model` now resolves to Sonnet 4.6. Tools list = `["repo.overview"]` (NOT `repo.list_files`) per revision §5. System prompt uses XML template per revision §3.

**Files:**
- Create: `src/ai_driven/claude/prompts/specialists/stack_analyst.py`
- Create: `src/ai_driven/agents/specialists/stack_analyst/__init__.py` (empty)
- Create: `src/ai_driven/agents/specialists/stack_analyst/agent.py`
- Test: `tests/unit/test_specialist_stack_analyst.py`

- [ ] **Step 1: Write the failing test**

Create `tests/unit/test_specialist_stack_analyst.py`:

```python
"""Unit tests for StackAnalystSpecialist with mocked Claude."""
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
        role="business_analyst", verdict="pass", reasoning="",
        payload={"ambiguities_resolved": [],
                 "acceptance_criteria": [
                     {"given": "x", "when": "POST /api/orders", "then": "200"},
                 ],
                 "out_of_scope": [], "blocking_questions": []},
        model_used="m", input_tokens=10, output_tokens=10,
    )
    return SpecialistContext(
        task_id="t-sa",
        ticket={"key": "KAN-2", "summary": "Add /api/orders POST",
                "description": "FastAPI route + Postgres insert", "labels": []},
        repo={"full_name": "org/api", "scm_type": "github", "default_branch": "main"},
        tier="full_workflow",
        prior_artifacts={"business_analyst": ba},
    )


@pytest.mark.asyncio
async def test_stack_analyst_classifies_backend_only() -> None:
    agent = StackAnalystSpecialist()
    agent._publish_event = AsyncMock()
    body = {
        "verdict": "pass",
        "reasoning": "Backend-only change.",
        "payload": {
            "stacks": ["backend"], "primary_stack": "backend",
            "cross_cutting_concerns": ["database migration"],
            "reasoning": "FastAPI handler + Postgres write.",
        },
    }
    text = json.dumps(body)[1:] + "\n"
    agent._claude.chat = AsyncMock(return_value=SimpleNamespace(
        text=text, usage=SimpleNamespace(input_tokens=80, output_tokens=40),
    ))
    art = await agent.run_specialist(_ctx_with_ba())
    assert art.verdict == "pass"
    assert art.payload["stacks"] == ["backend"]
    assert art.payload["primary_stack"] == "backend"


@pytest.mark.asyncio
async def test_stack_analyst_does_not_block() -> None:
    """StackAnalyst is forbidden from returning verdict='block'."""
    agent = StackAnalystSpecialist()
    agent._publish_event = AsyncMock()
    # Even with sparse signal, verdict must be pass or pass_with_notes.
    body = {
        "verdict": "pass_with_notes",
        "reasoning": "Low signal; defaulting to backend.",
        "payload": {
            "stacks": ["backend"], "primary_stack": "backend",
            "cross_cutting_concerns": [],
            "reasoning": "No clear stack indicators in description.",
        },
    }
    agent._claude.chat = AsyncMock(return_value=SimpleNamespace(
        text=json.dumps(body)[1:] + "\n",
        usage=SimpleNamespace(input_tokens=10, output_tokens=10),
    ))
    art = await agent.run_specialist(_ctx_with_ba())
    assert art.verdict in ("pass", "pass_with_notes")
```

- [ ] **Step 2: Run test to verify it fails**

```bash
pytest tests/unit/test_specialist_stack_analyst.py -v
```
Expected: ImportError.

- [ ] **Step 3: Implement system prompt**

Create `src/ai_driven/claude/prompts/specialists/stack_analyst.py`:

```python
"""System prompt for StackAnalyst — multi-label classification of stacks."""

STACK_ANALYST_SYSTEM_PROMPT = """\
# ROLE
You are the StackAnalyst specialist. Your job is to classify which of
seven stacks a coding task touches:
  backend | frontend | mobile | devops | data | ai | aws

# WHAT YOU READ
- Jira ticket and repository metadata.
- BusinessAnalyst's artifact (acceptance criteria especially).
- File-glob signals from the repository (you may peek at top-level
  package files like package.json, pyproject.toml, build.gradle, etc.).

# WHAT YOU PRODUCE
A single JSON object in one ```json fence:

{
  "verdict": "pass" | "pass_with_notes",
  "reasoning": "<1-2 sentences>",
  "payload": {
    "stacks": ["backend","frontend",...],
    "primary_stack": "<the stack doing the heaviest lifting>",
    "cross_cutting_concerns": ["database migration","API contract change",...],
    "reasoning": "<1-2 sentences on why these stacks were chosen>"
  }
}

# RULES
- 'stacks' MUST be a non-empty subset of:
   {backend, frontend, mobile, devops, data, ai, aws}.
- 'primary_stack' MUST be a member of 'stacks'.
- You NEVER return verdict='block'. If signals are thin, default to
  the most likely single stack with verdict='pass_with_notes' and a
  low-confidence reasoning.

# ANTI-PATTERNS
- You do NOT model threats — that is Security.
- You do NOT design APIs — that is TechLead.
- You do NOT verify acceptance — that is QA.
- You do NOT include stacks the task does not actually touch — extra
  stacks add noise that hurts CodingAgent's focus.
"""
```

- [ ] **Step 4: Implement the agent class**

Create `src/ai_driven/agents/specialists/stack_analyst/__init__.py` (empty).

Create `src/ai_driven/agents/specialists/stack_analyst/agent.py`:

```python
"""StackAnalyst specialist — multi-label classification of task stacks.

Post-revision (2026-04-30), Phase 1 adds the `repo.overview` read-only
tool so the agent sees the repo tree + manifest files in a single call
before classifying. This replaces the original plan's deferred-to-Phase-2
`repo.list_files` approach — `repo.overview` is strictly better for stack
classification (see revision §5) and was made available by H5.
"""
from __future__ import annotations

import json

from ai_driven.agents.specialists.base import SpecialistBase, SpecialistContext
from ai_driven.claude.prompts.specialists.stack_analyst import (
    STACK_ANALYST_SYSTEM_PROMPT,
)


_SCHEMA_REMINDER = (
    "Re-emit ONLY the StackAnalyst artifact JSON inside one ```json fence, "
    "with verdict in {pass, pass_with_notes}, reasoning, and a payload "
    "containing stacks (subset of backend/frontend/mobile/devops/data/ai/aws), "
    "primary_stack, cross_cutting_concerns, reasoning. NEVER block."
)


class StackAnalystSpecialist(SpecialistBase):
    agent_type = "specialist_stack_analyst"
    role = "stack_analyst"
    model_setting_attr = "claude_fast_model"   # fast-model slot (Sonnet post-H1) — short fixed-set classify
    max_output_tokens = 1024

    def _system_prompt(self) -> str:
        return STACK_ANALYST_SYSTEM_PROMPT

    def _schema_reminder(self) -> str:
        return _SCHEMA_REMINDER

    def _build_user_prompt(self, ctx: SpecialistContext) -> str:
        ba = ctx.prior_artifacts.get("business_analyst")
        ba_payload = ba.payload if ba else {}
        return (
            "## Jira ticket\n"
            f"{json.dumps(ctx.ticket, indent=2)}\n\n"
            "## Repository\n"
            f"{json.dumps(ctx.repo, indent=2)}\n\n"
            "## BA artifact (for acceptance criteria signal)\n"
            f"{json.dumps(ba_payload, indent=2)}\n\n"
            "Classify which of {backend, frontend, mobile, devops, data, ai, "
            "aws} this task touches."
        )
```

- [ ] **Step 5: Run test to verify it passes**

```bash
pytest tests/unit/test_specialist_stack_analyst.py -v
```
Expected: 2 passed.

- [ ] **Step 6: Commit**

```bash
git add src/ai_driven/claude/prompts/specialists/stack_analyst.py src/ai_driven/agents/specialists/stack_analyst tests/unit/test_specialist_stack_analyst.py
git commit -m "feat(specialists): add StackAnalyst specialist (live, fast-model/Sonnet post-H1)"
```

---

## Task 10: Security specialist (live, Sonnet)

**Files:**
- Create: `src/ai_driven/claude/prompts/specialists/security.py`
- Create: `src/ai_driven/agents/specialists/security/__init__.py` (empty)
- Create: `src/ai_driven/agents/specialists/security/agent.py`
- Test: `tests/unit/test_specialist_security.py`

- [ ] **Step 1: Write the failing test**

Create `tests/unit/test_specialist_security.py`:

```python
"""Unit tests for SecuritySpecialist with mocked Claude."""
from __future__ import annotations

import json
from types import SimpleNamespace
from unittest.mock import AsyncMock

import pytest

from ai_driven.agents.orchestrator.artifacts import Artifact
from ai_driven.agents.specialists.base import SpecialistContext
from ai_driven.agents.specialists.security.agent import SecuritySpecialist


def _ctx() -> SpecialistContext:
    ba = Artifact(
        role="business_analyst", verdict="pass", reasoning="",
        payload={"ambiguities_resolved": [], "acceptance_criteria": [],
                 "out_of_scope": [], "blocking_questions": []},
        model_used="m", input_tokens=1, output_tokens=1,
    )
    sa = Artifact(
        role="stack_analyst", verdict="pass", reasoning="",
        payload={"stacks": ["backend"], "primary_stack": "backend",
                 "cross_cutting_concerns": [], "reasoning": ""},
        model_used="m", input_tokens=1, output_tokens=1,
    )
    return SpecialistContext(
        task_id="t-sec",
        ticket={"key": "KAN-3", "summary": "Add /login JWT", "description": "auth",
                "labels": ["security"]},
        repo={"full_name": "o/r", "scm_type": "github", "default_branch": "main"},
        tier="full_workflow",
        prior_artifacts={"business_analyst": ba, "stack_analyst": sa},
    )


@pytest.mark.asyncio
async def test_security_pass_with_rules() -> None:
    agent = SecuritySpecialist()
    agent._publish_event = AsyncMock()
    body = {
        "verdict": "pass",
        "reasoning": "Standard JWT login; manageable threats.",
        "payload": {
            "threat_model": [{
                "stride_category": "Spoofing", "threat": "Stolen tokens",
                "severity": "high", "mitigation": "Short TTL + refresh"
            }],
            "implementation_rules": [
                "Hash passwords with argon2id",
                "Sign JWT with RS256",
            ],
            "compliance_notes": [],
            "blocking_concerns": [],
        },
    }
    agent._claude.chat = AsyncMock(return_value=SimpleNamespace(
        text=json.dumps(body)[1:] + "\n",
        usage=SimpleNamespace(input_tokens=200, output_tokens=120),
    ))
    art = await agent.run_specialist(_ctx())
    assert art.verdict == "pass"
    assert "argon2id" in art.payload["implementation_rules"][0]


@pytest.mark.asyncio
async def test_security_block_on_unsafe_request() -> None:
    agent = SecuritySpecialist()
    agent._publish_event = AsyncMock()
    body = {
        "verdict": "block",
        "reasoning": "Plaintext credential storage requested.",
        "payload": {
            "threat_model": [], "implementation_rules": [],
            "compliance_notes": [],
            "blocking_concerns": ["Ticket asks for plaintext password storage"],
        },
    }
    agent._claude.chat = AsyncMock(return_value=SimpleNamespace(
        text=json.dumps(body)[1:] + "\n",
        usage=SimpleNamespace(input_tokens=200, output_tokens=120),
    ))
    art = await agent.run_specialist(_ctx())
    assert art.verdict == "block"
    assert art.payload["blocking_concerns"]
```

- [ ] **Step 2: Run test to verify it fails**

```bash
pytest tests/unit/test_specialist_security.py -v
```
Expected: ImportError.

- [ ] **Step 3: Implement system prompt**

Create `src/ai_driven/claude/prompts/specialists/security.py`:

```python
"""System prompt for the Security specialist."""

SECURITY_SYSTEM_PROMPT = """\
# ROLE
You are the Security specialist. Apply STRIDE threat modelling to the
proposed change and produce concrete implementation_rules that the
CodingAgent will follow verbatim. Block ONLY for flatly unsafe requests.

# WHAT YOU READ
- Jira ticket and repository metadata.
- BusinessAnalyst's acceptance criteria (what is being built).
- StackAnalyst's stacks list (focus the threat model on relevant stacks
  — skip mobile-specific threats for a backend-only change, etc.).

# WHAT YOU PRODUCE
Single JSON in one ```json fence:

{
  "verdict": "pass" | "pass_with_notes" | "block",
  "reasoning": "<1-2 sentences>",
  "payload": {
    "threat_model": [
      {"stride_category":"Spoofing|Tampering|Repudiation|Information disclosure|Denial of service|Elevation of privilege",
       "threat":"<one-line>","severity":"low|medium|high|critical",
       "mitigation":"<one-line>"}
    ],
    "implementation_rules": ["<numbered, imperative, copy-paste-ready>"],
    "compliance_notes":     ["<soft concern, not a blocker>"],
    "blocking_concerns":    ["<flatly unsafe behavior>"]
  }
}

# DECISION RULES
- 'pass'              Reasonable change with manageable threats.
- 'pass_with_notes'   Soft concerns belong in compliance_notes (PII handling
                      hint, log scrubbing reminder).
- 'block'             ONLY for flatly unsafe requests:
                        * plaintext credential storage,
                        * disabling auth,
                        * exposing secrets to logs/PR text,
                        * obvious broken-crypto choices (MD5 for passwords).
                      blocking_concerns MUST be non-empty.

# ANTI-PATTERNS — THINGS YOU DO NOT DO
- You do NOT design APIs — that is TechLead.
- You do NOT verify acceptance — that is QA.
- You do NOT classify which stacks the task touches — that is StackAnalyst.
- You do NOT block to express discomfort. Block only when proceeding
  would clearly cause a security incident or compliance violation.
"""
```

- [ ] **Step 4: Implement the agent class**

Create `src/ai_driven/agents/specialists/security/__init__.py` (empty).

Create `src/ai_driven/agents/specialists/security/agent.py`:

```python
"""Security specialist — STRIDE threat modelling + implementation rules."""
from __future__ import annotations

import json

from ai_driven.agents.specialists.base import SpecialistBase, SpecialistContext
from ai_driven.claude.prompts.specialists.security import SECURITY_SYSTEM_PROMPT


_SCHEMA_REMINDER = (
    "Re-emit ONLY the Security artifact JSON inside one ```json fence, "
    "with verdict in {pass, pass_with_notes, block}, reasoning, and a "
    "payload containing threat_model, implementation_rules, "
    "compliance_notes, blocking_concerns. Block only for flatly unsafe."
)


class SecuritySpecialist(SpecialistBase):
    agent_type = "specialist_security"
    role = "security"
    model_setting_attr = "claude_default_model"  # Sonnet
    max_output_tokens = 2048

    def _system_prompt(self) -> str:
        return SECURITY_SYSTEM_PROMPT

    def _schema_reminder(self) -> str:
        return _SCHEMA_REMINDER

    def _build_user_prompt(self, ctx: SpecialistContext) -> str:
        ba = ctx.prior_artifacts.get("business_analyst")
        sa = ctx.prior_artifacts.get("stack_analyst")
        return (
            "## Jira ticket\n"
            f"{json.dumps(ctx.ticket, indent=2)}\n\n"
            "## Repository\n"
            f"{json.dumps(ctx.repo, indent=2)}\n\n"
            "## BusinessAnalyst artifact\n"
            f"{json.dumps(ba.payload if ba else {}, indent=2)}\n\n"
            "## StackAnalyst artifact\n"
            f"{json.dumps(sa.payload if sa else {}, indent=2)}\n\n"
            "Produce the Security artifact JSON."
        )
```

- [ ] **Step 5: Run test to verify it passes**

```bash
pytest tests/unit/test_specialist_security.py -v
```
Expected: 2 passed.

- [ ] **Step 6: Commit**

```bash
git add src/ai_driven/claude/prompts/specialists/security.py src/ai_driven/agents/specialists/security tests/unit/test_specialist_security.py
git commit -m "feat(specialists): add Security specialist (live, Sonnet)"
```

---

## Task 11: QA specialist (live, Sonnet, final gate)

**Files:**
- Create: `src/ai_driven/claude/prompts/specialists/qa.py`
- Create: `src/ai_driven/agents/specialists/qa/__init__.py` (empty)
- Create: `src/ai_driven/agents/specialists/qa/agent.py`
- Test: `tests/unit/test_specialist_qa.py`

- [ ] **Step 1: Write the failing test**

Create `tests/unit/test_specialist_qa.py`:

```python
"""Unit tests for QaSpecialist with mocked Claude."""
from __future__ import annotations

import json
from types import SimpleNamespace
from unittest.mock import AsyncMock

import pytest

from ai_driven.agents.orchestrator.artifacts import Artifact
from ai_driven.agents.specialists.base import SpecialistContext
from ai_driven.agents.specialists.qa.agent import QaSpecialist


def _ctx() -> SpecialistContext:
    ba = Artifact(role="business_analyst", verdict="pass", reasoning="",
                  payload={"ambiguities_resolved": [], "acceptance_criteria": [
                      {"given": "g", "when": "w", "then": "t"}
                  ], "out_of_scope": [], "blocking_questions": []},
                  model_used="m", input_tokens=1, output_tokens=1)
    sec = Artifact(role="security", verdict="pass", reasoning="",
                   payload={"threat_model": [], "implementation_rules": ["rule"],
                            "compliance_notes": [], "blocking_concerns": []},
                   model_used="m", input_tokens=1, output_tokens=1)
    return SpecialistContext(
        task_id="t-qa",
        ticket={"key": "K", "summary": "x", "description": "y", "labels": []},
        repo={"full_name": "o/r", "scm_type": "github", "default_branch": "main"},
        tier="full_workflow",
        prior_artifacts={"business_analyst": ba, "security": sec},
    )


@pytest.mark.asyncio
async def test_qa_pass_when_all_checks_passed() -> None:
    agent = QaSpecialist()
    agent._publish_event = AsyncMock()
    body = {
        "verdict": "pass",
        "reasoning": "All ACs met, no security regressions.",
        "payload": {
            "checks_performed": [
                {"check": "AC1 satisfied", "passed": True, "detail": "test_login.py"},
                {"check": "Security rule 1 followed", "passed": True, "detail": "argon2id used"},
            ],
            "test_coverage_gap": [],
            "regression_risk": "low",
        },
    }
    agent._claude.chat = AsyncMock(return_value=SimpleNamespace(
        text=json.dumps(body)[1:] + "\n",
        usage=SimpleNamespace(input_tokens=300, output_tokens=200),
    ))
    art = await agent.run_specialist(_ctx())
    assert art.verdict == "pass"
    assert art.payload["regression_risk"] == "low"


@pytest.mark.asyncio
async def test_qa_block_when_ac_unsatisfied() -> None:
    agent = QaSpecialist()
    agent._publish_event = AsyncMock()
    body = {
        "verdict": "block",
        "reasoning": "AC2 not implemented.",
        "payload": {
            "checks_performed": [
                {"check": "AC2", "passed": False, "detail": "endpoint missing"},
            ],
            "test_coverage_gap": ["AC2 untested"],
            "regression_risk": "medium",
        },
    }
    agent._claude.chat = AsyncMock(return_value=SimpleNamespace(
        text=json.dumps(body)[1:] + "\n",
        usage=SimpleNamespace(input_tokens=300, output_tokens=200),
    ))
    art = await agent.run_specialist(_ctx())
    assert art.verdict == "block"
```

- [ ] **Step 2: Run test to verify it fails**

```bash
pytest tests/unit/test_specialist_qa.py -v
```
Expected: ImportError.

- [ ] **Step 3: Implement system prompt**

Create `src/ai_driven/claude/prompts/specialists/qa.py`:

```python
"""System prompt for the QA specialist (final gate)."""

QA_SYSTEM_PROMPT = """\
# ROLE
You are the QA specialist — the FINAL GATE before a task is marked
completed. You read every prior artifact (BA, StackAnalyst, Security,
TechLead/UX/APITest if present), the PR diff, and reviewer comments.
You issue a GO/NO-GO decision via the verdict field.

# WHAT YOU READ
- All prior specialist artifacts.
- (Phase 2: PR diff and reviewer comments. In Phase 1 shadow mode, the
   ticket and prior artifacts are sufficient — you may flag gaps.)

# WHAT YOU PRODUCE
Single JSON in one ```json fence:

{
  "verdict": "pass" | "pass_with_notes" | "block",
  "reasoning": "<1-2 sentences GO/NO-GO summary>",
  "payload": {
    "checks_performed": [{"check":"...","passed":true|false,"detail":"..."}],
    "test_coverage_gap": ["<missing test>"],
    "regression_risk": "low" | "medium" | "high"
  }
}

# DECISION RULES
- 'pass'              Every BA acceptance criterion is satisfied AND every
                      Security implementation_rule has evidence of being
                      followed AND test coverage is acceptable.
- 'pass_with_notes'   Minor gaps that don't block ship (low-priority test
                      coverage, optional edge cases).
- 'block'             Any AC unsatisfied OR any Security rule violated OR
                      regression_risk == 'high' without justification.

# ANTI-PATTERNS — THINGS YOU DO NOT DO
- You do NOT re-run line-by-line code review — that is ReviewerAgent.
- You do NOT design tests — you check that tests EXIST for ACs.
- You do NOT model new threats — that is Security.
- You do NOT block over style or refactoring opinions.
"""
```

- [ ] **Step 4: Implement the agent class**

Create `src/ai_driven/agents/specialists/qa/__init__.py` (empty).

Create `src/ai_driven/agents/specialists/qa/agent.py`:

```python
"""QA specialist — final acceptance gate."""
from __future__ import annotations

import json

from ai_driven.agents.specialists.base import SpecialistBase, SpecialistContext
from ai_driven.claude.prompts.specialists.qa import QA_SYSTEM_PROMPT


_SCHEMA_REMINDER = (
    "Re-emit ONLY the QA artifact JSON inside one ```json fence, with "
    "verdict in {pass, pass_with_notes, block}, reasoning, and a payload "
    "containing checks_performed, test_coverage_gap, regression_risk."
)


class QaSpecialist(SpecialistBase):
    agent_type = "specialist_qa"
    role = "qa"
    model_setting_attr = "claude_default_model"   # Sonnet (context-heavy)
    max_output_tokens = 2048

    def _system_prompt(self) -> str:
        return QA_SYSTEM_PROMPT

    def _schema_reminder(self) -> str:
        return _SCHEMA_REMINDER

    def _build_user_prompt(self, ctx: SpecialistContext) -> str:
        prior = {role: art.payload for role, art in ctx.prior_artifacts.items()}
        return (
            "## Jira ticket\n"
            f"{json.dumps(ctx.ticket, indent=2)}\n\n"
            "## Repository\n"
            f"{json.dumps(ctx.repo, indent=2)}\n\n"
            "## Prior specialist artifacts\n"
            f"{json.dumps(prior, indent=2)}\n\n"
            "Produce the QA gate JSON."
        )
```

- [ ] **Step 5: Run test to verify it passes**

```bash
pytest tests/unit/test_specialist_qa.py -v
```
Expected: 2 passed.

- [ ] **Step 6: Commit**

```bash
git add src/ai_driven/claude/prompts/specialists/qa.py src/ai_driven/agents/specialists/qa tests/unit/test_specialist_qa.py
git commit -m "feat(specialists): add QA specialist (live, Sonnet, final gate)"
```

---

## Task 12: Tier classifier

**Files:**
- Create: `src/ai_driven/claude/prompts/orchestrator.py`
- Create: `src/ai_driven/agents/orchestrator/classifier.py`
- Test: `tests/unit/test_orchestrator_classifier.py`

- [ ] **Step 1: Write the failing test**

Create `tests/unit/test_orchestrator_classifier.py`:

```python
"""Unit tests for the tier classifier."""
from __future__ import annotations

from types import SimpleNamespace
from unittest.mock import AsyncMock

import pytest

from ai_driven.agents.orchestrator.classifier import (
    TierClassifier,
    parse_tier,
)


def test_parse_tier_strict_match() -> None:
    assert parse_tier("fast_path") == "fast_path"
    assert parse_tier("```\nfull_workflow\n```") == "full_workflow"
    assert parse_tier("multi_domain") == "multi_domain"


def test_parse_tier_unknown_falls_back_to_full_workflow() -> None:
    assert parse_tier("not a tier") == "full_workflow"
    assert parse_tier("") == "full_workflow"


@pytest.mark.asyncio
async def test_classify_calls_haiku_with_temp_zero() -> None:
    classifier = TierClassifier()
    classifier._claude.chat = AsyncMock(return_value=SimpleNamespace(
        text="fast_path", usage=SimpleNamespace(input_tokens=20, output_tokens=2),
    ))
    tier = await classifier.classify(
        ticket={"summary": "Fix typo in README", "description": "tiny"},
        repo={"full_name": "o/r"},
    )
    assert tier == "fast_path"
    classifier._claude.chat.assert_awaited_once()
    call = classifier._claude.chat.await_args
    assert call.kwargs["temperature"] == 0.0
    # Model comes from settings.claude_fast_model. Post-H1 the platform
    # pins every slot to Sonnet 4.6; the indirection (not the literal) is
    # what this test locks — no specialist/classifier should ever
    # hardcode a model string.
    from ai_driven.config import get_settings
    assert call.kwargs["model"] == get_settings().claude_fast_model


@pytest.mark.asyncio
async def test_classify_falls_back_on_infra_error() -> None:
    classifier = TierClassifier()
    classifier._claude.chat = AsyncMock(side_effect=RuntimeError("boom"))
    tier = await classifier.classify(
        ticket={"summary": "x", "description": "y"},
        repo={"full_name": "o/r"},
    )
    assert tier == "full_workflow"
```

- [ ] **Step 2: Run test to verify it fails**

```bash
pytest tests/unit/test_orchestrator_classifier.py -v
```
Expected: ImportError.

- [ ] **Step 3: Implement prompts module**

Create `src/ai_driven/claude/prompts/orchestrator.py`:

```python
"""System prompts for the orchestrator's classifier and picker."""

TIER_CLASSIFIER_SYSTEM_PROMPT = """\
You classify a Jira ticket into exactly one of three orchestration tiers
for a coding-agent platform. Output exactly one of these strings, with
no surrounding text or punctuation: fast_path | full_workflow | multi_domain

Definitions:
- fast_path:      No trust boundary touched (no auth/session, payments,
                  PII, external API surface). Single domain. A mistake
                  here cannot cause a security incident, data breach, or
                  financial loss.
- full_workflow:  Trust boundary touched but only one domain (e.g.
                  backend-only auth change, PII handling in a single
                  service, new external endpoint).
- multi_domain:   Trust boundary touched AND at least two engineering
                  domains affected (e.g. auth + mobile + backend, or
                  payments + UI + infra).

Output requirement: ONE word, lowercase, no quotes, no markdown.
"""

PICKER_SYSTEM_PROMPT = """\
You select which optional specialists should run for a given task. You
will be given the candidates list. Output a JSON array of role names
that you choose to include — a subset of the candidates. No other
specialists. No prose. Example output: ["tech_lead"]
"""
```

- [ ] **Step 4: Implement classifier module**

Create `src/ai_driven/agents/orchestrator/classifier.py`:

```python
"""Tier classifier — single fast-model call per task (Sonnet post-H1)."""
from __future__ import annotations

import json
import logging
from typing import Any

from ai_driven.claude.client import ClaudeClient
from ai_driven.claude.prompts.orchestrator import TIER_CLASSIFIER_SYSTEM_PROMPT
from ai_driven.config import get_settings

logger = logging.getLogger(__name__)

_VALID_TIERS = {"fast_path", "full_workflow", "multi_domain"}
_DEFAULT_TIER = "full_workflow"   # safe default on parse/infra failure


def parse_tier(text: str) -> str:
    """Extract the tier word from raw model output. Defaults to full_workflow."""
    if not text:
        return _DEFAULT_TIER
    cleaned = text.strip().strip("`").strip()
    # First whitespace-separated token, lowercased
    token = cleaned.split()[0].lower() if cleaned else ""
    if token in _VALID_TIERS:
        return token
    return _DEFAULT_TIER


class TierClassifier:
    """Single-call classifier (settings.claude_fast_model) returning {fast_path,full_workflow,multi_domain}."""

    def __init__(self, claude_client: ClaudeClient | None = None) -> None:
        self._claude = claude_client or ClaudeClient()

    async def classify(
        self,
        ticket: dict[str, Any],
        repo: dict[str, Any],
    ) -> str:
        settings = get_settings()
        user = (
            "## Ticket\n"
            f"{json.dumps(ticket, indent=2)}\n\n"
            "## Repo\n"
            f"{json.dumps(repo, indent=2)}\n\n"
            "Output exactly one word: fast_path | full_workflow | multi_domain"
        )
        try:
            resp = await self._claude.chat(
                messages=[{"role": "user", "content": user}],
                system=TIER_CLASSIFIER_SYSTEM_PROMPT,
                model=settings.claude_fast_model,
                max_tokens=8,
                temperature=0.0,
            )
        except Exception as exc:  # noqa: BLE001
            logger.warning("Tier classifier infra error (falling back): %s", exc)
            return _DEFAULT_TIER
        return parse_tier(resp.text or "")
```

- [ ] **Step 5: Run test to verify it passes**

```bash
pytest tests/unit/test_orchestrator_classifier.py -v
```
Expected: 4 passed.

- [ ] **Step 6: Commit**

```bash
git add src/ai_driven/claude/prompts/orchestrator.py src/ai_driven/agents/orchestrator/classifier.py tests/unit/test_orchestrator_classifier.py
git commit -m "feat(orchestrator): add tier classifier (settings.claude_fast_model)"
```

---

## Task 13: Routing rules

**Files:**
- Create: `src/ai_driven/agents/orchestrator/routing.py`
- Test: `tests/unit/test_orchestrator_routing.py`

- [ ] **Step 1: Write the failing test**

Create `tests/unit/test_orchestrator_routing.py`:

```python
"""Unit tests for routing rules — pure functions, no mocks needed."""
from __future__ import annotations

import pytest

from ai_driven.agents.orchestrator.routing import (
    TIER_RULES,
    SpecialistPlan,
    plan_specialist_sequence,
)


def test_tier_rules_has_three_tiers() -> None:
    assert set(TIER_RULES) == {"fast_path", "full_workflow", "multi_domain"}


def test_fast_path_skips_to_pipeline_with_no_specialists() -> None:
    plan = plan_specialist_sequence("fast_path", task_context={})
    assert plan.skip_to_pipeline is True
    assert plan.mandatory == []
    assert plan.candidates == []
    assert plan.final_gate is None


def test_full_workflow_runs_ba_then_stack_analyst_then_security() -> None:
    plan = plan_specialist_sequence("full_workflow", task_context={})
    assert plan.mandatory == ["business_analyst", "stack_analyst", "security"]
    assert plan.final_gate == "qa"
    assert plan.skip_to_pipeline is False


def test_multi_domain_includes_tech_lead_in_mandatory() -> None:
    plan = plan_specialist_sequence("multi_domain", task_context={})
    assert plan.mandatory == [
        "business_analyst", "stack_analyst", "tech_lead", "security",
    ]
    assert "ux_designer" in plan.candidates
    assert "api_test" in plan.candidates


def test_unknown_tier_raises() -> None:
    with pytest.raises(ValueError):
        plan_specialist_sequence("nonsense", task_context={})


def test_specialist_plan_full_sequence_orders_qa_last() -> None:
    plan = plan_specialist_sequence("full_workflow", task_context={})
    full = plan.full_sequence()
    assert full[0] == "business_analyst"
    assert full[-1] == "qa"
```

- [ ] **Step 2: Run test to verify it fails**

```bash
pytest tests/unit/test_orchestrator_routing.py -v
```
Expected: ImportError.

- [ ] **Step 3: Implement routing module**

Create `src/ai_driven/agents/orchestrator/routing.py`:

```python
"""Tier-based routing rules — pure Python, code-reviewable.

Per-team customization is deferred to spec C (teams.settings_json
overrides). This module is the single source of truth for which
specialists run for each tier.
"""
from __future__ import annotations

from dataclasses import dataclass, field
from typing import Any


@dataclass(frozen=True)
class TierRule:
    mandatory: list[str]
    candidates: list[str]    # picked by Layer 3 (LLM picker)
    skip_to_pipeline: bool
    final_gate: str | None = None


TIER_RULES: dict[str, TierRule] = {
    "fast_path": TierRule(
        mandatory=[], candidates=[], skip_to_pipeline=True, final_gate=None,
    ),
    "full_workflow": TierRule(
        mandatory=["business_analyst", "stack_analyst", "security"],
        candidates=["tech_lead", "ux_designer"],
        skip_to_pipeline=False,
        final_gate="qa",
    ),
    "multi_domain": TierRule(
        mandatory=["business_analyst", "stack_analyst", "tech_lead", "security"],
        candidates=["ux_designer", "api_test"],
        skip_to_pipeline=False,
        final_gate="qa",
    ),
}


@dataclass
class SpecialistPlan:
    tier: str
    mandatory: list[str]
    candidates: list[str]
    skip_to_pipeline: bool
    final_gate: str | None
    picked: list[str] = field(default_factory=list)

    def full_sequence(self) -> list[str]:
        """Mandatory + picked + final_gate, in run order."""
        seq = list(self.mandatory) + list(self.picked)
        if self.final_gate is not None:
            seq.append(self.final_gate)
        return seq


def plan_specialist_sequence(
    tier: str,
    task_context: dict[str, Any],
) -> SpecialistPlan:
    """Return the SpecialistPlan for a tier. Picker fills in `picked` later."""
    rule = TIER_RULES.get(tier)
    if rule is None:
        raise ValueError(f"Unknown orchestration tier: {tier}")
    return SpecialistPlan(
        tier=tier,
        mandatory=list(rule.mandatory),
        candidates=list(rule.candidates),
        skip_to_pipeline=rule.skip_to_pipeline,
        final_gate=rule.final_gate,
    )
```

- [ ] **Step 4: Run test to verify it passes**

```bash
pytest tests/unit/test_orchestrator_routing.py -v
```
Expected: 6 passed.

- [ ] **Step 5: Commit**

```bash
git add src/ai_driven/agents/orchestrator/routing.py tests/unit/test_orchestrator_routing.py
git commit -m "feat(orchestrator): add code-level tier routing rules"
```

---

## Task 14: Fan-out rules

**Files:**
- Create: `src/ai_driven/agents/orchestrator/fan_out.py`
- Test: `tests/unit/test_orchestrator_fan_out.py`

- [ ] **Step 1: Write the failing test**

Create `tests/unit/test_orchestrator_fan_out.py`:

```python
"""Unit tests for fan-out — what each specialist sees."""
from __future__ import annotations

import pytest

from ai_driven.agents.orchestrator.artifacts import Artifact
from ai_driven.agents.orchestrator.fan_out import relevant_artifacts_for


def _art(role: str, payload: dict | None = None) -> Artifact:
    return Artifact(
        role=role, verdict="pass", reasoning="",
        payload=payload or {}, model_used="m",
        input_tokens=1, output_tokens=1,
    )


def _all() -> dict[str, Artifact]:
    return {
        "business_analyst": _art("business_analyst"),
        "stack_analyst": _art("stack_analyst",
                              {"stacks": ["frontend"], "primary_stack": "frontend",
                               "cross_cutting_concerns": [], "reasoning": ""}),
        "security": _art("security"),
        "tech_lead": _art("tech_lead"),
    }


def test_business_analyst_sees_nothing() -> None:
    assert relevant_artifacts_for("business_analyst", _all()) == {}


def test_stack_analyst_sees_only_ba() -> None:
    out = relevant_artifacts_for("stack_analyst", _all())
    assert set(out) == {"business_analyst"}


def test_security_sees_ba_and_stack_analyst() -> None:
    out = relevant_artifacts_for("security", _all())
    assert set(out) == {"business_analyst", "stack_analyst"}


def test_qa_sees_all_prior() -> None:
    out = relevant_artifacts_for("qa", _all())
    assert set(out) == {"business_analyst", "stack_analyst", "security", "tech_lead"}


def test_ux_designer_only_runs_when_frontend_or_mobile_in_stacks() -> None:
    artifacts = _all()
    # frontend → UX runs; sees BA + stack_analyst
    out = relevant_artifacts_for("ux_designer", artifacts)
    assert set(out) == {"business_analyst", "stack_analyst"}

    # backend-only stack_analyst → UX should not have been scheduled, but
    # if it was, it still receives BA + stack_analyst (filter is at the
    # picker stage, not here).
    artifacts["stack_analyst"] = _art("stack_analyst",
        {"stacks": ["backend"], "primary_stack": "backend",
         "cross_cutting_concerns": [], "reasoning": ""})
    out = relevant_artifacts_for("ux_designer", artifacts)
    assert set(out) == {"business_analyst", "stack_analyst"}


def test_unknown_role_raises() -> None:
    with pytest.raises(ValueError):
        relevant_artifacts_for("unicorn", _all())
```

- [ ] **Step 2: Run test to verify it fails**

```bash
pytest tests/unit/test_orchestrator_fan_out.py -v
```
Expected: ImportError.

- [ ] **Step 3: Implement fan-out module**

Create `src/ai_driven/agents/orchestrator/fan_out.py`:

```python
"""Fan-out rules — which prior artifacts each specialist sees.

Pure function: relevant_artifacts_for(role, all_artifacts) -> dict[str, Artifact].

Hard-coded per the matrix in
docs/specs/2026-04-29-orchestrator-specialists-design.md.
"""
from __future__ import annotations

from ai_driven.agents.orchestrator.artifacts import Artifact

_FAN_OUT: dict[str, list[str]] = {
    "business_analyst": [],
    "stack_analyst":   ["business_analyst"],
    "security":        ["business_analyst", "stack_analyst"],
    "tech_lead":       ["business_analyst", "stack_analyst", "security"],
    "ux_designer":     ["business_analyst", "stack_analyst"],
    "api_test":        ["stack_analyst", "tech_lead", "security"],
    "qa":              ["business_analyst", "stack_analyst", "security",
                        "tech_lead", "ux_designer", "api_test"],
}


def relevant_artifacts_for(
    role: str,
    all_artifacts: dict[str, Artifact],
) -> dict[str, Artifact]:
    """Return the subset of all_artifacts the given role is allowed to read."""
    allowed = _FAN_OUT.get(role)
    if allowed is None:
        raise ValueError(f"Unknown specialist role: {role}")
    return {r: all_artifacts[r] for r in allowed if r in all_artifacts}


def should_run_ux_designer(stack_analyst_payload: dict) -> bool:
    """UX runs only for frontend or mobile work. Used by the picker stage."""
    stacks = set(stack_analyst_payload.get("stacks", []))
    return bool(stacks & {"frontend", "mobile"})
```

- [ ] **Step 4: Run test to verify it passes**

```bash
pytest tests/unit/test_orchestrator_fan_out.py -v
```
Expected: 6 passed.

- [ ] **Step 5: Commit**

```bash
git add src/ai_driven/agents/orchestrator/fan_out.py tests/unit/test_orchestrator_fan_out.py
git commit -m "feat(orchestrator): add fan-out rules for prior-artifact visibility"
```

---

## Task 15: Config — `orchestrator_mode` + new SQS queue URLs

**Files:**
- Modify: `src/ai_driven/config.py`
- Test: covered indirectly by `tests/unit/test_config.py` if it exists; otherwise add a focused test.

- [ ] **Step 1: Write the failing test**

Create `tests/unit/test_orchestrator_config.py`:

```python
"""Unit tests for new orchestrator config knobs."""
from __future__ import annotations

import os
from unittest.mock import patch

from ai_driven.config import Settings, get_settings


def test_orchestrator_mode_defaults_to_shadow() -> None:
    s = Settings()
    assert s.orchestrator_mode == "shadow"


def test_orchestrator_mode_accepts_shadow_live_off() -> None:
    for v in ("shadow", "live", "off"):
        s = Settings(orchestrator_mode=v)
        assert s.orchestrator_mode == v


def test_new_sqs_queue_urls_default_empty() -> None:
    s = Settings()
    assert s.sqs_orchestrator_queue_url == ""
    assert s.sqs_specialists_queue_url == ""


def test_orchestrator_mode_env_var_picks_up_prefix() -> None:
    with patch.dict(os.environ, {"AI_DRIVEN_ORCHESTRATOR_MODE": "live"}):
        get_settings.cache_clear()
        try:
            assert get_settings().orchestrator_mode == "live"
        finally:
            get_settings.cache_clear()
```

- [ ] **Step 2: Run test to verify it fails**

```bash
pytest tests/unit/test_orchestrator_config.py -v
```
Expected: AttributeError on `orchestrator_mode`.

- [ ] **Step 3: Add config fields**

In `src/ai_driven/config.py`, near the existing SQS section (around line 119):

```python
    # SQS — orchestrator + specialists (Phase 1 of orchestrator rollout)
    sqs_orchestrator_queue_url: str = ""
    sqs_specialists_queue_url: str = ""

    # Orchestrator mode — controls whether the orchestrator's classification
    # affects downstream pipelines. Default 'shadow' for safe rollout.
    #   shadow: classify + write artifacts, but do NOT gate the existing
    #           coding/review pipeline (Phase 1 default).
    #   live:   gate the pipeline on specialist verdicts (Phase 2).
    #   off:    skip orchestrator entirely; tasks route as before.
    orchestrator_mode: str = "shadow"
```

- [ ] **Step 4: Run test to verify it passes**

```bash
pytest tests/unit/test_orchestrator_config.py -v
```
Expected: 4 passed.

- [ ] **Step 5: Commit**

```bash
git add src/ai_driven/config.py tests/unit/test_orchestrator_config.py
git commit -m "feat(config): add orchestrator_mode and new SQS queue URLs"
```

---

## Task 16: `OrchestratorAgent` class

**Files:**
- Create: `src/ai_driven/agents/orchestrator/agent.py`
- Test: `tests/unit/test_orchestrator_agent.py`

- [ ] **Step 1: Write the failing test**

Create `tests/unit/test_orchestrator_agent.py`:

```python
"""Unit tests for OrchestratorAgent — full flow with mocked specialists."""
from __future__ import annotations

import uuid
from types import SimpleNamespace
from unittest.mock import AsyncMock, MagicMock, patch

import pytest

from ai_driven.agents.orchestrator.agent import OrchestratorAgent
from ai_driven.agents.orchestrator.artifacts import Artifact


def _ba_artifact(verdict: str = "pass") -> Artifact:
    return Artifact(
        role="business_analyst", verdict=verdict, reasoning="ok",
        payload={"ambiguities_resolved": [], "acceptance_criteria": [],
                 "out_of_scope": [], "blocking_questions": [] if verdict != "block" else ["?"]},
        model_used="m", input_tokens=10, output_tokens=10,
    )


def _other_pass(role: str) -> Artifact:
    return Artifact(role=role, verdict="pass", reasoning="ok",
                    payload={}, model_used="m", input_tokens=1, output_tokens=1)


@pytest.mark.asyncio
async def test_fast_path_skips_specialists_and_logs_only(monkeypatch) -> None:
    agent = OrchestratorAgent()
    agent._publish_event = AsyncMock()
    agent._classify_tier = AsyncMock(return_value="fast_path")
    agent._persist_tier = AsyncMock()
    agent._dispatch_specialist = AsyncMock()
    agent._is_shadow_mode = MagicMock(return_value=True)

    payload = {"task_id": str(uuid.uuid4()),
               "ticket": {"key": "K", "summary": "x", "description": "y"},
               "repo": {"full_name": "o/r"}}
    result = await agent.orchestrate(payload)

    assert result["tier"] == "fast_path"
    assert result["specialists_run"] == []
    agent._dispatch_specialist.assert_not_called()


@pytest.mark.asyncio
async def test_full_workflow_runs_mandatory_specialists_in_order() -> None:
    agent = OrchestratorAgent()
    agent._publish_event = AsyncMock()
    agent._classify_tier = AsyncMock(return_value="full_workflow")
    agent._persist_tier = AsyncMock()
    agent._is_shadow_mode = MagicMock(return_value=True)

    # Stub dispatcher: returns role-keyed Artifact
    async def fake_dispatch(role, ctx):
        if role == "business_analyst":
            return _ba_artifact()
        return _other_pass(role)
    agent._dispatch_specialist = AsyncMock(side_effect=fake_dispatch)
    agent._pick_optional_specialists = AsyncMock(return_value=[])

    payload = {"task_id": str(uuid.uuid4()),
               "ticket": {"key": "K", "summary": "x", "description": "y"},
               "repo": {"full_name": "o/r"}}
    result = await agent.orchestrate(payload)

    assert result["tier"] == "full_workflow"
    # Order matters: BA -> stack_analyst -> security -> qa
    assert [c.args[0] for c in agent._dispatch_specialist.call_args_list] == [
        "business_analyst", "stack_analyst", "security", "qa",
    ]


@pytest.mark.asyncio
async def test_blocker_verdict_halts_remaining_specialists() -> None:
    agent = OrchestratorAgent()
    agent._publish_event = AsyncMock()
    agent._classify_tier = AsyncMock(return_value="full_workflow")
    agent._persist_tier = AsyncMock()
    agent._is_shadow_mode = MagicMock(return_value=True)

    async def fake_dispatch(role, ctx):
        if role == "business_analyst":
            return _ba_artifact("block")
        return _other_pass(role)
    agent._dispatch_specialist = AsyncMock(side_effect=fake_dispatch)
    agent._pick_optional_specialists = AsyncMock(return_value=[])

    payload = {"task_id": str(uuid.uuid4()),
               "ticket": {"key": "K", "summary": "x", "description": "y"},
               "repo": {"full_name": "o/r"}}
    result = await agent.orchestrate(payload)

    # BA blocked -> stack_analyst, security, qa skipped
    called = [c.args[0] for c in agent._dispatch_specialist.call_args_list]
    assert called == ["business_analyst"]
    assert result["halted_on"] == "business_analyst"


@pytest.mark.asyncio
async def test_shadow_mode_does_not_signal_pipeline_change() -> None:
    """Phase 1 invariant: orchestrator never returns gate-pass/fail signals
    that would change downstream behavior. result['shadow'] must be True."""
    agent = OrchestratorAgent()
    agent._publish_event = AsyncMock()
    agent._classify_tier = AsyncMock(return_value="fast_path")
    agent._persist_tier = AsyncMock()
    agent._dispatch_specialist = AsyncMock()
    agent._is_shadow_mode = MagicMock(return_value=True)

    payload = {"task_id": str(uuid.uuid4()),
               "ticket": {"key": "K", "summary": "x", "description": "y"},
               "repo": {"full_name": "o/r"}}
    result = await agent.orchestrate(payload)
    assert result["shadow"] is True
```

- [ ] **Step 2: Run test to verify it fails**

```bash
pytest tests/unit/test_orchestrator_agent.py -v
```
Expected: ImportError.

- [ ] **Step 3: Implement OrchestratorAgent**

Create `src/ai_driven/agents/orchestrator/agent.py`:

```python
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
"""
from __future__ import annotations

import json
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
                "tier": tier, "specialists_run": [], "shadow": self._is_shadow_mode(),
            })
            return {
                "tier": tier, "specialists_run": [],
                "shadow": self._is_shadow_mode(),
                "halted_on": None, "artifacts": {},
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
        [] is correct.
        """
        all_stubs = all(c in {"tech_lead", "ux_designer", "api_test"}
                        for c in plan.candidates)
        if all_stubs:
            return []
        # Phase 2 will add the picker call here.
        return []
```

- [ ] **Step 4: Run test to verify it passes**

```bash
pytest tests/unit/test_orchestrator_agent.py -v
```
Expected: 4 passed.

- [ ] **Step 5: Commit**

```bash
git add src/ai_driven/agents/orchestrator/agent.py tests/unit/test_orchestrator_agent.py
git commit -m "feat(orchestrator): add OrchestratorAgent with shadow-mode flow"
```

---

## Task 17: Wire orchestrator into producer + consumer (shadow mode)

**Files:**
- Modify: `src/ai_driven/queue/producer.py` (add `orchestrator` and `specialist` to queue map)
- Modify: `src/ai_driven/queue/consumer.py` (add orchestrator + specialist queue dispatch)
- Test: `tests/integration/test_orchestrator_shadow_mode.py`

In shadow mode, every incoming task that would normally enqueue to `coding` ALSO enqueues to `orchestrator` (or runs the orchestrator inline if SQS is unavailable). The orchestrator runs independently and persists artifacts; the existing coding pipeline is unaffected.

- [ ] **Step 1: Write the failing integration test**

Create `tests/integration/test_orchestrator_shadow_mode.py`:

```python
"""Integration test: shadow-mode orchestrator runs alongside coding pipeline.

Real Postgres + Redis, mocked Claude. Verifies:
  * tasks.orchestration_tier is set
  * task_artifacts rows are written for fast_path (none) and full_workflow
  * coding pipeline continues unaffected (no gate signal returned)
"""
from __future__ import annotations

import json
import uuid
from types import SimpleNamespace
from unittest.mock import AsyncMock, patch

import pytest
from sqlalchemy import select

from ai_driven.agents.orchestrator.agent import OrchestratorAgent
from ai_driven.db.postgres.models import Task, TaskArtifact, Team
from ai_driven.db.postgres.session import async_session_factory


def _bytes(d: dict) -> str:
    return json.dumps(d)[1:] + "\n"


@pytest.mark.asyncio
async def test_shadow_mode_classifies_and_writes_artifacts(monkeypatch) -> None:
    # 1. Seed a task in the DB
    task_id = uuid.uuid4()
    async with async_session_factory() as session:
        team = Team(name=f"orch-shadow-{uuid.uuid4().hex[:6]}",
                    api_key_hash="x")
        session.add(team)
        await session.flush()
        session.add(Task(id=task_id, team_id=team.id, agent_type="coding",
                         status="queued", title="Add /api/orders POST"))
        await session.commit()

    # 2. Mock Claude responses for: tier classifier (full_workflow),
    #    BA (pass), stack_analyst (pass with backend), security (pass), qa (pass)
    classifier_resp = SimpleNamespace(text="full_workflow",
                                      usage=SimpleNamespace(input_tokens=10, output_tokens=2))
    ba_resp = SimpleNamespace(
        text=_bytes({"verdict": "pass", "reasoning": "ok",
                     "payload": {"ambiguities_resolved": [],
                                 "acceptance_criteria": [
                                     {"given": "g", "when": "w", "then": "t"}],
                                 "out_of_scope": [], "blocking_questions": []}}),
        usage=SimpleNamespace(input_tokens=50, output_tokens=20))
    sa_resp = SimpleNamespace(
        text=_bytes({"verdict": "pass", "reasoning": "ok",
                     "payload": {"stacks": ["backend"],
                                 "primary_stack": "backend",
                                 "cross_cutting_concerns": [],
                                 "reasoning": "FastAPI route"}}),
        usage=SimpleNamespace(input_tokens=40, output_tokens=20))
    sec_resp = SimpleNamespace(
        text=_bytes({"verdict": "pass", "reasoning": "ok",
                     "payload": {"threat_model": [], "implementation_rules": [],
                                 "compliance_notes": [], "blocking_concerns": []}}),
        usage=SimpleNamespace(input_tokens=80, output_tokens=40))
    qa_resp = SimpleNamespace(
        text=_bytes({"verdict": "pass", "reasoning": "ok",
                     "payload": {"checks_performed": [],
                                 "test_coverage_gap": [],
                                 "regression_risk": "low"}}),
        usage=SimpleNamespace(input_tokens=120, output_tokens=60))

    chat_responses = iter([classifier_resp, ba_resp, sa_resp, sec_resp, qa_resp])

    async def fake_chat(**kwargs):
        return next(chat_responses)

    # 3. Run orchestrator in shadow mode (default)
    orch = OrchestratorAgent()
    with patch.object(orch._claude, "chat", AsyncMock(side_effect=fake_chat)):
        # Each specialist class instantiates its own ClaudeClient — patch the
        # base ClaudeClient.chat globally for this test.
        with patch("ai_driven.claude.client.ClaudeClient.chat",
                   AsyncMock(side_effect=fake_chat)):
            result = await orch.orchestrate({
                "task_id": str(task_id),
                "ticket": {"key": "INT-1", "summary": "Add /api/orders POST",
                           "description": "FastAPI handler", "labels": []},
                "repo": {"full_name": "org/api", "scm_type": "github",
                         "default_branch": "main"},
            })

    assert result["tier"] == "full_workflow"
    assert result["shadow"] is True
    assert result["halted_on"] is None
    assert set(result["specialists_run"]) >= {
        "business_analyst", "stack_analyst", "security", "qa",
    }

    # 4. Verify DB state
    async with async_session_factory() as session:
        row = await session.execute(select(Task).where(Task.id == task_id))
        task = row.scalar_one()
        assert task.orchestration_tier == "full_workflow"

        arts = await session.execute(
            select(TaskArtifact).where(TaskArtifact.task_id == task_id)
        )
        roles = {a.role for a in arts.scalars()}
        assert {"business_analyst", "stack_analyst", "security", "qa"} <= roles


@pytest.mark.asyncio
async def test_fast_path_writes_only_orchestration_tier_no_artifacts() -> None:
    task_id = uuid.uuid4()
    async with async_session_factory() as session:
        team = Team(name=f"orch-fast-{uuid.uuid4().hex[:6]}", api_key_hash="x")
        session.add(team)
        await session.flush()
        session.add(Task(id=task_id, team_id=team.id, agent_type="coding",
                         status="queued", title="fix typo"))
        await session.commit()

    fast_resp = SimpleNamespace(text="fast_path",
                                usage=SimpleNamespace(input_tokens=5, output_tokens=2))
    orch = OrchestratorAgent()
    with patch("ai_driven.claude.client.ClaudeClient.chat",
               AsyncMock(return_value=fast_resp)):
        result = await orch.orchestrate({
            "task_id": str(task_id),
            "ticket": {"key": "K", "summary": "fix typo", "description": "x"},
            "repo": {"full_name": "o/r", "scm_type": "github",
                     "default_branch": "main"},
        })

    assert result["tier"] == "fast_path"
    assert result["specialists_run"] == []

    async with async_session_factory() as session:
        row = await session.execute(select(Task).where(Task.id == task_id))
        assert row.scalar_one().orchestration_tier == "fast_path"
        arts = await session.execute(
            select(TaskArtifact).where(TaskArtifact.task_id == task_id)
        )
        assert arts.scalars().all() == []
```

- [ ] **Step 2: Run test to verify it fails**

```bash
pytest tests/integration/test_orchestrator_shadow_mode.py -v
```
Expected: import errors or queue-wiring errors — the orchestrator entry point in producer/consumer doesn't exist yet.

- [ ] **Step 3: Wire producer**

In `src/ai_driven/queue/producer.py`, extend `_queue_map`:

```python
        self._queue_map = {
            "coding": settings.sqs_task_queue_url,
            "review": settings.sqs_review_queue_url,
            "devops": settings.sqs_devops_queue_url,
            "orchestrator": settings.sqs_orchestrator_queue_url,
            "specialist": settings.sqs_specialists_queue_url,
        }
```

The `enqueue_task` method already handles missing queue URLs by falling back to inline processing (line 43–59), so an empty `sqs_orchestrator_queue_url` in local dev still works.

- [ ] **Step 4: Wire consumer**

In `src/ai_driven/queue/consumer.py`, extend `__init__`:

```python
        self._queue_urls = [
            url
            for url in [
                settings.sqs_task_queue_url,
                settings.sqs_review_queue_url,
                settings.sqs_devops_queue_url,
                settings.sqs_orchestrator_queue_url,
                settings.sqs_specialists_queue_url,
            ]
            if url
        ]
```

Add to `_create_agent`:

```python
        agents: dict[str, Any] = {
            "coding": lambda: CodingAgent(
                jira_client=jira_client, scm_client=scm_client
            ),
            "review": lambda: ReviewAgent(scm_client=scm_client),
            "devops": lambda: DevOpsAgent(jira_client=jira_client),
            "orchestrator": lambda: _OrchestratorWrapper(),
        }
```

At the bottom of `consumer.py`, add a thin wrapper that adapts `OrchestratorAgent.orchestrate(payload)` to the consumer's `agent.run(payload)` contract:

```python
class _OrchestratorWrapper:
    """Adapter exposing OrchestratorAgent.orchestrate() through `.run()`.

    The consumer expects every dispatched agent to expose `.run(payload)`
    and return an object with a `.conversation` attribute carrying token
    counts. The orchestrator doesn't use the agent loop, so we synthesize
    a minimal AgentResult-shaped object.
    """

    def __init__(self) -> None:
        from ai_driven.agents.orchestrator.agent import OrchestratorAgent
        self._inner = OrchestratorAgent()

    async def run(self, payload: dict[str, Any]) -> Any:
        from ai_driven.agents.base import AgentResult
        from ai_driven.claude.conversation import Conversation

        # Adapt the consumer payload to the orchestrator's expected shape
        ticket = payload.get("context", {}).get("ticket") or payload.get("ticket") or {
            "summary": payload.get("title") or payload.get("message", ""),
            "description": payload.get("message", ""),
        }
        repo = payload.get("context", {}).get("repo") or {
            "full_name": payload.get("repo_full_name", ""),
            "scm_type": payload.get("scm_type", "github"),
            "default_branch": "main",
        }
        result = await self._inner.orchestrate({
            "task_id": payload["task_id"] if "task_id" in payload else payload.get("task_id"),
            "ticket": ticket,
            "repo": repo,
        })
        conv = Conversation()
        return AgentResult(
            success=True, output=json.dumps(result),
            conversation=conv, tool_calls=0, iterations=0,
            duration_seconds=0.0,
        )
```

(Add `import json` if not already imported at top of consumer.py.)

The `task_id` field needs to be present on the orchestrator payload. Update producer's `enqueue_task` to include it on every message — it already does (line 64). Confirm by inspection.

- [ ] **Step 5: Add a producer-side fan-out hook (shadow mode)**

The simplest shadow rollout is to enqueue `orchestrator` alongside `coding` whenever a coding task is enqueued. Add this to `producer.py`'s `enqueue_task`, right after the `if agent_type == "coding"` line is determined (place it near the top of the method):

```python
    async def enqueue_task(
        self, task_id: str, agent_type: str, payload: dict[str, Any]
    ) -> str:
        settings = get_settings()
        # SHADOW: every coding-task submission also kicks off orchestrator.
        # The orchestrator persists artifacts but does not gate the pipeline.
        if (
            agent_type == "coding"
            and settings.orchestrator_mode != "off"
        ):
            try:
                await self._enqueue_shadow_orchestrator(task_id, payload)
            except Exception:  # noqa: BLE001
                logger.exception("shadow orchestrator dispatch failed (non-fatal)")

        # ... existing body unchanged ...
```

Then add the helper method on the same class:

```python
    async def _enqueue_shadow_orchestrator(
        self, task_id: str, payload: dict[str, Any],
    ) -> None:
        """Fire-and-forget enqueue of the orchestrator alongside coding."""
        shadow_payload = {**payload, "task_id": task_id}
        # Reuse the same enqueue_task path — recursion is safe because
        # agent_type='orchestrator' will not re-enter this branch.
        await self.enqueue_task(task_id, "orchestrator", shadow_payload)
```

This keeps the existing coding path unchanged: the orchestrator is purely additive in shadow mode.

- [ ] **Step 6: Run integration test**

```bash
pytest tests/integration/test_orchestrator_shadow_mode.py -v
```
Expected: 2 passed.

- [ ] **Step 7: Run consumer regression suite**

```bash
pytest tests/unit/test_consumer.py -v
```
Expected: existing consumer tests still pass.

- [ ] **Step 8: Commit**

```bash
git add src/ai_driven/queue/producer.py src/ai_driven/queue/consumer.py tests/integration/test_orchestrator_shadow_mode.py
git commit -m "feat(queue): wire orchestrator + specialists into producer/consumer (shadow mode)"
```

---

## Final verification

- [ ] **Step 1: Run the full Phase 1 test suite**

```bash
pytest \
  tests/unit/test_orchestrator_artifacts.py \
  tests/unit/test_orchestrator_routing.py \
  tests/unit/test_orchestrator_fan_out.py \
  tests/unit/test_orchestrator_classifier.py \
  tests/unit/test_orchestrator_agent.py \
  tests/unit/test_orchestrator_config.py \
  tests/unit/test_specialist_business_analyst.py \
  tests/unit/test_specialist_stack_analyst.py \
  tests/unit/test_specialist_security.py \
  tests/unit/test_specialist_qa.py \
  tests/unit/test_specialist_stubs.py \
  tests/unit/test_base_agent_repair_loop.py \
  tests/unit/test_reviewer_json_repair.py \
  tests/integration/test_orchestrator_shadow_mode.py \
  -v
```
Expected: every test passes.

- [ ] **Step 2: Run the full project test suite to catch regressions**

```bash
pytest tests/ -q --ignore=tests/evals
```
Expected: pre-existing baseline (≈620 unit + ≈25 integration) still green.

- [ ] **Step 3: Confirm the orchestrator runs end-to-end via the inline-fallback path**

```bash
AI_DRIVEN_ORCHESTRATOR_MODE=shadow python -c "
import asyncio, uuid
from unittest.mock import patch, AsyncMock
from types import SimpleNamespace
from ai_driven.agents.orchestrator.agent import OrchestratorAgent

async def main():
    fast = SimpleNamespace(text='fast_path',
                           usage=SimpleNamespace(input_tokens=5, output_tokens=1))
    with patch('ai_driven.claude.client.ClaudeClient.chat',
               AsyncMock(return_value=fast)):
        result = await OrchestratorAgent().orchestrate({
            'task_id': str(uuid.uuid4()),
            'ticket': {'summary': 'fix typo', 'description': 'tiny'},
            'repo': {'full_name': 'o/r', 'scm_type': 'github'},
        })
        print(result)

asyncio.run(main())
"
```
Expected output includes `'tier': 'fast_path'`, `'shadow': True`, `'specialists_run': []`.

- [ ] **Step 4: Sanity-check shadow behavior — coding pipeline still works**

Submit a real coding task to your local stack and confirm the existing flow runs to PR creation while a `task_artifacts` row is written for the orchestrator's classification (and any specialists, if `full_workflow`):

```bash
curl -X POST http://localhost:8000/api/admin/tasks \
  -H "X-API-Key: $LOCAL_API_KEY" \
  -H "Content-Type: application/json" \
  -d '{"agent_type":"coding","title":"Fix typo in README","message":"fix the typo on line 3","repo_full_name":"org/repo"}'

# After a few seconds:
psql "$AI_DRIVEN_POSTGRES_URL" -c \
  "SELECT id, orchestration_tier FROM tasks ORDER BY created_at DESC LIMIT 1;"
psql "$AI_DRIVEN_POSTGRES_URL" -c \
  "SELECT role, verdict FROM task_artifacts ORDER BY created_at DESC LIMIT 5;"
```
Expected: the task is processed by the coding pipeline normally; the latest task row has a non-NULL `orchestration_tier`; `task_artifacts` has zero rows for fast_path tasks and BA/stack_analyst/security/qa rows for full_workflow tasks.

---

## What's NOT done (deferred to Phase 2 / Phase 3)

Confirmed out of scope for this plan, do not implement:

- CodingAgent reading `task_artifacts` (security implementation_rules injection)
- ReviewerAgent reading `task_artifacts`
- `prompt_composer.py` + `stack_guides/*.md` library (StackAnalyst output is persisted but not yet consumed)
- ~~StackAnalyst's `repo.list_files` read-only tool~~ → now in-scope as `repo.overview` per 2026-04-30 revision §5
- `teams.orchestration_enabled` column + per-team UI gating
- UI Artifacts tab on task detail page
- Dashboard KPI for `% tasks by orchestration_tier`
- DataDog dashboards (orchestration health, cost attribution, gate effectiveness)
- Eval suite under `tests/evals/specialists/`
- Playwright e2e for the artifacts flow
- WorkflowAgent class deletion
- Relocating `state_machine.py` and `triggers.py` out of `agents/workflow/`
- Documentation updates to `docs/implementation/00-overview.md` and friends

Each will get its own plan — Phase 2 (per-team live + CodingAgent/ReviewerAgent integration + UI) and Phase 3 (WorkflowAgent deletion + relocation).
