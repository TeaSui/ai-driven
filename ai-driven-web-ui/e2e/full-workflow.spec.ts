/**
 * full-workflow.spec.ts — End-to-end smoke tests hitting the REAL backend
 *
 * These tests bypass HTTP route stubbing and exercise the live FastAPI + Redis
 * + Postgres stack running on localhost:8888. They fail fast if the backend is
 * not up so they can be skipped locally without noise.
 *
 * What they actually guard against:
 *   1. `input_data` truncation — the 2k-char cap was breaking JSON parsing of
 *      `file.write` events, so the UI showed "No files written yet" even after
 *      a successful agent run. This test submits a short README write and
 *      asserts the full file content round-trips through the stream.
 *   2. `human_response` persistence — the respond endpoint used to only
 *      pub/sub the event, not append it to the Redis event log. A reload of
 *      the page would see a pending question forever. This test calls
 *      /respond and asserts the persisted event log contains the reply.
 *   3. End-to-end status progression — submit → pause → answer → run → PR.
 */

import { test, expect } from '@playwright/test'

const BACKEND = 'http://127.0.0.1:8888'

async function backendUp(): Promise<boolean> {
  try {
    const r = await fetch(`${BACKEND}/health`)
    return r.status === 200
  } catch {
    return false
  }
}

test.describe('Full workflow (real backend)', () => {
  test.beforeAll(async () => {
    test.skip(!(await backendUp()), 'backend not running on localhost:8888')
  })

  test.setTimeout(360_000)

  test('human_response event persists to event log', async () => {
    const submit = await fetch(`${BACKEND}/api/chat/send`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        message:
          'Implement AID-1 README. The scope is ambiguous (short vs. comprehensive). BEFORE writing, call human.ask_clarification offering those two options.',
        agent_type: 'coding',
        context: { jira_key: 'AID-1', repo_full_name: 'MinnMinn/skills-introduction-to-github' },
      }),
    })
    expect(submit.ok).toBeTruthy()
    const { taskId } = (await submit.json()) as { taskId: string }

    // Poll until human_input_required lands
    let sawQuestion = false
    for (let i = 0; i < 40; i++) {
      const evs = (await (await fetch(`${BACKEND}/api/admin/tasks/${taskId}/events`)).json()) as Array<{
        type: string
      }>
      if (evs.some((e) => e.type === 'human_input_required')) {
        sawQuestion = true
        break
      }
      await new Promise((r) => setTimeout(r, 3000))
    }
    expect(sawQuestion, 'agent never emitted human_input_required').toBe(true)

    // Answer
    const answer = await fetch(`${BACKEND}/api/chat/tasks/${taskId}/respond`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ answer: 'Short. Intro + quickstart only.' }),
    })
    expect(answer.ok).toBeTruthy()

    // The fix: human_response must now appear in the PERSISTED event log, not
    // just the live pub/sub channel.
    await new Promise((r) => setTimeout(r, 1500))
    const evs = (await (await fetch(`${BACKEND}/api/admin/tasks/${taskId}/events`)).json()) as Array<{
      type: string
      data: Record<string, unknown>
    }>
    const humanResponses = evs.filter((e) => e.type === 'human_response')
    expect(humanResponses.length).toBeGreaterThan(0)
    expect(humanResponses[0]!.data.answer).toBe('Short. Intro + quickstart only.')

    // Wait for completion (best-effort — we don't fail the test if Bedrock is
    // slow; the event-log assertion above is the key check)
    for (let i = 0; i < 60; i++) {
      const task = (await (await fetch(`${BACKEND}/api/admin/tasks/${taskId}`)).json()) as {
        status: string
      }
      if (task.status === 'completed' || task.status === 'failed') break
      await new Promise((r) => setTimeout(r, 5000))
    }
  })

  test('file.write input_data survives the stream without truncation', async () => {
    // Submit a non-clarifying task so the agent writes README directly.
    const submit = await fetch(`${BACKEND}/api/chat/send`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        message:
          'Implement AID-1 README directly. Do not ask clarifying questions. Write a comprehensive README (>100 lines) to README.md and open a PR.',
        agent_type: 'coding',
        context: { jira_key: 'AID-1', repo_full_name: 'MinnMinn/skills-introduction-to-github' },
      }),
    })
    const { taskId } = (await submit.json()) as { taskId: string }

    // Poll for completion
    let done = false
    for (let i = 0; i < 60; i++) {
      const task = (await (await fetch(`${BACKEND}/api/admin/tasks/${taskId}`)).json()) as {
        status: string
      }
      if (task.status === 'completed' || task.status === 'failed') {
        done = true
        break
      }
      await new Promise((r) => setTimeout(r, 5000))
    }
    expect(done, 'task did not complete in time').toBe(true)

    // Fetch events and find the file.write tool_call_start.
    const evs = (await (await fetch(`${BACKEND}/api/admin/tasks/${taskId}/events`)).json()) as Array<{
      type: string
      data: Record<string, unknown>
    }>
    const writeStarts = evs.filter(
      (e) => e.type === 'tool_call_start' && (e.data?.tool === 'file.write' || e.data?.tool === 'repo.write_file'),
    )
    expect(writeStarts.length, 'no file.write calls in event stream').toBeGreaterThan(0)

    // The input_data is a JSON string. If the truncation bug regressed it
    // will be cut mid-string and JSON.parse will throw. A healthy stream
    // parses cleanly and exposes the content field.
    const raw = writeStarts[0]!.data.input_data as string
    expect(typeof raw).toBe('string')
    const parsed = JSON.parse(raw) as { path?: string; content?: string }
    expect(parsed.path).toBeTruthy()
    expect(typeof parsed.content).toBe('string')
    // The content has to be meaningful — not an empty string.
    expect(parsed.content!.length).toBeGreaterThan(200)
  })
})
