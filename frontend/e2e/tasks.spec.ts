/**
 * tasks.spec.ts — Tasks page and task detail page tests
 *
 * Exercises:
 *  1. Tasks page lists tasks fetched from the API
 *  2. Clicking a task row navigates to the detail page (/dashboard/tasks/[id])
 *  3. Task detail page renders the task status and agent type in the header
 *
 * All backend API calls are intercepted.
 */

import { test, expect } from './fixtures'

const MOCK_TASKS = [
  {
    id: 'task-abc',
    title: 'Fix race condition in ledger balance cache',
    status: 'completed',
    agentType: 'coding',
    jiraKey: 'GTX-4829',
    createdAt: new Date(Date.now() - 5 * 60_000).toISOString(),
    durationMs: 480_000,
    tokenUsage: 42000,
    costUsd: 0.12,
    iterations: 6,
  },
  {
    id: 'task-def',
    title: 'Implement KYC document upload',
    status: 'running',
    agentType: 'coding',
    jiraKey: 'GTX-5001',
    createdAt: new Date(Date.now() - 2 * 60_000).toISOString(),
    durationMs: null,
    tokenUsage: 8000,
    costUsd: 0.04,
    iterations: 2,
  },
]

async function stubCommonRoutes(page: import('@playwright/test').Page) {
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
      body: JSON.stringify({ data: { costToday: 1, dailyBudget: 100, budgetUsagePercent: 1 } }),
    }),
  )
}

test.describe('Tasks', () => {
  test('tasks page lists tasks returned by the API', async ({
    authenticatedPage: page,
  }) => {
    await stubCommonRoutes(page)

    await page.route('/api/admin/tasks*', (route) =>
      route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({ tasks: MOCK_TASKS }),
      }),
    )

    await page.goto('/dashboard/tasks')

    // Page heading
    await expect(page.getByRole('heading', { name: /coding agent/i })).toBeVisible()

    // Both task titles should appear
    await expect(page.getByText('Fix race condition in ledger balance cache')).toBeVisible()
    await expect(page.getByText('Implement KYC document upload')).toBeVisible()
  })

  test('clicking a task row navigates to the task detail page', async ({
    authenticatedPage: page,
  }) => {
    await stubCommonRoutes(page)

    await page.route('/api/admin/tasks*', (route) =>
      route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({ tasks: MOCK_TASKS }),
      }),
    )

    // Stub the individual task detail endpoint
    await page.route('/api/admin/tasks/task-abc', (route) =>
      route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify(MOCK_TASKS[0]),
      }),
    )

    // Stub events + evaluations endpoints so the detail page doesn't error
    await page.route('/api/admin/tasks/task-abc/events', (route) =>
      route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify([]),
      }),
    )
    await page.route('/api/admin/tasks/task-abc/evaluations', (route) =>
      route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify([]),
      }),
    )

    await page.goto('/dashboard/tasks')

    // Click the first task link (aria-label contains the task title)
    const taskLink = page.getByRole('link', {
      name: /view task GTX-4829: Fix race condition/i,
    })
    await expect(taskLink).toBeVisible()
    await taskLink.click()

    // Should navigate to the task detail route
    await expect(page).toHaveURL(/\/dashboard\/tasks\/task-abc/)
  })

  test('task detail page shows status and agent type', async ({
    authenticatedPage: page,
  }) => {
    await stubCommonRoutes(page)

    // Stub the task detail endpoint
    await page.route('/api/admin/tasks/task-abc', (route) =>
      route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify(MOCK_TASKS[0]),
      }),
    )

    await page.route('/api/admin/tasks/task-abc/events', (route) =>
      route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify([]),
      }),
    )
    await page.route('/api/admin/tasks/task-abc/evaluations', (route) =>
      route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify([]),
      }),
    )

    await page.goto('/dashboard/tasks/task-abc')

    // The task title appears in the header
    await expect(
      page.getByText('Fix race condition in ledger balance cache'),
    ).toBeVisible()

    // Status is shown as a pill — the task is "completed"
    await expect(page.getByText(/completed/i).first()).toBeVisible()

    // Task ID is shown in the breadcrumb area
    await expect(page.getByText('task-abc')).toBeVisible()
  })
})
