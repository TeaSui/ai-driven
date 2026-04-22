# AI-Driven Development Platform — Docs ↔ UI Review (Draft)

Status: **DRAFT** — to be challenged by a second agent before presenting.
Date: 2026-04-22
Scope: `docs/` (architecture, features, implementation, configuration) vs `ai-driven-web-ui/` (Next.js 14 scaffold + Babel-standalone design prototype)

## 0. Executive summary

The Next.js implementation is further along than the summary suggested: 13 dashboard routes exist, SWR + WebSocket streaming are wired, evaluation scores render, and pause/resume endpoints are defined in the API client. However, there are real bugs (hardcoded port), doc-to-doc contradictions that will cause silent drift, and several production-readiness gaps around auth/multi-tenancy/safety rails.

Findings are grouped by severity:
- **P1 (blocker)**: real bugs or inconsistencies that will hurt prod.
- **P2 (strong recommendation)**: gaps that block excellent CX.
- **P3 (polish)**: quality improvements.

## 1. Doc ↔ Doc internal conflicts (P1)

These must be resolved before engineers pick a side.

### 1.1 Embedding dimension: 1024 vs 1536
- `docs/architecture/05-rag-caching-evaluation.md` — "Voyage AI v3 Embeddings (1536d)"
- `docs/features/01-ai-coding-agent.md` — "voyage-code-3 (1024-dim)"
- `docs/features/05-rag-semantic-search.md` — `vector(1024)` pgvector column
- **Canonical answer**: Voyage `voyage-code-3` defaults to 1024 dims. The 1536 number in architecture/05 is wrong.
- **Action**: fix `05-rag-caching-evaluation.md`. Verify the `code_embeddings.embedding` column DDL in migrations matches 1024.

### 1.2 Incident severity taxonomy: P1-P3 vs P1-P4
- `docs/architecture/07-devops-incident.md` — lists **P1, P2, P3**.
- `docs/features/04-ai-devops-assistant.md` — lists **P1, P2, P3, P4**.
- **Impact**: Alert routing, SLA tables, UI filters, and DataDog severity mapping will behave inconsistently.
- **Action**: Pick one taxonomy (recommend P1-P4 aligning with standard DataDog), update both docs, and ensure the `incidents.severity` column constraint enforces the set.

### 1.3 Coding Agent tool count: 11 vs 10 + optional
- `docs/architecture/02-system-architecture.md` — "Coding Agent: 11 tools + RAG + MCP"
- `docs/features/01-ai-coding-agent.md` — "10 registered tools + optional semantic_search_code + MCP"
- **Impact**: Doc consumers get confused; onboarding/training materials diverge.
- **Action**: Normalize phrasing — either "10 core + 1 conditional RAG tool (when repo > 500 files)" or drop the numeric claim in favour of a table.

### 1.4 Model ID drift
- Docs reference `claude-opus-4-6` for planning.
- Current Anthropic families are Opus 4.7, Sonnet 4.6, Haiku 4.5 (per environment notes).
- **Action**: audit docs for specific model IDs and pin to a constants file (e.g. `docs/implementation/00-overview.md` + backend `settings.claude_model_*`), then update to the latest where capability matches.

### 1.5 Auto-merge default
- `docs/features/02-ai-code-review-bot.md` says auto-merge default is `false`.
- UI copy ("Your engineering org, augmented") and Timeline filter tabs (All/Active/Merged/Blocked) imply smooth auto-merge flow.
- **Action**: align narrative — keep default OFF, and add UI affordance explicitly showing "requires human merge" state.

## 2. Doc ↔ UI implementation conflicts (P1)

### 2.1 WebSocket hardcoded port (real bug)
File: [ai-driven-web-ui/src/app/dashboard/tasks/[id]/page.tsx:1034](ai-driven-web-ui/src/app/dashboard/tasks/[id]/page.tsx:1034)
```ts
const wsUrl = `${protocol}//localhost:8000/api/chat/stream/${id}`
```
But the backend runs on `:8888` (per `next.config.js` rewrites and `docs/configuration.md`). **Real-time streaming will fail silently** in dev; in prod the absolute URL to `localhost` will break entirely.
- **Action**: construct from `window.location.host`, or introduce `NEXT_PUBLIC_WS_URL`, or add a WebSocket rewrite in `next.config.js`.

### 2.2 Sidebar label ↔ route mismatches
File: [ai-driven-web-ui/src/components/layout/sidebar.tsx:168-175](ai-driven-web-ui/src/components/layout/sidebar.tsx:168)
- Nav label "Metrics" → route `/dashboard/insights` (confusing for bookmarking, search).
- Nav label "DevOps" → route `/dashboard/incidents` (DevOps is a first-class agent in docs, not just incidents).
- **Action**: either rename routes (`/metrics`, `/devops`) or rename labels (Insights, Incidents).

### 2.3 Missing pages vs doc promises
- **Chat page** — `docs/features/08-realtime-visibility.md` describes WebSocket chat; scaffold has no `/chat` route. Task detail page covers streaming but not conversational back-and-forth.
- **Team / auth pages** — no login, no team switcher, no API-key management UI. Multi-tenant `teams` table exists in DB (per architecture/01).
- **DevOps agent page** — sidebar conflates DevOps with Incidents; no standalone agent view parallel to Coding Agent.
- **Action**: prioritize Auth + Team switcher; Chat can reuse task detail stream initially.

### 2.4 Hardcoded user profile
File: [ai-driven-web-ui/src/components/layout/sidebar.tsx:339-344](ai-driven-web-ui/src/components/layout/sidebar.tsx:339)
```tsx
<Avatar name="Alex Chen" size={28} />
… <div>Alex Chen</div>
<div>Platform · Manila</div>
```
- **Action**: wire to a session context (NextAuth / cookie / API-key identity echo).

### 2.5 Settings is a browser `alert()`
File: [ai-driven-web-ui/src/components/layout/sidebar.tsx:348](ai-driven-web-ui/src/components/layout/sidebar.tsx:348)
```tsx
onClick={() => alert('Settings page coming soon.')}
```
Not just a stub — it's also an accessibility regression (blocking modal, keyboard trap).
- **Action**: replace with a route or a proper dialog component; or hide the button until feature ships.

### 2.6 Pause/Resume only wired in demo mode
File: `ai-driven-web-ui/src/app/dashboard/tasks/[id]/page.tsx:1262-1273`
The Pause/Resume button is rendered only when `!isRealMode`. The API client exports `pauseTask`/`resumeTask`/`stopTask` and `docs/architecture/03-coding-agent-sequence.md` shows the control channel, but real tasks have no way to use it.
- **Action**: call the API from the header buttons; poll `task.status` to toggle between Running / Paused.

## 3. Production-readiness gaps (P2)

### 3.1 No auth flow
- `docs/configuration.md`: `AI_DRIVEN_DEBUG=true` bypasses auth. In prod this becomes the identity boundary.
- No login, no API-key manager, no team switcher, no logout.
- **Action**: MVP = login by email link / SSO → store team + role in session; API-key flow for webhooks and CI only.

### 3.2 Debug-mode indicator missing
- If prod ever ships with `AI_DRIVEN_DEBUG=true`, there is no visible warning. This is a realistic failure mode.
- **Action**: top-of-page banner sourced from `/api/admin/health` exposing `debug: true`.

### 3.3 No budget / cost-burn UI
- Docs mention daily budget and $100/day halt (`docs/features` and `docs/implementation/03-coding-agent.md`).
- Dashboard summary fetches `budgetUsagePercent` but doesn't render a burn-down chart or near-limit alert.
- **Action**: render a horizontal bar in header; alert at 80%; disable "New run" button when over budget.

### 3.4 No DLQ / stuck-task visibility
- `docs/implementation/03-coding-agent.md` lists DLQ as unchecked.
- Operators will need to see "N tasks stuck > 10 minutes" and retry/abandon controls. Nothing in the UI surfaces this.
- **Action**: add a "System health" card to `/admin/runs` with queue depth, DLQ size, longest in-flight.

### 3.5 Demo fallback bleeds into prod bundle
File: `ai-driven-web-ui/src/app/dashboard/tasks/[id]/page.tsx:45-63` exports `DEMO_STEPS` used when no events arrive.
- Risk: an API outage shows "race condition in ledger balance cache" as the current task — misleading.
- **Action**: gate behind `process.env.NEXT_PUBLIC_DEMO_MODE`; otherwise show an empty state / retry.

### 3.6 No visible PII / data-outflow disclosure
- Docs acknowledge Claude sees source code, Jira text. UI does not show what leaves the VPC.
- **Action**: "Data egress" badge on repo config and on each run; link to a short policy page.

### 3.7 Error boundaries + empty states incomplete
- Dashboard overview handles empty feed; task detail defaults to fake demo data; reviews/incidents/insights likely relying on happy-path.
- **Action**: add React error boundary per route; unified empty-state component.

### 3.8 No Auto-merge / approval queue UI
- Doc auto-merge is OFF by default; human merges required. UI has no queue of "PRs waiting for my decision".
- **Action**: `/dashboard/reviews` should split into "Awaiting me", "Awaiting others", "Merged"; include keyboard shortcut to approve/merge.

### 3.9 Observability hand-off to DataDog missing
- No deep-link to a DataDog trace from a task or incident.
- **Action**: each `task_id` → APM span → UI shows "View trace" button.

## 4. Customer-experience concerns (P2)

### 4.1 Trust & transparency
- AI confidence scores (e.g. "89%") show in UI without explanation.
- **Action**: hover tooltip showing: model, RAG hits used, evidence sources, evaluation dimensions.

### 4.2 Human-in-the-loop clarity
- Pipeline bubbles (Jira → Code → Review → Deploy → Monitor) don't distinguish "AI completed" from "waiting for human".
- **Action**: 3-state stage indicator: `ai-completed`, `awaiting-human`, `human-completed`.

### 4.3 Rollback / kill-switch
- No UI to revert an auto-merged PR, disable a repo, or pause all agents.
- **Action**: Admin → "Emergency stop" toggle (disables all dispatch, drains queues).

### 4.4 Onboarding
- No first-run wizard: pick repo → install GitHub app → set up webhook → choose agent defaults.
- **Action**: `/onboarding` route triggered when `teams.setup_completed = false`.

### 4.5 Accessibility
- Inline styles throughout (harder to audit contrast).
- `alert()` usage, no visible focus rings on custom buttons, no `aria-live` on streaming regions.
- **Action**: axe-core audit; move to CSS variables already defined in `tokens.css`.

## 5. Polish (P3)

- **5.1** Next.js 14 rewrites only cover `/api/*`; WebSocket needs its own path or env-based URL.
- **5.2** Nav counts ("3", "12", "2") are hardcoded in sidebar; should reflect live data.
- **5.3** `main.jsx` / `design-canvas.jsx` at repo root are mockup artifacts — decide whether to keep in repo or move under `ai-driven-web-ui/design/` to avoid confusion with the real app.
- **5.4** No dark-mode toggle in the real Next.js shell (prototype has one).
- **5.5** Keyboard shortcut `⌘K` in sidebar search input has no handler.
- **5.6** Sidebar has "Platform · Manila" static string — localize or drop.
- **5.7** Strategy file for `capture-pages.mjs` uses Playwright but isn't referenced in `package.json` scripts; either add a `capture` script or remove.

## 6. Recommended canonical decisions (for this review to sign off)

1. **Embedding dim**: 1024. Fix docs and DDL.
2. **Severity**: P1-P4. Update architecture/07.
3. **Model IDs**: declare in a single constants module; bump Opus to 4.7.
4. **UI stack**: Next.js 14 under `ai-driven-web-ui/src/` is the production path; Babel-standalone prototype files stay only for design iteration (move or delete from repo root).
5. **Auto-merge**: OFF by default, explicit UI opt-in per repo, always visible "human required" state.
6. **Auth**: wire MVP login and team switcher before v1.
7. **WebSocket**: env-var–driven URL; no hardcoded host/port.

## 7. Prioritized action list (first two weeks to prod-ready)

| # | Item | Severity | Est. effort |
|---|------|----------|-------------|
| 1 | Fix WS URL hardcoded port | P1 bug | 1 h |
| 2 | Resolve embedding dim + severity conflicts in docs | P1 | 1 h |
| 3 | Wire Pause/Resume/Stop to real API | P1 | 4 h |
| 4 | Add login + session + team switcher | P2 | 2 d |
| 5 | Budget burn-down in header, disable New Run at cap | P2 | 1 d |
| 6 | Auto-merge approval queue UI | P2 | 1 d |
| 7 | Emergency-stop admin toggle | P2 | 4 h |
| 8 | Debug-mode banner from /admin/health | P2 | 2 h |
| 9 | Sidebar label↔route alignment | P3 | 1 h |
| 10 | Remove DEMO_STEPS fallback in prod builds | P3 | 2 h |
| 11 | Accessibility audit pass | P3 | 1 d |
| 12 | Onboarding wizard | P3 | 2 d |
