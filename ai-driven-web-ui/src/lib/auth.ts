// Auth utilities — Sprint 14 session-token flow.
//
// The session token lives in an HttpOnly cookie set by POST /api/auth/login,
// so JavaScript cannot read it directly. Browsers auto-attach the cookie on
// same-origin fetches, which is exactly what we want for the Next.js app
// (frontend + API proxied through the same origin via next.config.js).
//
// Legacy: a few existing call sites still set a non-HttpOnly `api_key` cookie
// (mostly the pre-Sprint-14 login page + Playwright fixtures). `authFetch`
// falls through to sending `X-API-Key` from that cookie if present, so the
// rollout doesn't break existing sessions mid-flight.

const LEGACY_API_KEY_COOKIE = 'api_key'
const SESSION_COOKIE_NAME_HINT = 'session_token'  // reference only; cookie is HttpOnly

function readCookie(name: string): string | null {
  if (typeof document === 'undefined') return null
  const match = document.cookie.match(new RegExp(`(?:^|; )${name}=([^;]*)`))
  return match ? decodeURIComponent(match[1]) : null
}

/** Legacy accessor kept for existing call sites (`getApiKey`). Returns the
 *  legacy `api_key` cookie if present, or null. */
export function getApiKey(): string | null {
  return readCookie(LEGACY_API_KEY_COOKIE)
}

/** Legacy setter kept so the pre-Sprint-14 flow still works during rollout. */
export function setApiKey(key: string): void {
  const secure = window.location.protocol === 'https:' ? '; Secure' : ''
  document.cookie = `${LEGACY_API_KEY_COOKIE}=${encodeURIComponent(key)}; path=/; SameSite=Strict; max-age=604800${secure}`
}

/** Clear both the legacy api_key cookie and any Next-middleware sentinel. */
export function clearApiKey(): void {
  document.cookie = `${LEGACY_API_KEY_COOKIE}=; path=/; max-age=0`
}

/** Whether a session-token cookie is (probably) present.
 *
 *  We can't read the HttpOnly cookie from JS, but we can send a probe to
 *  /api/auth/session — a 200 means the cookie is valid, 401 means not. This
 *  helper returns a best-effort synchronous guess based on the legacy cookie
 *  or any non-HttpOnly hint; the real answer comes from the session probe.
 */
export function hasLegacyAuth(): boolean {
  return getApiKey() !== null || readCookie(SESSION_COOKIE_NAME_HINT) !== null
}

/**
 * Fetch wrapper for authenticated calls.
 *
 * - Always sends cookies (`credentials: 'include'`) so the HttpOnly
 *   session_token goes along automatically.
 * - If the legacy `api_key` cookie is present, also sends it as `X-API-Key`.
 *   This lets pre-Sprint-14 sessions keep working and Playwright fixtures
 *   that inject `api_key` continue to pass.
 */
export function authFetch(url: string, init?: RequestInit): Promise<Response> {
  const key = getApiKey()
  const headers = new Headers(init?.headers)
  if (key) headers.set('X-API-Key', key)
  return fetch(url, {
    ...init,
    credentials: init?.credentials ?? 'include',
    headers,
  })
}

// ── Sprint 14: session-token flow ──────────────────────────────────────────

export interface LoginResult {
  sessionToken: string
  teamId: string
  memberId: string | null
  email: string | null
  role: string
  createdAt: string
  lastSeenAt: string
  expiresAt: string
}

export async function login(apiKey: string, email?: string): Promise<LoginResult> {
  const res = await fetch('/api/auth/login', {
    method: 'POST',
    credentials: 'include',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ api_key: apiKey, email: email || undefined }),
  })
  if (!res.ok) {
    const err = await res.json().catch(() => ({}))
    throw new Error(err?.detail ?? err?.error?.message ?? `Login failed: ${res.status}`)
  }
  const data = await res.json()
  // Backend serializes LoginResponse as camelCase via CamelModel
  // (see src/ai_driven/api/_shared.py). Single source of truth —
  // every API response on this platform is camelCase.
  return {
    sessionToken: data.sessionToken,
    teamId: data.teamId,
    memberId: data.memberId ?? null,
    email: data.email ?? null,
    role: data.role,
    createdAt: data.createdAt,
    lastSeenAt: data.lastSeenAt,
    expiresAt: data.expiresAt,
  }
}

export async function logout(): Promise<void> {
  try {
    await fetch('/api/auth/logout', {
      method: 'POST',
      credentials: 'include',
    })
  } finally {
    // Clear the legacy cookie too, in case a session was upgraded mid-flight.
    clearApiKey()
  }
}
