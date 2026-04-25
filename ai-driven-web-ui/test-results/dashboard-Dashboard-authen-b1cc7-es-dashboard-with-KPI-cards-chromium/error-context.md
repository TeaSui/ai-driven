# Instructions

- Following Playwright test failed.
- Explain why, be concise, respect Playwright best practices.
- Provide a snippet of code with the fix, if possible.

# Test info

- Name: dashboard.spec.ts >> Dashboard >> authenticated user sees dashboard with KPI cards
- Location: e2e/dashboard.spec.ts:78:7

# Error details

```
Error: expect(locator).toBeVisible() failed

Locator: getByRole('heading', { name: /overview/i })
Expected: visible
Timeout: 5000ms
Error: element(s) not found

Call log:
  - Expect "toBeVisible" with timeout 5000ms
  - waiting for getByRole('heading', { name: /overview/i })

```

# Page snapshot

```yaml
- generic [active] [ref=e1]:
  - link "Skip to content" [ref=e2] [cursor=pointer]:
    - /url: "#main-content"
  - generic [ref=e3]:
    - complementary "Main navigation" [ref=e4]:
      - link "GoTyme·X AI" [ref=e6] [cursor=pointer]:
        - /url: /dashboard
        - generic [ref=e7]:
          - img [ref=e8]
          - generic [ref=e11]: GoTyme·X AI
      - navigation [ref=e12]:
        - button "Search (Command K)" [ref=e13] [cursor=pointer]:
          - img [ref=e14]
          - generic [ref=e17]: Search…
          - generic [ref=e18]: ⌘K
        - link "Timeline" [ref=e19] [cursor=pointer]:
          - /url: /dashboard/timeline
          - img [ref=e21]
          - generic [ref=e23]: Timeline
        - link "Coding Agent" [ref=e24] [cursor=pointer]:
          - /url: /dashboard/tasks
          - img [ref=e26]
          - generic [ref=e30]: Coding Agent
        - link "Code Review" [ref=e31] [cursor=pointer]:
          - /url: /dashboard/reviews
          - img [ref=e33]
          - generic [ref=e39]: Code Review
        - link "DevOps" [ref=e40] [cursor=pointer]:
          - /url: /dashboard/incidents
          - img [ref=e42]
          - generic [ref=e45]: DevOps
        - link "Metrics" [ref=e46] [cursor=pointer]:
          - /url: /dashboard/insights
          - img [ref=e48]
          - generic [ref=e51]: Metrics
        - link "Slack" [ref=e52] [cursor=pointer]:
          - /url: /dashboard/slack
          - img [ref=e54]
          - generic [ref=e59]: Slack
        - separator [ref=e60]: Configure
        - link "Repositories" [ref=e61] [cursor=pointer]:
          - /url: /dashboard/admin/repos
          - img [ref=e63]
          - generic [ref=e65]: Repositories
        - link "Review rules" [ref=e66] [cursor=pointer]:
          - /url: /dashboard/admin/rules
          - img [ref=e68]
          - generic [ref=e70]: Review rules
        - link "Run history" [ref=e71] [cursor=pointer]:
          - /url: /dashboard/admin/runs
          - img [ref=e73]
          - generic [ref=e76]: Run history
      - generic [ref=e78]:
        - generic "Team" [ref=e79]: T
        - generic [ref=e80]:
          - generic [ref=e81]: Loading...
          - generic [ref=e82]: Team
        - button "Switch to dark mode" [ref=e83] [cursor=pointer]:
          - img [ref=e84]
        - button "Sign out" [ref=e86] [cursor=pointer]:
          - img [ref=e87]
    - main [ref=e91]:
      - generic [ref=e92]:
        - generic [ref=e93]:
          - generic [ref=e94]:
            - list "Onboarding steps" [ref=e95]:
              - listitem "Step 1 (current)" [ref=e96]: "1"
              - listitem "Step 2" [ref=e98]: "2"
              - listitem "Step 3" [ref=e100]: "3"
              - listitem "Step 4" [ref=e102]: "4"
            - generic [ref=e103]: Step 1 of 4 — Welcome
          - button "Skip onboarding and go to dashboard" [ref=e104] [cursor=pointer]:
            - img [ref=e105]
        - generic [ref=e109]:
          - generic [ref=e110]:
            - img [ref=e112]
            - heading "Welcome to GoTymeX AI" [level=2] [ref=e114]:
              - text: Welcome to
              - emphasis [ref=e115]: GoTymeX AI
            - paragraph [ref=e116]: Your engineering org, augmented. Let us walk you through the basics in three quick steps.
          - list "Platform highlights" [ref=e117]:
            - listitem [ref=e118]:
              - img [ref=e120]
              - generic [ref=e124]:
                - generic [ref=e125]: Automated code changes
                - generic [ref=e126]: Agents read Jira tickets and open pull requests — fully automated, fully attributable.
            - listitem [ref=e127]:
              - img [ref=e129]
              - generic [ref=e133]:
                - generic [ref=e134]: Inline code review
                - generic [ref=e135]: Every PR gets an AI review with line-level comments before your team even looks at it.
            - listitem [ref=e136]:
              - img [ref=e138]
              - generic [ref=e140]:
                - generic [ref=e141]: Incident triage while you sleep
                - generic [ref=e142]: DevOps agent correlates alerts, finds root causes, and drafts remediation before you wake up.
        - button "Get started" [ref=e145] [cursor=pointer]:
          - text: Get started
          - img [ref=e147]
      - button "Skip and go to dashboard" [ref=e149] [cursor=pointer]
  - alert [ref=e150]
```

# Test source

```ts
  1   | /**
  2   |  * dashboard.spec.ts — Dashboard overview page tests
  3   |  *
  4   |  * Exercises:
  5   |  *  1. KPI / hero stats cards are rendered
  6   |  *  2. Sidebar navigation links work (Tasks, Reviews, Insights)
  7   |  *  3. Debug banner appears when the backend returns debug=true via /health
  8   |  *
  9   |  * All backend API calls are intercepted so tests are backend-independent.
  10  |  */
  11  | 
  12  | import { test, expect } from './fixtures'
  13  | 
  14  | /** Stub all /api/* routes with empty-but-valid responses. */
  15  | async function stubApiRoutes(page: import('@playwright/test').Page) {
  16  |   await page.route('/api/admin/dashboard', (route) =>
  17  |     route.fulfill({
  18  |       status: 200,
  19  |       contentType: 'application/json',
  20  |       body: JSON.stringify({
  21  |         data: {
  22  |           totalTasks: 10,
  23  |           completedTasks: 8,
  24  |           activeTasks: 2,
  25  |           costToday: 5.0,
  26  |           dailyBudget: 100.0,
  27  |           budgetUsagePercent: 5,
  28  |         },
  29  |       }),
  30  |     }),
  31  |   )
  32  | 
  33  |   await page.route('/api/admin/tasks*', (route) =>
  34  |     route.fulfill({
  35  |       status: 200,
  36  |       contentType: 'application/json',
  37  |       body: JSON.stringify({
  38  |         data: [
  39  |           {
  40  |             id: 'task-1',
  41  |             title: 'Fix race condition in ledger',
  42  |             status: 'completed',
  43  |             agentType: 'coding',
  44  |             createdAt: new Date().toISOString(),
  45  |           },
  46  |           {
  47  |             id: 'task-2',
  48  |             title: 'Update payment flow',
  49  |             status: 'running',
  50  |             agentType: 'coding',
  51  |             createdAt: new Date().toISOString(),
  52  |           },
  53  |         ],
  54  |       }),
  55  |     }),
  56  |   )
  57  | 
  58  |   await page.route('/api/admin/me', (route) =>
  59  |     route.fulfill({
  60  |       status: 200,
  61  |       contentType: 'application/json',
  62  |       body: JSON.stringify({ data: { teamName: 'GoTyme Team' } }),
  63  |     }),
  64  |   )
  65  | 
  66  |   // Stub all remaining /api/* calls
  67  |   await page.route('/api/**', (route) => {
  68  |     // Only fulfill if not already handled
  69  |     route.fulfill({
  70  |       status: 200,
  71  |       contentType: 'application/json',
  72  |       body: JSON.stringify({ data: [] }),
  73  |     })
  74  |   })
  75  | }
  76  | 
  77  | test.describe('Dashboard', () => {
  78  |   test('authenticated user sees dashboard with KPI cards', async ({
  79  |     authenticatedPage: page,
  80  |   }) => {
  81  |     await stubApiRoutes(page)
  82  | 
  83  |     // Stub /health so the debug banner does not appear by default
  84  |     await page.route('/health', (route) =>
  85  |       route.fulfill({
  86  |         status: 200,
  87  |         contentType: 'application/json',
  88  |         body: JSON.stringify({ status: 'ok', debug: false }),
  89  |       }),
  90  |     )
  91  | 
  92  |     await page.goto('/dashboard')
  93  | 
  94  |     // The HeroBanner contains stats — assert the heading text is visible
> 95  |     await expect(page.getByRole('heading', { name: /overview/i })).toBeVisible()
      |                                                                    ^ Error: expect(locator).toBeVisible() failed
  96  | 
  97  |     // KPI stat labels rendered in HeroBanner (any one is sufficient to confirm render)
  98  |     await expect(page.getByText(/PRs merged/i)).toBeVisible()
  99  |     await expect(page.getByText(/engineering hours saved/i)).toBeVisible()
  100 |   })
  101 | 
  102 |   test('sidebar navigation links navigate to the correct pages', async ({
  103 |     authenticatedPage: page,
  104 |   }) => {
  105 |     await stubApiRoutes(page)
  106 |     await page.route('/health', (route) =>
  107 |       route.fulfill({
  108 |         status: 200,
  109 |         contentType: 'application/json',
  110 |         body: JSON.stringify({ status: 'ok', debug: false }),
  111 |       }),
  112 |     )
  113 | 
  114 |     await page.goto('/dashboard')
  115 | 
  116 |     // Sidebar is rendered as <aside aria-label="Main navigation">
  117 |     const sidebar = page.getByRole('complementary', { name: /main navigation/i })
  118 |     await expect(sidebar).toBeVisible()
  119 | 
  120 |     // Tasks link (labeled "Coding Agent" in sidebar)
  121 |     const tasksLink = sidebar.getByRole('link', { name: /coding agent/i })
  122 |     await expect(tasksLink).toBeVisible()
  123 |     await tasksLink.click()
  124 |     await expect(page).toHaveURL(/\/dashboard\/tasks/)
  125 | 
  126 |     await page.goto('/dashboard')
  127 | 
  128 |     // Reviews link
  129 |     const reviewsLink = sidebar.getByRole('link', { name: /code review/i })
  130 |     await expect(reviewsLink).toBeVisible()
  131 |     await reviewsLink.click()
  132 |     await expect(page).toHaveURL(/\/dashboard\/reviews/)
  133 | 
  134 |     await page.goto('/dashboard')
  135 | 
  136 |     // Insights / Metrics link
  137 |     const insightsLink = sidebar.getByRole('link', { name: /metrics/i })
  138 |     await expect(insightsLink).toBeVisible()
  139 |     await insightsLink.click()
  140 |     await expect(page).toHaveURL(/\/dashboard\/insights/)
  141 |   })
  142 | 
  143 |   test('debug banner appears when backend returns debug=true', async ({
  144 |     authenticatedPage: page,
  145 |   }) => {
  146 |     await stubApiRoutes(page)
  147 | 
  148 |     // Return debug=true from /health
  149 |     await page.route('/health', (route) =>
  150 |       route.fulfill({
  151 |         status: 200,
  152 |         contentType: 'application/json',
  153 |         body: JSON.stringify({ status: 'ok', debug: true }),
  154 |       }),
  155 |     )
  156 | 
  157 |     await page.goto('/dashboard')
  158 | 
  159 |     // Debug banner has role="alert" per dashboard/layout.tsx
  160 |     const banner = page.getByRole('alert').filter({
  161 |       hasText: /debug mode active/i,
  162 |     })
  163 |     await expect(banner).toBeVisible()
  164 |     await expect(banner).toContainText(/authentication is bypassed/i)
  165 |   })
  166 | })
  167 | 
```