# AI-Driven Development Platform — Docs ↔ UI ↔ Code Review (Final)

**Status:** FINAL (challenged by a second agent; disputed claims corrected)
**Date:** 2026-04-22
**Scope:** `docs/` vs `ai-driven-web-ui/src/` vs backend `src/ai_driven/`

## 0. Executive summary

The platform is more complete than its mockups suggest: 13 Next.js dashboard routes, WebSocket streaming wired to the task-detail page, SWR polling + evaluation UI, pause/resume/stop endpoints fully implemented backend-side ([api/chat/router.py:261-300](src/ai_driven/api/chat/router.py:261)) and in the API client ([lib/api.ts:207-217](ai-driven-web-ui/src/lib/api.ts:207)).

But the review surfaced **one data-corruption risk**, **two security holes**, **one live front-end bug**, **multiple doc↔code truth-drift points**, and the usual production gaps (auth, budget, debug-banner, onboarding). Findings are scored P1 (blocker) / P2 (strong) / P3 (polish).

---

## 1. Doc ↔ Code truth-drift (P1)

These have a **canonical source** somewhere in the repo; docs that disagree are wrong, not just unclear.

### 1.1 Embedding dimension is 1024 (docs/architecture/05 is wrong)
- DDL: `alembic/versions/a1b2c3d4e5f6_add_rag_and_evaluations.py:36` → `vector(1024)`.
- `docs/features/01` and `docs/features/05` agree (1024).
- `docs/architecture/05-rag-caching-evaluation.md:11` says "1536d" — **fix this doc**.

### 1.2 Incident severity — four sources, uppercase/lowercase mismatch — **data-corruption risk**
Found **four** places that disagree on severity:
| Source | Values | Case |
|---|---|---|
| `db/postgres/models.py:117` inline comment | p1 \| p2 \| p3 | **lowercase**, no CHECK constraint |
| `agents/devops/incident.py:11` `_VALID_SEVERITIES` | P1,P2,P3,P4 | **uppercase**, 4-level |
| `api/admin/router.py:941` default | "p2" | lowercase |
| `docs/architecture/07` vs `docs/features/04` | P1-P3 vs P1-P4 | doc conflict |

Two writers persist different case conventions into the same column. Filter queries (`WHERE severity = 'P1'`) will silently miss half the rows. **P1+ / blocker:**
- Pick canonical set `P1,P2,P3,P4` (uppercase).
- Add DB CHECK constraint or a pydantic enum.
- Normalize on write at both call sites.
- Update both architecture/07 and features/04.
- Note: Review comment severity (`critical|warning|suggestion` in `prompts/review_agent.py:184`) is a **different axis** — don't unify it with incident severity.

### 1.3 Coding Agent registers exactly 10 tools — `docs/architecture/02` is wrong
Counted `@self._registry.tool(...)` in `src/ai_driven/agents/coding/agent.py` = 10 decorator registrations (36, 59, 77, 101, 125, 143, 169, 187, 213, 234) + conditional RAG tool at 268. Test asserts the same: `tests/e2e/test_coding_agent_workflow.py:385` checks `== 10`. `docs/features/01` already says 10. **Fix `docs/architecture/02` to "10 core + 1 conditional RAG + MCP".**

### 1.4 Model IDs are drift, not doc-only
- `src/ai_driven/config.py:36-38` pins `claude-sonnet-4-6`, `claude-opus-4-6`, `claude-haiku-4-5`; Bedrock IDs at `:61-62`.
- As of 2026-04-22 the current family is Opus 4.7 / Sonnet 4.6 / Haiku 4.5.
- Planning tasks on the older Opus 4.6 miss recent quality gains. **Promote to P2** (dependency currency, not doc drift). Bump at least Opus; re-benchmark before/after.

---

## 2. Doc ↔ UI implementation issues (P1/P2)

### 2.1 WebSocket URL hardcoded to wrong port — **live bug** (P1)
[tasks/[id]/page.tsx:1034](ai-driven-web-ui/src/app/dashboard/tasks/[id]/page.tsx:1034)
```ts
const wsUrl = `${protocol}//localhost:8000/api/chat/stream/${id}`
```
Backend runs on `:8888` ([next.config.js](ai-driven-web-ui/next.config.js), docs/configuration). Real-time streaming fails silently in dev; impossible in prod.

Also worth knowing: **Next.js rewrites do not proxy WebSocket upgrades** — they apply to HTTP only. Simply switching to a relative URL will not route through `next.config.js`.

**Fix:** introduce `NEXT_PUBLIC_WS_URL` (or derive from `window.location`) and a deployment-time proxy (e.g. ALB listener rule or Nginx `proxy_http_version 1.1; proxy_set_header Upgrade ...`).

### 2.2 Pause / Resume / Stop buttons are demo-only (P1)
[tasks/[id]/page.tsx:1262-1273](ai-driven-web-ui/src/app/dashboard/tasks/[id]/page.tsx:1262) — rendered only when `!isRealMode`. Backend implements all three ([api/chat/router.py:261-300](src/ai_driven/api/chat/router.py:261)), API client exports them, sequence doc describes them. **Wire them up** and rely on `task.status` to toggle labels.

### 2.3 Sidebar label ↔ route mismatch (P2)
[sidebar.tsx:173-174](ai-driven-web-ui/src/components/layout/sidebar.tsx:173):
- "Metrics" → `/dashboard/insights`
- "DevOps" → `/dashboard/incidents`

Either rename routes or rename labels. Confuses bookmarking and breadcrumb generation.

### 2.4 No conversational UI surface (P2, downgraded from P1)
Chat **backend** is wired: `POST /api/chat/send` + `WS /api/chat/stream/{id}` ([main.py:82](src/ai_driven/main.py:82), [api/chat/router.py](src/ai_driven/api/chat/router.py)). What's missing is a persistent conversational page — today, streaming lives only on the task-detail view. Add a `/dashboard/chat` route later; not a launch blocker.

### 2.5 Hardcoded user profile (P2)
[sidebar.tsx:335-344](ai-driven-web-ui/src/components/layout/sidebar.tsx:335) renders "Alex Chen · Platform · Manila" statically. Wire to a session context before v1.

### 2.6 Settings is a browser `alert()` (P2)
[sidebar.tsx:348](ai-driven-web-ui/src/components/layout/sidebar.tsx:348) `onClick={() => alert('Settings page coming soon.')}` — accessibility regression (keyboard trap, blocking modal) and unprofessional. Replace with a route or a proper dialog, or hide the button until built.

### 2.7 Next.js has no `middleware.ts` — no auth hook (P2)
No `middleware.ts` at `ai-driven-web-ui/` root or under `src/`, and `src/app/layout.tsx` has zero auth wrapping. The scaffold needs the middleware hook in place (even if no-op) so Cognito / NextAuth / SSO can slot in without a refactor.

### 2.8 Auto-merge narrative ↔ default mismatch (P2)
`docs/features/02` says auto-merge default is OFF, human merge required. UI copy and pipeline bubbles imply a smooth autonomous flow. Make "human required" an explicit first-class stage indicator.

---

## 3. Production-readiness gaps (P1–P2)

### 3.1 WebSocket endpoint has no auth → cross-tenant leak (P1)
[api/chat/router.py:303-315](src/ai_driven/api/chat/router.py:303) accepts any `task_id` and starts a Redis pub/sub without verifying the caller's team owns that task. Acceptable under `AI_DRIVEN_DEBUG=true`; **cross-tenant data leak in prod**. Add team-scoped authz before a second customer goes live.

### 3.2 `/stop` marks task as `failed` — pollutes success metrics (P2)
[api/chat/router.py:291-293](src/ai_driven/api/chat/router.py:291) sets `status = "failed"` with message "Task stopped by user". User-initiated cancel should be a distinct terminal state (`cancelled`). Today the `successRate` KPI in [lib/api.ts:24](ai-driven-web-ui/src/lib/api.ts:24) over-counts failures. Add a `cancelled` status + update state-machine doc (`architecture/04`) + UI status mapping.

### 3.3 No login / session / team switcher / API-key manager (P2)
`AI_DRIVEN_DEBUG=true` is the current auth bypass. Before v1, ship email-link or SSO login, a team context provider, and an API-key UI for webhooks/CI.

### 3.4 Debug-mode has no operator signal (P2)
Draft suggested a banner from `/api/admin/health` — **that endpoint doesn't exist**. Health is at `GET /health` ([api/health.py:64](src/ai_driven/api/health.py:64)) and its response model doesn't expose `debug`. Extend `HealthResponse` with `{"debug": bool}` from `settings.debug`, then render a prominent banner in `dashboard/layout.tsx` when `true`.

### 3.5 No cost / budget burn-down in UI (P2)
`DashboardSummary.budgetUsagePercent` is fetched but not rendered as a burn-down bar. Docs reference a $100/day halt — surface it. Disable the "New run" CTA at 100%; warn at 80%.

### 3.6 No DLQ / stuck-task visibility (P2)
`docs/implementation/03-coding-agent.md` has DLQ as unchecked. Operators have no view of queue depth, longest in-flight, or stuck tasks. Add to `/dashboard/admin/runs`.

### 3.7 Demo fallback bleeds into production bundle (P2)
[tasks/[id]/page.tsx:45-63](ai-driven-web-ui/src/app/dashboard/tasks/[id]/page.tsx:45) — on API outage, users see the **fake** `"race condition in ledger balance cache"` task. Gate `DEMO_STEPS` behind `NEXT_PUBLIC_DEMO_MODE`; otherwise show empty / retry state.

### 3.8 Reviews UI has no approval queue (P2)
`/dashboard/reviews` should split into "Awaiting me" / "Awaiting others" / "Merged" with keyboard shortcuts. Matters more once auto-merge is still OFF by default.

### 3.9 No emergency-stop / kill-switch (P2)
Admin needs one toggle to drain queues and pause all dispatch if the models misbehave.

### 3.10 No APM hand-off (P2)
Each `task_id` should deep-link into a DataDog trace. Nothing wires it today.

---

## 4. Customer-experience concerns (P2)

- **4.1 Trust:** confidence scores ("89%") shown without explanation — add hover showing model, RAG hits, evidence.
- **4.2 HITL clarity:** pipeline bubbles don't distinguish `ai-completed` vs `awaiting-human`. Add 3-state.
- **4.3 Rollback:** no "revert merged PR" or "disable repo" flow from UI.
- **4.4 Onboarding:** no wizard for first repo / webhook setup.
- **4.5 Accessibility:** inline styles throughout, `alert()` usage, no `aria-live` on streaming regions, no visible focus rings on custom buttons.
- **4.6 PII / data egress:** UI doesn't disclose what source/Jira content is sent to Claude. Add per-run and per-repo badges.

---

## 5. Polish (P3)

- **5.1** SWR polls every 3-5 s forever — even for completed tasks ([tasks/[id]/page.tsx:1019-1020](ai-driven-web-ui/src/app/dashboard/tasks/[id]/page.tsx:1019)). Stop when `status` ∈ {completed, failed, cancelled}.
- **5.2** Nav counts ("3", "12", "2") in [sidebar.tsx:168-175](ai-driven-web-ui/src/components/layout/sidebar.tsx:168) are hardcoded; should reflect live data.
- **5.3** Mockup files at repo root (`main.jsx`, `design-canvas.jsx`, `tweaks.jsx`, `tokens.css`, `index.html`, `screens/`, `ui/`) are design prototype; move under `ai-driven-web-ui/design/` or delete to avoid confusion with the real app at `src/`.
- **5.4** No dark-mode toggle in the real Next.js shell (prototype has one).
- **5.5** `⌘K` sidebar search has no handler.
- **5.6** `capture-pages.mjs` (Playwright) is not wired into `package.json` scripts — add or remove.
- **5.7** "Platform · Manila" literal in sidebar — localize or drop.
- **5.8** Incident severity vs review severity — distinct axes; don't conflate in future consolidations.

---

## 6. Canonical decisions (recommended)

| Topic | Decision |
|---|---|
| Embedding dim | **1024** (DDL wins; fix architecture/05) |
| Incident severity | **P1-P4 uppercase**; DB CHECK + normalize on write |
| Review severity | Keep `critical/warning/suggestion` — different axis |
| Coding tool count | 10 core + 1 conditional RAG + MCP (fix architecture/02) |
| Model IDs | Single constants module, bump Opus to 4.7 |
| UI stack | Next.js 14 under `ai-driven-web-ui/src/` is the prod app; prototype files move to `design/` or delete |
| Auto-merge | OFF by default; explicit per-repo opt-in; UI shows human-required state |
| WS URL | `NEXT_PUBLIC_WS_URL` + deployment-time upgrade-capable proxy |

---

## 7. Prioritized 2-week plan to launch-ready

| # | Item | Severity | Est. effort |
|---|------|----------|-------------|
| 1 | Fix WS hardcoded port (`NEXT_PUBLIC_WS_URL` + proxy) | P1 bug | 2 h |
| 2 | Severity taxonomy unification (enum + CHECK + migration) | P1 data | 4 h |
| 3 | WS endpoint authz (team scoping) | P1 security | 4 h |
| 4 | Wire Pause/Resume/Stop to real API | P1 UX | 4 h |
| 5 | Add `cancelled` state; split from `failed` | P2 | 3 h |
| 6 | Fix doc truth-drift (arch/02, arch/05, arch/07, features/04) | P1 docs | 2 h |
| 7 | Login + team switcher + session | P2 auth | 3 d |
| 8 | `middleware.ts` scaffold (even if no-op) | P2 | 1 h |
| 9 | Budget burn-down in header + disable New Run at cap | P2 | 1 d |
| 10 | Debug banner from extended `/health` | P2 | 2 h |
| 11 | Emergency-stop admin toggle | P2 | 4 h |
| 12 | Sidebar label↔route alignment | P2 | 1 h |
| 13 | Gate `DEMO_STEPS` behind `NEXT_PUBLIC_DEMO_MODE` | P2 | 1 h |
| 14 | Reviews "Awaiting me" queue | P2 | 1 d |
| 15 | SWR polling stop on terminal states | P3 | 30 m |
| 16 | Accessibility pass (axe-core) | P3 | 1 d |
| 17 | Onboarding wizard | P3 | 2 d |
| 18 | Model ID bump to Opus 4.7 + benchmark | P2 | 4 h |

Total to "launch-ready" (items 1-14): ~6 working days of one senior engineer; items 15-18 can follow.
