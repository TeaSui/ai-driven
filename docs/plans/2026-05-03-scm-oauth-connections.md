# SCM OAuth Connections — Brief (v3, post-red-team-round-2)

**Author:** 2026-05-03
**Status:** v3 addresses 4 still-not-fixed + 5 new issues from round 2.

## Changelog v2 → v3

**Still-not-fixed from round 2, now corrected:**
- B1 **fully** camelCase + canonical envelope in every example. All 412/400/502 payloads use `{"error": {"code": ..., "message": ..., "details": [...]}}` with `JSONResponse` (not `HTTPException.detail`). `connectUrl`, `scopeNeeded`, `currentScope` — all camelCase.
- B2 **resolved to one story:** admin-only. `/authorize`, `/callback`, `/me/connections` all use `require_admin`. The OAuth feature is admin-only for v1 — plain-member self-service is a follow-up after we have real plain-member usage. Removes `require_authenticated_member` entirely.
- B3 **correct PUBLIC_PATHS usage:** add two literal entries `/api/auth/scm/github/callback` and `/api/auth/scm/bitbucket/callback`. `startswith` matches literally — template path `{provider}` would not have worked.
- B4 **migration rev = `a2b3c4d5e6f7`** (12-char lowercase hex, matches project convention). `down_revision = "f6a7b8c9d0e1"`.
- M4 **scope escalation plumbed:** `GET /api/auth/scm/github/authorize?scope=repo` — server-side allowlist `{public_repo, repo}` rejects other values. Default when param absent: `public_repo`.
- M5 **ON CONFLICT corrected:** single `ON CONFLICT (team_member_id, provider) DO UPDATE`. Second unique constraint `(team_id, provider, scm_user_id)` surfaces as an `IntegrityError` caught and mapped to 409 `SCM_IDENTITY_CLAIMED`.
- M6 **provider-side revoke auth specified:** `DELETE /applications/{client_id}/token` uses HTTP Basic `{client_id}:{client_secret}`, not the user token.
- m1 **log scrubbing corrected:** Python `logging.Filter` on `uvicorn.access` logger that redacts `code=` from msg; middleware does NOT work for access logs (runs after).

**Removed / simplified:**
- M1 (HMAC compare): state is Redis-key lookup; no comparison to protect. Removed the misleading reference.
- "Two deps" confusion: there's only one dep now (`require_admin`).

**New issues from round 2, addressed:**
- **Scope hint plumbing:** `?scope=` query param with allowlist (fixes M4 hand-wave AND open-redirect-to-escalation risk).
- **Redis namespace per env:** explicit in §Runbook: "different envs MUST use different Redis instances or the `scm_oauth:state:` prefix is env-specific."
- **`team_id` denormalization:** `team_members.team_id` is immutable (documented invariant; no current code path modifies it). Disclaimer added.
- **Scope superset logic:** `has_scope(stored, needed)` helper where `repo` implies `public_repo`. Spelled out.
- **Refresh-lock crash semantics:** documented — if lock-holder crashes after calling provider refresh but before DB UPDATE, next holder will get an "invalid refresh token" from provider (Bitbucket rotates single-use); user reconnects. Not worth a distributed transaction.

## Changelog v1 → v2

### Blockers fixed
- **B1**: Error envelope now follows `api/_shared.py` canonical shape: `{"error": {"code": "...", "message": "...", "details": [...]}}`. `connectUrl` is camelCase.
- **B2**: Replaced `require_admin` on authorize/connections endpoints with a new `require_authenticated_member` dep. Admin-only was wrong — any authenticated member manages their own SCM connection.
- **B3**: Callback added to `PUBLIC_PATHS` in `auth/middleware.py` (no session auth). State is the ONLY auth; must be 32-byte `secrets.token_urlsafe`, atomic `GETDEL` consumes, `hmac.compare_digest` for any comparison.
- **B4**: Migration rev ID specified: `g1a2b3c4d5e6_user_scm_connections`, `down_revision = "f6a7b8c9d0e1"`.
- **B5**: State deletion uses atomic `GETDEL` at lookup time — consumed regardless of downstream success, eliminating the replay window.

### Majors fixed
- **M1**: `hmac.compare_digest` for all state-related comparisons.
- **M2**: `RefreshOutcome` enum distinguishes `REFRESHED / NO_REFRESH_SUPPORTED / REFRESH_EXPIRED / REFRESH_REVOKED` for logging + metrics. UI surfaces all as "Reconnect required."
- **M3**: Redis lock `SET NX PX 10000` keyed on `(member_id, provider)` around refresh. Second concurrent caller waits up to 10s, then retries with refreshed token.
- **M4**: Start with `public_repo` scope. First private-repo approval returns 412 `SCM_SCOPE_UPGRADE_REQUIRED`; UI triggers reconnect with `repo` scope. (Trades +0.5 day build for cleaner security posture.)
- **M5**: Add `UNIQUE (team_id, provider, scm_user_id)` via denormalized `team_id` column on the connection row. Prevents cross-member identity collision within a team.
- **M6**: Disconnect calls `DELETE /applications/{client_id}/token` (GitHub) / equivalent (Bitbucket) to revoke at provider. Best-effort; ignore errors but log.
- **M7**: `AI_DRIVEN_SCM_TOKEN_KEY` loader supports two modes: (a) env var (dev), (b) AWS Secrets Manager via `AI_DRIVEN_SCM_TOKEN_KEY_SECRET_ARN` (prod). Lazy-load with 5-min cache. Key-loss SOP documented in runbook.

### Minor promoted to major
- **m1 → major**: OAuth callback carries `?code=` in URL, and uvicorn logs query params. Strip `code` from access logs via middleware, and use `SecretStr` for all token fields in Pydantic models.

### Minors addressed
- Logging sweep: `SecretStr` for tokens, Sentry scrubber for `access_token*` / `refresh_token*` / `code`, uvicorn middleware strips `code` param from callback access log.
- Cross-team scope: all connection queries filter by `team_member_id = :caller`.
- Tests added: (a) team_member deleted between authorize+callback → 400 not 500, (b) decryption under `MultiFernet` key rotation, (c) refresh lock contention, (d) 422 body doesn't leak code, (e) callback access log excludes code.
- Runbook section added (§14) covering OAuth App registration, key rotation, key loss, dev vs prod callback URLs.
- Bitbucket: one OAuth consumer per env (dev/staging/prod are separate consumers). Documented.

---

**Scope:** Per-user GitHub + Bitbucket OAuth so team_member actions against SCM are attributed to the human, not a shared bot token.
**Why:** Today a single `AI_DRIVEN_GITHUB_TOKEN` does everything — CodingAgent opens PRs, ReviewAgent comments, admin UI wants to Approve. GitHub's "no self-review" rule surfaces this as UX friction, but the real issues are attribution (audit logs all say "ai-driven bot"), scope (operators can't limit per-member PR powers), and revocation (single token = single blast radius).

## Non-goals (explicit)

- NOT replacing the bot token for CodingAgent / ReviewAgent in this phase. Bot continues to open/comment on PRs. User tokens are used only when the action is attributable to the human clicking the button.
- NOT migrating to a full GitHub App installation (scoped per-org) yet. OAuth App for v1; GitHub App is a follow-up when we have multi-tenant orgs.
- NOT building a "SCM accounts manager" for workspace admins to see who's connected what. `/dashboard/settings/connections` is first-person only.

---

## Threat model (abbreviated STRIDE)

| Threat | Mitigation |
|---|---|
| **S:** attacker impersonates our OAuth callback to steal tokens | state parameter + CSRF validation on callback |
| **T:** attacker tampers with stored access_token to escalate | tokens symmetrically encrypted at rest (Fernet); DB-only leak yields ciphertext |
| **R:** user denies they approved a PR | GitHub's own audit shows THEIR username (the whole point); we also log `task_override_audit`-style events on every connect/disconnect |
| **I:** token leaks in logs | never log the token; only log `scm_username` + `connection_id` |
| **D:** OAuth callback is a public surface — rate-limit abuse? | rate-limit on the callback endpoint (existing middleware) |
| **E:** user with no `repo` scope approves a PR they can't see | GitHub rejects with 403; we surface cleanly as 502 |

---

## DB schema

New table `user_scm_connections` — migration rev `a2b3c4d5e6f7`, `down_revision = "f6a7b8c9d0e1"`:

```sql
CREATE TABLE user_scm_connections (
    id                   UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    team_id              UUID NOT NULL REFERENCES teams(id) ON DELETE CASCADE,
    team_member_id       UUID NOT NULL REFERENCES team_members(id) ON DELETE CASCADE,
    provider             VARCHAR(20) NOT NULL,          -- 'github' | 'bitbucket'
    scm_user_id          VARCHAR(64) NOT NULL,          -- provider's numeric user id (stable across username changes)
    scm_username         VARCHAR(128) NOT NULL,         -- display-only; may drift if user renames on GitHub
    scm_email            VARCHAR(320),                  -- display-only; nullable
    access_token_enc     BYTEA NOT NULL,                -- Fernet-encrypted ciphertext
    refresh_token_enc    BYTEA,                         -- nullable
    scope                VARCHAR(255) NOT NULL,
    expires_at           TIMESTAMPTZ,                   -- null = non-expiring
    connected_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    last_used_at         TIMESTAMPTZ,
    last_refreshed_at    TIMESTAMPTZ,
    CONSTRAINT uq_member_provider      UNIQUE (team_member_id, provider),
    CONSTRAINT uq_team_provider_scmuser UNIQUE (team_id, provider, scm_user_id),
    CONSTRAINT ck_provider             CHECK (provider IN ('github', 'bitbucket'))
);
CREATE INDEX ix_user_scm_connections_member ON user_scm_connections(team_member_id);
CREATE INDEX ix_user_scm_connections_team   ON user_scm_connections(team_id);
```

Two uniqueness guards: `(team_member_id, provider)` — one connection per (member, provider); `(team_id, provider, scm_user_id)` — prevent two members on the same team both claiming the same GitHub identity. `team_id` is denormalized for the second constraint (could be a deferred computed column from `team_members.team_id`, but a direct column keeps migration + queries simpler).

## Token encryption

**Library:** `cryptography.fernet` (symmetric AES-128-CBC + HMAC-SHA256, transitive dep of existing project requirements).

**Key source — two modes:**

```python
def get_scm_token_key() -> MultiFernet:
    """Returns MultiFernet — first key encrypts new writes, all keys can decrypt."""
    settings = get_settings()
    if settings.scm_token_key_secret_arn:   # prod
        keys = _load_from_aws_secrets_manager(settings.scm_token_key_secret_arn)  # [current, prior]
    elif settings.scm_token_key:            # dev
        keys = [settings.scm_token_key] + settings.scm_token_key_prior_keys  # env-var list
    else:
        raise RuntimeError("No SCM token key configured")
    return MultiFernet([Fernet(k) for k in keys])

# Memoized with 5-min TTL so Secrets Manager isn't hit per request.
```

**Rotation:**
1. Generate new key; in Secrets Manager, set `current = new`, `prior = [old, ...]`.
2. Deploy — all decrypts still work (old key in `prior`).
3. Run `scripts/rotate_scm_tokens.py` — re-encrypts every row with the current key (reads via MultiFernet, writes via new).
4. Remove old key from `prior` list; next deploy drops decrypt capability.

**Key loss:** Cannot recover tokens. All users must disconnect + reconnect. Acceptable because OAuth flow is self-service; document in runbook.

**Key exposure:** If the key leaks, every stored token is compromised. Rotation is the remediation. OAuth tokens themselves are not further weakened — attacker with key + DB can impersonate users on GitHub until tokens are revoked provider-side. Response plan: rotate key AND call `DELETE /applications/{client_id}/token` for every affected row (revokes provider-side).

## OAuth flow (GitHub)

We register an OAuth App on github.com once, get `CLIENT_ID` + `CLIENT_SECRET`. Redirect URI: `https://<your-domain>/api/auth/scm/github/callback`.

```
Step 1 — user clicks Connect:
  UI → GET /api/auth/scm/github/authorize?scope=public_repo    (default)
       or  /api/auth/scm/github/authorize?scope=repo           (upgrade after 412)
  Backend:
    - require_admin
    - scope must be in ALLOWED_SCOPES = {"public_repo", "repo"} — else 400
    - state = secrets.token_urlsafe(32)
    - Redis: SET scm_oauth:state:<state> {"memberId":..., "provider":"github", "teamId":..., "requestedScope":"<scope>"} EX 600
    - 302 → https://github.com/login/oauth/authorize?
        client_id=...&redirect_uri=...&scope=<scope>%20read:user&state=<state>

Step 2 — provider redirects to callback (no auth; in PUBLIC_PATHS):
  GET /api/auth/scm/github/callback?code=...&state=...
  Backend:
    - raw = redis.getdel("scm_oauth:state:" + state)   # atomic consume
    - if raw is None: 400 {"error":{"code":"OAUTH_INVALID_STATE", "message":"...","details":[]}}
    - data = json.loads(raw)
    - POST https://github.com/login/oauth/access_token (form) {code, client_id, client_secret}
    - parse access_token + granted scope
    - GET api.github.com/user → scm_user_id, scm_username, scm_email
    - encrypt access_token via Fernet; compute expires_at (None for GitHub OAuth App)
    - INSERT INTO user_scm_connections (...) VALUES (...)
      ON CONFLICT (team_member_id, provider) DO UPDATE SET
        scm_user_id = EXCLUDED.scm_user_id,
        scm_username = EXCLUDED.scm_username,
        scm_email = EXCLUDED.scm_email,
        access_token_enc = EXCLUDED.access_token_enc,
        refresh_token_enc = EXCLUDED.refresh_token_enc,
        scope = EXCLUDED.scope,
        expires_at = EXCLUDED.expires_at,
        connected_at = now()
    - On IntegrityError for uq_team_provider_scmuser (second constraint):
        409 {"error":{"code":"SCM_IDENTITY_CLAIMED", "message":"Another member of your team is already connected as this GitHub user","details":[{"scmUsername":"<name>"}]}}
    - 302 → /dashboard/settings/connections?connected=github
  Access log: uvicorn.access logger has a Filter that redacts `code=<value>` → `code=<redacted>` before emit.
  Tokens: SecretStr in Pydantic models; never logged by our code.

Step 3 — user clicks Approve in UI:
  POST /api/admin/reviews/{id}/approve
  Backend:
    - require_admin
    - conn = load_connection(team_member_id=member.member_id, provider=repo.scm_type)
    - if conn is None:
        412 JSONResponse({"error":{"code":"SCM_NOT_CONNECTED","message":"...","details":[{"provider":"github","connectUrl":"/api/auth/scm/github/authorize"}]}})
    - if conn.expired and conn.refresh_token_enc is not None:
        acquired = redis.set("scm_refresh_lock:" + member_id + ":github", "1", nx=True, px=10000)
        if acquired:
          try:
            outcome = await refresh(conn)   # returns REFRESHED | REFRESH_EXPIRED | REFRESH_REVOKED
            if outcome is not REFRESHED:
              412 {"error":{"code":"SCM_RECONNECT_REQUIRED", ...}}
          finally:
            redis.delete(lock_key)
        else:
          # another caller is refreshing; wait up to 10s and re-read
          await wait_and_reload(conn, timeout=10)
    - if conn.expired and conn.refresh_token_enc is None:
        412 SCM_RECONNECT_REQUIRED
    - if not has_scope(conn.scope, needed="repo") and repo_is_private(repo_full_name):
        412 {"error":{"code":"SCM_SCOPE_UPGRADE_REQUIRED","message":"...","details":[{"currentScope":conn.scope,"scopeNeeded":"repo","connectUrl":"/api/auth/scm/github/authorize?scope=repo"}]}}
        # has_scope("repo ...", "repo") = True        (has scope directly)
        # has_scope("repo ...", "public_repo") = True (superset)
        # has_scope("public_repo ...", "repo") = False
    - token = decrypt(conn.access_token_enc)
    - scm = get_scm_client(scm_type, {"token": token})
    - await scm.approve_pr(...)
    - UPDATE user_scm_connections SET last_used_at = now() WHERE id = conn.id
```

**Refresh-lock crash semantics:** if lock-holder crashes after provider returned a new refresh_token but before we `UPDATE` the row, the lock TTLs out in 10s. Next caller reads the old (now-invalid) refresh_token from DB, calls provider, gets REFRESH_REVOKED, returns 412 → user reconnects. Partial-state is resolved by forcing reconnect; we don't implement distributed transactions.

**PUBLIC_PATHS additions** (in `backend/src/ai_driven/auth/middleware.py`):
```python
PUBLIC_PATHS = {
    ...,   # existing
    "/api/auth/scm/github/callback",
    "/api/auth/scm/bitbucket/callback",
}
```
(Literal entries — `startswith` matching, no regex. Authorize stays protected by `require_admin` as a normal route.)

## Bitbucket flow

Same shape. Differences:
- Bitbucket uses OAuth 2.0 with refresh tokens baked in (tokens expire in 2h).
- Scopes: `pullrequest:write account` for PR approve + read user.
- Endpoint: `https://bitbucket.org/site/oauth2/authorize` / `.../access_token`.
- User info: `https://api.bitbucket.org/2.0/user` returns `{uuid, username, nickname}`.

## API endpoints (new)

| Method | Path | Auth | Purpose |
|---|---|---|---|
| `GET` | `/api/auth/scm/{provider}/authorize?scope=<public_repo\|repo>` | `require_admin` | Generate state, 302 to provider. Scope param validated against allowlist. |
| `GET` | `/api/auth/scm/{provider}/callback?code=&state=` | PUBLIC (state-only) | Atomic GETDEL state, exchange code for token, persist connection. |
| `GET` | `/api/admin/me/connections` | `require_admin` | List current member's connections (no tokens in response). |
| `DELETE` | `/api/admin/me/connections/{provider}` | `require_admin` | Revoke connection — calls provider's `DELETE /applications/{client_id}/token` with Basic `{client_id}:{client_secret}` auth (best-effort, log on fail), then DELETE from DB. |
| `POST` | `/api/admin/me/connections/{provider}/test` | `require_admin` | Verify token still works (GET /user); surface expired/revoked state. |

All but the callback are under `require_admin` (v1 scope: OAuth connections are admin-only). Callback is in `PUBLIC_PATHS` because the provider's redirect has no way to carry a session cookie.

## Review action endpoint refactor

Change `/api/admin/reviews/{id}/approve` and `/request-changes` from using `get_scm_client_from_settings(scm_type)` (bot token) to the user-token flow. Full flow is specified in Step 3 above. Error shape uses `JSONResponse` with the canonical envelope:

```python
from fastapi.responses import JSONResponse

conn = await load_connection(member_id=member.member_id, provider=scm_type)
if conn is None:
    return JSONResponse(
        status_code=412,
        content={"error": {
            "code": "SCM_NOT_CONNECTED",
            "message": f"Connect your {scm_type} account to use this action",
            "details": [{
                "provider": scm_type,
                "connectUrl": f"/api/auth/scm/{scm_type}/authorize",
            }],
        }},
    )

if conn.expired:
    outcome = await try_refresh_with_lock(conn)    # see Step 3 for the lock flow
    if outcome is not RefreshOutcome.REFRESHED:
        return JSONResponse(
            status_code=412,
            content={"error": {
                "code": "SCM_RECONNECT_REQUIRED",
                "message": "Your SCM token has expired and cannot be refreshed",
                "details": [{
                    "provider": scm_type,
                    "reason": outcome.value,    # NO_REFRESH_SUPPORTED | REFRESH_EXPIRED | REFRESH_REVOKED
                    "connectUrl": f"/api/auth/scm/{scm_type}/authorize",
                }],
            }},
        )

if not has_scope(conn.scope, "repo") and await repo_is_private(repo_full_name, conn):
    return JSONResponse(
        status_code=412,
        content={"error": {
            "code": "SCM_SCOPE_UPGRADE_REQUIRED",
            "message": "Private-repo action requires 'repo' scope — please reconnect",
            "details": [{
                "provider": scm_type,
                "currentScope": conn.scope,
                "scopeNeeded": "repo",
                "connectUrl": f"/api/auth/scm/{scm_type}/authorize?scope=repo",
            }],
        }},
    )

token = decrypt(conn.access_token_enc)            # returns SecretStr internally
scm = get_scm_client(scm_type, {"token": token.get_secret_value()})
try:
    await scm.approve_pr(repo_full_name, pr_number, comment)
finally:
    await scm.close()
await mark_used(conn.id)
return ReviewActionResult(id=str(review.id), status="approved")
```

Precondition-failed (412) is the right code: "you provided valid auth, but a per-user SCM-connection precondition isn't met."

## UI (Settings > Connections)

`/dashboard/settings/connections` page, two cards (GitHub + Bitbucket), each showing:
- **Disconnected state**: "Connect your GitHub account" + [Connect] button → opens `/api/auth/scm/github/authorize` in new window/redirect
- **Connected state**: `@<scm_username>` + "Connected <date>" + [Test] [Disconnect] buttons
- **Expired state**: "⚠ Reconnect required" + [Reconnect] button

Approve/Request-Changes buttons on the Reviews page get a new error-handling branch: on 412 with `SCM_NOT_CONNECTED`, pop a toast "Connect your GitHub account to approve" with a link to `/dashboard/settings/connections`.

## Fallback semantics

When user is NOT connected, we do NOT silently fall back to the bot token — that defeats the entire attribution point. Instead:
- Review actions return 412 with `SCM_NOT_CONNECTED`.
- UI surfaces a Connect CTA.
- Once connected, future actions work.

The bot token (`AI_DRIVEN_GITHUB_TOKEN`) is still used for CodingAgent/ReviewAgent operations (opening PRs, posting AI review comments). Only human-initiated actions require a user token.

## Failure modes

| Scenario | Behavior |
|---|---|
| User clicks Approve without being connected | 412 → UI shows Connect CTA |
| User's token expires, refresh_token present | Silent refresh; retry action once |
| User's token expired, no refresh_token (GitHub OAuth App) | 412 `SCM_RECONNECT_REQUIRED` → UI shows Reconnect CTA |
| User revoked the app from github.com/settings/applications | Next action: SCM returns 401 → we mark connection expired, return 412 |
| GitHub API down | 502 with underlying error (existing pattern) |
| Callback state missing/expired | 400 "Invalid or expired authorization request" |
| Callback code exchange fails | 502 "GitHub rejected the authorization code" |
| User connects account, then their team_member row deleted | CASCADE deletes the connection row. No orphans. |

## Tests

- Unit: token encryption round-trip, state-generation uniqueness, expired-token detection, SCM client factory with per-user creds.
- Integration: full OAuth callback flow with mocked GitHub token endpoint; approve endpoint with connection present → SCM called with user token (verify via mock inspection); approve endpoint without connection → 412.
- Security probe (CI): callback rejects state mismatch, callback rejects replay (same state reused), expired state rejected, cross-team state rejected.

## Rollout

1. Ship DB migration + endpoints in shadow mode (no UI yet) — verify OAuth flow via curl + browser manual test.
2. Ship Settings UI — operator can connect but nothing uses it yet.
3. Flip review endpoints to require user connection — 412 becomes visible in UI for anyone not yet connected.

Three separate PRs, three deploys. The alternative (one-shot rollout) would flip the review buttons to 412 for every user the moment it merges, which is a bad UX even if it's a 10-minute window.

## Commit sequence (within each PR, not across)

**PR 1 — OAuth plumbing:**
1. `feat(db): user_scm_connections table + migration`
2. `feat(auth): Fernet token encryption helpers`
3. `feat(auth): SCM OAuth authorize + callback endpoints (GitHub)`
4. `feat(auth): SCM OAuth authorize + callback endpoints (Bitbucket)`
5. `feat(admin): /me/connections list + test + delete endpoints`
6. `test: OAuth flow coverage + encryption round-trip + CSRF probe`

**PR 2 — UI:**
1. `feat(ui): /dashboard/settings/connections page + API client`
2. `feat(ui): connection state badges + Connect/Reconnect/Disconnect buttons`
3. `test(ui): connection page component tests`

**PR 3 — Wire up review actions:**
1. `feat(admin): review actions use caller's SCM connection (412 on missing)`
2. `feat(ui): handle 412 from review actions with Connect CTA toast`
3. `docs: findings-final.md — strike through the self-review bug with OAuth fix`

## Red-team targets (what I want challenged before build)

1. **Token encryption key management.** Is Fernet + env var + rotation plan enough, or should we require AWS KMS for prod? Trade-off: KMS is the right answer for prod but adds dev-loop friction.
2. **State storage in Redis.** If Redis is down during OAuth callback, user gets 400. Alternative: encode state as a signed JWT so it's self-contained (no Redis round-trip). Trade-off: JWT adds a signing key to manage.
3. **Scope choices.** `repo` gives full private-repo write. Do we want to start narrower (`public_repo`) and escalate? GitHub App solves this properly; OAuth App doesn't.
4. **Fallback policy.** "Never fall back to bot token" is strict — is there a case where a team explicitly wants "approve as bot" for compliance reasons? If yes, make it a per-team setting.
5. **Cross-team token reuse.** If the same human is in Team A and Team B on this platform, and has one GitHub account, do they reconnect per team or once globally? Connection is keyed to `team_member_id` which is per-team; same human in two teams connects twice. That's probably correct (audit trail per team) but worth explicit.
6. **Callback URL in dev vs prod.** OAuth Apps have fixed redirect URIs. How do we support `localhost` for dev and prod HTTPS from the same OAuth App? Answer: register multiple callback URLs on the app (GitHub supports comma-separated).
7. **What if CodingAgent should eventually also use user token?** Today's scope says no. But if a user opens a ticket via chat and the bot opens a PR on their behalf, GitHub shows "opened by ai-driven bot" not them. Is that fine forever, or future migration? Out of scope for v1 but flag.

## Helpers

```python
ALLOWED_SCOPES = {"public_repo", "repo"}

def has_scope(stored: str, needed: str) -> bool:
    """GitHub scope superset logic. stored is space-separated.
      has_scope("repo read:user", "repo") → True
      has_scope("repo read:user", "public_repo") → True     (repo implies public_repo)
      has_scope("public_repo read:user", "repo") → False
    """
    granted = set(stored.split())
    if needed in granted:
        return True
    if needed == "public_repo" and "repo" in granted:
        return True
    return False
```

## Log scrubbing

Not a middleware — middleware runs AFTER uvicorn's access log. Use a `logging.Filter`:

```python
# backend/src/ai_driven/observability/log_scrub.py
import logging, re
_CODE_PATTERN = re.compile(r"code=[A-Za-z0-9_.-]+")

class ScrubOAuthCodeFilter(logging.Filter):
    def filter(self, record: logging.LogRecord) -> bool:
        if isinstance(record.msg, str) and "code=" in record.msg:
            record.msg = _CODE_PATTERN.sub("code=<redacted>", record.msg)
        return True

# Attach at app startup:
# logging.getLogger("uvicorn.access").addFilter(ScrubOAuthCodeFilter())
```

Also `SecretStr` for every token field in Pydantic models + explicit Sentry scrub rule `["access_token", "refresh_token", "code"]`.

## Invariants

- **`team_members.team_id` is immutable.** Nothing in the current codebase modifies it after insert. The denormalized `user_scm_connections.team_id` therefore never goes stale. If a future change adds member-team-transfer, this brief's uniqueness guarantees break — document that constraint then.
- **Redis state namespace is env-specific.** Either envs use separate Redis instances (typical) OR the `scm_oauth:state:` key prefix is made env-aware via settings. State from dev cannot be consumed on prod.

## §14 — Runbook (docs/runbooks/07-scm-oauth.md, to be written during PR 1)

Operational procedures, draft:

1. **Register the GitHub OAuth App (one-time per env):**
   - github.com → Settings → Developer settings → OAuth Apps → New.
   - Callback URLs: `http://localhost:8888/api/auth/scm/github/callback` (dev) AND `https://<domain>/api/auth/scm/github/callback` (prod). GitHub supports comma-separated; Bitbucket does NOT — separate consumer per env.
   - Generate `client_secret`. Store in AWS Secrets Manager (prod) or `.env.local` (dev):
     - `AI_DRIVEN_GITHUB_OAUTH_CLIENT_ID`
     - `AI_DRIVEN_GITHUB_OAUTH_CLIENT_SECRET`
2. **Generate + stash token encryption key:**
   ```python
   from cryptography.fernet import Fernet
   print(Fernet.generate_key().decode())
   ```
   Prod: store in Secrets Manager, set `AI_DRIVEN_SCM_TOKEN_KEY_SECRET_ARN` on workers.
   Dev: paste into `backend/.env.local` as `AI_DRIVEN_SCM_TOKEN_KEY=...`.
3. **Rotate `AI_DRIVEN_SCM_TOKEN_KEY`:** see §Token encryption — Rotation steps.
4. **Key lost or `.env.local` wiped in dev:** no recovery possible. Delete all `user_scm_connections` rows, generate a new key, have each user reconnect. The tokens themselves are still valid on GitHub until the user revokes on github.com/settings/applications, but we can't use them anymore.
5. **User reports "approve gives 412":** verify they're connected (`GET /api/admin/me/connections`); if yes, check token expiry; if yes, have them reconnect. If refresh-lock contention is suspected, check Redis keys `scm_refresh_lock:*`.
6. **Bulk-revoke all tokens (incident response):** `scripts/revoke_all_scm_tokens.py` iterates `user_scm_connections`, calls `DELETE /applications/{client_id}/token` for each, then TRUNCATE the table. Users reconnect.

## Open questions (post-red-team)

1. **Should CodingAgent ever use user token?** Out of scope for v1. Worth tracking — today CodingAgent opens PRs as the bot. A future phase might open PRs as the requesting human, which would eliminate the self-review problem we started from. Flag: if that's where we want to end up, this current architecture is a stepping stone, not the destination.
2. **Migration rollback:** downgrade drops the table. All stored connections lost. Users reconnect on next upgrade. Acceptable; documented.
