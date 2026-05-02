import { NextResponse, type NextRequest } from 'next/server'

/**
 * Protect all /dashboard routes.
 *
 * Sprint 14 — session-token flow:
 *   - `session_token` cookie present → let request through.
 *   - Legacy `api_key` cookie present (pre-Sprint-14 sessions, Playwright
 *     fixtures) → also accepted so rollout doesn't evict existing users.
 *   - Neither → redirect to /login, unless the debug-mode bypass applies.
 *
 * Debug-mode convenience:
 *   When the backend reports `debug: true` on GET /health, we auto-set a
 *   debug sentinel cookie (`session_token=debug-session` — the backend
 *   recognizes this sentinel when `settings.debug=true`). Fresh browsers get
 *   a frictionless dev experience; subsequent requests skip the probe.
 *
 *   Real deployments set `debug: false` (or omit the field), so this has no
 *   effect in production. Tests can set the cookie directly via
 *   `ai-driven-web-ui/e2e/fixtures.ts`.
 *
 *   Tests that want to exercise the prod redirect path can set the cookie
 *   `disable_debug_bypass=1` to force the redirect regardless of `/health`.
 */
export async function middleware(request: NextRequest) {
  const sessionToken = request.cookies.get('session_token')?.value
  const legacyApiKey = request.cookies.get('api_key')?.value

  if (sessionToken || legacyApiKey) {
    const response = NextResponse.next()
    // Forward whatever we have for server components / request logging.
    if (sessionToken) response.headers.set('x-session-token', sessionToken)
    if (legacyApiKey) response.headers.set('x-api-key', legacyApiKey)
    return response
  }

  // Test-only override: Playwright can't intercept the edge-side /health
  // probe (page.route stubs browser fetches, not middleware fetches). To
  // exercise the non-debug redirect path deterministically, tests set
  // `disable_debug_bypass=1` cookie to force the redirect.
  if (request.cookies.get('disable_debug_bypass')?.value === '1') {
    return NextResponse.redirect(new URL('/login', request.url))
  }

  // No cookie — try the debug-mode convenience bypass before redirecting.
  try {
    const healthUrl = new URL('/health', request.url)
    const healthRes = await fetch(healthUrl, {
      cache: 'no-store',
      signal: AbortSignal.timeout(1500),
    })
    if (healthRes.ok) {
      const health = (await healthRes.json()) as { debug?: boolean }
      if (health.debug === true) {
        // Auto-issue a sentinel session cookie. The backend middleware
        // accepts `debug-session` as a valid sentinel when
        // `settings.debug=true`, so everything downstream "just works".
        const response = NextResponse.next()
        response.cookies.set({
          name: 'session_token',
          value: 'debug-session',
          path: '/',
          sameSite: 'lax',
          maxAge: 60 * 60 * 8,
          // httpOnly omitted — fetch auto-attaches regardless, and keeping
          // it readable simplifies Playwright assertions that check the
          // sentinel was set.
        })
        response.headers.set('x-session-token', 'debug-session')
        return response
      }
    }
  } catch {
    // /health unreachable — fall through to redirect.
  }

  return NextResponse.redirect(new URL('/login', request.url))
}

export const config = {
  matcher: ['/dashboard/:path*'],
}
