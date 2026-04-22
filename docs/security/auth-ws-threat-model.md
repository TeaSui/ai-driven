---
title: Auth & WebSocket Security — Threat Model
date: 2026-04-22
status: review
spec: docs/implementation/08-auth-and-ws-security.md
---

# Threat Model: Auth & WebSocket Security

Review of `08-auth-and-ws-security.md` against STRIDE. Each section assesses the spec's proposed mitigations, flags gaps, and provides implementation rules.

---

## STRIDE Analysis

### S — Spoofing

**Threat:** Attacker impersonates a legitimate user or team to access tasks, events, or admin endpoints.

**Spec mitigations:**
- Cognito JWT with `team_id` claim (Phase 2)
- `require_task_access(task_id)` dependency checks `task.team_id == user.team_id` (Phase 1)
- WebSocket `auth_from_ws()` validates token before subscribing (Phase 1)
- Next.js `middleware.ts` validates JWT on every `/dashboard/*` route (Phase 3)

**Assessment:** Solid. The spec covers the critical paths.

**Gaps:**
1. **JWT signature verification not specified.** The spec mentions `verifyJwt(token)` but doesn't specify: use Cognito's JWKS endpoint, cache keys with TTL, verify `iss`, `aud`, and `exp` claims.
2. **API key auth path for webhooks is underspecified.** Jira/DataDog/GitHub webhooks use API keys, but there's no signing verification for webhook payloads (e.g., GitHub uses HMAC-SHA256 on the body).
3. **Token via WebSocket query param** leaks the token in server access logs and browser history. Prefer first-message auth pattern.

**Rules:**
- **RULE-S1:** JWT verification MUST validate `iss` (Cognito issuer URL), `aud` (app client ID), `exp`, and signature against cached JWKS. Do not accept unsigned or `alg: none` tokens.
- **RULE-S2:** WebSocket auth MUST use the first-message pattern (`{"type": "auth", "token": "..."}`) NOT query params. Close with 4401 if first message is not auth within 5 seconds.
- **RULE-S3:** Webhook endpoints (Jira, GitHub, DataDog, Slack) MUST verify payload signatures using the provider's signing secret. The Slack webhook already does this (`slack.py:_verify_slack_signature`); replicate for all.

---

### T — Tampering

**Threat:** Attacker modifies WebSocket messages, task data, or API requests to alter system behavior.

**Spec mitigations:**
- Team-scoped access on every resource read (Phase 1 step 4)
- `require_task_access` returns 404 (not 403) to prevent existence leaks

**Assessment:** Adequate for read paths. Write paths need attention.

**Gaps:**
1. **No input validation on WebSocket client messages.** The current handler reads messages from the client (ping/control) without schema validation.
2. **`POST /api/chat/send` doesn't validate `context` dict.** Arbitrary keys could be injected.
3. **Task control endpoints** (`pause`, `resume`, `stop`) lack team ownership check — any authenticated user could stop another team's task.

**Rules:**
- **RULE-T1:** All task mutation endpoints (`pause`, `resume`, `stop`, `send`) MUST use `require_task_access` dependency.
- **RULE-T2:** WebSocket client messages MUST be validated against a strict schema. Unknown message types are silently dropped.
- **RULE-T3:** `SendMessageRequest.context` values MUST be string-only, max 1KB per key, max 10 keys.

---

### R — Repudiation

**Threat:** A user performs a destructive action (stop task, merge PR, create incident) and denies it.

**Spec mitigations:** Not explicitly addressed.

**Gaps:**
1. **No audit log for auth events** (login, token refresh, key creation/rotation).
2. **No audit log for destructive actions** (task stop, PR merge approval, incident creation).
3. **API key usage not logged** — can't attribute webhook-triggered actions to a specific key.

**Rules:**
- **RULE-R1:** Log every auth event to a dedicated `audit_log` table: `{timestamp, user_id, team_id, action, resource_type, resource_id, ip, user_agent}`.
- **RULE-R2:** Destructive actions (`stop`, `pause`, `merge`, `approve`, API key rotate/revoke) MUST write an audit entry before executing.
- **RULE-R3:** API key usage MUST log the key prefix (first 8 chars) for attribution without exposing the full key.

---

### I — Information Disclosure

**Threat:** Sensitive data leaks across team boundaries or to unauthorized parties.

**Spec mitigations:**
- 404 instead of 403 to prevent resource enumeration (Phase 1)
- Team-scoped joins on all queries (Phase 1 step 4)
- WebSocket closes with 4404 on team mismatch (Phase 1 step 3)

**Assessment:** Good defensive posture. Needs hardening in specific areas.

**Gaps:**
1. **Redis pub/sub channels are not scoped.** `task:{task_id}:events` — if an attacker can connect to Redis directly, they see all events. This is acceptable if Redis is network-isolated, but should be documented as an assumption.
2. **Error messages in API responses** may leak internal details (stack traces, SQL errors). The spec doesn't mention error sanitization.
3. **Token in WebSocket close reason** — don't include auth details in close frames.
4. **Agent events may contain source code.** `streaming_chunk` and `text_delta` events include Claude's response which may contain code from private repos. The team-scoping on WebSocket is critical here.

**Rules:**
- **RULE-I1:** Redis MUST be network-isolated (VPC private subnet, no public endpoint). Document as deployment prerequisite.
- **RULE-I2:** FastAPI exception handlers MUST sanitize errors. Return `{"error": {"code": "...", "message": "..."}}` — never stack traces, SQL, or internal paths.
- **RULE-I3:** WebSocket close frames MUST use only numeric codes (4401, 4403, 4404). No text payloads containing tokens or user info.
- **RULE-I4:** Log sinks (DataDog, PostgreSQL) MUST mask PII: email → `u***@domain`, API keys → first 8 chars only.

---

### D — Denial of Service

**Threat:** Attacker exhausts resources via connection flooding, large payloads, or expensive operations.

**Spec mitigations:** Not explicitly addressed.

**Gaps:**
1. **No rate limiting** on any endpoint. `POST /api/chat/send` triggers an expensive Claude API call — an attacker with valid creds can exhaust the budget.
2. **No WebSocket connection limit** per user or team.
3. **No max message size** on WebSocket client messages.
4. **No per-team concurrent task limit.** An attacker could queue 1000 tasks.

**Rules:**
- **RULE-D1:** Rate limit `POST /api/chat/send` to 10 req/min per user, 30 req/min per team.
- **RULE-D2:** WebSocket connections limited to 5 per user, 20 per team. New connections above limit get 4429 close code.
- **RULE-D3:** WebSocket client message max size: 4KB. Disconnect on oversized message.
- **RULE-D4:** Per-team concurrent task limit: 10 (configurable). Reject with 429 when exceeded.
- **RULE-D5:** Daily budget hard-stop (from `02-production-readiness.md`) MUST be checked at dispatch time, not after the task starts.

---

### E — Elevation of Privilege

**Threat:** A member of Team A accesses Team B's resources, or a regular user performs admin actions.

**Spec mitigations:**
- `require_team_member` on all admin endpoints (Phase 1 step 4)
- `require_task_access` checks `task.team_id == user.team_id`
- Single role per team (member) — no RBAC day-1

**Assessment:** The single-role model is pragmatic for launch. The team boundary is the critical control.

**Gaps:**
1. **No explicit test for cross-tenant access.** The spec mentions acceptance criteria but doesn't mandate a permanent CI test.
2. **API key rotation "grace period 7 days"** — during grace, both old and new keys work. An attacker with a compromised old key has 7 days before lockout.
3. **`AI_DRIVEN_DEBUG=true` bypasses all auth.** The spec adds a banner but doesn't add a safeguard to prevent debug mode in production environments.

**Rules:**
- **RULE-E1:** Cross-tenant probe test MUST run in CI on every PR: `teamA` token hitting `teamB` task/repo/incident IDs must return 404.
- **RULE-E2:** API key grace period: 48 hours max (not 7 days). Old key logs a deprecation warning on each use during grace.
- **RULE-E3:** `AI_DRIVEN_DEBUG` MUST be blocked in production: check `ENV` / `DEPLOYMENT_STAGE` env var. If `production` and `debug=true`, log CRITICAL alert and refuse to start.
- **RULE-E4:** Future RBAC: plan for `admin` (manage keys, repos, team settings) and `member` (run tasks, view results). Design the claim structure now even if enforcement is day-2.

---

## Implementation Rules Summary

| ID | Rule | Priority |
|----|------|----------|
| S1 | JWT: validate iss, aud, exp, signature via JWKS | P1 |
| S2 | WebSocket: first-message auth, not query param | P1 |
| S3 | Webhooks: verify payload signatures (all providers) | P1 |
| T1 | Task mutations require team ownership check | P1 |
| T2 | WebSocket client messages: strict schema validation | P2 |
| T3 | SendMessage context: string values, 1KB max, 10 keys max | P2 |
| R1 | Audit log table for auth events | P2 |
| R2 | Audit log for destructive actions | P2 |
| R3 | API key usage: log key prefix for attribution | P2 |
| I1 | Redis: network-isolated, document as deployment prerequisite | P1 |
| I2 | Sanitize all API error responses | P1 |
| I3 | WebSocket close: numeric codes only, no text payloads | P2 |
| I4 | PII masking in all log sinks | P1 |
| D1 | Rate limit chat/send: 10/min user, 30/min team | P1 |
| D2 | WebSocket connection limit: 5/user, 20/team | P2 |
| D3 | WebSocket message max: 4KB | P2 |
| D4 | Concurrent task limit: 10/team | P1 |
| D5 | Budget hard-stop at dispatch time | P1 |
| E1 | Cross-tenant probe in CI | P1 |
| E2 | API key grace: 48h max, log deprecation | P2 |
| E3 | Block debug mode in production env | P1 |
| E4 | Plan RBAC claim structure now | P3 |

## Recommendations

### Before implementation begins
1. Agree on JWT claim schema: `{sub, email, teamId, role, iat, exp}`. Add `teamId` as Cognito custom attribute.
2. Create the `audit_log` table schema alongside the auth work — retrofitting is expensive.
3. Add `DEPLOYMENT_STAGE` env var to all environments now; block debug in `production`.

### During implementation
4. Implement Phase 1 (backend hardening) as a single PR with the cross-tenant CI test included.
5. Use `slowapi` or custom middleware for rate limiting — avoid per-endpoint decorators.
6. WebSocket first-message auth timeout: 5 seconds. After that, close with 4401.

### After implementation
7. Run a penetration test focused on: cross-tenant access, WebSocket auth bypass, rate limit bypass.
8. Schedule quarterly API key rotation review.
9. Add the cross-tenant probe to the production readiness checklist gate.

---

**Verdict: The spec is well-structured and covers the critical threats. 8 P1 gaps identified above must be addressed before implementation. No architectural changes needed — the gaps are additive rules, not design flaws.**
