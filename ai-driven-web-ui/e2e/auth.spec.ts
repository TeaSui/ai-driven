/**
 * auth.spec.ts — Authentication flow tests
 *
 * These tests exercise:
 *  1. Redirect to /login for unauthenticated users
 *  2. Login page rendering
 *  3. Invalid API key error feedback
 *  4. Valid API key redirects to /dashboard
 *
 * Backend API calls from the login page (/api/admin/dashboard) are intercepted
 * so the tests run reliably without a live backend.
 */

import { test, expect } from './fixtures'

test.describe('Authentication', () => {
  test('unauthenticated user visiting /dashboard is redirected to /login (non-debug backend)', async ({
    page,
  }) => {
    // The middleware's /health probe runs on the Node edge, which
    // page.route can't intercept. Setting `disable_debug_bypass=1` cookie
    // forces the middleware to skip the debug bypass and take the prod
    // redirect path.
    await page.context().addCookies([
      {
        name: 'disable_debug_bypass',
        value: '1',
        domain: 'localhost',
        path: '/',
        sameSite: 'Lax',
        httpOnly: false,
        secure: false,
      },
    ])

    await page.goto('/dashboard/overview')
    await expect(page).toHaveURL(/\/login/)
  })

  test('authenticated user with api_key cookie is not redirected', async ({
    authenticatedPage: page,
  }) => {
    // Also stub the backend-side calls the dashboard makes on load.
    await page.route('/health', (route) =>
      route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({ status: 'ok', debug: false }),
      }),
    )
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

    await page.goto('/dashboard/overview')
    await expect(page).toHaveURL(/\/dashboard/)
  })

  test('debug-mode backend auto-bypasses the redirect and sets a sentinel cookie', async ({
    page,
  }) => {
    // debug=true short-circuits the redirect so local dev is frictionless.
    await page.route('/health', (route) =>
      route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({ status: 'ok', debug: true }),
      }),
    )
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
    // Landed on /dashboard, not /login.
    await expect(page).toHaveURL(/\/dashboard/)

    // Middleware set the sentinel session cookie (Sprint 14 switched from
    // api_key=debug-bypass to session_token=debug-session).
    const cookies = await page.context().cookies()
    const sessionCookie = cookies.find((c) => c.name === 'session_token')
    expect(sessionCookie?.value).toBe('debug-session')
  })

  test('login page renders the API key input and sign-in button', async ({ page }) => {
    await page.goto('/login')

    // Label "API Key" must be associated with the password input
    await expect(page.getByLabel('API Key')).toBeVisible()

    // Submit button
    await expect(page.getByRole('button', { name: /sign in/i })).toBeVisible()
  })

  test.skip('entering an invalid key shows an error message', async ({ page }) => {
    // TODO: intermittent timing issue — the fetch to /api/admin/dashboard
    // races the route stub registration. Investigate and re-enable.
    // Intercept the validation request and return 401
    await page.route('/api/admin/dashboard', (route) => {
      route.fulfill({ status: 401, body: JSON.stringify({ error: 'Unauthorized' }) })
    })

    await page.goto('/login')
    await page.getByLabel('API Key').fill('bad_key')
    await page.getByRole('button', { name: /sign in/i }).click()

    // The error message element has role="alert" per the login page implementation
    const errorAlert = page.getByRole('alert')
    await expect(errorAlert).toBeVisible()
    await expect(errorAlert).toContainText(/invalid api key/i)
  })

  test('entering a valid key redirects to /dashboard', async ({ page }) => {
    // Intercept the validation request and return 200
    await page.route('/api/admin/dashboard', (route) => {
      route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({ data: { ok: true } }),
      })
    })

    // Stub downstream dashboard data calls so the redirect destination loads
    await page.route('/api/**', (route) => {
      route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({ data: [] }),
      })
    })

    await page.goto('/login')
    await page.getByLabel('API Key').fill('aidrv_valid_key_123')
    await page.getByRole('button', { name: /sign in/i }).click()

    // Should land on /dashboard (or a sub-path)
    await expect(page).toHaveURL(/\/dashboard/, { timeout: 10_000 })
  })
})
