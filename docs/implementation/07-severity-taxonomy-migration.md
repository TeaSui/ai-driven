# Implementation Plan 07 — Severity Taxonomy Unification

**Owner:** Data / Backend
**Severity:** P1 (data-corruption risk)
**Estimated effort:** 4–6 hours
**Blocks:** incident analytics, alert routing, SLA dashboards

## Problem

Four places in the repo disagree on what an incident severity value looks like. Same column — `incidents.severity` — accepts both uppercase and lowercase, and both 3-level and 4-level taxonomies.

| Source | Values | Case |
|---|---|---|
| `src/ai_driven/db/postgres/models.py:117` (column comment) | p1 \| p2 \| p3 | lowercase |
| `src/ai_driven/agents/devops/incident.py:11` `_VALID_SEVERITIES` | P1,P2,P3,P4 | uppercase |
| `src/ai_driven/api/admin/router.py:941` (default) | "p2" | lowercase |
| `docs/architecture/07` (pre-fix) / `docs/features/04` | P1-P3 vs P1-P4 | mixed |

Resulting bugs:
- `WHERE severity = 'P1'` misses records written as `'p1'`.
- Dashboards that bucket by severity under-count by ~50%.
- Alert routing (pager for P1, Slack-only for P4) misfires.
- UI colour mapping (`status-danger` for `P1`) doesn't match lowercase rows.

## Canonical decision

- **Set:** `P1, P2, P3, P4`.
- **Case:** uppercase.
- **Review-comment severity** (`critical|warning|suggestion`) is a **separate axis** — do **not** unify.

## Implementation steps

### 1. Introduce a Python enum (1 h)

```python
# src/ai_driven/domain/enums.py  (new file)
from enum import Enum

class IncidentSeverity(str, Enum):
    P1 = "P1"
    P2 = "P2"
    P3 = "P3"
    P4 = "P4"

    @classmethod
    def normalize(cls, value: str | None) -> "IncidentSeverity | None":
        if value is None:
            return None
        upper = value.strip().upper()
        return cls(upper) if upper in cls._value2member_map_ else None
```

### 2. Alembic migration (1 h)

New revision `a1b2c3d4e5f7_severity_uppercase.py`:

```python
def upgrade():
    # 1. Normalize existing data
    op.execute("""
        UPDATE incidents
        SET severity = UPPER(severity)
        WHERE severity ~ '^[a-z][0-9]$'
    """)
    # 2. Widen column if needed and add CHECK constraint
    op.create_check_constraint(
        "ck_incidents_severity",
        "incidents",
        "severity IN ('P1','P2','P3','P4')",
    )

def downgrade():
    op.drop_constraint("ck_incidents_severity", "incidents", type_="check")
```

### 3. Writer call sites (1 h)

- `agents/devops/incident.py`: already uppercase — just switch to `IncidentSeverity.P2.value`.
- `api/admin/router.py:941`: default `"p2"` → `IncidentSeverity.P2.value`.
- Any webhook ingester: pipe through `IncidentSeverity.normalize(payload["severity"])`; reject on `None`.

### 4. Pydantic models (30 min)

- `IncidentCreate`, `IncidentOut`, `IncidentFilter`: replace `severity: str` with `severity: IncidentSeverity`.

### 5. Frontend (30 min)

- `ai-driven-web-ui/src/lib/api.ts`: `Incident.severity: string` → `'P1' | 'P2' | 'P3' | 'P4'`.
- UI colour mapping — ensure lookup is case-sensitive-safe.

### 6. Tests (1 h)

- Unit: `IncidentSeverity.normalize("p1") == P1`, `normalize("X") is None`.
- DB: after migration, `SELECT DISTINCT severity FROM incidents` returns only uppercase.
- E2E: create incident via API with lowercase body — server normalizes before insert.

## Rollout

1. Deploy backend with enum + normalization (writes uppercase) **without** the CHECK constraint.
2. Let it run for 24 h so any in-flight records drain.
3. Run the migration (UPDATE → CHECK) in a maintenance window.
4. Ship frontend typing last.

## Rollback

If the CHECK constraint blocks a surprise writer, remove it, log offending rows, re-normalize, re-add. Data in the column is never destroyed.

## Acceptance

- `SELECT DISTINCT severity FROM incidents ORDER BY 1` returns exactly `{P1,P2,P3,P4}`.
- `docs/architecture/07-devops-incident.md` and `docs/features/04-ai-devops-assistant.md` both show P1-P4 uppercase (already done).
- New API request with `"severity":"p1"` is normalized to `P1` before insert.
- CHECK constraint exists (`\d+ incidents`).
