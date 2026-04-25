/**
 * reviews-sidebar.spec.ts — Code Review sidebar + header + detail tabs
 *
 * Covers the real-data replacements for the previously hardcoded pieces:
 *   - Header banner (no fake "3 issues / 7 files / took 12s" when nothing is selected)
 *   - Review summary bar (Safety/Correctness/Style counts come from files.comments)
 *   - Sidebar reviewers (includes synthesised AI reviewer + human reviewers from SCM)
 *   - Sidebar checks (names + conclusion state come from SCM)
 *   - Detail-tab counts (Commits & Checks = null until SWR returns, then real numbers)
 *   - Commits tab renders real shas + messages
 *   - Checks tab renders real CI check rows
 *
 * All network calls are stubbed with Playwright route fulfill.
 */

import { test, expect } from './fixtures'

const REVIEW_ID = 'rev-100'
const JIRA_KEY = 'GTX-9000'

const MOCK_REVIEW_LIST = [
  {
    id: REVIEW_ID,
    title: 'Add rate limiting to /api/kyc endpoint',
    repo: 'go-tyme/api-gateway',
    prNumber: 721,
    prUrl: 'https://github.com/go-tyme/api-gateway/pull/721',
    status: 'pending',
    jiraKey: JIRA_KEY,
    author: 'review',
    summary: '',
    branch: 'agent/GTX-9000',
    createdAt: new Date(Date.now() - 10 * 60_000).toISOString(),
    durationMs: 42_000,
    files: [
      {
        path: 'src/api/kyc.ts',
        additions: 14,
        deletions: 2,
        comments: [
          {
            id: 'c1',
            line: 44,
            severity: 'warn',
            category: 'safety',
            confidence: '90',
            body: 'Missing rate-limit guard',
          },
          {
            id: 'c2',
            line: 80,
            severity: 'info',
            category: 'correctness',
            confidence: '80',
            body: 'Consider using stricter type',
          },
        ],
      },
    ],
  },
]

const MOCK_REVIEWERS = {
  reviewers: [
    {
      username: 'gotyme-x-ai',
      displayName: 'GoTyme-X AI',
      avatarUrl: null,
      isBot: true,
      state: 'commented',
      commentCount: 2,
    },
    {
      username: 'alex-dev',
      displayName: 'Alex Dev',
      avatarUrl: null,
      isBot: false,
      state: 'approved',
      commentCount: 0,
    },
    {
      username: 'jordan-qa',
      displayName: 'Jordan QA',
      avatarUrl: null,
      isBot: false,
      state: 'pending',
      commentCount: 0,
    },
  ],
}

const MOCK_CHECKS = {
  checks: [
    {
      name: 'ci / unit',
      status: 'completed',
      conclusion: 'success',
      durationSeconds: 42,
      detailsUrl: 'https://github.com/go-tyme/api-gateway/actions/runs/1',
    },
    {
      name: 'ci / lint',
      status: 'completed',
      conclusion: 'success',
      durationSeconds: 18,
      detailsUrl: null,
    },
    {
      name: 'coverage / report',
      status: 'in_progress',
      conclusion: null,
      durationSeconds: null,
      detailsUrl: null,
    },
  ],
}

const MOCK_COMMITS = {
  commits: [
    {
      sha: 'abcdef1234567890abcdef1234567890abcdef12',
      shortSha: 'abcdef1',
      message: 'Add sliding-window rate limiter',
      author: 'Alex Dev',
      timestamp: new Date(Date.now() - 30 * 60_000).toISOString(),
      url: null,
    },
    {
      sha: '0011223344556677001122334455667700112233',
      shortSha: '0011223',
      message: 'Test: limit exceeds threshold returns 429',
      author: 'Alex Dev',
      timestamp: new Date(Date.now() - 20 * 60_000).toISOString(),
      url: null,
    },
  ],
}

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
      body: JSON.stringify({
        data: { costToday: 1, dailyBudget: 100, budgetUsagePercent: 1 },
      }),
    }),
  )

  await page.route('/api/admin/reviews', (route) =>
    route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify(MOCK_REVIEW_LIST),
    }),
  )

  await page.route(`/api/admin/reviews/${REVIEW_ID}/reviewers`, (route) =>
    route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify(MOCK_REVIEWERS),
    }),
  )

  await page.route(`/api/admin/reviews/${REVIEW_ID}/checks`, (route) =>
    route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify(MOCK_CHECKS),
    }),
  )

  await page.route(`/api/admin/reviews/${REVIEW_ID}/commits`, (route) =>
    route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify(MOCK_COMMITS),
    }),
  )
}

test.describe('Code Review — sidebar and detail tabs wired to real data', () => {
  test('summary bar derives counts from review.files, not hardcoded', async ({
    authenticatedPage: page,
  }) => {
    await stubCommonRoutes(page)
    await page.goto('/dashboard/reviews')

    // First review is auto-selected.
    await expect(page.getByText(/AI review complete/)).toBeVisible()
    await expect(page.getByText(/2 issues flagged/)).toBeVisible()
    await expect(page.getByText(/Analyzed 1 file/)).toBeVisible()

    // Duration: 42_000 ms → "42s"
    await expect(page.getByText(/took 42s/)).toBeVisible()

    // No leftover demo strings.
    await expect(page.getByText('.gotyme/review.yml')).toHaveCount(0)
    await expect(page.getByText(/took 12s/)).toHaveCount(0)
    await expect(page.getByText('3 issues flagged, 0 blocking')).toHaveCount(0)
  })

  test('sidebar Reviewers shows AI + human reviewers, not hardcoded Mira/Jamie', async ({
    authenticatedPage: page,
  }) => {
    await stubCommonRoutes(page)
    await page.goto('/dashboard/reviews')

    // GoTyme-X AI appears in the app brand too — assert it within the
    // Reviewers section specifically by finding it near its sibling rows.
    await expect(page.getByText('GoTyme-X AI').last()).toBeVisible()
    await expect(page.getByText('Alex Dev')).toBeVisible()
    await expect(page.getByText('Jordan QA')).toBeVisible()

    // AI comment count rendered as sub-line under the reviewer name in the sidebar.
    // (Also appears in the queue ReviewCard aria-label, so scope to the div form.)
    await expect(page.locator('div').filter({ hasText: /^2 comments$/ })).toBeVisible()

    // The old hardcoded demo reviewers must be gone.
    await expect(page.getByText('Mira Delgado')).toHaveCount(0)
    await expect(page.getByText('Jamie Ortiz')).toHaveCount(0)
  })

  test('sidebar Checks shows real CI check names, not the hardcoded seven', async ({
    authenticatedPage: page,
  }) => {
    await stubCommonRoutes(page)
    await page.goto('/dashboard/reviews')

    await expect(page.getByText('ci / unit').first()).toBeVisible()
    await expect(page.getByText('ci / lint').first()).toBeVisible()
    await expect(page.getByText('coverage / report').first()).toBeVisible()

    // The old hardcoded checks that we never wired up must be gone.
    await expect(page.getByText('ci / integration')).toHaveCount(0)
    await expect(page.getByText('ci / race-detector')).toHaveCount(0)
    await expect(page.getByText('security / snyk')).toHaveCount(0)
    await expect(page.getByText('preview / staging')).toHaveCount(0)
  })

  test('sidebar Linked shows real Jira key, not the hardcoded GTX-4829/Alert', async ({
    authenticatedPage: page,
  }) => {
    await stubCommonRoutes(page)
    await page.goto('/dashboard/reviews')

    await expect(page.getByText(JIRA_KEY).first()).toBeVisible()
    await expect(page.getByText('GTX-4829')).toHaveCount(0)
    await expect(page.getByText(/Alert-8821/)).toHaveCount(0)
  })

  test('detail-tab counts: AI Review = comment total, Commits = 2, Checks = 3', async ({
    authenticatedPage: page,
  }) => {
    await stubCommonRoutes(page)
    await page.goto('/dashboard/reviews')

    // AI Review: derived from selectedReview.files.comments (2 comments).
    await expect(page.getByTestId('detail-tab-count-review')).toHaveText('2')

    // Commits + Checks populate once SWR settles.
    await expect(page.getByTestId('detail-tab-count-commits')).toHaveText('2')
    await expect(page.getByTestId('detail-tab-count-checks')).toHaveText('3')
  })

  test('Commits tab renders real shas + messages when selected', async ({
    authenticatedPage: page,
  }) => {
    await stubCommonRoutes(page)
    await page.goto('/dashboard/reviews')

    await page.getByRole('button', { name: /Commits/ }).click()

    const list = page.getByTestId('commit-list')
    await expect(list).toBeVisible()
    await expect(list.getByText('abcdef1')).toBeVisible()
    await expect(list.getByText('0011223')).toBeVisible()
    await expect(list.getByText('Add sliding-window rate limiter')).toBeVisible()
  })

  test('Checks tab renders real CI check rows when selected', async ({
    authenticatedPage: page,
  }) => {
    await stubCommonRoutes(page)
    await page.goto('/dashboard/reviews')

    await page.getByRole('button', { name: /^Checks/ }).click()

    const list = page.getByTestId('check-list')
    await expect(list).toBeVisible()
    await expect(list.getByText('ci / unit')).toBeVisible()
    await expect(list.getByText('ci / lint')).toBeVisible()
    await expect(list.getByText('coverage / report')).toBeVisible()
  })
})

test.describe('Code Review — empty states when no review is selected', () => {
  test('when review list is empty, header shows the empty summary, no fake counts', async ({
    authenticatedPage: page,
  }) => {
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
    await page.route('/api/admin/reviews', (route) =>
      route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify([]),
      }),
    )

    await page.goto('/dashboard/reviews')

    // Queue empty state
    await expect(page.getByText(/No code reviews yet/)).toBeVisible()

    // Summary bar shows the empty placeholder, not fake "3 issues / 7 files / took 12s"
    await expect(page.getByTestId('review-summary-empty')).toBeVisible()
    await expect(page.getByText(/took 12s/)).toHaveCount(0)
    await expect(page.getByText('.gotyme/review.yml')).toHaveCount(0)

    // Sidebar shows the empty hints, not hardcoded demo names.
    await expect(page.getByText(/Select a review to see reviewers/)).toBeVisible()
    await expect(page.getByText(/Select a review to see CI checks/)).toBeVisible()
    await expect(page.getByText(/No Jira ticket linked/)).toBeVisible()
  })
})
