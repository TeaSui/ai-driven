# PR 2 — Settings > Connections UI (brief, v1)

**Author:** 2026-05-03
**Parent plan:** `docs/plans/2026-05-03-scm-oauth-connections.md` (v3)
**Scope:** Ship the frontend Settings shell + Connections page. No backend changes. Uses PR 1's endpoints.

## User-locked decisions (from chat)

1. **Route shape:** A — dedicated `/dashboard/settings` with sub-nav.
2. **Upgrade to repo button:** Yes — show when connection has `public_repo` scope only.
3. **Sidebar entry:** Keep the existing gear icon at the bottom of the sidebar; rewire it from `alert('coming soon')` to a real `<Link href="/dashboard/settings">`.
4. **Disconnect confirm:** Light — a confirm toast/inline prompt with a single "Yes, disconnect" button, NOT a blocking modal.

## File structure

```
frontend/src/app/dashboard/settings/
├── layout.tsx                 Settings shell — sub-nav on left, pane content on right
├── page.tsx                   Redirects to /dashboard/settings/connections
└── connections/
    └── page.tsx               The Connections pane

frontend/src/components/settings/
├── settings-nav.tsx           Left sub-nav (Connections today; future: API Keys, Team)
└── connection-card.tsx        Reusable: renders one provider's state + buttons
```

## Data flow

### Fetching
```typescript
// In connections/page.tsx
const { data: connections, mutate, isLoading } = useSWR<Connection[]>(
  "me/connections",
  () => getMyConnections(),
  {
    refreshInterval: 0,
    revalidateOnFocus: true,  // reconnect after user returns from github.com
  }
);
```

### Connection list shape (matches PR 1 response)
```typescript
interface Connection {
  id: string;
  provider: "github" | "bitbucket";
  scmUsername: string;
  scmEmail: string | null;
  scope: string;                  // "public_repo read:user" or "repo read:user" etc.
  expiresAt: string | null;       // ISO; null = non-expiring (GitHub OAuth App)
  connectedAt: string;
  lastUsedAt: string | null;
}
```

### Actions

```typescript
// Connect — full navigation (302 chain needs it, fetch won't follow)
function connectGithub(scope: "public_repo" | "repo" = "public_repo") {
  window.location.href = `/api/auth/scm/github/authorize?scope=${scope}`;
}

// Disconnect — camelCase envelope; error bubbles for toast
async function disconnect(provider: string) {
  const res = await authFetch(`/api/admin/me/connections/${provider}`, { method: "DELETE" });
  if (!res.ok) throw new Error(await readErrorMessage(res));
  return mutate("me/connections");
}

// Test — soft affordance; result rendered in a toast
async function testConnection(provider: string) {
  const res = await authFetch(`/api/admin/me/connections/${provider}/test`, { method: "POST" });
  const json = await res.json();
  return json.data;   // { ok: boolean, scmUsername?, scmEmail?, reason? }
}
```

### Post-callback handling

User returns from `github.com` to `/dashboard/settings/connections?connected=github` (set by PR 1's callback 302). The page:

1. On mount: read `?connected=<provider>` + `?error=<code>` from URL.
2. If `?connected=...`: show green toast "GitHub connected as @{scmUsername}"; `mutate("me/connections")` to refresh.
3. If `?error=...`: show red toast with human-readable error (map known codes, fallback to "Connection failed. Please try again.").
4. `router.replace("/dashboard/settings/connections")` to strip the query string so a refresh doesn't re-toast.

Known error codes to map (from PR 1):
- `OAUTH_INVALID_STATE` → "Session expired — please try again"
- `OAUTH_TOKEN_EXCHANGE_FAILED` → "GitHub rejected the authorization code"
- `SCM_IDENTITY_CLAIMED` → "Another member of your team is already connected as @{scmUsername}"
- `OAUTH_NOT_CONFIGURED` → "SCM OAuth not configured. Ask your workspace owner to set up the OAuth App."

## State machine per card

```
                 ┌─────────────────┐
                 │  Disconnected   │
                 │  [Connect]      │
                 └────────┬────────┘
                          │ user completes OAuth
                          ▼
                 ┌─────────────────┐
     ┌───────────│   Healthy       │───────────┐
     │           │  [Test]         │           │
     │           │  [Disconnect]   │           │
     │           │  [Upgrade]*     │           │
     │           └────────┬────────┘           │
     │ test fails /       │ disconnect         │ scope includes 'repo'
     │ token revoked      │ confirmed          │ → hide Upgrade
     ▼                    ▼
┌─────────────────┐  (back to Disconnected)
│  Expired /      │
│  Revoked        │
│  [Reconnect]    │
│  [Disconnect]   │
└─────────────────┘
```

* Upgrade button visible only when connection scope === `public_repo` (doesn't include `repo`).

State classification (client-side):
- `Disconnected` — no row for this provider in the list.
- `Expired` — `expiresAt` is set AND `expiresAt < now`.
- `Revoked` — the most recent `POST /test` returned `{ok: false, reason: "revoked"}`. Cached per session in local state; cleared on `mutate`.
- `Healthy` — everything else.

## Component contracts

### `<ConnectionCard>`
```typescript
interface ConnectionCardProps {
  provider: "github" | "bitbucket";
  connection: Connection | null;       // null = Disconnected state
  onConnect: (scope: "public_repo" | "repo") => void;
  onTest: () => Promise<TestResult>;
  onDisconnect: () => Promise<void>;
  disabled?: boolean;                  // during mutation
}
```
- Renders provider logo (GitHub octocat / Bitbucket atlassian mark), name, state-appropriate content + buttons.
- Owns its loading/error transitions (Test spinner, Disconnect inline-confirm).
- Emits no global state; parent handles toast notifications.

### `<SettingsNav>`
Static for PR 2 — single item "Connections" highlighted on this route. Structure prepared for future items (API Keys, Team) without building their pages.

## Wire the sidebar gear icon

Currently (per finding 2.6) the gear icon at bottom of `components/layout/sidebar.tsx` has `onClick={() => alert('Settings page coming soon.')}`. Replace with:
```tsx
<Link href="/dashboard/settings" className={...}>
  <Settings size={15} /> Settings
</Link>
```

Remove the `alert` call entirely. Update `findings-final.md` §2.6 evidence path (was marked ✅ already; re-verify the alert is really gone in current code — if a stub remains, strike it now).

## Tests

**Component tests** (TS / React Testing Library):
- `ConnectionCard` in each state renders correct buttons.
- Disconnected state shows only [Connect].
- Healthy state with `public_repo` scope shows [Upgrade to repo] + [Test] + [Disconnect].
- Healthy state with `repo` scope hides [Upgrade to repo].
- Expired state shows [Reconnect] not [Connect].

**Integration (Playwright or equivalent, if the frontend test harness supports it):**
- Visiting `/dashboard/settings` redirects to `/connections`.
- With mocked `GET /me/connections` returning `[]`, both cards show Disconnected.
- Clicking Connect navigates to `/api/auth/scm/github/authorize?scope=public_repo`.
- URL `?connected=github` on mount triggers success toast + mutate; query string is cleared.
- URL `?error=SCM_IDENTITY_CLAIMED` on mount shows the matching red toast.
- Disconnect flow: click → inline confirm → click confirm → mutate fires → card flips.

Note from PR 1's summary: frontend test infra is Playwright E2E only (no jest/vitest). If component tests require vitest setup, defer them with a TODO note and ship Playwright integration tests. Match PR 1's decision to not block on missing unit test infra.

## Accessibility

- Keyboard: Tab order Connect → Test → Disconnect within each card; Enter activates.
- Screen readers: Card's `aria-label` describes state ("GitHub — Connected as @user" / "GitHub — Not connected").
- Toast uses `role="status"` for success, `role="alert"` for errors.
- Focus management: after disconnect confirm fires, focus returns to the Connect button.

## Out of scope

- API key management UI (Tier-2 E).
- Team switcher (Tier-2 E).
- Notifications preferences.
- Operator-level OAuth App configuration UI (env / Secrets Manager only).
- Any per-team overrides (Phase 3).

## Commit plan

One atomic commit — this is tightly-coupled UI work with no bisect value in splitting:

```
feat(settings): Connections page + sidebar wire-up + shell

- /dashboard/settings shell with sub-nav (Connections today)
- /dashboard/settings/connections page with GitHub + Bitbucket cards
- <ConnectionCard> component (all 4 states: disconnected, healthy,
  expired, revoked)
- Sidebar gear icon → real <Link> (closes finding 2.6)
- lib/api.ts: getMyConnections, disconnectConnection, testConnection
- Callback-return toast + query-string cleanup
```

Same rationale as the `file.write_batch` commit — one logical unit, one commit.

## Rollout

Ships as-is. Until PR 3, clicking a connection Card has no functional effect on approve/request-changes buttons (they still use the bot token). PR 2 just exposes the Settings surface so users CAN connect; PR 3 makes the connection actually matter.

## Risks

1. **`?error=` toast spamming.** If the OAuth callback 302s back with an error and the user refreshes, URL still has `?error=...` and the toast re-fires. Mitigation: `router.replace` before showing toast.
2. **SWR cache key collision.** `"me/connections"` is unique; no collision with existing keys (checked).
3. **Sub-nav growing in place.** Adding "API Keys" / "Team" later means a sidebar redesign if we only ever have 1-2 items. Accept — it's cheap to add later.
4. **GitHub vs Bitbucket visual differentiation.** Two near-identical cards may confuse. Clear provider logo + color accent (GitHub black, Bitbucket blue) mitigates.

## Open questions for red-team

1. **Should Test auto-fire on page load?** Pro: users see accurate "Healthy" / "Revoked" without clicking. Con: doubles GitHub API hits per page view. My call: no; rely on the "use" path (PR 3's approve) to surface revocation, or user clicks Test explicitly.
2. **Do we surface the scope superset visually?** The badge "public + private repos" when stored scope is `repo` is a UX choice. If users think `repo` means ONLY private, they'll get confused. Use text "full repository access" vs "public repositories only" — clearer.
3. **Confirm dialog pattern** — I said "inline confirm toast" in the user-locked decisions. Concretely: click Disconnect → button transforms into "Click again to confirm" for 3 seconds → second click fires DELETE. That's lighter than a modal and matches GitHub's own UI pattern. OK?
