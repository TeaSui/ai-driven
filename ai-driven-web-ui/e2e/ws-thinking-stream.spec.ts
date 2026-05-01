/**
 * ws-thinking-stream.spec.ts
 *
 * Reproduces user's actual bug: after login + opening a running task,
 * the "Thinking stream" panel stays at 0 steps because WebSocket events
 * don't arrive.
 *
 * We don't mock the backend at all. We:
 *   1. Login via the UI
 *   2. Submit a new task (or navigate to an existing running one)
 *   3. Monitor WebSocket frames
 *   4. Assert the UI shows streamed events
 */

import { test, expect } from '@playwright/test'

const API_KEY =
  process.env.AI_DRIVEN_E2E_API_KEY ||
  'tyme_AQU8b320-HPZSS01r-DCmaG-dTFNkiCW5qnXk-Sn7M4'

test('WS thinking stream receives events after login', async ({ page, context }) => {
  await context.clearCookies()

  // Capture all WebSocket activity
  const wsEvents: Array<{ direction: 'in' | 'out'; payload: string }> = []
  const wsCloses: Array<{ code: number; reason: string }> = []
  const wsConnections: string[] = []

  page.on('websocket', (ws) => {
    wsConnections.push(ws.url())
    ws.on('framesent', (f) => {
      if (typeof f.payload === 'string') wsEvents.push({ direction: 'out', payload: f.payload })
    })
    ws.on('framereceived', (f) => {
      if (typeof f.payload === 'string') wsEvents.push({ direction: 'in', payload: f.payload.slice(0, 300) })
    })
    ws.on('close', () => wsCloses.push({ code: 0, reason: 'closed' }))
  })

  // Also capture any 4xx/5xx HTTP responses for diagnosis
  const apiErrors: Array<{ url: string; status: number; body: string }> = []
  page.on('response', async (r) => {
    if (r.url().includes('/api/') && r.status() >= 400) {
      try {
        apiErrors.push({ url: r.url(), status: r.status(), body: (await r.text()).slice(0, 200) })
      } catch {}
    }
  })

  // 1. Login
  await page.goto('http://localhost:3000/login')
  await page.getByLabel(/API Key/i).fill(API_KEY)
  await page.getByRole('button', { name: /sign in/i }).click()
  await page.waitForURL(/\/dashboard(\/|$)/, { timeout: 10_000 })

  // 2. Find any existing task from the list (we don't want to burn money on a real run)
  await page.goto('http://localhost:3000/dashboard/tasks', { waitUntil: 'networkidle' })
  await page.waitForTimeout(500)

  // Grab the first task id from the API (simpler than scraping the UI)
  const tasksResp = await page.request.get('http://localhost:3000/api/admin/tasks?limit=1')
  const tasksBody = await tasksResp.json()
  const taskId = tasksBody?.tasks?.[0]?.id
  expect(taskId, `need at least one task; got: ${JSON.stringify(tasksBody).slice(0, 200)}`).toBeTruthy()

  // 3. Open the task detail page — this triggers the WebSocket
  await page.goto(`http://localhost:3000/dashboard/tasks/${taskId}`, { waitUntil: 'networkidle' })
  await page.waitForTimeout(3000) // let WS auth + some events flow

  // 4. Diagnostic dump
  console.log('WS connections opened:', wsConnections)
  console.log('WS frames count:', wsEvents.length)
  console.log('First 5 WS frames:', JSON.stringify(wsEvents.slice(0, 5), null, 2))
  console.log('API errors:', JSON.stringify(apiErrors, null, 2))

  // Check localStorage to see if session_token got stashed
  const lsToken = await page.evaluate(() => {
    try {
      return localStorage.getItem('session_token')
    } catch {
      return null
    }
  })
  console.log('localStorage session_token present:', lsToken ? `yes (${lsToken.slice(0, 10)}...)` : 'NO')

  // Assertions
  expect(wsConnections.length, 'UI should open a WebSocket').toBeGreaterThan(0)

  // Did the UI send an auth frame?
  const authFrame = wsEvents.find((e) => e.direction === 'out' && e.payload.includes('"type":"auth"'))
  expect(authFrame, `UI must send {type:auth} as first frame. Sent frames: ${JSON.stringify(wsEvents.filter(e => e.direction === 'out'))}`).toBeTruthy()

  // Did auth token contain anything?
  const parsed = JSON.parse(authFrame!.payload)
  expect(parsed.token, `auth frame token must not be empty (UI has no credentials). Frame: ${authFrame!.payload}`).toBeTruthy()

  // Did we receive any events back?
  const eventFrames = wsEvents.filter((e) => e.direction === 'in')
  expect(eventFrames.length, `WS must receive at least one event. Got ${eventFrames.length} frames. Closes: ${JSON.stringify(wsCloses)}`).toBeGreaterThan(0)
})
