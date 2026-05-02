/**
 * tasks-list-session.spec.ts
 *
 * Reproduces the bug: after session-token login, /dashboard/tasks shows
 * "No coding tasks yet" despite tasks existing in DB.
 *
 * Expected behavior (with the backend list_tasks fix):
 *   - Login exchanges api_key for session_token cookie
 *   - /dashboard/tasks page calls GET /api/admin/tasks
 *   - Response contains the task list
 *   - UI renders the tasks
 */

import { test, expect } from '@playwright/test'

const API_KEY = process.env.AI_DRIVEN_E2E_API_KEY
  || 'tyme_AQU8b320-HPZSS01r-DCmaG-dTFNkiCW5qnXk-Sn7M4'

test('tasks list renders after session-token login', async ({ page, context }) => {
  // Start clean — no cookies, no localStorage
  await context.clearCookies()

  // 1. Log in
  await page.goto('http://localhost:3000/login')
  await page.getByLabel(/API Key/i).fill(API_KEY)
  await page.getByRole('button', { name: /sign in/i }).click()
  await page.waitForURL(/\/dashboard(\/|$)/, { timeout: 10_000 })

  // Assert the session_token cookie was set and is on the right host
  const cookies = await context.cookies()
  const sessionCookie = cookies.find((c) => c.name === 'session_token')
  if (!sessionCookie) {
    throw new Error(`No session_token cookie set after login. All cookies: ${JSON.stringify(cookies, null, 2)}`)
  }
  console.log('session_token cookie:', {
    domain: sessionCookie.domain,
    path: sessionCookie.path,
    sameSite: sessionCookie.sameSite,
    httpOnly: sessionCookie.httpOnly,
    secure: sessionCookie.secure,
  })

  // 2. Navigate to tasks list and make the request directly via request context
  // (avoids the Playwright response-body-GC race where page.on('response')
  // hands back a handle that's already invalidated by the time we await it)
  await page.goto('http://localhost:3000/dashboard/tasks', { waitUntil: 'networkidle' })
  await page.waitForTimeout(500)

  const resp = await page.request.get('http://localhost:3000/api/admin/tasks')
  expect(resp.status(), `Tasks endpoint failed: ${await resp.text()}`).toBe(200)
  const body = await resp.json()
  expect(body.total, `expected total>0 but was ${body.total}; body=${JSON.stringify(body).slice(0, 200)}`).toBeGreaterThan(0)

  // UI should NOT show empty-state
  await expect(page.getByText(/No coding tasks yet/i)).toBeHidden({ timeout: 5_000 })
})
