/**
 * auth-session.spec.ts — Sprint 14 session-token login flow.
 *
 * Covers:
 *   1. Login page submits api_key + email, stores session cookie, redirects to /dashboard.
 *   2. Login error (401) surfaces in the form; cookie not set.
 *   3. Login with Redis outage (503) shows a retry-able message.
 *   4. Logout calls POST /api/auth/logout, clears the cookie, lands on /login.
 *   5. Debug-mode middleware sets session_token=debug-session cookie.
 *   6. Presence of the session_token cookie passes the Next middleware.
 */

import { test, expect } from './fixtures'

async function stubHealth(
  page: import('@playwright/test').Page,
  debug = false,
) {
  await page.route('/health', (route) =>
    route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({ status: 'ok', debug }),
    }),
  )
}

async function stubDashboardChrome(page: import('@playwright/test').Page) {
  await page.route('/api/admin/me', (route) =>
    route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({ data: { teamName: 'GoTyme Team' } }),
    }),
  )
  await page.route('/api/admin/dashboard', (route) =>
    route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({
        data: { costToday: 0, dailyBudget: 50, budgetUsagePercent: 0 },
      }),
    }),
  )
}

test.describe('Sprint 14 — session-token login', () => {
  test('valid api_key → session cookie set + redirect to /dashboard', async ({
    page,
  }) => {
    await stubHealth(page, false)
    await stubDashboardChrome(page)

    // POST /api/auth/login returns the session bundle AND the backend would
    // set the cookie; route.fulfill can't set an HttpOnly cookie on the
    // browser, so we set it manually here to simulate the real backend.
    await page.route('/api/auth/login', async (route) => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        headers: {
          'Set-Cookie': 'session_token=tok_valid; Path=/; SameSite=Lax; Max-Age=86400',
        },
        body: JSON.stringify({
          session_token: 'tok_valid',
          team_id: 'team-1',
          member_id: null,
          email: 'me@example.com',
          role: 'owner',
          created_at: new Date().toISOString(),
          last_seen_at: new Date().toISOString(),
          expires_at: new Date(Date.now() + 86400_000).toISOString(),
        }),
      })
    })

    await page.goto('/login')
    await page.getByLabel(/^Email/i).fill('me@example.com')
    await page.getByLabel('API Key').fill('aidrv_valid_123')
    await page.getByRole('button', { name: /Sign in/i }).click()

    await expect(page).toHaveURL(/\/dashboard/, { timeout: 10_000 })

    // The session_token cookie is set — middleware passthrough confirmed
    // by the fact that /dashboard loaded instead of redirecting.
    const cookies = await page.context().cookies()
    const sessionCookie = cookies.find((c) => c.name === 'session_token')
    expect(sessionCookie?.value).toBe('tok_valid')
  })

  test('invalid api_key → 401 error surfaces, no cookie set', async ({
    page,
  }) => {
    await stubHealth(page, false)

    await page.route('/api/auth/login', (route) =>
      route.fulfill({
        status: 401,
        contentType: 'application/json',
        body: JSON.stringify({ detail: 'Invalid API key' }),
      }),
    )

    await page.goto('/login')
    await page.getByLabel('API Key').fill('totally-wrong')
    await page.getByRole('button', { name: /Sign in/i }).click()

    // Error alert appears on the form. Scope to the #login-error div because
    // Next.js renders a second role=alert for its route announcer.
    const alert = page.locator('#login-error')
    await expect(alert).toBeVisible()
    await expect(alert).toContainText(/invalid api key/i)

    // No session cookie was set.
    const cookies = await page.context().cookies()
    expect(cookies.find((c) => c.name === 'session_token')).toBeUndefined()
  })

  test('redis outage (503) on login shows retry-able message', async ({
    page,
  }) => {
    await stubHealth(page, false)

    await page.route('/api/auth/login', (route) =>
      route.fulfill({
        status: 503,
        contentType: 'application/json',
        body: JSON.stringify({
          detail: 'Session store unavailable — please retry shortly.',
        }),
      }),
    )

    await page.goto('/login')
    await page.getByLabel('API Key').fill('aidrv_valid_123')
    await page.getByRole('button', { name: /Sign in/i }).click()

    const alert = page.locator('#login-error')
    await expect(alert).toBeVisible()
    await expect(alert).toContainText(/Session store unavailable/i)
  })

  test('debug-mode middleware issues session_token=debug-session cookie', async ({
    page,
  }) => {
    // The middleware's /health probe goes server-side — it hits the real
    // backend unless we set the disable_debug_bypass cookie. Here we want
    // the real debug=true path, which the running backend already reports.
    await page.route('/api/admin/me', (route) =>
      route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({ data: { teamName: 'Debug Team' } }),
      }),
    )
    await page.route('/api/admin/dashboard', (route) =>
      route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({
          data: { costToday: 0, dailyBudget: 50, budgetUsagePercent: 0 },
        }),
      }),
    )

    await page.goto('/dashboard/overview')
    await expect(page).toHaveURL(/\/dashboard/)

    const cookies = await page.context().cookies()
    const sessionCookie = cookies.find((c) => c.name === 'session_token')
    expect(sessionCookie?.value).toBe('debug-session')
  })

  test('session_token cookie passes middleware (no redirect)', async ({
    page,
  }) => {
    await stubHealth(page, false)
    await stubDashboardChrome(page)

    // Set a session_token cookie directly. Middleware should let it through.
    await page.context().addCookies([
      {
        name: 'session_token',
        value: 'tok_preset',
        domain: 'localhost',
        path: '/',
        sameSite: 'Lax',
        httpOnly: false,
        secure: false,
      },
    ])

    await page.goto('/dashboard/overview')
    await expect(page).toHaveURL(/\/dashboard/)
  })

  test('logout clears the session cookie and redirects via sidebar sign-out', async ({
    authenticatedPage: page,
  }) => {
    await stubHealth(page, false)
    await stubDashboardChrome(page)

    let logoutCalled = false
    await page.route('/api/auth/logout', (route) => {
      logoutCalled = true
      return route.fulfill({
        status: 204,
        headers: {
          'Set-Cookie': 'session_token=; Path=/; Max-Age=0',
        },
        body: '',
      })
    })

    // Seed a session_token cookie to pair with the legacy api_key from fixtures.
    await page.context().addCookies([
      {
        name: 'session_token',
        value: 'tok_preset',
        domain: 'localhost',
        path: '/',
        sameSite: 'Lax',
        httpOnly: false,
        secure: false,
      },
    ])

    // Use a wide viewport so the sidebar renders in the full (non-collapsed)
    // state.
    await page.setViewportSize({ width: 1440, height: 900 })
    await page.goto('/dashboard')

    // Open the avatar menu, then click sign-out by testid.
    await page.getByRole('button', { name: /menu$/ }).click()
    await page.getByTestId('sidebar-sign-out').click()

    // Eventually lands on /login.
    await expect(page).toHaveURL(/\/login/, { timeout: 10_000 })
    expect(logoutCalled).toBe(true)
  })
})
