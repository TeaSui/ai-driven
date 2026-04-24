# Implementation Plan 09 — Feature Completeness Audit (Master Checklist)

**Purpose:** one checklist to ship every feature the platform claims. Derived from a pass over `src/ai_driven/` and `ai-driven-web-ui/src/` on 2026-04-22, then validated end-to-end with Playwright against live backend + frontend on 2026-04-23.

## Playwright runtime validation (2026-04-23)

Booted backend (`uvicorn :8888`, debug mode) + frontend (Next dev `:3000`) with real Postgres/Redis/Anthropic-stub, walked every nav item, exercised interactive elements, and ran the New-run → Task-detail → Stop workflow:

| Check | Result |
|---|---|
| All 12 dashboard routes return 200 | ✅ |
| Zero console errors across all pages | ✅ (was 1 — fixed heatmap hydration mismatch) |
| Zero failed network requests | ✅ |
| Sidebar nav works (Timeline, Tasks, Reviews, Incidents, Metrics, Slack) | ✅ |
| Timeline filter tabs (All/Active/Merged/Blocked) | ✅ |
| Incidents toast notifications (replaces former `alert()`) | ✅ |
| Insights range switcher (7d/30d/90d) | ✅ |
| Add-repository toast | ✅ |
| Review-rules Save toast | ✅ |
| Esc closes New-run modal | ✅ |
| New-run modal → `POST /api/chat/send` → task created in DB | ✅ |
| Redirect to `/dashboard/tasks/{id}` | ✅ |
| Pause + Stop buttons visible on real task (no longer demo-only) | ✅ |
| WebSocket connects + no 5s dead period | ✅ (was 5s — fixed by sending empty-token auth first-message) |
| Stop → backend sets `status='cancelled'` | ✅ confirmed in SQL: `UPDATE tasks SET status='cancelled', error_message='Task stopped by user'` |
| New run appears in Live feed | ✅ |
| successRate KPI unchanged when task cancelled | ✅ (`total_terminal = completed + failed`; cancelled excluded) |

Two runtime bugs were discovered and fixed in this pass:
- [x] Hydration mismatch on `/dashboard/insights` Heatmap — float alpha serialized differently server vs client. Fixed by `.toFixed(3)` on the alpha channel.
- [x] WebSocket 5-second dead period on every task-detail page — UI did not send the first-message auth frame. Fixed: always send `{type:"auth",token:""}` on open (backend in debug accepts empty; in prod will close `4401` and UI falls back to SWR polling).

One infrastructure gap fixed:
- [x] No `pyproject.toml` — `pip install -e .` from Makefile would fail. Created manifest with pinned deps.
- [x] Makefile ran uvicorn on `:8000` but `next.config.js` proxies `:8888` — aligned to `:8888` + set `AI_DRIVEN_DEBUG=true` in the target.

Screenshots archived at `/tmp/ui-walk/*.png` (12 pages + 4 workflow states + 2 deep-interaction).


**How to use:**
- Each row has a file reference so you can jump to the code.
- Check items as you go — never delete; this is the audit trail.
- "Status legend": `[x]` done (verified in repo), `[~]` partially done, `[ ]` not started.
- This doc supersedes stale TODO markers scattered in sprints 03 / 04 / 06.

---

## 1. Core agents

### 1.1 Coding Agent — `src/ai_driven/agents/coding/`
- [x] 10 core tools registered (`agent.py:36-234`)
- [x] Conditional RAG tool when repo > 500 files (`agent.py:268`)
- [x] Planner (`planner.py`)
- [x] Sandbox runner (`sandbox.py`)
- [x] PR creation via SCM abstraction
- [ ] Token counting annotated on each tool result (sprint-03 TODO)
- [ ] Retry policy on tool failures (currently fails fast)
- [ ] Write-scope allowlist honoured per repo config (doc vs code confirm)

### 1.2 Review Agent — `src/ai_driven/agents/reviewer/`
- [x] 8 tools
- [x] Per-team `ReviewConfig` (`config.py`)
- [x] Model selection by diff size
- [ ] Batch comments via GitHub review API (sprint-04 TODO — currently posts individually)
- [ ] Cost/model tracking on `Review` record (sprint-04 TODO)
- [ ] `reviews` table write on completion (sprint-04 TODO, deferred to sprint-05)

### 1.3 DevOps Agent — `src/ai_driven/agents/devops/`
- [x] 7 tools, incident schema, Claude-driven triage
- [~] Severity taxonomy — see `implementation/07`
- [ ] Auto-posting to Slack `#incidents` (wired but not verified e2e)
- [ ] Runbook linking in incident tickets

### 1.4 Workflow Agent — `src/ai_driven/agents/workflow/`
- [x] 5 tools, state machine, triggers
- [ ] Durable workflow engine (current: in-process) — tracked as `architecture-vision §target topology`
- [ ] Human-approval gate as a first-class state
- [ ] Retry / resume on partial failure

---

## 2. Backend API — `src/ai_driven/api/`

### 2.1 Chat / task control — `api/chat/router.py`
- [x] `POST /api/chat/send` (team-scoped, rate-limited, budget-checked)
- [x] `POST /tasks/{id}/pause|resume|stop`
- [x] `WS /api/chat/stream/{id}` with first-message auth + team ownership
- [ ] `/stop` sets `status='cancelled'` (currently `failed` — see `impl/08 Phase 1`)
- [ ] Reconnection protocol if WS drops mid-stream (client resumes from last event_id)

### 2.2 Admin — `api/admin/router.py`
- [x] Teams CRUD + key rotation
- [x] Tasks list / detail / events / evaluations
- [x] Dashboard summary + usage history + usage by agent
- [x] Pipeline runs
- [x] Incidents list + create
- [x] Reviews list
- [x] Repos CRUD
- [x] Data export / purge (`/teams/{id}/export`, `DELETE /teams/{id}/data`)
- [x] `/me` endpoint
- [ ] `successRate` excludes `cancelled` tasks (depends on §2.1 stop fix)
- [ ] `/insights` endpoint matching UI `getInsights(range)` — verify exists or add
- [ ] Repo scan / RAG re-index trigger endpoint (admin utility)

### 2.3 Webhooks — `api/webhooks/`
- [x] GitHub, Bitbucket, Jira, DataDog handlers
- [ ] Bitbucket IP allowlist or shared-secret verification (sprint-02 TODO)
- [ ] Jira webhook-secret verification (sprint-02 TODO)
- [ ] DataDog shared-secret header verification (sprint-02 TODO)
- [ ] Webhook replay endpoint for ops (idempotent)

### 2.4 Health & meta
- [x] `GET /health`
- [ ] Extend `HealthResponse` with `debug: bool`
- [ ] `GET /ready` (k8s-style readiness distinct from liveness)
- [ ] `GET /metrics` (Prometheus-style; today only DataDog)

---

## 3. Auth & multi-tenancy — `src/ai_driven/auth/`

- [x] API-key middleware + Redis-cached team lookup (`middleware.py`)
- [x] Rate limiter (per-team, concurrent + RPM)
- [x] `require_task_access`, `require_role` deps (`dependencies.py`)
- [x] Cross-tenant integration test (`tests/security/test_cross_tenant.py`)
- [ ] **Cognito / JWT login flow** (see `impl/08`)
- [ ] **Next.js `middleware.ts`** route guard (see `impl/08`)
- [ ] **API-key management UI** (see `impl/08`)
- [ ] Team switcher
- [ ] SCIM provisioning (backlog, year 2)

---

## 4. Billing & cost — `src/ai_driven/billing/tracker.py`

- [x] Per-model pricing table
- [x] Daily budget enforcement (blocks dispatch over cap)
- [x] Cost attribution per team per day (Redis INCR + PG sink)
- [ ] Model pricing for Opus 4.7 / Sonnet 4.6.x (see §11 dependency currency)
- [ ] Near-cap warning (at 80% daily budget) — DataDog monitor + UI banner
- [ ] Monthly rollup view
- [ ] Stripe integration (year-2 pricing work)

---

## 5. Integrations

### 5.1 SCM — `src/ai_driven/scm/`
- [x] GitHub client
- [x] Bitbucket client
- [x] Unified abstraction
- [ ] Credential cache per team (sprint-02 TODO)
- [ ] GitLab client (backlog)
- [ ] PAT rotation automation (sprint-06 deferred)

### 5.2 Jira — `src/ai_driven/jira/`
- [x] Client, webhook handler, transitions
- [ ] Credential lookup from Secrets Manager (sprint-02 TODO)
- [ ] Bulk-update tool (ticket triage use-case)

### 5.3 Slack — via MCP
- [x] Posting via MCP Slack server
- [ ] Slash-command handler `/ai-code`, `/ai-triage`
- [ ] Thread-reply support (currently top-level only)

### 5.4 DataDog — `src/ai_driven/integrations/datadog*`
- [x] Query logs, metrics, service health
- [x] APM tracing binding (`observability/trace_context.py`)
- [ ] Deep-link from task → trace in UI
- [ ] Dashboard JSON committed to repo + applied via CI

### 5.5 MCP — `src/ai_driven/mcp/`
- [x] GitHub MCP stdio transport
- [x] Context7 MCP
- [x] Jira MCP
- [ ] DataDog MCP registration (mentioned in arch/02; verify)
- [ ] Slack MCP stream health

### 5.6 Voyage AI
- [x] Embeddings for RAG (`voyage-code-3`, 1024-dim)
- [ ] Model-upgrade job: reindex all `code_embeddings` on model bump
- [ ] Cost cap for indexing (per-team token ceiling)

---

## 6. Data & storage

### 6.1 PostgreSQL + pgvector
- [x] 8 tables (teams, repositories, tasks, reviews, incidents, conversation_records, code_embeddings, task_evaluations)
- [x] `code_embeddings.embedding vector(1024)`
- [ ] `users` table (for Cognito mapping — see `impl/08 Phase 2`)
- [ ] `team_api_keys` table if not already present (rotation grace)
- [ ] `tasks.status` enum includes `cancelled`
- [ ] `incidents.severity` CHECK constraint (see `impl/07`)
- [ ] Backup / restore runbook verified monthly
- [ ] Read-replica failover drill

### 6.2 Redis
- [x] Event pub/sub (`task:{id}:events`)
- [x] 24 h event log TTL
- [x] Cost ledger (`cost:{team}:{date}`)
- [x] Team-lookup cache
- [ ] Cluster mode for prod (today single node)
- [ ] Recovery-replay of missed events into PG

### 6.3 DynamoDB — `TaskStateMachineStore`
- [x] Workflow poll state
- [ ] TTL on stale state rows (auto-reap after 24 h)

### 6.4 SQS
- [x] Task / Review / DevOps queues
- [ ] **DLQ processing** — log + alert on DLQ message (sprint-03 TODO)
- [ ] DataDog queue-depth metric (sprint-03 TODO)
- [ ] Visibility-timeout tuning doc

---

## 7. RAG — `src/ai_driven/rag/`

- [x] AST-aware chunkers for Python, TS/JS, Java, Go, Kotlin
- [x] Hybrid retrieval with RRF (60% vector + 40% BM25)
- [x] Auto-enable at repo > 500 files
- [x] Per-repo index, keyed by `last_commit_sha`
- [ ] Reindex on commit SHA change (webhook-driven)
- [ ] Cross-repo search (year 2 — per-team namespace)
- [ ] Cost visibility: "RAG-attributed tokens" in cost ledger

---

## 8. Evaluation — `src/ai_driven/evaluation/`

- [x] Haiku-based grader
- [x] Coding / Review / DevOps dimension rubrics
- [x] `task_evaluations` persistence
- [x] UI displays scores per dimension
- [ ] Separate eval worker (see `architecture-vision §Target topology`)
- [ ] Backfill job for pre-eval tasks
- [ ] 7-day rolling weighted-avg on overview hero card
- [ ] Release gate (block deploy if trailing 7-day eval avg < 7.0)

---

## 9. Frontend — `ai-driven-web-ui/src/`

### 9.1 Routes present
- [x] `/dashboard` — overview
- [x] `/dashboard/timeline`
- [x] `/dashboard/tasks` + `/dashboard/tasks/[id]`
- [x] `/dashboard/reviews`
- [x] `/dashboard/incidents`
- [x] `/dashboard/insights`
- [x] `/dashboard/slack`
- [x] `/dashboard/onboarding`
- [x] `/dashboard/admin/repos`
- [x] `/dashboard/admin/rules`
- [x] `/dashboard/admin/runs`

### 9.2 Routes missing (see `impl/08`)
- [ ] `/login`
- [ ] `/dashboard/admin/api-keys`
- [ ] `/dashboard/admin/settings` (replaces `alert()` on settings button)
- [ ] `/dashboard/chat` (conversational surface — demoted P2 in findings-final.md)

### 9.3 UI wiring gaps (from source grep)
- [x] WS URL no longer hardcoded to `localhost:8000` (fixed in this review)
- [x] Pause/Resume/Stop wired to real API (fixed)
- [x] `DEMO_STEPS` gated behind `NEXT_PUBLIC_DEMO_MODE` (fixed)
- [x] SWR polling stops on terminal statuses (fixed)
- [ ] WS client sends `{"type":"auth","token":…}` first message (required for prod — `impl/08 Phase 4`)
- [ ] `dashboard/incidents/page.tsx:416,428,432,564` — four `window.alert()` placeholders (replace with real handlers calling backend)
- [ ] `dashboard/admin/repos/page.tsx:80` — "Add repository form coming soon" alert → form
- [ ] `dashboard/admin/rules/page.tsx:215` — "Settings saved" alert → real POST
- [ ] `components/layout/sidebar.tsx:348` — Settings button alert → router push
- [ ] Sidebar nav counts (3/12/2) are hardcoded → bind to live admin summary
- [ ] Hardcoded user "Alex Chen · Platform · Manila" (see `impl/08 Phase 3`)
- [ ] Sidebar label vs route: "Metrics"→`/insights`, "DevOps"→`/incidents` — rename one side

### 9.4 UX features still to build
- [ ] Budget burn-down bar in global header
- [ ] Disable "New run" at 100% daily cap
- [ ] Emergency-stop admin toggle
- [ ] "Awaiting me" review queue with j/k/a/r shortcuts
- [ ] Human-approval stage indicator in pipeline bubbles
- [ ] Empty / error / loading states on every page
- [ ] Dark-mode toggle in Next.js shell (prototype has one; real app doesn't)
- [ ] Cmd-K search handler (placeholder UI exists)
- [ ] Onboarding wizard content (route exists; confirm steps wired to backend)
- [ ] Per-task DataDog trace deep-link
- [ ] Confidence-score tooltip (hover shows model, RAG hits, evidence)

### 9.5 Accessibility
- [ ] axe-core in CI
- [ ] Kill every remaining `alert()` (6 confirmed via grep)
- [ ] `aria-live="polite"` on streaming text panel
- [ ] Visible focus rings on `Btn` / `Pill` components
- [ ] Keyboard-only walkthrough of primary flows
- [ ] Colour-contrast audit against WCAG 2.2 AA

---

## 10. Observability — `src/ai_driven/observability/`

- [x] DataDog trace binding (`trace_context.py`)
- [x] Per-team / per-agent metrics
- [ ] Saved DataDog dashboards JSON in `docs/datadog/`
- [ ] Alerts: error rate, p95 latency, daily spend, queue depth
- [ ] Deep-link task_id → trace in UI
- [ ] Log retention policy documented (30 d hot / 90 d cold)

---

## 11. Dependency currency

- [ ] Bump `claude_planning_model` in `config.py:38` from `claude-opus-4-6` → `claude-opus-4-7`
- [ ] Bump Bedrock model IDs at `config.py:62`
- [ ] Add Opus 4.7 + Sonnet 4.6.x pricing to `billing/tracker.py:14-22`
- [ ] Canary 10% of planning tasks on 4.7; rollback if eval weighted-avg drops > 0.5
- [ ] Update pricing table in `docs/features/01-ai-coding-agent.md`
- [ ] Update `docs/configuration.md` env-var defaults

---

## 12. Reliability & DR

- [ ] Circuit breaker trip test (kill Anthropic in staging; confirm Bedrock takeover)
- [ ] Redis outage drill
- [ ] Postgres failover drill
- [ ] Task hard-timeout (20 min) cleanly transitions to `failed` with reason
- [ ] Daily DB snapshot + monthly restore drill
- [ ] CDK stacks: blue/green deploy proven end-to-end

---

## 13. Security posture

- [x] API keys stored hashed
- [x] Cross-tenant test
- [ ] Dependabot / `pip-audit` / `npm audit` clean on main
- [ ] SBOM generated per release
- [ ] PII masking verified at every log sink (spot-audit)
- [ ] Secret scanner in CI (gitleaks)
- [ ] Threat model doc in `docs/security/`
- [ ] SOC2 Type I gap analysis

---

## 14. Documentation

- [x] `docs/architecture/` snapshot (post-fix consistent)
- [x] `docs/features/` spec
- [x] `docs/implementation/` sprint log (through sprint 6 + plans 07/08/09)
- [x] `docs/vision/` (new)
- [x] `docs/review/findings-final.md` (new)
- [ ] OpenAPI spec published and linked from README
- [ ] Customer runbooks (`docs/runbooks/`): "Add repo", "Rotate key", "Stuck task"
- [ ] Changelog with every tagged release
- [ ] ADRs directory for design decisions

---

## How to drive this to zero

1. **Every PR references checkbox IDs** it ticks (e.g. `fixes impl/09 §9.3 sidebar alerts`).
2. **Weekly sweep** — PM + tech-lead tick boxes, reconcile with roadmap status.
3. **Launch gate** — all `[x]` in sections 1-10 before GA; sections 11-14 can trail.
4. **New features** get appended as new subsections; never squash the audit trail.

---

## Cross-reference summary

| Finding doc | Section |
|---|---|
| `docs/review/findings-final.md` | prioritized severity review |
| `docs/implementation/07-severity-taxonomy-migration.md` | §1.3, §2.2, §6.1 |
| `docs/implementation/08-auth-and-ws-security.md` | §3, §9.2, §9.3 |
| `docs/vision/01-roadmap.md` | Now/Next/Later dates |
| `docs/vision/02-production-readiness.md` | launch gate |
| `docs/vision/03-architecture-vision.md` | §1.4, §8 future-state |
