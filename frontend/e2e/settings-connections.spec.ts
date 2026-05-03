/**
 * settings-connections.spec.ts — PR 2: Settings > Connections UI.
 *
 * Covers:
 *   1. /dashboard/settings redirects to /dashboard/settings/connections.
 *   2. Empty list → both cards show Disconnected + Connect button.
 *   3. One GitHub connection with `public_repo` scope → Healthy state with
 *      Test, Disconnect, and "Upgrade to full access" buttons.
 *   4. Scope `repo` → Upgrade button is hidden.
 *   5. ?error=SCM_IDENTITY_CLAIMED&scmUsername=alice → red toast with the
 *      interpolated username.
 */

import { test, expect } from './fixtures'

async function stubBase(page: import('@playwright/test').Page) {
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
      body: JSON.stringify({
        data: { teamId: 'team-abc', teamName: 'Engineering' },
      }),
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

test.describe('Settings > Connections', () => {
  test('/dashboard/settings redirects to /connections', async ({
    authenticatedPage: page,
  }) => {
    await stubBase(page)
    // Endpoint returns a BARE ARRAY (not {data, meta}) — matches PR 1's
    // admin_connections router.
    await page.route('/api/admin/me/connections', (route) =>
      route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify([]),
      }),
    )

    await page.goto('/dashboard/settings')
    await expect(page).toHaveURL(/\/dashboard\/settings\/connections$/)
  })

  test('empty list shows both providers in disconnected state', async ({
    authenticatedPage: page,
  }) => {
    await stubBase(page)
    await page.route('/api/admin/me/connections', (route) =>
      route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify([]),
      }),
    )

    await page.goto('/dashboard/settings/connections')

    const githubCard = page.getByTestId('connection-card-github')
    const bitbucketCard = page.getByTestId('connection-card-bitbucket')
    await expect(githubCard).toBeVisible()
    await expect(bitbucketCard).toBeVisible()
    await expect(githubCard).toHaveAttribute('data-state', 'disconnected')
    await expect(bitbucketCard).toHaveAttribute('data-state', 'disconnected')
    await expect(
      githubCard.getByRole('button', { name: /Connect GitHub/i }),
    ).toBeVisible()
    await expect(
      bitbucketCard.getByRole('button', { name: /Connect Bitbucket/i }),
    ).toBeVisible()
  })

  test('public_repo connection shows Upgrade + Test + Disconnect', async ({
    authenticatedPage: page,
  }) => {
    await stubBase(page)
    await page.route('/api/admin/me/connections', (route) =>
      route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify([
          {
            id: 'conn-1',
            provider: 'github',
            scmUsername: 'octocat',
            scmEmail: 'octocat@github.com',
            scope: 'public_repo read:user',
            expiresAt: null,
            connectedAt: '2026-05-01T10:00:00Z',
            lastUsedAt: null,
          },
        ]),
      }),
    )

    await page.goto('/dashboard/settings/connections')

    const githubCard = page.getByTestId('connection-card-github')
    await expect(githubCard).toHaveAttribute('data-state', 'healthy')
    await expect(githubCard).toContainText('@octocat')
    await expect(
      githubCard.getByRole('button', { name: /Upgrade to full access/i }),
    ).toBeVisible()
    await expect(
      githubCard.getByRole('button', { name: /^Test$/i }),
    ).toBeVisible()
    await expect(
      githubCard.getByRole('button', { name: /Disconnect GitHub/i }),
    ).toBeVisible()
  })

  test('full repo scope hides the Upgrade button', async ({
    authenticatedPage: page,
  }) => {
    await stubBase(page)
    await page.route('/api/admin/me/connections', (route) =>
      route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify([
          {
            id: 'conn-2',
            provider: 'github',
            scmUsername: 'octocat',
            scmEmail: null,
            scope: 'repo read:user',
            expiresAt: null,
            connectedAt: '2026-05-01T10:00:00Z',
            lastUsedAt: null,
          },
        ]),
      }),
    )

    await page.goto('/dashboard/settings/connections')

    const githubCard = page.getByTestId('connection-card-github')
    await expect(githubCard).toHaveAttribute('data-state', 'healthy')
    await expect(
      githubCard.getByRole('button', { name: /Upgrade to full access/i }),
    ).toHaveCount(0)
  })

  test('?error=SCM_IDENTITY_CLAIMED shows interpolated username toast', async ({
    authenticatedPage: page,
  }) => {
    await stubBase(page)
    await page.route('/api/admin/me/connections', (route) =>
      route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify([]),
      }),
    )

    await page.goto(
      '/dashboard/settings/connections?error=SCM_IDENTITY_CLAIMED&scmUsername=alice',
    )

    // The notify helper renders into a top-right live region; query by text.
    await expect(
      page.getByText(/Another member of your team is already connected as @alice/i),
    ).toBeVisible()

    // Query string is stripped so a refresh doesn't re-fire the toast.
    await expect(page).toHaveURL(/\/dashboard\/settings\/connections$/)
  })

  test('disconnect inline-confirm: first click transforms, second click fires DELETE', async ({
    authenticatedPage: page,
  }) => {
    await stubBase(page)
    await page.route('/api/admin/me/connections', (route) => {
      if (route.request().method() === 'GET') {
        return route.fulfill({
          status: 200,
          contentType: 'application/json',
          body: JSON.stringify([
            {
              id: 'conn-3',
              provider: 'github',
              scmUsername: 'octocat',
              scmEmail: null,
              scope: 'public_repo read:user',
              expiresAt: null,
              connectedAt: '2026-05-01T10:00:00Z',
              lastUsedAt: null,
            },
          ]),
        })
      }
      return route.fallback()
    })

    let deleteCount = 0
    await page.route('/api/admin/me/connections/github', (route) => {
      if (route.request().method() === 'DELETE') {
        deleteCount += 1
        return route.fulfill({ status: 204, body: '' })
      }
      return route.fallback()
    })

    await page.goto('/dashboard/settings/connections')
    const githubCard = page.getByTestId('connection-card-github')
    const disconnect = githubCard.getByTestId('disconnect-btn')

    await disconnect.click()
    await expect(disconnect).toHaveAttribute('data-confirm-open', 'true')
    await expect(disconnect).toContainText(/Click again to confirm/i)
    expect(deleteCount, 'no DELETE on the first click').toBe(0)

    await disconnect.click()
    await expect.poll(() => deleteCount).toBe(1)
  })
})
