/**
 * dashboard.spec.ts — Dashboard overview page tests
 *
 * Exercises:
 *  1. KPI / hero stats cards are rendered
 *  2. Sidebar navigation links work (Tasks, Reviews, Insights)
 *  3. Debug banner appears when the backend returns debug=true via /health
 *
 * All backend API calls are intercepted so tests are backend-independent.
 */

import { test, expect } from './fixtures'

/** Stub all /api/* routes with empty-but-valid responses. */
async function stubApiRoutes(page: import('@playwright/test').Page) {
  await page.route('/api/admin/dashboard', (route) =>
    route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({
        data: {
          totalTasks: 10,
          completedTasks: 8,
          activeTasks: 2,
          costToday: 5.0,
          dailyBudget: 100.0,
          budgetUsagePercent: 5,
        },
      }),
    }),
  )

  await page.route('/api/admin/tasks*', (route) =>
    route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({
        data: [
          {
            id: 'task-1',
            title: 'Fix race condition in ledger',
            status: 'completed',
            agentType: 'coding',
            createdAt: new Date().toISOString(),
          },
          {
            id: 'task-2',
            title: 'Update payment flow',
            status: 'running',
            agentType: 'coding',
            createdAt: new Date().toISOString(),
          },
        ],
      }),
    }),
  )

  await page.route('/api/admin/me', (route) =>
    route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({ data: { teamName: 'GoTyme Team' } }),
    }),
  )

  // Stub all remaining /api/* calls
  await page.route('/api/**', (route) => {
    // Only fulfill if not already handled
    route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({ data: [] }),
    })
  })
}

test.describe('Dashboard', () => {
  test('authenticated user sees dashboard with KPI cards', async ({
    authenticatedPage: page,
  }) => {
    await stubApiRoutes(page)

    // Stub /health so the debug banner does not appear by default
    await page.route('/health', (route) =>
      route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({ status: 'ok', debug: false }),
      }),
    )

    await page.goto('/dashboard')

    // The HeroBanner contains stats — assert the heading text is visible
    await expect(page.getByRole('heading', { name: /overview/i })).toBeVisible()

    // KPI stat labels rendered in HeroBanner (any one is sufficient to confirm render)
    await expect(page.getByText(/PRs merged/i)).toBeVisible()
    await expect(page.getByText(/engineering hours saved/i)).toBeVisible()
  })

  test('sidebar navigation links navigate to the correct pages', async ({
    authenticatedPage: page,
  }) => {
    await stubApiRoutes(page)
    await page.route('/health', (route) =>
      route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({ status: 'ok', debug: false }),
      }),
    )

    await page.goto('/dashboard')

    // Sidebar is rendered as <aside aria-label="Main navigation">
    const sidebar = page.getByRole('complementary', { name: /main navigation/i })
    await expect(sidebar).toBeVisible()

    // Tasks link (labeled "Coding Agent" in sidebar)
    const tasksLink = sidebar.getByRole('link', { name: /coding agent/i })
    await expect(tasksLink).toBeVisible()
    await tasksLink.click()
    await expect(page).toHaveURL(/\/dashboard\/tasks/)

    await page.goto('/dashboard')

    // Reviews link
    const reviewsLink = sidebar.getByRole('link', { name: /code review/i })
    await expect(reviewsLink).toBeVisible()
    await reviewsLink.click()
    await expect(page).toHaveURL(/\/dashboard\/reviews/)

    await page.goto('/dashboard')

    // Insights / Metrics link
    const insightsLink = sidebar.getByRole('link', { name: /metrics/i })
    await expect(insightsLink).toBeVisible()
    await insightsLink.click()
    await expect(page).toHaveURL(/\/dashboard\/insights/)
  })

  test('debug banner appears when backend returns debug=true', async ({
    authenticatedPage: page,
  }) => {
    await stubApiRoutes(page)

    // Return debug=true from /health
    await page.route('/health', (route) =>
      route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({ status: 'ok', debug: true }),
      }),
    )

    await page.goto('/dashboard')

    // Debug banner has role="alert" per dashboard/layout.tsx
    const banner = page.getByRole('alert').filter({
      hasText: /debug mode active/i,
    })
    await expect(banner).toBeVisible()
    await expect(banner).toContainText(/authentication is bypassed/i)
  })
})
