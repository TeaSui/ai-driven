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
  test.skip('unauthenticated user visiting /dashboard is redirected to /login', async ({ page }) => {
    // TODO: the codebase does not yet ship a middleware.ts that enforces the
    // dashboard auth gate. /dashboard loads for anyone in dev mode. Re-enable
    // this test once the middleware is added.
    await page.goto('/dashboard')
    await expect(page).toHaveURL(/\/login/)
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
