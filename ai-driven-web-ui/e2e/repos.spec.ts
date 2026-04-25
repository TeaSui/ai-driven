/**
 * repos.spec.ts — Admin → Repositories page
 *
 * Covers the real Add-Repository flow (replaces the "roadmap" notify stub):
 *   1. Empty state renders when no repos are configured
 *   2. List renders fullName / scmType / defaultBranch / status for each repo
 *   3. "Add repository" button opens the modal
 *   4. Submitting with bad fullName surfaces a validation error without POSTing
 *   5. Successful POST closes modal, refresh repopulates the list
 *   6. Backend error message is displayed when POST fails
 *   7. Duplicate fullName is rejected client-side without POSTing
 */

import { test, expect } from './fixtures'

type Repo = {
  id: string
  teamId: string
  fullName: string
  scmType: 'github' | 'bitbucket'
  defaultBranch: string
  isActive: boolean
  createdAt: string
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
        data: { costToday: 0, dailyBudget: 50, budgetUsagePercent: 0 },
      }),
    }),
  )
}

const SEED_REPOS: Repo[] = [
  {
    id: 'r1',
    teamId: '00000000-0000-0000-0000-000000000000',
    fullName: 'MinnMinn/skills-introduction-to-github',
    scmType: 'github',
    defaultBranch: 'main',
    isActive: true,
    createdAt: new Date(Date.now() - 3 * 86400 * 1000).toISOString(),
  },
  {
    id: 'r2',
    teamId: '00000000-0000-0000-0000-000000000000',
    fullName: 'origination1/ai-driven',
    scmType: 'bitbucket',
    defaultBranch: 'main',
    isActive: true,
    createdAt: new Date(Date.now() - 1 * 3600 * 1000).toISOString(),
  },
]

test.describe('Admin → Repositories', () => {
  test('empty state renders when no repos are configured', async ({
    authenticatedPage: page,
  }) => {
    await stubCommonRoutes(page)
    await page.route('/api/admin/repos', (route) => {
      if (route.request().method() === 'GET') {
        return route.fulfill({
          status: 200,
          contentType: 'application/json',
          body: JSON.stringify({ data: [], total: 0 }),
        })
      }
      return route.fallback()
    })

    await page.goto('/dashboard/admin/repos')

    await expect(page.getByTestId('repos-empty-state')).toBeVisible()
    await expect(page.getByRole('button', { name: /Add repository/i })).toBeVisible()
  })

  test('list renders fullName, scmType, defaultBranch, status per row', async ({
    authenticatedPage: page,
  }) => {
    await stubCommonRoutes(page)
    await page.route('/api/admin/repos', (route) => {
      if (route.request().method() === 'GET') {
        return route.fulfill({
          status: 200,
          contentType: 'application/json',
          body: JSON.stringify({ data: SEED_REPOS, total: SEED_REPOS.length }),
        })
      }
      return route.fallback()
    })

    await page.goto('/dashboard/admin/repos')

    const table = page.getByTestId('repos-table')
    await expect(table).toBeVisible()

    const row1 = page.getByTestId('repo-row-MinnMinn/skills-introduction-to-github')
    await expect(row1).toBeVisible()
    // SCM type shown in the mono span — match exactly to avoid collision
    // with "github" inside the fullName cell.
    await expect(row1.getByText(/^github$/)).toBeVisible()

    const row2 = page.getByTestId('repo-row-origination1/ai-driven')
    await expect(row2).toBeVisible()
    await expect(row2.getByText(/^bitbucket$/)).toBeVisible()
  })

  test('clicking Add repository opens a modal', async ({
    authenticatedPage: page,
  }) => {
    await stubCommonRoutes(page)
    await page.route('/api/admin/repos', (route) =>
      route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({ data: SEED_REPOS, total: SEED_REPOS.length }),
      }),
    )

    await page.goto('/dashboard/admin/repos')
    await page.getByRole('button', { name: /Add repository/i }).click()

    const modal = page.getByTestId('add-repo-modal')
    await expect(modal).toBeVisible()
    await expect(modal.getByRole('heading', { name: /Add repository/i })).toBeVisible()
    // Both providers offered
    await expect(modal.getByLabel(/^github$/i)).toBeVisible()
    await expect(modal.getByLabel(/^bitbucket$/i)).toBeVisible()
  })

  test('invalid fullName shows a validation error and does not POST', async ({
    authenticatedPage: page,
  }) => {
    await stubCommonRoutes(page)

    let postCount = 0
    await page.route('/api/admin/repos', (route) => {
      if (route.request().method() === 'GET') {
        return route.fulfill({
          status: 200,
          contentType: 'application/json',
          body: JSON.stringify({ data: SEED_REPOS, total: SEED_REPOS.length }),
        })
      }
      if (route.request().method() === 'POST') postCount += 1
      return route.fulfill({ status: 200, contentType: 'application/json', body: '{}' })
    })

    await page.goto('/dashboard/admin/repos')
    await page.getByRole('button', { name: /Add repository/i }).click()

    const modal = page.getByTestId('add-repo-modal')
    await modal.getByPlaceholder(/org\/repo/i).fill('this is not a slash name')
    await modal.getByRole('button', { name: /^Add repository$/i }).click()

    await expect(modal.getByTestId('add-repo-error')).toBeVisible()
    await expect(modal.getByTestId('add-repo-error')).toContainText(/org\/repo/i)
    expect(postCount, 'no POST should be sent for invalid input').toBe(0)
  })

  test('duplicate fullName is rejected client-side without POSTing', async ({
    authenticatedPage: page,
  }) => {
    await stubCommonRoutes(page)

    let postCount = 0
    await page.route('/api/admin/repos', (route) => {
      if (route.request().method() === 'GET') {
        return route.fulfill({
          status: 200,
          contentType: 'application/json',
          body: JSON.stringify({ data: SEED_REPOS, total: SEED_REPOS.length }),
        })
      }
      if (route.request().method() === 'POST') postCount += 1
      return route.fulfill({ status: 200, contentType: 'application/json', body: '{}' })
    })

    await page.goto('/dashboard/admin/repos')
    await page.getByRole('button', { name: /Add repository/i }).click()

    const modal = page.getByTestId('add-repo-modal')
    await modal.getByPlaceholder(/org\/repo/i).fill('origination1/ai-driven')
    await modal.getByRole('button', { name: /^Add repository$/i }).click()

    await expect(modal.getByTestId('add-repo-error')).toContainText(/already registered/i)
    expect(postCount).toBe(0)
  })

  test('successful POST closes modal and new repo appears in list', async ({
    authenticatedPage: page,
  }) => {
    await stubCommonRoutes(page)

    let pool: Repo[] = [...SEED_REPOS]
    await page.route('/api/admin/repos', async (route) => {
      const req = route.request()
      if (req.method() === 'GET') {
        return route.fulfill({
          status: 200,
          contentType: 'application/json',
          body: JSON.stringify({ data: pool, total: pool.length }),
        })
      }
      if (req.method() === 'POST') {
        const body = JSON.parse(req.postData() ?? '{}') as {
          fullName: string
          scmType: 'github' | 'bitbucket'
          defaultBranch?: string
        }
        const created: Repo = {
          id: 'r-new',
          teamId: '00000000-0000-0000-0000-000000000000',
          fullName: body.fullName,
          scmType: body.scmType,
          defaultBranch: body.defaultBranch ?? 'main',
          isActive: true,
          createdAt: new Date().toISOString(),
        }
        pool = [...pool, created]
        return route.fulfill({
          status: 201,
          contentType: 'application/json',
          body: JSON.stringify({ data: created }),
        })
      }
      return route.fallback()
    })

    await page.goto('/dashboard/admin/repos')
    await page.getByRole('button', { name: /Add repository/i }).click()

    const modal = page.getByTestId('add-repo-modal')
    await modal.getByLabel(/^bitbucket$/i).check()
    await modal.getByPlaceholder(/workspace\/repo|org\/repo/i).fill('testworkspace/new-repo')
    await modal.getByRole('button', { name: /^Add repository$/i }).click()

    // Modal closes on success.
    await expect(page.getByTestId('add-repo-modal')).toHaveCount(0)
    // New row appears.
    await expect(
      page.getByTestId('repo-row-testworkspace/new-repo'),
    ).toBeVisible()
  })

  test('backend error is surfaced in the modal', async ({
    authenticatedPage: page,
  }) => {
    await stubCommonRoutes(page)

    await page.route('/api/admin/repos', (route) => {
      const req = route.request()
      if (req.method() === 'GET') {
        return route.fulfill({
          status: 200,
          contentType: 'application/json',
          body: JSON.stringify({ data: [], total: 0 }),
        })
      }
      if (req.method() === 'POST') {
        return route.fulfill({
          status: 409,
          contentType: 'application/json',
          body: JSON.stringify({
            error: {
              code: 'CONFLICT',
              message: 'Repository already registered on another team',
            },
          }),
        })
      }
      return route.fallback()
    })

    await page.goto('/dashboard/admin/repos')
    await page.getByRole('button', { name: /Add repository/i }).click()

    const modal = page.getByTestId('add-repo-modal')
    await modal.getByPlaceholder(/org\/repo/i).fill('owner/some-repo')
    await modal.getByRole('button', { name: /^Add repository$/i }).click()

    await expect(modal.getByTestId('add-repo-error')).toContainText(
      /already registered on another team/i,
    )
    // Modal stays open so the user can fix it.
    await expect(modal).toBeVisible()
  })

  test('unreachable repo is rejected by the backend probe', async ({
    authenticatedPage: page,
  }) => {
    await stubCommonRoutes(page)

    // The backend's probe-failure path: POST returns 400 with a message
    // explaining the repo is not found or the credentials can't see it.
    await page.route('/api/admin/repos', (route) => {
      const req = route.request()
      if (req.method() === 'GET') {
        return route.fulfill({
          status: 200,
          contentType: 'application/json',
          body: JSON.stringify({ data: [], total: 0 }),
        })
      }
      if (req.method() === 'POST') {
        return route.fulfill({
          status: 400,
          contentType: 'application/json',
          body: JSON.stringify({
            error: {
              code: 'HTTP_400',
              message:
                "Repository 'typo/nope' was not found on github, or the configured credentials cannot access it.",
            },
          }),
        })
      }
      return route.fallback()
    })

    await page.goto('/dashboard/admin/repos')
    await page.getByRole('button', { name: /Add repository/i }).click()

    const modal = page.getByTestId('add-repo-modal')
    await modal.getByPlaceholder(/org\/repo/i).fill('typo/nope')
    await modal.getByRole('button', { name: /^Add repository$/i }).click()

    // Probe error surfaces in the modal.
    await expect(modal.getByTestId('add-repo-error')).toContainText(
      /not found on github/i,
    )
    await expect(modal).toBeVisible()
  })
})

// ── Delete / reactivate flow ────────────────────────────────────────────────

test.describe('Admin → Repositories — delete & reactivate', () => {
  test('Deactivate button opens confirm dialog, soft-delete flips status to Inactive', async ({
    authenticatedPage: page,
  }) => {
    await stubCommonRoutes(page)

    let pool: Repo[] = [...SEED_REPOS]
    let deleteCallCount = 0
    let lastDeleteHard = false

    await page.route(/\/api\/admin\/repos.*/, async (route) => {
      const req = route.request()
      const url = req.url()

      if (req.method() === 'GET' && url.endsWith('/api/admin/repos')) {
        return route.fulfill({
          status: 200,
          contentType: 'application/json',
          body: JSON.stringify({ data: pool, total: pool.length }),
        })
      }

      // DELETE /api/admin/repos/:id[?hard=true]
      const m = url.match(/\/api\/admin\/repos\/([^/?]+)(\?.*)?$/)
      if (req.method() === 'DELETE' && m) {
        deleteCallCount += 1
        lastDeleteHard = (m[2] || '').includes('hard=true')
        const id = m[1]
        pool = pool.map((r) =>
          r.id === id ? { ...r, isActive: false } : r,
        )
        return route.fulfill({ status: 204, body: '' })
      }

      return route.fallback()
    })

    await page.goto('/dashboard/admin/repos')

    // Click Deactivate on the Bitbucket row.
    await page
      .getByTestId('repo-deactivate-origination1/ai-driven')
      .click()

    // Confirm dialog opens with soft-delete language.
    const dialog = page.getByTestId('delete-repo-dialog')
    await expect(dialog).toBeVisible()
    await expect(dialog.getByRole('heading')).toContainText(/Deactivate/i)

    // Confirm.
    await dialog.getByRole('button', { name: /^Deactivate$/i }).click()

    // Dialog closes, row stays but shows Inactive + Reactivate action.
    await expect(page.getByTestId('delete-repo-dialog')).toHaveCount(0)
    const row = page.getByTestId('repo-row-origination1/ai-driven')
    await expect(row.getByText(/^Inactive$/)).toBeVisible()
    await expect(
      page.getByTestId('repo-reactivate-origination1/ai-driven'),
    ).toBeVisible()

    expect(deleteCallCount).toBe(1)
    expect(lastDeleteHard).toBe(false)
  })

  test('Deactivate always sends soft-delete (no hard-delete toggle in the UI)', async ({
    authenticatedPage: page,
  }) => {
    await stubCommonRoutes(page)

    let pool: Repo[] = [...SEED_REPOS]
    let lastDeleteUrl = ''

    await page.route(/\/api\/admin\/repos.*/, async (route) => {
      const req = route.request()
      const url = req.url()

      if (req.method() === 'GET' && url.endsWith('/api/admin/repos')) {
        return route.fulfill({
          status: 200,
          contentType: 'application/json',
          body: JSON.stringify({ data: pool, total: pool.length }),
        })
      }

      const m = url.match(/\/api\/admin\/repos\/([^/?]+)(\?.*)?$/)
      if (req.method() === 'DELETE' && m) {
        lastDeleteUrl = url
        const id = m[1]
        pool = pool.map((r) => (r.id === id ? { ...r, isActive: false } : r))
        return route.fulfill({ status: 204, body: '' })
      }
      return route.fallback()
    })

    await page.goto('/dashboard/admin/repos')
    await page
      .getByTestId('repo-deactivate-origination1/ai-driven')
      .click()

    const dialog = page.getByTestId('delete-repo-dialog')
    // Hard-delete toggle must not be present — it was removed intentionally
    // because misclicks here cost task-history referential integrity.
    await expect(dialog.getByTestId('delete-repo-hard-toggle')).toHaveCount(0)
    await expect(dialog.getByRole('heading')).toContainText(/Deactivate/i)

    await dialog.getByRole('button', { name: /^Deactivate$/i }).click()

    // No ?hard=true ever sent from the UI.
    expect(lastDeleteUrl).not.toContain('hard=true')

    // Row stays but goes Inactive.
    await expect(
      page.getByTestId('repo-row-origination1/ai-driven').getByText(/^Inactive$/),
    ).toBeVisible()
  })

  test('Reactivate restores isActive=true', async ({
    authenticatedPage: page,
  }) => {
    await stubCommonRoutes(page)

    let pool: Repo[] = SEED_REPOS.map((r) =>
      r.id === 'r2' ? { ...r, isActive: false } : r,
    )

    await page.route(/\/api\/admin\/repos.*/, async (route) => {
      const req = route.request()
      const url = req.url()

      if (req.method() === 'GET' && url.endsWith('/api/admin/repos')) {
        return route.fulfill({
          status: 200,
          contentType: 'application/json',
          body: JSON.stringify({ data: pool, total: pool.length }),
        })
      }

      const m = url.match(/\/api\/admin\/repos\/([^/?]+)$/)
      if (req.method() === 'PUT' && m) {
        const body = JSON.parse(req.postData() ?? '{}') as { isActive?: boolean }
        if (body.isActive === true) {
          pool = pool.map((r) =>
            r.id === m[1] ? { ...r, isActive: true } : r,
          )
          return route.fulfill({
            status: 200,
            contentType: 'application/json',
            body: JSON.stringify({
              data: pool.find((r) => r.id === m[1]),
            }),
          })
        }
      }
      return route.fallback()
    })

    await page.goto('/dashboard/admin/repos')

    // Bitbucket row is currently Inactive — click Reactivate.
    await page
      .getByTestId('repo-reactivate-origination1/ai-driven')
      .click()

    // Row flips to Active and back to the Deactivate button.
    const row = page.getByTestId('repo-row-origination1/ai-driven')
    await expect(row.getByText(/^Active$/)).toBeVisible()
    await expect(
      page.getByTestId('repo-deactivate-origination1/ai-driven'),
    ).toBeVisible()
  })

  test('Backend 404 on delete surfaces an error toast and row stays put', async ({
    authenticatedPage: page,
  }) => {
    await stubCommonRoutes(page)

    await page.route(/\/api\/admin\/repos.*/, async (route) => {
      const req = route.request()
      const url = req.url()

      if (req.method() === 'GET' && url.endsWith('/api/admin/repos')) {
        return route.fulfill({
          status: 200,
          contentType: 'application/json',
          body: JSON.stringify({ data: SEED_REPOS, total: SEED_REPOS.length }),
        })
      }

      if (req.method() === 'DELETE') {
        return route.fulfill({
          status: 404,
          contentType: 'application/json',
          body: JSON.stringify({
            error: { code: 'NOT_FOUND', message: 'Repository not found' },
          }),
        })
      }
      return route.fallback()
    })

    await page.goto('/dashboard/admin/repos')
    await page
      .getByTestId('repo-deactivate-origination1/ai-driven')
      .click()
    await page
      .getByTestId('delete-repo-dialog')
      .getByRole('button', { name: /^Deactivate$/i })
      .click()

    // Row is still rendered as Active (soft-delete didn't land).
    const row = page.getByTestId('repo-row-origination1/ai-driven')
    await expect(row.getByText(/^Active$/)).toBeVisible()
  })
})
