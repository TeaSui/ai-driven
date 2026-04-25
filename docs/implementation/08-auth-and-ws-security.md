# Implementation Plan 08 — Auth, Session, Multi-Tenancy & WebSocket Authorization

**Owner:** Backend + Security + Frontend
**Severity:** P1 (operator visibility) + P2 (UI login, session, API-key UI)
**Estimated effort:** 3–5 days
**Blocks:** second customer onboarding, GA launch

> **Requires Security-engineer subagent review before merging.** Touches authentication and cross-tenant authorization.

## What exists today (corrections to earlier draft)

Exploration of `src/ai_driven/` found more than the first review suggested:

- [x] API-key middleware with Redis-cached team lookup — `src/ai_driven/auth/middleware.py`
- [x] `get_team_id`, `require_task_access`, `require_role` — `src/ai_driven/dependencies.py`
- [x] Rate limiting per team (chat_send + concurrent) — `src/ai_driven/auth/rate_limiter.py`
- [x] Billing tracker with daily budget enforcement — `src/ai_driven/billing/tracker.py`
- [x] **WebSocket first-message auth** with team-scoped task ownership check — `src/ai_driven/api/chat/router.py:408-466` (closes with 4404 on mismatch)
- [x] Admin endpoints scoped by `team_id` (e.g. `/api/admin/tasks`, `/repos`, `/incidents`)

## What's still missing

- [ ] Next.js `middleware.ts` — no central route guard; `/dashboard/*` is reachable without a session
- [ ] Login / session flow in the UI (`alert()` stubs and hardcoded "Alex Chen")
- [ ] API-key management UI (create / rotate / revoke)
- [ ] Team switcher for users in >1 team
- [ ] Debug-mode visual indicator — `settings.debug` bypasses auth at `auth/middleware.py:171`; nothing in UI surfaces that
- [ ] `cancelled` terminal state — `/stop` sets `failed` (`api/chat/router.py` + UI successRate)
- [ ] WebSocket client in the Next.js UI does **not** send the required `{"type":"auth","token":"..."}` first message (works today only because debug mode allows empty auth)
- [ ] API-key rotation with grace period (`/teams/{id}/rotate-key` exists but no UI)

## Checklist

### Phase 1 — Backend corrections · ~½ day

- [x] Introduce `cancelled` task status + DB CHECK — `alembic/versions/a1b2c3d4e5f9_task_status_check.py` pins the full status set (`queued`, `planning`, `coding`, `testing`, `running`, `awaiting_input`, `paused`, `completed`, `failed`, `cancelled`)
- [x] `POST /tasks/{id}/stop` sets `status='cancelled'`, not `failed` (`api/chat/router.py:396`)
- [x] `DashboardSummary.successRate` excludes `cancelled` from denominator — math is `completed / (completed + failed)`, `cancelled` is neither
- [x] Extend `HealthResponse` with `debug: bool = settings.debug` — `src/ai_driven/api/health.py:27`
- [x] Unit test: stopping a task yields `status='cancelled'` and a `complete` event with `stopped=true` — `tests/unit/test_stop_task_cancelled.py`

### Phase 2 — Identity provider · ~1 day

- [ ] Cognito User Pool per env (dev / staging / prod); `teamId` custom attribute — **blocked on AWS provisioning**
- [x] ~~`users` table (id, email, team_id, role, created_at) — migration~~ — **superseded:** `team_members` (migration `a1b2c3d4e5f8`) already stores email + team_id + role. Reusing it avoids two parallel people models.
- [x] `/api/auth/session` endpoint returns `{userId, teamId, teamName, role, email, authSource}` — scaffolded at `src/ai_driven/api/auth/router.py`. Resolves identity via API key today; `verify_cognito_jwt()` shim raises `NotImplementedError` until the pool exists. 4-test unit coverage in `tests/unit/test_auth_session.py`.
- [ ] Federation skeleton (SAML / OIDC) — spec only, not implemented

### Phase 3 — Next.js middleware & pages · ~1 day

- [ ] `ai-driven-web-ui/middleware.ts` at repo root (not under `src/`), matches `/dashboard/:path*`
- [ ] Cookie-stored session token, `verifyJwt()` helper in `src/lib/auth/`
- [ ] Redirect unauthenticated to `/login`
- [ ] `/login` page with Cognito Hosted UI redirect
- [ ] `SessionProvider` context; `sidebar.tsx` reads `user.name`, `user.role`, `user.team`
- [ ] Remove hardcoded "Alex Chen · Platform · Manila" (`src/components/layout/sidebar.tsx:335-344`)
- [ ] Replace `alert()` on Settings button with router push to `/dashboard/admin/settings`

### Phase 4 — WebSocket client auth · ~2 h

- [x] `tasks/[id]/page.tsx` WebSocket: sends `{"type":"auth","token": sessionApiKey}` as first message after `ws.onopen`
- [x] Handle close codes 4401 (bad auth) / 4404 (not yours) with visible error banner (`data-testid="ws-auth-error-banner"`, `role="alert"`, `aria-live="assertive"`)
- [x] Env-configured API key for dev (`NEXT_PUBLIC_DEV_API_KEY`) with cookie-sourced `api_key` taking precedence in prod

### Phase 5 — API-key manager UI · ~½ day

- [ ] `/dashboard/admin/api-keys` page lists existing keys (masked) with create/rotate/revoke
- [ ] Wire to existing `POST /api/admin/teams/{id}/rotate-key`
- [ ] Surface 7-day grace period clearly (old key still works until {date})
- [ ] Copy-on-create modal (one-time reveal); keys never displayed after creation

### Phase 6 — Debug banner · ~2 h

- [x] `dashboard/layout.tsx` fetches `/health` on mount and reads `debug` flag
- [x] Red sticky top banner when `debug: true`:
      **"Debug mode active — authentication is bypassed. Do not use in production."**
- [x] Banner announces with `role="alert"` + `aria-live="assertive"` for screen readers
- [x] Banner has no dismiss button — stays visible as long as backend reports debug mode (spec says visible always in prod)

### Phase 7 — Observability & ops · ~½ day

- [ ] DataDog alert: `ai_driven.auth.debug_mode_active` metric emitted by backend when `settings.debug=true` at boot
- [ ] Cross-tenant probe integration test: `teamA` JWT hitting `teamB` IDs returns 404 (`tests/security/test_cross_tenant.py` already exists — extend with WS case)
- [ ] API-key rotation runbook in `docs/runbooks/`

## Acceptance

- [ ] `AI_DRIVEN_DEBUG=true` causes a red banner + a DataDog alert within 60 s
- [ ] Unauthenticated visit to `/dashboard/overview` redirects to `/login`
- [ ] Stop button sets task to `cancelled`; `successRate` excludes it
- [ ] `teamA` cannot read `teamB`'s tasks / incidents / reviews / PR data (404, not 403)
- [ ] WS with wrong token closes with `code=4401`; wrong `task_id` with `code=4404`
- [ ] API-key rotation works without service interruption (additive, 7-day grace)
- [ ] No `alert()` calls remain in production UI bundle

## Rollback

- [ ] Feature flag `auth.enforce=false` leaves middleware in log-only mode for 24 h before enforcement flips on

## Out of scope (backlog)

- Fine-grained RBAC beyond `member` role — tracked in a future `10-rbac.md`
- SCIM provisioning
- Enterprise SSO federation with customer IdPs
