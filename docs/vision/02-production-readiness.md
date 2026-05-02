# Production Readiness Checklist

Use this before promoting an environment from beta → GA. Every item should be **green** and **dated**; red items must have an ADR waiving them.

Current status snapshot: 2026-05-01 (post Phase 2 close). Derived from `docs/review/findings-final.md` + `docs/retrospectives/2026-05-01-phase-2.md`.

## 1. Correctness

- [x] Doc truth-drift resolved. `arch/01`, `arch/02`, `arch/04`, `arch/06` refreshed 2026-05-01 (`20d0e1b`, `fb73880`, `cb8ad15`, `ff697e9`).
- [x] Severity taxonomy migration shipped. Migration `a1b2c3d4e5f7`, enum `IncidentSeverity`, CHECK constraint `ck_incidents_severity` re-verified 2026-05-01.
- [x] `cancelled` task state exists and is honoured end-to-end (backend + UI + analytics). Phase 2 also added `blocked`, `awaiting_qa`, `qa_failed` to `ck_tasks_status`.
- [x] Pydantic models + DB CHECK constraints cover every enum/union across `tasks` (Phase 2 statuses), `task_artifacts.verdict`, `task_override_audit.action`, `incidents.severity` (P1-P4 enforced); `reviews` still has free-form status strings.
- [ ] Integration tests cover the full Jira → Code → Review → Merge → Notify path on real Anthropic + Bedrock (not just mocks). Phase 2 ships `scripts/phase2_dogfood_harness.py` as the closest equivalent.
- [ ] Evaluation score floor set (e.g. ≥ 7.0 weighted-avg) as a release gate.

## 2. Security

- [x] Every admin endpoint requires `team_id`-scoped auth (`implementation/08`). Tier-1 Item A landed 2026-05-01 (commit `fef6ea7`): all 32 `/api/admin/*` routes now gate on `require_admin`, and several pre-existing cross-tenant leaks were closed in the same commit (dashboard/pipeline/insights aggregates, `POST /incidents` team commingling, `DELETE /teams/{id}` without scope guard, etc.). Mutating routes with another team's resource ID return 404 per the existence-leak convention.
- [x] WebSocket `/stream/{task_id}` enforces team ownership before the first event is sent. `api/chat/router.py:628` calls `_auth_from_ws(websocket, task_id)` which resolves the task row and rejects on team mismatch before any event is emitted.
- [x] API keys stored hashed (SHA-256); rotation flow tested (`POST /admin/teams/{id}/rotate-key`); **grace period is 48h in code, not the 7-day target** — decide whether to bump or update the goal.
- [ ] Secrets only in AWS Secrets Manager; no `.env` in prod.
- [ ] `AI_DRIVEN_DEBUG=true` is flagged visually and alerts operators within 60 s of being set.
- [x] Cross-tenant probe is a permanent CI test (`teamA` token hitting `teamB` IDs must 404). Landed 2026-05-01 (commit `9ce4ef0`) as `tests/integration/test_cross_tenant_isolation.py`: parametrized over 6 mutating endpoints + 3 representative reads + 3 list endpoints, asserting 404 on cross-team known-ID and empty results on cross-tenant list aggregates.
- [ ] PII masking verified at every log sink (DataDog, PostgreSQL, Redis events).
- [ ] Dependency audit (`pip-audit` + `npm audit`) clean on main.
- [ ] SBOM generated for every release tag.

## 3. Observability

- [ ] DataDog APM trace per `task_id`; "View trace" link in UI.
- [ ] Redis event log retention = 24 h confirmed.
- [x] Token + cost metrics dashboards live (per team, per model, per agent type). Phase 2 added per-artifact `cost_usd` attribution (T-A2) and `orchestrator.*`, `qa.*`, `admin.override.*`, `reaper.*` counters.
- [ ] Alerts: error rate > 1%, latency p95 > 10 s, daily spend > 80% budget, queue depth > 100. Phase 2 alert specs in `docs/runbooks/06-orchestrator.md`; wiring to on-call is manual-ops work.
- [ ] Log retention: 30 days hot, 90 days cold.

## 4. Reliability

- [x] SQS DLQ wired for every worker queue; dashboard view of DLQ depth. All 5 queues (task, review, devops, orchestrator, qa) have DLQs with `maxReceiveCount=3`. CloudWatch alarms on DLQ depth still manual ops.
- [ ] Circuit breaker for Claude (Anthropic → Bedrock → mock) tested in staging with chaos-kill.
- [ ] Redis outage drill: system degrades gracefully (tasks persist, UI shows "events paused").
- [ ] Postgres read-replica and failover runbook.
- [x] Per-task timeout (hard cap: 20 min) enforced; task transitions to `failed` with reason. Phase 2 extends this with stuck-task reapers (30 min threshold) for `orchestrating` / `awaiting_qa` / `running` via `background/reapers.py`.
- [ ] Blue/green deploy path validated; one-click rollback.

## 5. Performance

- [ ] First WebSocket event < 500 ms after `POST /api/chat/send`.
- [ ] 95th-percentile task end-to-end < 15 min for small tickets.
- [ ] Dashboard initial load < 2 s on 4G.
- [ ] RAG query (hybrid) < 300 ms p95 on a 100k-file repo.

## 6. Cost

- [x] Daily budget hard-stop enforced at dispatch time (not after). `chat/router.py` checks budget pre-dispatch and returns 429 with `BUDGET_EXCEEDED`.
- [ ] Prompt caching enabled for every agent; cache-hit rate ≥ 60% on 7-day trailing.
- [ ] Model routing honored: Haiku for classification/eval, Sonnet default, Opus for planning & large diffs.
- [ ] Per-customer cost < $2.50 per merged PR. Awaiting T27 dogfood results for a real measurement.

## 7. Compliance & legal

- [ ] DPA template reviewed by legal; signed by every paying customer.
- [ ] Data-residency posture documented (today: `ap-southeast-1`; `eu-west-1` in year-1 late).
- [ ] Privacy policy lists third parties: Anthropic, Voyage AI, AWS, DataDog, Jira/GitHub/Slack.
- [ ] Incident response runbook; named on-call; < 1 h acknowledgement.
- [ ] SOC2 Type I gap analysis (Type I target: Q3).

## 8. User experience

- [x] Pause/Resume/Stop wired on real tasks.
- [x] WebSocket URL no longer hardcoded.
- [x] Demo fallback gated behind env var.
- [x] Login, session shipped (`POST /api/auth/login` → opaque session token in Redis); team switcher not yet shipped.
- [ ] Debug-mode banner.
- [ ] Budget burn-down in header.
- [ ] Reviews "Awaiting me" queue with keyboard shortcuts.
- [ ] Onboarding wizard.
- [ ] Empty/error/loading states on every page.
- [ ] WCAG 2.2 AA — axe-core clean; manual keyboard-only walk-through.
- [ ] Dark-mode parity with the design prototype.
- [x] Specialists tab on task detail page with per-role artifact cards + QA failure breakdown + admin override button (T25).

## 9. Documentation

- [ ] Architecture docs match code. Post-Phase-2 drift flagged — `arch/01` (table count), `arch/02` (agent count, queue count), `arch/04` (state machine), `arch/06` (workflow path) need updates.
- [ ] Public API reference (OpenAPI) published. FastAPI auto-serves at `/openapi.json`; not published externally.
- [x] Operator runbook for orchestrator: `docs/runbooks/06-orchestrator.md` (state machine, env flags, common ops tasks, counters, alert response).
- [ ] Customer-facing runbooks: "Add a repo", "Rotate API key", "Trigger a run from Slack", "What to do when a run gets stuck". Key-rotation procedure is in the Phase 2 runbook; the others still pending.
- [x] Changelog discipline — `CHANGELOG.md` present with Phase 2 section.

## 10. Data

- [ ] Backup/restore drill: daily snapshot, restore tested monthly.
- [ ] `code_embeddings` re-index job for when `voyage-code-3` model is upgraded.
- [x] Per-team data export (`GET /api/admin/teams/{team_id}/export`) for portability.
- [ ] Deletion flow: `DELETE /teams/{id}` purges within 30 days across every table and every cache. Soft-delete exists (`is_active=false`); hard-delete cascade not implemented.

---

## Launch gate

- **All P1 items green** for a minimum of 7 consecutive days on staging.
- **P2 items**: at least 80% green; remainder have ADRs with target dates.
- **P3 items**: tracked, no gating.
- Signed off by: Engineering, Security, Product. Archived in `docs/releases/{version}-sign-off.md`.
