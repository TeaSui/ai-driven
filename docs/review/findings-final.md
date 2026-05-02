# AI-Driven Development Platform — Findings

**Last verified:** 2026-05-01 (post Phase 2). Original audit was 2026-04-22; this is a re-verification against the current codebase.

Closed items are struck through + annotated. Open items are kept in their original priority bucket. Two-week plan at the bottom is rewritten for the actual remaining work.

---

## 1. Doc ↔ Code truth-drift (P1)

### ~~1.1 Embedding dimension (docs/architecture/05)~~ — ✅ FIXED
`docs/architecture/05-rag-caching-evaluation.md:11` now says "1024-dim" matching DDL. Audit: `grep 1024 docs/architecture/05-rag-caching-evaluation.md` resolves.

### ~~1.2 Incident severity normalization~~ — ✅ FIXED
Migration `alembic/versions/a1b2c3d4e5f7_severity_uppercase.py` added `ck_incidents_severity` CHECK constraint. `src/ai_driven/agents/devops/incident.py:11` now references an `IncidentSeverity` enum. Both call sites normalized.

### ~~1.3 Coding Agent tool count in `docs/architecture/02`~~ — ✅ FIXED
Refreshed 2026-05-01 in `fb73880`. Current doc shows live tool counts (Coding 13, Review 12, DevOps 7, Workflow 5, Orchestrator 0, QA 3, StackAnalyst 1, rest 0) across the Core / Orchestration / Specialists split.

### ~~1.4 Model IDs~~ — ✅ FIXED (partial per-tier routing)
`claude_planning_model` now points at `claude-opus-4-7` (2026-05-01). Anthropic Direct now has tier parity with Bedrock (`bedrock_planning_model_id` was already on Opus 4.7). Default + fast stay on Sonnet 4.6 — the orchestrator classifier gates planning-vs-default, so Opus's cost only bites on planning-heavy runs. Full benchmark comparison (Opus 4.7 vs Opus 4.6 across the eval suite) is Phase 3 polish; not a GA blocker. Pricing tables in `billing/tracker.py:23,31` and `observability/metrics.py:162` already cover Opus 4.7.

---

## 2. Doc ↔ UI implementation issues

### ~~2.1 WebSocket hardcoded port~~ — ✅ FIXED
`ai-driven-web-ui/.env.local:4` defines `NEXT_PUBLIC_WS_URL=ws://localhost:8888`; `tasks/[id]/page.tsx:1558-1562` reads it with a same-origin fallback. The rewrites-don't-proxy-WS note remains operationally true (document in deploy runbook when we get one).

### ~~2.2 Pause/Resume/Stop wired~~ — ✅ FIXED
`tasks/[id]/page.tsx` imports `pauseTask`, `resumeTask`, `stopTask` and wires them at lines 1842-1889. Note: they still render behind `!isRealMode` and `DEMO_MODE` gates in some branches — verify once you exercise a real task that all three buttons surface.

### 2.3 Sidebar label ↔ route mismatch — 🔲 STILL OPEN (P2)
`sidebar.tsx:195-196` still has "DevOps" → `/dashboard/incidents` and "Metrics" → `/dashboard/insights`. Cosmetic but real — a bookmark or breadcrumb follows the route name and gets the wrong label.

### 2.4 No conversational UI surface — 🔲 STILL OPEN (P2)
No `/dashboard/chat` route. Same recommendation as before; not a launch blocker.

### ~~2.5 Hardcoded user profile~~ — ✅ FIXED (appears removed)
The "Alex Chen · Platform · Manila" string no longer appears in `sidebar.tsx`.

### ~~2.6 Settings alert()~~ — ✅ FIXED (appears removed)
No `alert()` calls in `sidebar.tsx`.

### 2.7 Next.js `middleware.ts` — ◐ PARTIAL
`ai-driven-web-ui/src/middleware.ts` now exists. Contents weren't audited; confirm it actually guards session and isn't a stub.

### 2.8 Auto-merge narrative vs default — 🔲 STILL OPEN (P2)
Requires a UX pass on pipeline bubbles; not touched by Phase 2.

---

## 3. Production-readiness gaps

### ~~3.1 WebSocket endpoint authz~~ — ✅ FIXED
`api/chat/router.py:628` now calls `_auth_from_ws(websocket, task_id)` before accepting the subscription. Verified 2026-05-01 during Tier-1 Item A: `_auth_from_ws` resolves the task row, compares `task.team_id` to the resolved session/API-key team, and closes the WS before sending any event on mismatch. Tier-1 Item A (commit `fef6ea7`) closes the parallel gap on the REST admin surface: every `/api/admin/*` endpoint now requires `require_admin` AND enforces per-resource team scoping.

### ~~3.2 `/stop` → `failed` status pollution~~ — ✅ FIXED
`router.py:464-479` now sets `status="cancelled"` (not `failed`) and returns `{"status": "cancelled"}`. `SUCCESS_STATUSES` / `FAILURE_STATUSES` constants in `admin/router.py` treat them distinctly. KPIs correct.

### 3.3 No login / session / team switcher — ◐ PARTIAL
- Login: ✅ `/api/auth/login` with session tokens in Redis.
- Admin role enforcement: ✅ as of 2026-05-01 (commit `fef6ea7`) — every `/api/admin/*` endpoint requires `require_admin` with team-scoped authz. Previously only 3 endpoints (override/artifacts/override-audit) checked roles.
- Team switcher: 🔲 still missing.
- API-key UI for webhooks/CI: 🔲 still missing.

### ~~3.4 Debug mode banner signal~~ — ✅ BACKEND FIXED
`HealthResponse.debug` exists (`api/health.py:27,89`). UI still needs to render the banner — check `dashboard/layout.tsx`.

### ~~3.5 Budget burn-down in UI~~ — ✅ FIXED
`dashboard/layout.tsx:6,79,100` imports `<BudgetBar/>` and renders burn-down. Verify "disable New Run at 100%" behavior (§6 of original canonical table).

### 3.6 DLQ / stuck-task visibility — ◐ PARTIAL
- Reapers: ✅ shipped in Phase 2 T24 (stuck-task auto-reap after 30 min).
- DLQ dashboard UI: 🔲 still missing from `/dashboard/admin/runs`.
- Override endpoint: ✅ admin can manually unstick.

### ~~3.7 Demo fallback gating~~ — ✅ FIXED
`DEMO_MODE = process.env.NEXT_PUBLIC_DEMO_MODE === 'true'` at `page.tsx:40`; `DEMO_STEPS` is rendered only when `DEMO_MODE=true`.

### 3.8 Reviews approval queue — 🔲 STILL OPEN (P2)
Not touched.

### 3.9 Emergency-stop / kill-switch — 🔲 STILL OPEN (P2)
No admin toggle anywhere. Principle from `vision/00-vision.md` ("reversible by default"). Still P2.

### 3.10 APM deep-link — 🔲 STILL OPEN (P2)
No DataDog trace link in UI. Phase 2 counters are emitted but not hyperlinked.

---

## 4. Customer-experience concerns (P2)

All items (4.1-4.6) unchanged. None touched by Phase 2.

---

## 5. Polish (P3)

### ~~5.1 SWR polling stops on terminal~~ — ✅ FIXED
`page.tsx:38` defines `TERMINAL_STATUSES`; `useSWR` uses dynamic `refreshInterval` that returns 0 on terminal state.

### 5.2 Hardcoded nav counts — 🔲 STILL OPEN (P3)
Not verified; original finding was `sidebar.tsx:168-175`. Re-audit when you touch the sidebar.

### 5.3 Mockup files at repo root — 🔲 STILL OPEN (P3)
Not verified; may still be present.

### 5.4-5.8 — 🔲 ALL STILL OPEN (P3)

---

## 6. Canonical decisions (recommendations from original review)

Unchanged; all still stand as guidance.

---

## 7. Status summary (2026-05-01)

| Original finding | State | Notes |
|---|---|---|
| 1.1 embedding dim | ✅ | Doc fixed |
| 1.2 severity normalization | ✅ | Migration + enum; CHECK constraint verified 2026-05-01 |
| 1.3 tool-count doc drift | ✅ | Refreshed 2026-05-01 (`fb73880`) |
| 1.4 model IDs | ✅ | `claude_planning_model` → Opus 4.7 (parity with Bedrock) |
| 2.1 WS URL | ✅ | Env var + fallback |
| 2.2 Pause/Resume/Stop | ✅ | Wired to real API |
| 2.3 Sidebar labels | 🔲 | Still mismatched |
| 2.4 Chat route | 🔲 | Not launch-blocking |
| 2.5 Hardcoded user | ✅ | Removed |
| 2.6 Settings alert() | ✅ | Removed |
| 2.7 Next.js middleware | ◐ | File exists; content unaudited |
| 2.8 Auto-merge UX | 🔲 | Unchanged |
| 3.1 WS authz | ✅ | `_auth_from_ws` enforces; REST admin surface also closed 2026-05-01 (`fef6ea7`) |
| 3.2 `/stop` = cancelled | ✅ | Distinct terminal state |
| 3.3 Login + session | ◐ | Login ✅; admin role gate ✅ (`fef6ea7`); team switcher 🔲; API-key UI 🔲 |
| 3.4 Debug banner | ◐ | Backend ✅; UI render unaudited |
| 3.5 Budget bar | ✅ | Header burn-down live |
| 3.6 DLQ / stuck-task | ◐ | Reapers ✅; UI dashboard 🔲 |
| 3.7 Demo fallback gate | ✅ | `NEXT_PUBLIC_DEMO_MODE` |
| 3.8 Reviews queue split | 🔲 | Unchanged |
| 3.9 Emergency-stop | 🔲 | Not built |
| 3.10 APM deep-link | 🔲 | Not built |
| 4.* CX concerns | 🔲 | Unchanged |
| 5.1 SWR terminal polling | ✅ | Dynamic refreshInterval |
| 5.2-5.8 P3 polish | 🔲 | Mostly unchanged |

**Counts:** 15 original findings ✅ closed · 4 ◐ partial · 13 🔲 still open.

---

## 8. Revised 2-week plan — what's actually next

The original plan's items 1-6 are done. What remains, ordered by leverage:

### Week 1 — P1/P2 gaps that block a second tenant

| # | Item | Priority | Effort | Where |
|---|---|---|---|---|
| A | Complete auth + WS security (`implementation/08`): role checks on ~20 non-Phase-2 admin endpoints; cross-tenant CI probe; verify `middleware.ts` isn't a stub | P1 | 2-3 d | `src/ai_driven/api/admin/*`, `ai-driven-web-ui/src/middleware.ts` |
| B | Severity taxonomy closure (`implementation/07`): verify migration ran, backfill any stragglers, remove the doc-drift note | P1 | 2 h | `alembic/` + `docs/architecture/07` |
| C | Architecture docs refresh: arch/01 table count, arch/02 agent count + queue count, arch/04 state machine (blocked/awaiting_qa/qa_failed), arch/06 orchestrator path | P1 docs | 4 h | `docs/architecture/` |
| D | Model ID bump (1.4): benchmark Opus 4.6 → 4.7 + Sonnet 4.6 → 4.7 + re-run eval suite; bump constants in `config.py:45-47,114` | P2 | 4 h | `config.py` + benchmark harness |

### Week 2 — Operational polish

| # | Item | Priority | Effort | Where |
|---|---|---|---|---|
| E | Team switcher + API-key manager UI (closes 3.3 remainder) | P2 | 1-2 d | web-ui settings pages |
| F | DLQ + reaper visibility on `/dashboard/admin/runs` (closes 3.6) | P2 | 1 d | admin route + backend list endpoint |
| G | Debug-mode banner in `dashboard/layout.tsx` (closes 3.4 UI) | P2 | 1 h | layout + health hook |
| H | Emergency-stop kill-switch (3.9) | P2 | 4 h | admin endpoint + UI toggle |
| I | Sidebar label↔route alignment (2.3) | P2 | 30 m | `sidebar.tsx:195-196` |
| J | APM deep-link to DataDog trace (3.10) | P2 | 2 h | admin task detail + env-driven trace URL |

### Later

- Reviews queue split (3.8), Onboarding wizard (4.4), Auto-merge UX (2.8), accessibility pass (4.5), and all P3 polish items.

---

## Policy for keeping this file honest

- Re-verify every ~4 weeks (or at the close of a named phase).
- When a finding closes, strike it through in place with ✅ FIXED + evidence path. Don't delete — history matters.
- When a finding stays open across two audits, promote to a named remediation plan in `docs/implementation/` or to the next phase's `docs/plans/`.
- Keep this file ≤ 300 lines. Once closed items dominate, move them to `docs/archive/`.
