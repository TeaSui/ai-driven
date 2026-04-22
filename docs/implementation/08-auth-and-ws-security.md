# Implementation Plan 08 — Auth, Session, Multi-Tenancy & WebSocket Authorization

**Owner:** Backend + Security + Frontend
**Severity:** P1 (security / cross-tenant leak) + P2 (no login UI)
**Estimated effort:** 3–5 days
**Blocks:** second customer onboarding, GA launch

> **Requires Security-engineer subagent review before merging.** Touches authentication, session management, and cross-tenant authorization — a trust boundary.

## Problem

1. **Cross-tenant data leak on WebSocket.** `src/ai_driven/api/chat/router.py:303-315` accepts any `task_id` and subscribes to `task:{task_id}:events` without checking the caller's team owns that task. Anyone with a task ID sees the stream.
2. **No login.** `AI_DRIVEN_DEBUG=true` is the current auth bypass. Production uses API keys per team (per `docs/architecture/01-database-er.md`), but there is no UI to create/rotate them and no login flow for humans.
3. **No `middleware.ts`** in the Next.js app — no central hook to enforce auth on route navigation.
4. **Hardcoded user** "Alex Chen · Platform · Manila" in `sidebar.tsx:335-344`.
5. **Debug mode invisible to operators.** If `AI_DRIVEN_DEBUG=true` ever reaches prod, nothing in the UI surfaces the warning.

## Target architecture

```
┌──────────────┐   SSO (OIDC)      ┌─────────────────┐
│ Browser/User │ ────────────────▶ │  Identity Provider │
└──────────────┘                   │  (Cognito / Auth0) │
      │ session cookie             └─────────────────┘
      ▼
┌─────────────────────────────┐
│ Next.js middleware.ts       │  — redirect unauthenticated → /login
│ - validates JWT             │  — injects {userId, teamId, role} into request headers
└─────────────────────────────┘
      │
      ▼
┌─────────────────────────────┐
│ FastAPI deps.py             │
│ - get_current_user          │  — verifies JWT / API key
│ - require_team_member       │  — enforces team scope
└─────────────────────────────┘
      │
      ▼
┌─────────────────────────────┐
│ Resource access             │  — every repo/task/incident read joins on team_id
└─────────────────────────────┘
```

## Implementation steps

### Phase 1 — Backend hardening (first, unblocks deploy) · ~1 day

1. **Add `team_id` to JWT claims** (issued at login).
2. **New FastAPI dependency** `require_task_access(task_id)`:
   ```python
   async def require_task_access(task_id: str, user: User = Depends(current_user)):
       task = await task_repo.get(task_id)
       if task is None or task.team_id != user.team_id:
           raise HTTPException(404)  # 404 not 403 — don't leak existence
       return task
   ```
3. **Guard the WebSocket.** Replace the permissive handler at `api/chat/router.py:303` with:
   ```python
   @router.websocket("/stream/{task_id}")
   async def stream(ws: WebSocket, task_id: str):
       await ws.accept()
       try:
           user = await auth_from_ws(ws)  # token via query param or first message
           task = await task_repo.get(task_id)
           if task is None or task.team_id != user.team_id:
               await ws.close(code=4404)
               return
           # ... existing pub/sub loop
   ```
4. **Guard every admin endpoint** with `require_team_member` — reviews, incidents, tasks, repos.
5. **Add `cancelled` terminal state** (replaces current `stop → failed` conflation at `api/chat/router.py:291-293`).

### Phase 2 — Identity provider · ~1 day

- Cognito User Pool per environment. Teams = Cognito groups with a `teamId` custom attribute.
- API-key issuance remains for webhooks/CI (Jira, DataDog, GitHub) — stored hashed in `team_api_keys` table, rotate every 90 days.

### Phase 3 — Next.js integration · ~1 day

1. `middleware.ts` at repo root:
   ```ts
   import { NextResponse, type NextRequest } from 'next/server'
   import { verifyJwt } from '@/lib/auth/jwt'

   export async function middleware(req: NextRequest) {
     if (req.nextUrl.pathname.startsWith('/dashboard')) {
       const token = req.cookies.get('session')?.value
       const user = token ? await verifyJwt(token) : null
       if (!user) return NextResponse.redirect(new URL('/login', req.url))
       const res = NextResponse.next()
       res.headers.set('x-user-id', user.sub)
       res.headers.set('x-team-id', user.teamId)
       return res
     }
   }
   export const config = { matcher: ['/dashboard/:path*'] }
   ```
2. `/login` page with Cognito Hosted UI redirect.
3. `SessionProvider` context consumed by `sidebar.tsx` (replace "Alex Chen").
4. Team switcher (if a user is in multiple teams).
5. `/dashboard/admin/api-keys` — issue / rotate / revoke API keys per team.

### Phase 4 — Operator visibility · ~½ day

- Extend `HealthResponse` in `api/health.py` with `debug: bool` sourced from `settings.debug`.
- `dashboard/layout.tsx`: fetch `/health` once on mount; if `debug: true`, render a red sticky banner: **"Debug mode active — authentication is bypassed. Do not use in production."**

## Acceptance

- [ ] A user with `teamA` JWT cannot fetch `/api/admin/tasks/{id}` for a task owned by `teamB` (404 returned).
- [ ] A WebSocket connection to `/api/chat/stream/{id}` with a mismatched token closes with code 4404 before any event is sent.
- [ ] Unauthenticated browser visits to `/dashboard/*` redirect to `/login`.
- [ ] `AI_DRIVEN_DEBUG=true` causes a banner to render.
- [ ] Stop button sets status to `cancelled`, not `failed`.
- [ ] `successRate` metric in `/api/admin/dashboard` excludes `cancelled` tasks.
- [ ] API-key rotation works without downtime (keys are additive; grace period 7 days).

## Rollback

- Feature flag `auth.enforce=false` leaves the new middleware in place but in log-only mode for 24 h, so ops can observe which requests would have been blocked before turning it on.

## Out of scope

- Fine-grained RBAC (admin vs viewer). Day-1 is **single role per team = member.** Promote to roles in a follow-up (`09-rbac.md`).
- SSO federation with the customer's IdP — backlog.
