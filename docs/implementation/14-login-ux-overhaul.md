# 13 — Login UX overhaul on top of `/api/auth/session`

**Status:** proposal, not started
**Depends on:** [08-auth-and-ws-security.md](08-auth-and-ws-security.md) Phase 2 scaffold (already landed), [middleware fix](../../ai-driven-web-ui/src/middleware.ts) (landed 2026-04-25)
**Date opened:** 2026-04-25

## Why

Today's login is a single field: *"Enter your team API key"*. The user pastes the raw key, it gets stored in a JS-readable cookie, and every request sends `X-API-Key: <raw>`. This is broken in three concrete ways — fix one and you still have the other two:

1. **The cookie is the credential.** If it's ever exfiltrated (malicious npm dep, subdomain XSS, proxy log, browser extension), the attacker owns the team *forever*. "Logout" just clears the cookie on this device — the raw key still works for anyone who has it. The only mitigation today is to rotate the team API key, which breaks every CI runner, webhook, and agent using it. Human session lifetime is coupled to machine credential lifetime.
2. **No real logout, no audit trail, no per-device revocation.** We can't tell the difference between "Alice logged in from her laptop" and "Alice's laptop got stolen" from server-side data.
3. **No migration path.** When Cognito lands (Sprint 9 Phase 2, blocked on AWS provisioning), we have to rewrite `/login` anyway because it doesn't understand JWTs or redirect-flow auth. Shipping a proper session layer now gives Cognito a seam to slot into later.

## Approaches considered

Three candidates were red-teamed. Keeping the discussion here for decision durability.

### A. HttpOnly-cookie-only fix

Server-set the cookie `HttpOnly`, leave the raw key inside it. Minimal change.

**Rejected.** The cookie is still the credential. HttpOnly stops XSS-based theft but not proxy logs, extensions, or dev-tool leaks. Logout remains a lie. Rotating the key still kills every CI runner. This is a velocity-trap fix that looks like progress.

### B. Email + API-key pair

`/login` asks for email AND key, verified against `team_members.email` + `team.api_key_hash`.

**Rejected as framing.** Email isn't secret, so it doesn't raise the attacker's bar. But **we should still capture email** — not as an auth factor, as session metadata for audit ("session X from alice@company.com") and as the forward-compatible path to Cognito (email is the Cognito username). So: **adopt the email field, but don't sell it as security.**

### C. API-key-to-session-token exchange ← **chosen**

User pastes the key once on `/login`. Backend hashes → validates → mints an opaque session token stored in Redis with a 24h TTL. Cookie carries the session token, not the key. Every request validates the session against Redis; on miss, the session is expired.

**Win:** real logout (delete the Redis entry), per-device sessions (multiple tokens per `api_key_hash`), idle timeout, audit trail, a place to hang CSRF tokens when we need them. Most importantly: **human session lifetime decouples from the machine key**. Stolen session = rotate session (one Redis DEL); stolen API key = rotate key (still breaks CI but that's what you wanted).

**Honest cost the red-team surfaced:** Redis becomes a **hard login dependency**. Today Redis is a cache — if it flaps, auth still works (it falls back to a Postgres lookup). With sessions in Redis, Redis down = nobody can log in. Two mitigations:

1. **On session-miss, fall back to direct X-API-Key validation** (if the client still has the raw key — we don't; the whole point is to stop storing it).
2. **Sign the session as a JWT with a Redis-backed denylist.** Token carries its own validity; Redis is only consulted to check "was this token revoked?" If Redis is down, we can choose: fail-closed (deny all sessions, tighter security) or fail-open-read-only (allow session, block sensitive writes). I lean fail-closed: login is a human-triggered flow with a known backoff behaviour, unlike request-time auth.

**Decision:** start with option 1 (opaque token + Redis), because it's simpler and the Redis-outage surface is small (local dev doesn't care, production has Redis in the hot path already for other things). Add JWT option later if Redis availability becomes a real concern.

## Design

### Backend — new endpoints

```
POST /api/auth/login
  body: { api_key: string, email?: string }
  → 200 { session_token, expires_at, user_id, team_id, team_name, role, email }
  → 401 { detail: "Invalid API key" }
  Side effects:
    - Validate api_key against Team.api_key_hash.
    - Mint session_token (32 bytes, base64url — NOT a JWT, keep it opaque).
    - Redis: SET session:{token} {team_id, member_id, email, created_at, last_seen_at} EX 86400
    - Redis: SADD sessions:team:{team_id} {token}  (for admin "sign out all devices")

POST /api/auth/logout
  → 204
  Side effects:
    - Read session token from cookie.
    - Redis: DEL session:{token}
    - Redis: SREM sessions:team:{team_id} {token}
    - Clear the cookie on the response (Set-Cookie: session_token=; Max-Age=0)

GET /api/auth/session  (already exists, extend)
  → 200 { ...existing SessionResponse fields, expires_at, auth_source }
  - Reads session_token cookie.
  - If valid: touches last_seen_at, returns identity.
  - If missing/expired/revoked: 401.
  - auth_source becomes "session" | "debug" (API-key path removed post-rollout).
```

### Backend — middleware change

`src/ai_driven/auth/middleware.py` currently reads `X-API-Key` for every request. Add a **second path**:

1. If `Authorization: Bearer <session_token>` or `session_token` cookie present → validate against Redis → set `request.state.team_id`.
2. Else if `X-API-Key` present → existing path (kept for CI runners, webhooks, and the coding/review agents that call backend directly).
3. Else → 401.

Critical: **do not remove the `X-API-Key` path.** Agents, webhooks, and `scripts/*.py` smoke scripts all depend on it. Session tokens are for the Next.js UI only.

### Frontend

- `lib/auth.ts` — drop `getApiKey` / `setApiKey`. Add `getSessionToken` / `setSessionToken` / `clearSession`. `authFetch` sends `Authorization: Bearer ...` instead of `X-API-Key: ...`.
- `src/middleware.ts` — check `session_token` cookie instead of `api_key`. Debug-mode bypass sets `session_token=debug-session` sentinel (backend auth middleware recognizes the sentinel when `settings.debug=true`).
- `/login` page — rewrite to collect email + API key, call `POST /api/auth/login`, store returned `session_token` via `setSessionToken`. Email is optional (placeholder: *"Your email — for audit log only"*).
- `/logout` route handler — call `POST /api/auth/logout`, clear cookie, redirect to `/login`.
- Add a "Sign out" menu item in the user avatar dropdown (`components/layout/sidebar.tsx`) — replaces the hardcoded "Alex Chen" placeholder.
- New page `/dashboard/admin/sessions` — lists active sessions for the team (device, IP, created_at, last_seen_at), with a per-row revoke button. Uses the `sessions:team:{team_id}` Redis set.

### Cognito migration path (non-blocking)

When the Cognito pool lands, `/login` gets a second form:
- Email + password → Cognito Hosted UI redirect flow → on callback, backend calls `verify_cognito_jwt()` and mints the same `session_token` as the API-key path.

From that point, the session token is the only thing the UI knows. API-key login becomes an admin-only fallback or gets deprecated entirely. The `auth_source` field on `SessionResponse` tells us whether the session came from Cognito or a key so we can monitor the migration.

## Rollout plan

1. **Phase 1 — Backend endpoints land, behind flag.** `POST /api/auth/login`, `/logout`. Middleware accepts both `session_token` and `X-API-Key`. Flag: `AI_DRIVEN_UI_AUTH_REQUIRE_SESSION` (default `false`). When `false`, UI can still post `X-API-Key` cookies — nothing breaks.
2. **Phase 2 — Frontend switches to session-token.** `lib/auth.ts` rewritten, `/login` updated, `/logout` added. Deploy.
3. **Phase 3 — Flip the flag.** `AI_DRIVEN_UI_AUTH_REQUIRE_SESSION=true` in prod. Middleware rejects `X-API-Key` on requests originating from a browser (via `Sec-Fetch-*` headers or a referer check). CI/webhooks/agents unaffected.
4. **Phase 4 — (optional) Session management UI.** `/dashboard/admin/sessions`.
5. **Phase 5 — (later, gated on AWS) Cognito slots in.** Same session-token cookie, different upstream identity.

## Non-goals for B.2

- Cognito JWT verification — still blocked on the pool. Leave the `verify_cognito_jwt()` shim.
- Multi-factor auth.
- Federated SSO (SAML / OIDC with customer IdPs).
- Removing `X-API-Key` header entirely — agents and scripts need it.
- RBAC beyond the existing `owner/admin/member/viewer` roles.

## Acceptance checklist (when we ship B.2)

- [ ] `POST /api/auth/login` mints session token, stores in Redis.
- [ ] `POST /api/auth/logout` deletes session, clears cookie.
- [ ] `GET /api/auth/session` reads session cookie, returns identity, touches `last_seen_at`.
- [ ] Middleware accepts both session token and `X-API-Key` during migration window.
- [ ] `/login` rewritten: email (optional) + API key, stores session cookie.
- [ ] `/logout` route clears both client + server state.
- [ ] Sidebar "Sign out" menu item works.
- [ ] Frontend middleware (`src/middleware.ts`) switched from `api_key` to `session_token` cookie.
- [ ] Debug-mode middleware bypass works end-to-end (backend recognizes `debug-session` sentinel when `settings.debug=true`).
- [ ] Playwright: login → dashboard → logout → redirect to /login; session revocation from another tab logs the user out.
- [ ] At least one Redis-outage simulation test: login fails cleanly with "Authentication service unavailable, try again shortly."

## Open questions (answer before starting B.2)

1. **TTL.** Proposal is 24h sliding (extend on activity). Too short? Too long? Current `api_key` cookie is 7 days.
2. **Sliding vs. absolute.** Sliding (extends on use) is friendlier; absolute (fixed from login time) is more auditable. Lean sliding with a 30-day hard cap.
3. **Should session token carry team_id in plain text** (so logout doesn't need a Redis read)? Opaque is simpler; embedded is faster. Favoring opaque for now.
4. **Sessions table in Postgres too?** Mirror in Postgres for audit retention beyond Redis TTL, or rely on Redis + DataDog logs? I'd skip the Postgres mirror until we have a compliance need.
