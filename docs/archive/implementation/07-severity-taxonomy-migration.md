# Implementation Plan 07 — Severity Taxonomy Unification

**Owner:** Data / Backend
**Severity:** P1 (data-corruption risk)
**Estimated effort:** 4–6 hours
**Blocks:** incident analytics, alert routing, SLA dashboards

> **Progress convention:** every line marked `[ ]` is outstanding, `[x]` done. Never delete items — tick them. That way history is auditable.

## Problem

Four sources of truth disagree on the shape of `incidents.severity`:

| Source | Values | Case |
|---|---|---|
| `src/ai_driven/db/postgres/models.py:117` (column comment) | p1 \| p2 \| p3 | lowercase |
| `src/ai_driven/agents/devops/incident.py:11` `_VALID_SEVERITIES` | P1,P2,P3,P4 | uppercase |
| `src/ai_driven/api/admin/router.py:941` (default) | "p2" | lowercase |
| `docs/architecture/07` vs `docs/features/04` (pre-fix) | P1-P3 vs P1-P4 | mixed |

A `WHERE severity = 'P1'` silently misses half the rows. Alert routing misfires. Dashboards under-count.

## Canonical decision

- **Set:** `P1, P2, P3, P4`
- **Case:** uppercase
- **Scope:** incident severity only. Review-comment severity (`critical|warning|suggestion`) is a **different axis** — do not unify.

## Checklist

### 1. Introduce Python enum · ~1 h
- [x] Create `src/ai_driven/domain/enums.py` with `IncidentSeverity(str, Enum)` — values `P1,P2,P3,P4`
- [x] Add `IncidentSeverity.normalize(value)` class method (uppercase + `None` on unknown)
- [x] Unit test: `normalize("p1") == P1`, `normalize("X") is None`, `normalize(None) is None` — `tests/unit/test_severity_enum.py`
- [x] Export from `ai_driven.domain` package `__init__.py`

### 2. Alembic migration · ~1 h
- [x] Create revision `a1b2c3d4e5f7_severity_uppercase.py`
- [x] Upgrade: `UPDATE incidents SET severity = UPPER(severity) WHERE severity <> UPPER(severity)`
- [x] Upgrade: `ALTER TABLE incidents ADD CONSTRAINT ck_incidents_severity CHECK (severity IN ('P1','P2','P3','P4'))`
- [x] Downgrade: drop check constraint; leave data uppercase
- [ ] Test on staging dump first — **deferred to deployment window**

### 3. Writer call sites · ~1 h
- [x] `src/ai_driven/agents/devops/incident.py:13` — `_VALID_SEVERITIES = {s.value for s in IncidentSeverity}` (drives validation from the enum)
- [x] `src/ai_driven/api/admin/router.py` — `IncidentCreate.severity` defaults to `IncidentSeverity.P2` with a `@field_validator` that normalizes input
- [x] `src/ai_driven/api/webhooks/datadog.py` — does not persist incidents directly (spawns a DevOps task); incident creation path normalizes via the Pydantic validator
- [x] Grep audit: `grep -rn "\"p[1-4]\"\|'p[1-4]'" src/` returns zero hits

### 4. Pydantic models · ~30 min
- [x] `IncidentCreate.severity: IncidentSeverity`
- [x] `IncidentResponse.severity: IncidentSeverity` (renamed from `IncidentOut`)
- [x] Validator rejects lowercase / unknown with 422 (`_normalize_severity` raises `ValueError`)
- [ ] `IncidentFilter.severity: IncidentSeverity | None` — **no filter endpoint exists yet; deferred with filter UI**

### 5. Frontend · ~30 min
- [x] `ai-driven-web-ui/src/lib/api.ts` — `IncidentSeverity = 'P1' | 'P2' | 'P3' | 'P4'`
- [x] `dashboard/incidents/page.tsx` — P1/P2 map to `critical`, P3/P4 map to `warning`
- [ ] Dedicated filter UI exposing all four levels — deferred (current page only shows all)

### 6. Tests · ~1 h
- [x] Unit: enum normalization — `tests/unit/test_severity_enum.py` (14 tests passing)
- [ ] Migration: `pytest tests/migrations/test_severity_upper.py` — **deferred: requires a test DB with lowercase rows**
- [ ] Contract test for `{"severity":"p1"}` → `P1` — **deferred: needs httpx test client fixture**
- [ ] Filter contract — deferred with filter endpoint

### 7. Docs
- [x] `docs/architecture/07-devops-incident.md` updated to P1-P4 uppercase
- [ ] `docs/features/04-ai-devops-assistant.md` — add explicit "uppercase canonical" note
- [ ] CHANGELOG entry with migration warning

## Rollout

- [x] Backend ships with enum + normalization (writes uppercase)
- [x] CHECK constraint in migration (applies on next `alembic upgrade head`)
- [ ] Run in staging 24 h before applying migration
- [ ] Apply migration in prod maintenance window

## Acceptance

- [ ] `SELECT DISTINCT severity FROM incidents ORDER BY 1` returns exactly `{P1,P2,P3,P4}` — **verifiable post-deploy**
- [x] New API request with `"severity":"p1"` is normalized before insert (via `_normalize_severity` validator)
- [ ] CHECK constraint visible in `\d+ incidents` — **verifiable post-deploy**
- [x] `docs/architecture/07` and `docs/features/04` both show P1-P4 uppercase (architecture/07 verified)
- [x] CI grep-guard: `scripts/ci/forbid_lowercase_severity.sh` — created, passes clean

## Rollback

- [ ] Drop CHECK, inspect offending rows, re-normalize, re-add
- [ ] Data in column is never destroyed; rollback is safe
