/**
 * add-repo-real-backend.spec.ts
 *
 * REAL end-to-end test (no mocks). Reproduces the bug the user hit manually:
 *
 *   1. Backend running with debug=false
 *   2. User logs in via /login with a valid API key
 *   3. Browser holds only the HttpOnly session_token cookie
 *   4. User opens Admin → Repositories → Add repository
 *   5. Submits github/foo-bar
 *   6. Backend rejects with 401 "Missing API key"
 *
 * Root cause: middleware sets request.state.team_id from session_token, but
 * admin/router.py::_resolve_team_or_401 checks request.state.api_key_hash
 * and has no fallback to team_id outside debug mode.
 *
 * Once fixed, this test should go green without any backend mocking.
 */

import { test, expect } from '@playwright/test'

const API_KEY = process.env.AI_DRIVEN_E2E_API_KEY
  || 'tyme_AQU8b320-HPZSS01r-DCmaG-dTFNkiCW5qnXk-Sn7M4'

test('add-repo flow works after API-key login (no debug mode, no mocks)', async ({ page }) => {
  // 1. Land on the login page
  await page.goto('http://localhost:3000/login')

  // 2. Paste the API key and sign in
  await page.getByLabel(/API Key/i).fill(API_KEY)
  await page.getByRole('button', { name: /sign in/i }).click()

  // 3. Wait until the dashboard loads
  await page.waitForURL(/\/dashboard(\/|$)/, { timeout: 10_000 })

  // 4. Navigate to the repositories admin page
  await page.goto('http://localhost:3000/dashboard/admin/repos')
  await expect(
    page.getByRole('heading', { name: /repositories/i }),
  ).toBeVisible({ timeout: 10_000 })

  // 5. Open the Add repository modal
  await page.getByRole('button', { name: /add repository/i }).first().click()

  // Scope subsequent locators to the modal to avoid the header button collision
  const modal = page.getByRole('dialog').or(page.locator('[role="dialog"]')).last()

  // Some UIs render the modal as a plain div; fall back to the form container.
  const scope = (await modal.count()) ? modal : page

  // 6. Pick GitHub and fill the fullName
  await scope.getByRole('radio', { name: /github/i }).check().catch(() => {
    // if not a radio, a label click is fine
  })
  const unique = `e2e-tester/demo-${Date.now()}`
  await scope.getByLabel(/full name/i).fill(unique)

  // 7. Submit and expect the request to reach the backend with a valid
  // identity — i.e. NOT 401 "Missing API key". We don't care about 4xx
  // from business-rule validation (e.g. GitHub repo doesn't exist) —
  // this test is strictly about the auth layer, not the repo CRUD.
  const responsePromise = page.waitForResponse(
    (r) => r.url().includes('/api/admin/repos') && r.request().method() === 'POST',
  )
  await scope.getByRole('button', { name: /^add repository$/i }).last().click()
  const response = await responsePromise
  const status = response.status()
  const body = await response.text()

  // The exact regression check: session auth must not fail with 401.
  expect(
    status,
    `Auth regression: got 401 "${body}". Session token was set but backend ` +
      `still rejected the request. Check admin router _get_team_from_request.`,
  ).not.toBe(401)

  // Also never 403 — that would mean the team lookup succeeded but the
  // team isn't allowed to create repos, which would be a different
  // middleware/role bug.
  expect(status).not.toBe(403)
})
