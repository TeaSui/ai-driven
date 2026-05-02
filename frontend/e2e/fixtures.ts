import { test as base, type Page } from '@playwright/test'

/**
 * Test API key injected as a cookie so the middleware treats the session as
 * authenticated.  The backend is mocked at the HTTP layer — this value just
 * needs to be a non-empty string that passes the middleware check and any
 * intercepted fetch calls we stub in individual tests.
 */
export const TEST_API_KEY = 'test_key_playwright'

/**
 * Sets the api_key cookie on the current page before a test begins.
 * The cookie path is "/" and sameSite is "Lax" so it is sent with same-origin
 * navigations while still being accessible to middleware.
 */
async function injectAuthCookie(page: Page): Promise<void> {
  await page.context().addCookies([
    {
      name: 'api_key',
      value: TEST_API_KEY,
      domain: 'localhost',
      path: '/',
      sameSite: 'Lax',
      httpOnly: false,
      secure: false,
    },
  ])
}

type Fixtures = {
  authenticatedPage: Page
}

/**
 * Extended test fixture that provides a `Page` pre-seeded with the auth cookie.
 * Use this in every test that requires an authenticated session.
 *
 * @example
 * import { test, expect } from './fixtures'
 * test('dashboard loads', async ({ authenticatedPage }) => { ... })
 */
export const test = base.extend<Fixtures>({
  authenticatedPage: async ({ page }, use) => {
    await injectAuthCookie(page)
    await use(page)
  },
})

export { expect } from '@playwright/test'
