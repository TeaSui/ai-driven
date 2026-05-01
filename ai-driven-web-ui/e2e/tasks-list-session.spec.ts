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

  // 2. Navigate to tasks list and capture the API call as it happens
  const apiResponses: Array<{ url: string; status: number; body: string }> = []
  page.on('response', async (r) => {
    if (r.url().includes('/api/admin/tasks') && r.request().method() === 'GET') {
      try {
        apiResponses.push({
          url: r.url(),
          status: r.status(),
          body: await r.text(),
        })
      } catch {
        apiResponses.push({ url: r.url(), status: r.status(), body: '<unreadable>' })
      }
    }
  })

  await page.goto('http://localhost:3000/dashboard/tasks', { waitUntil: 'networkidle' })
  await page.waitForTimeout(1000)

  console.log('captured API responses:', JSON.stringify(apiResponses, null, 2))

  const listCall = apiResponses.find((r) => r.url.endsWith('/api/admin/tasks') || r.url.includes('/api/admin/tasks?'))
  if (!listCall) {
    throw new Error(`UI did not call /api/admin/tasks. All calls: ${JSON.stringify(apiResponses)}`)
  }
  if (listCall.status !== 200) {
    throw new Error(`Tasks endpoint failed with ${listCall.status}: ${listCall.body}`)
  }

  const body = JSON.parse(listCall.body)
  expect(body.total, `expected total>0 but was ${body.total}; body=${listCall.body.slice(0, 200)}`).toBeGreaterThan(0)

  // UI should NOT show empty-state
  await expect(page.getByText(/No coding tasks yet/i)).toBeHidden({ timeout: 5_000 })
})
