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
- [ ] Create `src/ai_driven/domain/enums.py` with `IncidentSeverity(str, Enum)` — values `P1,P2,P3,P4`
- [ ] Add `IncidentSeverity.normalize(value)` class method (uppercase + `None` on unknown)
- [ ] Unit test: `normalize("p1") == P1`, `normalize("X") is None`, `normalize(None) is None`
- [ ] Export from `ai_driven.domain` package `__init__.py`

### 2. Alembic migration · ~1 h
- [ ] Create revision `a1b2c3d4e5f7_severity_uppercase.py`
- [ ] Upgrade: `UPDATE incidents SET severity = UPPER(severity) WHERE severity ~ '^[a-z][0-9]$'`
- [ ] Upgrade: `ALTER TABLE incidents ADD CONSTRAINT ck_incidents_severity CHECK (severity IN ('P1','P2','P3','P4'))`
- [ ] Downgrade: drop check constraint; leave data uppercase
- [ ] Test on staging dump first

### 3. Writer call sites · ~1 h
- [ ] `src/ai_driven/agents/devops/incident.py:11` — already uppercase; replace literal with `IncidentSeverity.P2.value`
- [ ] `src/ai_driven/api/admin/router.py:941` — default `"p2"` → `IncidentSeverity.P2.value`
- [ ] `src/ai_driven/api/webhooks/datadog.py` — normalize on ingest via `IncidentSeverity.normalize()`; 400 on `None`
- [ ] Grep audit: `grep -rn "p[1-4]\"\|'p[1-4]'" src/` returns zero hits after changes

### 4. Pydantic models · ~30 min
- [ ] `IncidentCreate.severity: IncidentSeverity` (replaces `str`)
- [ ] `IncidentOut.severity: IncidentSeverity`
- [ ] `IncidentFilter.severity: IncidentSeverity | None`
- [ ] Validator rejects lowercase / unknown with 422

### 5. Frontend · ~30 min
- [ ] `ai-driven-web-ui/src/lib/api.ts` — `Incident.severity: 'P1'|'P2'|'P3'|'P4'`
- [ ] Colour / tone mapping in `dashboard/incidents/page.tsx` handles P4
- [ ] Filter UI (if any) exposes all four levels

### 6. Tests · ~1 h
- [ ] Unit: enum normalization
- [ ] Migration: `pytest tests/migrations/test_severity_upper.py` — creates lowercase rows, runs upgrade, asserts uppercase + CHECK
- [ ] Contract: POST incident with `{"severity":"p1"}` → normalized to `P1`
- [ ] Filter: `GET /incidents?severity=P1` returns all previously-lowercase rows

### 7. Docs
- [x] `docs/architecture/07-devops-incident.md` updated to P1-P4 uppercase (done in this review)
- [ ] `docs/features/04-ai-devops-assistant.md` — add explicit "uppercase canonical" note
- [ ] CHANGELOG entry with migration warning

## Rollout

- [ ] Deploy backend with enum + normalization (writes uppercase), **without** CHECK constraint
- [ ] Run in staging 24 h, confirm no lowercase writers remain
- [ ] Apply migration in prod maintenance window
- [ ] Ship frontend typing last

## Acceptance (all must be checked before close)

- [ ] `SELECT DISTINCT severity FROM incidents ORDER BY 1` returns exactly `{P1,P2,P3,P4}`
- [ ] New API request with `"severity":"p1"` is normalized before insert
- [ ] CHECK constraint visible in `\d+ incidents`
- [ ] `docs/architecture/07` and `docs/features/04` both show P1-P4 uppercase
- [ ] CI grep-guard: `scripts/ci/forbid_lowercase_severity.sh` (rejects a PR that re-introduces `'p[1-4]'` literal)

## Rollback

- [ ] Drop CHECK, inspect offending rows, re-normalize, re-add
- [ ] Data in column is never destroyed; rollback is safe
