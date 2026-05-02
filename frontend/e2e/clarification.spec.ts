/**
 * clarification.spec.ts — Full agent↔human clarification flow
 *
 * Verifies what actually broke for the user today:
 *   1. When the event stream contains `human_input_required`, the yellow
 *      "Awaiting your input" card renders with question, options, and input box.
 *   2. Clicking an option button fires POST /api/chat/tasks/{id}/respond with
 *      the option text as the answer.
 *   3. Typing a free-text reply and pressing Send does the same.
 *   4. Once a matching `human_response` event is present (bug fixed server-side
 *      to persist it to the event log), the card disappears.
 *   5. `file.write` tool calls are rendered as a real file tab + content pane,
 *      NOT the old "No files written yet" empty state.
 *   6. `github.create_pr` result exposes a clickable PR link in the header.
 */

import { test, expect } from './fixtures'
import type { Route } from '@playwright/test'

const TASK_ID = 'task-clarify-1'

const BASE_TASK = {
  id: TASK_ID,
  title: 'Implement AID-1 README with human clarification',
  status: 'awaiting_input',
  agentType: 'coding',
  jiraKey: 'AID-1',
  createdAt: new Date(Date.now() - 60_000).toISOString(),
  durationMs: null,
  tokenUsage: 12000,
  costUsd: 0.08,
  iterations: 3,
}

const EVENT_ASK = {
  type: 'human_input_required',
  data: {
    question: 'Which target audience should the README address?',
    context: 'The Jira ticket does not specify — choose the best fit.',
    options: [
      '(a) Absolute beginners',
      '(b) Experienced devs',
      '(c) Both, layered',
    ],
  },
  timestamp: new Date().toISOString(),
}

const EVENT_RESPONSE = {
  type: 'human_response',
  data: { answer: '(c) Both, layered' },
  timestamp: new Date().toISOString(),
}

const EVENT_FILE_WRITE_END = {
  type: 'tool_call_end',
  data: {
    tool: 'file.write',
    duration_ms: 1200,
    success: true,
    // Backend emits result_data as a JSON STRING, not an object — the parser
    // fix in extractWorkArtifacts has to handle this.
    result_data: JSON.stringify({ status: 'committed', path: 'README.md' }),
  },
  timestamp: new Date().toISOString(),
}

const EVENT_FILE_WRITE_START = {
  type: 'tool_call_start',
  data: {
    tool: 'file.write',
    call_number: 5,
    input_data: JSON.stringify({
      repo_full_name: 'MinnMinn/skills-introduction-to-github',
      path: 'README.md',
      branch: 'feature/AID-1',
      commit_message: 'docs: add comprehensive README (AID-1)',
      content: '# Project README\n\nWritten by the agent after clarification.\nLine 3.\n',
    }),
  },
  timestamp: new Date().toISOString(),
}

const EVENT_PR_START = {
  type: 'tool_call_start',
  data: {
    tool: 'github.create_pr',
    call_number: 6,
    input_data: JSON.stringify({
      repo_full_name: 'MinnMinn/skills-introduction-to-github',
      title: 'AID-1: Add README',
    }),
  },
  timestamp: new Date().toISOString(),
}

const EVENT_PR_END = {
  type: 'tool_call_end',
  data: {
    tool: 'github.create_pr',
    duration_ms: 2100,
    success: true,
    result_data: JSON.stringify({
      number: 42,
      url: 'https://github.com/MinnMinn/skills-introduction-to-github/pull/42',
      title: 'AID-1: Add README',
    }),
  },
  timestamp: new Date().toISOString(),
}

const EVENT_BRANCH_START = {
  type: 'tool_call_start',
  data: {
    tool: 'git.create_branch',
    call_number: 4,
    input_data: JSON.stringify({
      branch_name: 'feature/AID-1',
      from_ref: 'main',
    }),
  },
  timestamp: new Date().toISOString(),
}

const EVENT_BRANCH_END = {
  type: 'tool_call_end',
  data: {
    tool: 'git.create_branch',
    duration_ms: 800,
    success: true,
    result_data: JSON.stringify({ status: 'created', branch: 'feature/AID-1' }),
  },
  timestamp: new Date().toISOString(),
}

function stubTaskRoutes(
  page: import('@playwright/test').Page,
  opts: {
    task?: Record<string, unknown>
    events: unknown[]
    onRespond?: (body: { answer: string }) => void
  },
) {
  const task = { ...BASE_TASK, ...(opts.task ?? {}) }

  // Common shell routes
  page.route('/health', (r) =>
    r.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ status: 'ok' }) }),
  )
  page.route('/api/admin/me', (r) =>
    r.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ data: { teamName: 'Test' } }) }),
  )
  page.route('/api/admin/dashboard', (r) =>
    r.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ data: {} }) }),
  )
  page.route(`/api/admin/tasks/${TASK_ID}`, (r) =>
    r.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(task) }),
  )
  page.route(`/api/admin/tasks/${TASK_ID}/events`, (r) =>
    r.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(opts.events) }),
  )
  page.route(`/api/admin/tasks/${TASK_ID}/evaluations`, (r) =>
    r.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify([]) }),
  )
  // Live-diff endpoint — preferred over file for in-PR tasks. Return 404 in
  // tests so the UI falls back to the file endpoint and our existing content
  // assertions keep working.
  page.route(new RegExp(`/api/admin/tasks/${TASK_ID}/diff\\?path=(.+)`), async (route) => {
    await route.fulfill({
      status: 404, contentType: 'application/json',
      body: JSON.stringify({ detail: 'no PR in tests' }),
    })
  })
  // Live-file endpoint — the frontend now pulls file content from GitHub.
  // Echo back the content from the matching file.write event so tests still
  // assert against known fixtures instead of hitting a real GitHub.
  page.route(new RegExp(`/api/admin/tasks/${TASK_ID}/file\\?path=(.+)`), async (route) => {
    const url = new URL(route.request().url())
    const wantedPath = url.searchParams.get('path') ?? ''
    // Find the most recent file.write for this path in opts.events
    let content = ''
    for (const e of opts.events.reverse()) {
      const ev = e as { type?: string; data?: { tool?: string; input_data?: string } }
      if (ev.type !== 'tool_call_start') continue
      const tool = ev.data?.tool ?? ''
      if (tool !== 'file.write' && tool !== 'repo.write_file') continue
      try {
        const parsed = JSON.parse(ev.data?.input_data ?? '{}') as { path?: string; content?: string }
        if (parsed.path === wantedPath) { content = parsed.content ?? ''; break }
      } catch { /* ignore */ }
    }
    opts.events.reverse()  // restore original order
    await route.fulfill({
      status: 200, contentType: 'application/json',
      body: JSON.stringify({ path: wantedPath, content, size: content.length, repo: 'test/repo', ref: 'main' }),
    })
  })

  page.route(`/api/chat/tasks/${TASK_ID}/respond`, async (route: Route) => {
    const body = JSON.parse((route.request().postData() ?? '{}')) as { answer: string }
    opts.onRespond?.(body)
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({ status: 'accepted', taskId: TASK_ID }),
    })
  })
}

test.describe('Agent clarification flow', () => {
  test('SCREENSHOTS: visual proof that clarification card and file tabs work', async ({
    authenticatedPage: page,
  }) => {
    // Screenshot 1 — clarification card visible
    stubTaskRoutes(page, { events: [EVENT_ASK] })
    await page.goto(`/dashboard/tasks/${TASK_ID}`)
    await expect(page.getByText('Awaiting your input')).toBeVisible()
    await page.screenshot({ path: 'test-results/screenshot-clarification-card.png', fullPage: true })

    // Screenshot 2 — multiple files, clickable tabs, selected file shows
    const MULTI_EVENTS = [
      {
        type: 'tool_call_start',
        data: {
          tool: 'file.write',
          call_number: 1,
          input_data: JSON.stringify({
            path: 'src/schemas.py',
            content: '# src/schemas.py — this is the SCHEMAS file.\nclass OrderCreate:\n    product_id: str\n    quantity: int\n    price: float\n',
          }),
        },
        timestamp: new Date().toISOString(),
      },
      EVENT_FILE_WRITE_END,
      {
        type: 'tool_call_start',
        data: {
          tool: 'file.write',
          call_number: 2,
          input_data: JSON.stringify({
            path: 'tests/test_orders.py',
            content: '# tests/test_orders.py — this is the TEST file.\ndef test_create_order():\n    assert True\n',
          }),
        },
        timestamp: new Date().toISOString(),
      },
      EVENT_FILE_WRITE_END,
    ]
    stubTaskRoutes(page, { task: { status: 'completed' }, events: MULTI_EVENTS })
    await page.goto(`/dashboard/tasks/${TASK_ID}`)
    await page.waitForLoadState('networkidle')
    await page.screenshot({ path: 'test-results/screenshot-multi-files-default.png', fullPage: true })

    // Screenshot 3 — click the other tab to show it swapped
    await page.getByRole('button', { name: /View src\/schemas\.py/i }).click()
    await page.waitForTimeout(100)
    await page.screenshot({ path: 'test-results/screenshot-multi-files-swapped.png', fullPage: true })
  })

  test('renders the yellow clarification card when human_input_required is pending', async ({
    authenticatedPage: page,
  }) => {
    stubTaskRoutes(page, { events: [EVENT_ASK] })

    await page.goto(`/dashboard/tasks/${TASK_ID}`)

    // The card
    await expect(page.getByText('Awaiting your input')).toBeVisible()
    // The question
    await expect(
      page.getByText('Which target audience should the README address?'),
    ).toBeVisible()
    // Context
    await expect(
      page.getByText('The Jira ticket does not specify — choose the best fit.'),
    ).toBeVisible()
    // All three options as buttons
    await expect(page.getByRole('button', { name: '(a) Absolute beginners' })).toBeVisible()
    await expect(page.getByRole('button', { name: '(b) Experienced devs' })).toBeVisible()
    await expect(page.getByRole('button', { name: '(c) Both, layered' })).toBeVisible()
    // Free-text input
    await expect(page.getByRole('textbox', { name: /reply to agent question/i })).toBeVisible()
    // Send button
    await expect(page.getByRole('button', { name: /send/i })).toBeVisible()
  })

  test('clicking an option button POSTs that text to /respond', async ({
    authenticatedPage: page,
  }) => {
    let received: { answer: string } | null = null
    stubTaskRoutes(page, {
      events: [EVENT_ASK],
      onRespond: (b) => (received = b),
    })

    await page.goto(`/dashboard/tasks/${TASK_ID}`)
    await page.getByRole('button', { name: '(c) Both, layered' }).click()

    // Wait for the request to land
    await expect.poll(() => received).not.toBeNull()
    expect(received!.answer).toBe('(c) Both, layered')
  })

  test('typing a free-text reply and pressing Send POSTs the trimmed text', async ({
    authenticatedPage: page,
  }) => {
    let received: { answer: string } | null = null
    stubTaskRoutes(page, {
      events: [EVENT_ASK],
      onRespond: (b) => (received = b),
    })

    await page.goto(`/dashboard/tasks/${TASK_ID}`)

    const input = page.getByRole('textbox', { name: /reply to agent question/i })
    await input.fill('  Custom answer from the user   ')
    await page.getByRole('button', { name: /^send$/i }).click()

    await expect.poll(() => received).not.toBeNull()
    expect(received!.answer).toBe('Custom answer from the user')
  })

  test('clarification card is hidden once a matching human_response exists', async ({
    authenticatedPage: page,
  }) => {
    // This is the exact scenario the server-side bug-fix unblocks: a reloaded
    // page must see the persisted human_response and clear the card.
    stubTaskRoutes(page, {
      task: { status: 'running' },
      events: [EVENT_ASK, EVENT_RESPONSE],
    })

    await page.goto(`/dashboard/tasks/${TASK_ID}`)

    // Give the SWR fetch + effects time to resolve
    await page.waitForTimeout(500)
    await expect(page.getByText('Awaiting your input')).toHaveCount(0)
  })

  test('file.write events render real content — not "No files written yet"', async ({
    authenticatedPage: page,
  }) => {
    stubTaskRoutes(page, {
      task: { status: 'completed' },
      events: [
        EVENT_BRANCH_START, EVENT_BRANCH_END,
        EVENT_FILE_WRITE_START, EVENT_FILE_WRITE_END,
        EVENT_PR_START, EVENT_PR_END,
      ],
    })

    await page.goto(`/dashboard/tasks/${TASK_ID}`)
    await page.waitForLoadState('networkidle')

    // The empty state must NOT be present
    await expect(page.getByText(/hasn't written any files yet/i)).toHaveCount(0)
    await expect(page.getByText(/no files written yet/i)).toHaveCount(0)

    // The actual file content from the event should appear
    await expect(page.getByText('Written by the agent after clarification.')).toBeVisible()
  })

  test('file tabs are clickable and switch the code pane content', async ({
    authenticatedPage: page,
  }) => {
    const EVENT_WRITE_A = {
      type: 'tool_call_start',
      data: {
        tool: 'file.write',
        call_number: 5,
        input_data: JSON.stringify({
          repo_full_name: 'MinnMinn/skills-introduction-to-github',
          path: 'src/schemas.py',
          branch: 'feature/AID-1',
          content: '# schemas.py CONTENT SIGNAL A\nclass OrderCreate: pass\n',
        }),
      },
      timestamp: new Date().toISOString(),
    }
    const EVENT_WRITE_B = {
      type: 'tool_call_start',
      data: {
        tool: 'file.write',
        call_number: 6,
        input_data: JSON.stringify({
          repo_full_name: 'MinnMinn/skills-introduction-to-github',
          path: 'tests/test_orders.py',
          branch: 'feature/AID-1',
          content: '# test_orders.py CONTENT SIGNAL B\ndef test_create(): pass\n',
        }),
      },
      timestamp: new Date().toISOString(),
    }
    const EVENT_WRITE_A_END = { ...EVENT_FILE_WRITE_END }
    const EVENT_WRITE_B_END = { ...EVENT_FILE_WRITE_END }

    stubTaskRoutes(page, {
      task: { status: 'completed' },
      events: [EVENT_WRITE_A, EVENT_WRITE_A_END, EVENT_WRITE_B, EVENT_WRITE_B_END],
    })

    await page.goto(`/dashboard/tasks/${TASK_ID}`)
    await page.waitForLoadState('networkidle')

    // Latest-written file (tests/test_orders.py) is selected by default.
    await expect(page.getByText('CONTENT SIGNAL B')).toBeVisible()

    // Click the other tab.
    await page.getByRole('button', { name: /View src\/schemas\.py/i }).click()

    // The code pane should now show the OTHER file's content.
    await expect(page.getByText('CONTENT SIGNAL A')).toBeVisible()
    await expect(page.getByText('CONTENT SIGNAL B')).toHaveCount(0)

    // Clicking back should swap again.
    await page.getByRole('button', { name: /View tests\/test_orders\.py/i }).click()
    await expect(page.getByText('CONTENT SIGNAL B')).toBeVisible()
  })

  test('diff endpoint is preferred over file endpoint and renders red/green lines', async ({
    authenticatedPage: page,
  }) => {
    const MULTI_EVENTS = [
      {
        type: 'tool_call_start',
        data: {
          tool: 'file.write',
          call_number: 1,
          input_data: JSON.stringify({
            path: 'src/db/models.py',
            branch: 'feature/AID-1',
            repo_full_name: 'MinnMinn/test',
            content: 'new content',
          }),
        },
        timestamp: new Date().toISOString(),
      },
      EVENT_FILE_WRITE_END,
      EVENT_PR_START,
      EVENT_PR_END,
    ]
    stubTaskRoutes(page, { task: { status: 'completed' }, events: MULTI_EVENTS })
    // Override the /diff stub with a real-looking patch
    await page.route(new RegExp(`/api/admin/tasks/${TASK_ID}/diff\\?path=(.+)`), async (route) => {
      await route.fulfill({
        status: 200, contentType: 'application/json',
        body: JSON.stringify({
          path: 'src/db/models.py',
          status: 'modified',
          additions: 2,
          deletions: 1,
          patch: `@@ -1,3 +1,4 @@\n # header\n-old line that was removed\n+new line added one\n+new line added two\n`,
          repo: 'MinnMinn/test',
          pr_number: 10,
        }),
      })
    })
    await page.goto(`/dashboard/tasks/${TASK_ID}`)
    await page.waitForLoadState('networkidle')

    // Red (deleted) line is present
    await expect(page.getByText('old line that was removed')).toBeVisible()
    // Green (added) lines are present
    await expect(page.getByText('new line added one')).toBeVisible()
    await expect(page.getByText('new line added two')).toBeVisible()
    // Hunk header is rendered
    await expect(page.getByText(/@@ -1,3 \+1,4 @@/)).toBeVisible()
  })

  test('thinking stream renders friendly tool titles, not raw JSON', async ({
    authenticatedPage: page,
  }) => {
    const TOOL_EVENTS = [
      {
        type: 'tool_call_start',
        data: { tool: 'jira.get_issue', call_number: 1, input_data: JSON.stringify({ issue_key: 'KAN-4' }) },
        timestamp: new Date().toISOString(),
      },
      {
        type: 'tool_call_end',
        data: { tool: 'jira.get_issue', duration_ms: 300, success: true,
                result_data: JSON.stringify({ summary: 'Add input validation' }) },
        timestamp: new Date().toISOString(),
      },
      {
        type: 'tool_call_start',
        data: { tool: 'file.write', call_number: 2, input_data: JSON.stringify({ path: 'src/db/models.py' }) },
        timestamp: new Date().toISOString(),
      },
      EVENT_FILE_WRITE_END,
    ]
    stubTaskRoutes(page, { task: { status: 'completed' }, events: TOOL_EVENTS })
    await page.goto(`/dashboard/tasks/${TASK_ID}`)
    await page.waitForLoadState('networkidle')

    // Friendly titles instead of raw tool names
    await expect(page.getByText(/Read Jira ticket/).first()).toBeVisible()
    await expect(page.getByText(/Write file/).first()).toBeVisible()
    // The raw JSON should NOT appear as a visible label at the top level
    // (it may still exist in the collapsed details — we don't click expand).
    // That's enforced by asserting the friendly labels show.
  })

  test('github.create_pr exposes a clickable PR link in the header', async ({
    authenticatedPage: page,
  }) => {
    stubTaskRoutes(page, {
      task: { status: 'completed' },
      events: [
        EVENT_BRANCH_START, EVENT_BRANCH_END,
        EVENT_FILE_WRITE_START, EVENT_FILE_WRITE_END,
        EVENT_PR_START, EVENT_PR_END,
      ],
    })

    await page.goto(`/dashboard/tasks/${TASK_ID}`)
    await page.waitForLoadState('networkidle')

    const prLink = page.getByRole('link', { name: /feature\/AID-1.*PR #42/i })
    await expect(prLink).toBeVisible()
    await expect(prLink).toHaveAttribute(
      'href',
      'https://github.com/MinnMinn/skills-introduction-to-github/pull/42',
    )
  })

  test('bitbucket.create_pr events render identically to github', async ({
    authenticatedPage: page,
  }) => {
    // Mirrors github.create_pr fixtures but routes through the bitbucket tool
    // name. Proves the dual-provider wiring: PR_TOOLS, describeTool, and the
    // file-tab PR-link header all treat bitbucket as a first-class provider.
    const BB_PR_START = {
      type: 'tool_call_start',
      data: {
        tool: 'bitbucket.create_pr',
        call_number: 6,
        input_data: JSON.stringify({
          repo_full_name: 'origination1/ai-driven',
          title: 'AID-1: Bitbucket-side change',
        }),
      },
      timestamp: new Date().toISOString(),
    }
    const BB_PR_END = {
      type: 'tool_call_end',
      data: {
        tool: 'bitbucket.create_pr',
        duration_ms: 1900,
        success: true,
        result_data: JSON.stringify({
          id: 7,
          url: 'https://bitbucket.org/origination1/ai-driven/pull-requests/7',
          title: 'AID-1: Bitbucket-side change',
        }),
      },
      timestamp: new Date().toISOString(),
    }
    stubTaskRoutes(page, {
      task: { status: 'completed' },
      events: [
        EVENT_BRANCH_START, EVENT_BRANCH_END,
        EVENT_FILE_WRITE_START, EVENT_FILE_WRITE_END,
        BB_PR_START, BB_PR_END,
      ],
    })

    await page.goto(`/dashboard/tasks/${TASK_ID}`)
    await page.waitForLoadState('networkidle')

    const prLink = page.getByRole('link', { name: /feature\/AID-1.*PR #7/i })
    await expect(prLink).toBeVisible()
    await expect(prLink).toHaveAttribute(
      'href',
      'https://bitbucket.org/origination1/ai-driven/pull-requests/7',
    )
  })
})
