/**
 * Submits a real task and verifies the thinking stream flows.
 * Skipped from CI (expensive) — run on demand:
 *   npx playwright test ws-live-task --reporter=list
 */

import { test, expect } from '@playwright/test'

const API_KEY =
  process.env.AI_DRIVEN_E2E_API_KEY ||
  'tyme_AQU8b320-HPZSS01r-DCmaG-dTFNkiCW5qnXk-Sn7M4'

test('WS streams events for a newly created task', async ({ page, context }) => {
  test.setTimeout(120_000)
  await context.clearCookies()

  // Capture everything
  const wsUrls: string[] = []
  const wsIn: string[] = []
  const wsOut: string[] = []
  const wsCloses: Array<{ url: string; code?: number }> = []

  page.on('websocket', (ws) => {
    const url = ws.url()
    if (url.includes('webpack-hmr')) return
    wsUrls.push(url)
    ws.on('framesent', (f) => {
      if (typeof f.payload === 'string') wsOut.push(f.payload)
    })
    ws.on('framereceived', (f) => {
      if (typeof f.payload === 'string') wsIn.push(f.payload.slice(0, 500))
    })
    ws.on('close', () => wsCloses.push({ url }))
  })

  // Login
  await page.goto('http://localhost:3000/login')
  await page.getByLabel(/API Key/i).fill(API_KEY)
  await page.getByRole('button', { name: /sign in/i }).click()
  await page.waitForURL(/\/dashboard(\/|$)/, { timeout: 10_000 })

  // Submit a fresh task directly via the API (bypass UI new-run flow)
  const submit = await page.request.post('http://localhost:3000/api/chat/send', {
    data: {
      message: 'Add a hello endpoint to the repo',
      agent_type: 'coding',
      context: {
        repo_full_name: 'MinnMinn/skills-introduction-to-github',
        scm_type: 'github',
      },
    },
  })
  const submitJson = await submit.json()
  console.log('submit response:', JSON.stringify(submitJson))
  const taskId = submitJson.taskId || submitJson.task_id
  expect(taskId, `submit must return task id; got: ${JSON.stringify(submitJson)}`).toBeTruthy()
  console.log('submitted task:', taskId)

  // Navigate to the task page — WS should open
  await page.goto(`http://localhost:3000/dashboard/tasks/${taskId}`, { waitUntil: 'domcontentloaded' })

  // Wait up to 30s for thinking-stream events
  const deadline = Date.now() + 30_000
  while (Date.now() < deadline) {
    const businessFrames = wsIn.filter(
      (p) => !p.includes('"action":"sync"') && p.length > 5,
    )
    if (businessFrames.length >= 2) break
    await page.waitForTimeout(500)
  }

  console.log('WS connections (non-HMR):', wsUrls)
  console.log('WS outgoing frames:', wsOut.slice(0, 3))
  console.log('WS incoming frames count:', wsIn.length)
  console.log('First 3 incoming frames:')
  wsIn.slice(0, 3).forEach((f, i) => console.log(`  [${i}]`, f))
  console.log('Closes:', wsCloses)

  expect(wsUrls.some((u) => u.includes('/api/chat/stream/'))).toBeTruthy()
  const authFrame = wsOut.find((p) => p.includes('"type":"auth"'))
  expect(authFrame, `auth frame required; got: ${JSON.stringify(wsOut)}`).toBeTruthy()

  const businessFrames = wsIn.filter(
    (p) => !p.includes('"action":"sync"') && p.length > 5,
  )
  expect(
    businessFrames.length,
    `WS must stream events from the running agent. Received: ${wsIn.length} total, ${businessFrames.length} business. Closes: ${JSON.stringify(wsCloses)}`,
  ).toBeGreaterThan(0)
})
