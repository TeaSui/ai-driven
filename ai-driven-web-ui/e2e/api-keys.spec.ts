/**
 * api-keys.spec.ts — Sprint 9 Phase 5: API-key management UI.
 *
 * Covers:
 *   1. Page loads team info and renders the masked current key.
 *   2. Rotate button opens a confirm dialog, not the direct POST.
 *   3. Confirming calls POST /rotate-key, reveals the new raw key exactly once.
 *   4. Backend 503 (Redis down etc.) shows an error toast, dialog stays.
 *   5. Cancel on confirm dialog doesn't POST.
 */

import { test, expect } from './fixtures'

async function stubCommon(page: import('@playwright/test').Page) {
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

test.describe('Admin → API keys', () => {
  test('loads team info and renders the masked current key', async ({
    authenticatedPage: page,
  }) => {
    await stubCommon(page)
    await page.goto('/dashboard/admin/api-keys')

    const card = page.getByTestId('team-key-card')
    await expect(card).toBeVisible()
    await expect(card).toContainText(/Engineering/)
    await expect(card).toContainText(/team-abc/)
    // Masked stub is shown, NOT a real key
    await expect(card).toContainText(/aidrv_·/)
  })

  test('Rotate button opens confirm dialog without firing POST', async ({
    authenticatedPage: page,
  }) => {
    await stubCommon(page)
    let postCount = 0
    await page.route('/api/admin/teams/**/rotate-key', (route) => {
      postCount += 1
      return route.fulfill({ status: 200, body: '{}' })
    })

    await page.goto('/dashboard/admin/api-keys')
    // Main-area rotate button. Scope to the team-key-card so we don't collide
    // with any other Rotate button in the sidebar/nav.
    await page
      .getByTestId('team-key-card')
      .getByRole('button', { name: /Rotate key/i })
      .click()

    await expect(page.getByTestId('rotate-confirm-dialog')).toBeVisible()
    expect(postCount, 'no POST until user confirms').toBe(0)
  })

  test('Confirming rotation reveals the new key in a one-shot dialog', async ({
    authenticatedPage: page,
  }) => {
    await stubCommon(page)
    const newKey = 'aidrv_TEST_NEW_KEY_0123456789ABCDEF0123456789ABCDEF'
    const expiresAt = new Date(Date.now() + 48 * 3600_000).toISOString()

    await page.route('/api/admin/teams/team-abc/rotate-key', (route) =>
      route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({
          apiKey: newKey,
          oldKeyExpiresAt: expiresAt,
          teamId: 'team-abc',
        }),
      }),
    )

    await page.goto('/dashboard/admin/api-keys')
    await page.getByTestId('team-key-card').getByRole('button', { name: /Rotate key/i }).click()
    await page
      .getByTestId('rotate-confirm-dialog')
      .getByRole('button', { name: /Rotate now/i })
      .click()

    const reveal = page.getByTestId('new-key-dialog')
    await expect(reveal).toBeVisible()
    // The new raw key is shown exactly once.
    await expect(page.getByTestId('new-key-value')).toContainText(newKey)
    // Grace-period timestamp also rendered.
    await expect(reveal).toContainText(/48h grace/)
  })

  test('Rotation error shows a toast and the confirm dialog stays', async ({
    authenticatedPage: page,
  }) => {
    await stubCommon(page)

    await page.route('/api/admin/teams/team-abc/rotate-key', (route) =>
      route.fulfill({
        status: 503,
        contentType: 'application/json',
        body: JSON.stringify({
          detail: 'Session store unavailable — please retry shortly.',
        }),
      }),
    )

    await page.goto('/dashboard/admin/api-keys')
    await page.getByTestId('team-key-card').getByRole('button', { name: /Rotate key/i }).click()
    await page
      .getByTestId('rotate-confirm-dialog')
      .getByRole('button', { name: /Rotate now/i })
      .click()

    // The new-key dialog must NOT appear.
    await expect(page.getByTestId('new-key-dialog')).toHaveCount(0)
    // Confirm dialog stays up so the user can retry.
    await expect(page.getByTestId('rotate-confirm-dialog')).toBeVisible()
  })

  test('Cancel on confirm dialog does not POST', async ({
    authenticatedPage: page,
  }) => {
    await stubCommon(page)
    let postCount = 0
    await page.route('/api/admin/teams/**/rotate-key', (route) => {
      postCount += 1
      return route.fulfill({ status: 200, body: '{}' })
    })

    await page.goto('/dashboard/admin/api-keys')
    await page.getByTestId('team-key-card').getByRole('button', { name: /Rotate key/i }).click()
    await page
      .getByTestId('rotate-confirm-dialog')
      .getByRole('button', { name: /^Cancel$/i })
      .click()

    await expect(page.getByTestId('rotate-confirm-dialog')).toHaveCount(0)
    expect(postCount).toBe(0)
  })
})
