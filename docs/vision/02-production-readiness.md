# Production Readiness Checklist

Use this before promoting an environment from beta → GA. Every item should be **green** and **dated**; red items must have an ADR waiving them.

Current status snapshot: 2026-04-22. Derived from `docs/review/findings-final.md`.

## 1. Correctness

- [x] Doc truth-drift resolved (`arch/02`, `arch/05`, `arch/07`, `arch/04`).
- [ ] Severity taxonomy migration shipped (`implementation/07`).
- [ ] `cancelled` task state exists and is honoured end-to-end (backend + UI + analytics).
- [ ] Pydantic models + DB CHECK constraints cover every enum/union across `incidents`, `tasks`, `reviews`.
- [ ] Integration tests cover the full Jira → Code → Review → Merge → Notify path on real Anthropic + Bedrock (not just mocks).
- [ ] Evaluation score floor set (e.g. ≥ 7.0 weighted-avg) as a release gate.

## 2. Security

- [ ] Every admin endpoint requires `team_id`-scoped auth (`implementation/08`).
- [ ] WebSocket `/stream/{task_id}` enforces team ownership before the first event is sent.
- [ ] API keys stored hashed; rotation flow tested; 7-day grace period.
- [ ] Secrets only in AWS Secrets Manager; no `.env` in prod.
- [ ] `AI_DRIVEN_DEBUG=true` is flagged visually and alerts operators within 60 s of being set.
- [ ] Cross-tenant probe is a permanent CI test (`teamA` token hitting `teamB` IDs must 404).
- [ ] PII masking verified at every log sink (DataDog, PostgreSQL, Redis events).
- [ ] Dependency audit (`pip-audit` + `npm audit`) clean on main.
- [ ] SBOM generated for every release tag.

## 3. Observability

- [ ] DataDog APM trace per `task_id`; "View trace" link in UI.
- [ ] Redis event log retention = 24 h confirmed.
- [ ] Token + cost metrics dashboards live (per team, per model, per agent type).
- [ ] Alerts: error rate > 1%, latency p95 > 10 s, daily spend > 80% budget, queue depth > 100.
- [ ] Log retention: 30 days hot, 90 days cold.

## 4. Reliability

- [ ] SQS DLQ wired for every worker queue; dashboard view of DLQ depth.
- [ ] Circuit breaker for Claude (Anthropic → Bedrock → mock) tested in staging with chaos-kill.
- [ ] Redis outage drill: system degrades gracefully (tasks persist, UI shows "events paused").
- [ ] Postgres read-replica and failover runbook.
- [ ] Per-task timeout (hard cap: 20 min) enforced; task transitions to `failed` with reason.
- [ ] Blue/green deploy path validated; one-click rollback.

## 5. Performance

- [ ] First WebSocket event < 500 ms after `POST /api/chat/send`.
- [ ] 95th-percentile task end-to-end < 15 min for small tickets.
- [ ] Dashboard initial load < 2 s on 4G.
- [ ] RAG query (hybrid) < 300 ms p95 on a 100k-file repo.

## 6. Cost

- [ ] Daily budget hard-stop enforced at dispatch time (not after).
- [ ] Prompt caching enabled for every agent; cache-hit rate ≥ 60% on 7-day trailing.
- [ ] Model routing honored: Haiku for classification/eval, Sonnet default, Opus for planning & large diffs.
- [ ] Per-customer cost < $2.50 per merged PR.

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
- [ ] Login, session, team switcher shipped.
- [ ] Debug-mode banner.
- [ ] Budget burn-down in header.
- [ ] Reviews "Awaiting me" queue with keyboard shortcuts.
- [ ] Onboarding wizard.
- [ ] Empty/error/loading states on every page.
- [ ] WCAG 2.2 AA — axe-core clean; manual keyboard-only walk-through.
- [ ] Dark-mode parity with the design prototype.

## 9. Documentation

- [x] Architecture docs match code (tool counts, embedding dims, severity).
- [ ] Public API reference (OpenAPI) published.
- [ ] Customer-facing runbooks: "Add a repo", "Rotate API key", "Trigger a run from Slack", "What to do when a run gets stuck".
- [ ] Changelog discipline — every release tagged with a dated entry.

## 10. Data

- [ ] Backup/restore drill: daily snapshot, restore tested monthly.
- [ ] `code_embeddings` re-index job for when `voyage-code-3` model is upgraded.
- [ ] Per-team data export (`GET /api/admin/export`) for portability.
- [ ] Deletion flow: `DELETE /teams/{id}` purges within 30 days across every table and every cache.

---

## Launch gate

- **All P1 items green** for a minimum of 7 consecutive days on staging.
- **P2 items**: at least 80% green; remainder have ADRs with target dates.
- **P3 items**: tracked, no gating.
- Signed off by: Engineering, Security, Product. Archived in `docs/releases/{version}-sign-off.md`.
