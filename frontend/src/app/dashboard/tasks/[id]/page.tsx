'use client'

import { useState, useEffect, useRef, useMemo } from 'react'
import { use } from 'react'
import useSWR from 'swr'
import {
  Sparkles,
  Code2,
  FileCode,
  Terminal,
  Play,
  Pause,
  X,
  ArrowRight,
} from 'lucide-react'
import { Pill } from '@/components/ui/pill'
import { Btn } from '@/components/ui/btn'
import { BrandMark } from '@/components/ui/brand-mark'
import { StatusDot } from '@/components/ui/status-dot'
import { Spinner } from '@/components/ui/spinner'
import {
  getTask,
  getTaskEvents,
  getTaskEvaluations,
  pauseTask,
  resumeTask,
  stopTask,
  respondToTask,
  extractPendingQuestion,
  getTaskFile,
  getTaskDiff,
} from '@/lib/api'
import type { AgentEvent, TaskEvaluation, PendingQuestion } from '@/lib/api'
import { SpecialistsTab } from '@/components/specialists/specialists-tab'

type TaskDetailTab = 'overview' | 'specialists'

const TERMINAL_STATUSES = new Set(['completed', 'failed', 'cancelled'])
const ACTIVE_STATUSES = new Set(['queued', 'running', 'coding', 'planning', 'testing', 'awaiting_input'])
const DEMO_MODE = process.env.NEXT_PUBLIC_DEMO_MODE === 'true'

// ── Step types ────────────────────────────────────────────────────────────────

type StepKind = 'think' | 'tool' | 'edit' | 'response' | 'cache'

interface AgentStep {
  kind: StepKind
  t: number
  text?: string
  name?: string
  args?: string
  result?: string
  file?: string
  op?: string
  summary?: string
  iteration?: number
  cacheRead?: number
  cacheWrite?: number
}

// ── Static demo data (GTX-4829) ───────────────────────────────────────────────

const DEMO_STEPS: AgentStep[] = [
  { kind: 'think', t: 0, text: 'Reading ticket GTX-4829 — "Fix race condition in ledger balance cache"' },
  { kind: 'tool', t: 400, name: 'jira.getIssue', args: 'GTX-4829', result: 'Found · 3 comments, 2 ACs' },
  { kind: 'think', t: 900, text: 'The description mentions intermittent failures when two concurrent transfers read the same account balance. Need to locate the cache layer.' },
  { kind: 'tool', t: 1400, name: 'github.search', args: 'BalanceCache ledger-core', result: '6 matches across 3 files' },
  { kind: 'tool', t: 1700, name: 'fs.readFile', args: 'internal/cache/balance.go', result: '218 lines' },
  { kind: 'think', t: 2100, text: 'I see it — `Get()` does a check-then-set without locking. Under concurrent load, two callers can both miss the cache and write stale reads. Need a sync.Map + singleflight.' },
  { kind: 'tool', t: 2600, name: 'fs.readFile', args: 'internal/cache/balance_test.go', result: '94 lines · 4 tests' },
  { kind: 'think', t: 3000, text: "Existing tests don't cover concurrency. I'll add a race test that reproduces the bug first, then fix it." },
  { kind: 'edit', t: 3400, file: 'internal/cache/balance_test.go', op: '+41 −0', summary: 'Add TestBalanceCache_ConcurrentReads' },
  { kind: 'tool', t: 3900, name: 'shell.run', args: 'go test -race ./internal/cache/...', result: 'FAIL · DATA RACE detected' },
  { kind: 'think', t: 4300, text: 'Reproduced. Now fixing.' },
  { kind: 'edit', t: 4700, file: 'internal/cache/balance.go', op: '+17 −17', summary: 'Wrap Get() in singleflight.Group' },
  { kind: 'tool', t: 5300, name: 'shell.run', args: 'go test -race ./internal/cache/...', result: 'PASS · 4/4 tests, 0 races' },
  { kind: 'think', t: 5800, text: 'Fix works. Running the full suite + linting before opening a PR.' },
  { kind: 'tool', t: 6300, name: 'shell.run', args: 'go test ./... && golangci-lint run', result: 'PASS · 312/312 tests' },
  { kind: 'tool', t: 6900, name: 'git.commit', args: 'fix(ledger): guard BalanceCache.Get() with singleflight', result: 'a3f21c8' },
  { kind: 'tool', t: 7300, name: 'github.openPR', args: 'fix/GTX-4829-ledger-race → main', result: 'PR #2419 opened' },
]

// ── FileTab ───────────────────────────────────────────────────────────────────

function FileTab({
  file,
  dir,
  active,
  dirty,
  added,
  onClick,
}: {
  file: string
  dir: string
  active?: boolean
  dirty?: boolean
  added?: boolean
  onClick?: () => void
}) {
  return (
    <button
      type="button"
      onClick={onClick}
      aria-pressed={active}
      aria-label={`View ${dir}/${file}`}
      style={{
        display: 'inline-flex',
        alignItems: 'center',
        gap: 6,
        padding: '10px 14px',
        borderRight: '1px solid var(--panel-border)',
        borderTop: 'none', borderLeft: 'none',
        background: active ? 'var(--bg)' : 'transparent',
        borderBottom: active ? '2px solid var(--accent-500)' : '2px solid transparent',
        fontSize: 12,
        color: active ? 'var(--text)' : 'var(--text-muted)',
        cursor: 'pointer',
        position: 'relative',
        marginBottom: -1,
        font: 'inherit',
      }}
    >
      <FileCode size={11} />
      <span className="gtx-mono" style={{ color: 'var(--text-subtle)' }}>
        {dir}/
      </span>
      <span className="gtx-mono" style={{ fontWeight: active ? 500 : 400 }}>
        {file}
      </span>
      {added && (
        <span style={{ fontSize: 10, color: 'var(--status-success)', fontWeight: 600 }}>
          NEW
        </span>
      )}
      {dirty && !added && (
        <span
          style={{
            width: 5,
            height: 5,
            borderRadius: 3,
            background: 'var(--accent-500)',
          }}
        />
      )}
    </button>
  )
}

// ── Real-work artifacts extractor ─────────────────────────────────────────────
// Derive file edits + shell runs from the live event stream. No more hardcoded
// balance.go / balance_test.go / "gh pr create --fill" demo content.

const FILE_WRITE_TOOLS = new Set([
  'file.write', 'file_write', 'repo.write_file', 'repo_write_file', 'write_file',
])
const SHELL_TOOLS = new Set([
  'sandbox.execute', 'sandbox_execute', 'shell.run', 'shell_run', 'sandbox.run_tests',
])
const GIT_BRANCH_TOOLS = new Set(['git.create_branch', 'git_create_branch', 'create_branch'])
const PR_TOOLS = new Set([
  'github.create_pr', 'github_create_pr', 'create_pr', 'scm.create_pr',
  'bitbucket.create_pr', 'bitbucket.create_pull_request',
])

interface FileEdit { path: string; content: string; message?: string; branch?: string }
interface ShellRun { command: string; exitCode?: number; stdout?: string; stderr?: string }

function extractWorkArtifacts(events: AgentEvent[]): {
  edits: FileEdit[]; runs: ShellRun[]; branch?: string; prNumber?: number; prUrl?: string
} {
  const edits: FileEdit[] = []
  const runs: ShellRun[] = []
  let branch: string | undefined
  let prNumber: number | undefined
  let prUrl: string | undefined
  const pendingByName: Record<string, Record<string, unknown>> = {}

  // Best-effort extraction of key=value from a JSON-ish string even if it's
  // truncated mid-stream. Older tasks have input_data capped at 2000 chars by
  // a now-fixed backend bug, so JSON.parse fails. We still want to surface at
  // least the path so the file tab renders — and as much content as survived.
  function salvage(raw: string): Record<string, unknown> {
    const out: Record<string, unknown> = {}
    // Match `"key": "value"` allowing escaped chars in value. Greedy so we
    // pick up as much of a truncated string as possible when the closing
    // quote isn't there.
    for (const key of ['path', 'content', 'branch', 'commit_message', 'message', 'branch_name', 'command', 'args']) {
      const m = raw.match(new RegExp(`"${key}"\\s*:\\s*"((?:\\\\.|[^"\\\\])*)`, 's'))
      if (m) {
        try {
          out[key] = JSON.parse(`"${m[1]}"`)
        } catch {
          out[key] = m[1].replace(/\\n/g, '\n').replace(/\\t/g, '\t').replace(/\\"/g, '"')
        }
      }
    }
    return out
  }

  for (const e of events) {
    const d = (e.data || {}) as Record<string, unknown>
    const toolName = (d.tool || d.tool_name || d.name || '') as string
    if (!toolName) continue
    let input = (d.input_data || d.input || {}) as unknown
    if (typeof input === 'string') {
      try {
        input = JSON.parse(input as string)
      } catch {
        input = salvage(input as string)
      }
    }
    if (e.type === 'tool_call_start') {
      pendingByName[toolName] = (input as Record<string, unknown>) ?? {}
      continue
    }
    if (e.type !== 'tool_call_end') continue

    const merged = { ...(pendingByName[toolName] || {}), ...(input as Record<string, unknown>) }
    // result_data is emitted as a JSON string by the backend; parse if needed.
    let resultRaw: unknown = d.result_data ?? d.result ?? {}
    if (typeof resultRaw === 'string') {
      try { resultRaw = JSON.parse(resultRaw as string) } catch { resultRaw = salvage(resultRaw as string) }
    }
    const result = (resultRaw || {}) as Record<string, unknown>

    if (FILE_WRITE_TOOLS.has(toolName)) {
      const p = (merged.path as string) || (result.path as string) || ''
      const c = (merged.content as string) || ''
      if (p) edits.push({
        path: p, content: c,
        message: (merged.commit_message || merged.message) as string | undefined,
        branch: merged.branch as string | undefined,
      })
    } else if (SHELL_TOOLS.has(toolName)) {
      runs.push({
        command: (merged.command || merged.args || toolName) as string,
        exitCode: result.exit_code as number | undefined,
        stdout: result.stdout as string | undefined,
        stderr: result.stderr as string | undefined,
      })
    } else if (GIT_BRANCH_TOOLS.has(toolName)) {
      branch = (merged.branch_name || merged.branch || result.branch) as string | undefined
    } else if (PR_TOOLS.has(toolName)) {
      // GitHub uses pr_number/number, Bitbucket uses id
      prNumber = (result.pr_number ?? result.number ?? result.id) as number | undefined
      prUrl = (result.pr_url || result.html_url || result.url) as string | undefined
    }
  }
  return { edits, runs, branch, prNumber, prUrl }
}

function splitDir(path: string): { dir: string; name: string } {
  const i = path.lastIndexOf('/')
  return i < 0 ? { dir: '', name: path } : { dir: path.slice(0, i), name: path.slice(i + 1) }
}

function RealCodeContent({ content }: { content: string | undefined | null }) {
  const lines = (content ?? '').split('\n')
  return (
    <div style={{ minWidth: 'max-content', padding: '8px 0' }}>
      {lines.map((line, i) => (
        <div key={i} style={{ display: 'flex' }}>
          <span style={{
            width: 48, padding: '0 10px', textAlign: 'right',
            color: 'var(--text-faint)', fontSize: 11, userSelect: 'none', flexShrink: 0,
          }}>{i + 1}</span>
          <span style={{ flex: 1, color: 'var(--code-text)', whiteSpace: 'pre' }}>{line || '\u00a0'}</span>
        </div>
      ))}
    </div>
  )
}

/**
 * Fetches the file from GitHub via the backend and renders its full content.
 * The event-stream `content` is kept as a fallback: older tasks had truncated
 * content in their event log, but the backend's /file endpoint reads from
 * GitHub using the repo + branch captured in the events. So live content is
 * always correct, regardless of event-stream fidelity.
 */
function LiveFileContent({
  taskId,
  path,
  fallback,
}: {
  taskId: string
  path: string
  fallback: string
}) {
  const { data, error, isLoading } = useSWR(
    ['task-file', taskId, path],
    () => getTaskFile(taskId, path),
    { revalidateOnFocus: false, shouldRetryOnError: false },
  )

  if (isLoading) {
    return (
      <div style={{
        display: 'flex', alignItems: 'center', justifyContent: 'center',
        height: '100%', color: 'var(--text-faint)', fontSize: 12.5,
        padding: 32, gap: 8,
      }}>
        <Spinner size={12} />
        Loading {path} from GitHub…
      </div>
    )
  }
  // If the live fetch failed (no token, branch deleted, private repo, etc.)
  // fall back to whatever content the event stream captured — partial is
  // better than blank.
  if (error || !data) return <RealCodeContent content={fallback || ''} />
  return <RealCodeContent content={data.content} />
}

/**
 * Renders a GitHub-style unified diff with red/green line coloring.
 * Parses the `patch` field (standard unified-diff format) into rows:
 *   - hunk headers `@@ -a,b +c,d @@`  → blue bar
 *   - context lines starting with ' ' → neutral
 *   - additions starting with '+'     → green
 *   - deletions starting with '-'     → red
 */
type DiffRow =
  | { kind: 'hunk'; text: string }
  | { kind: 'ctx' | 'add' | 'del'; oldNo: number | null; newNo: number | null; text: string }

function parsePatch(patch: string): DiffRow[] {
  const rows: DiffRow[] = []
  let oldNo = 0
  let newNo = 0
  for (const raw of patch.split('\n')) {
    if (raw.startsWith('@@')) {
      const m = raw.match(/@@\s+-(\d+)(?:,\d+)?\s+\+(\d+)(?:,\d+)?\s+@@/)
      if (m) { oldNo = parseInt(m[1], 10); newNo = parseInt(m[2], 10) }
      rows.push({ kind: 'hunk', text: raw })
      continue
    }
    if (raw.startsWith('+') && !raw.startsWith('+++')) {
      rows.push({ kind: 'add', oldNo: null, newNo: newNo++, text: raw.slice(1) })
    } else if (raw.startsWith('-') && !raw.startsWith('---')) {
      rows.push({ kind: 'del', oldNo: oldNo++, newNo: null, text: raw.slice(1) })
    } else if (raw.startsWith(' ')) {
      rows.push({ kind: 'ctx', oldNo: oldNo++, newNo: newNo++, text: raw.slice(1) })
    } else if (raw === '') {
      rows.push({ kind: 'ctx', oldNo: null, newNo: null, text: '' })
    }
    // Silently drop diff headers like `diff --git`, `index ...`, `+++ `, `--- `.
  }
  return rows
}

function DiffRenderer({ rows }: { rows: DiffRow[] }) {
  return (
    <div style={{ minWidth: 'max-content', padding: '8px 0' }}>
      {rows.map((r, i) => {
        if (r.kind === 'hunk') {
          return (
            <div key={i} style={{
              padding: '4px 12px', fontSize: 11.5,
              background: 'var(--diff-hunk-bg, rgba(56,139,253,0.12))',
              color: 'var(--diff-hunk-fg, #58a6ff)', whiteSpace: 'pre',
            }}>{r.text}</div>
          )
        }
        const bg =
          r.kind === 'add' ? 'var(--diff-add-bg, rgba(46,160,67,0.15))' :
          r.kind === 'del' ? 'var(--diff-del-bg, rgba(248,81,73,0.15))' :
          'transparent'
        const leftBar =
          r.kind === 'add' ? 'var(--diff-add-line, rgb(46,160,67))' :
          r.kind === 'del' ? 'var(--diff-del-line, rgb(248,81,73))' :
          'transparent'
        const sign = r.kind === 'add' ? '+' : r.kind === 'del' ? '-' : ' '
        const signColor =
          r.kind === 'add' ? 'var(--status-success, rgb(46,160,67))' :
          r.kind === 'del' ? 'var(--status-danger, rgb(248,81,73))' :
          'var(--text-faint)'
        return (
          <div key={i} style={{
            display: 'flex', background: bg,
            borderLeft: `2px solid ${leftBar}`,
          }}>
            <span style={{
              width: 44, padding: '0 8px', textAlign: 'right',
              color: 'var(--text-faint)', fontSize: 11, userSelect: 'none', flexShrink: 0,
            }}>{r.oldNo ?? ''}</span>
            <span style={{
              width: 44, padding: '0 8px', textAlign: 'right',
              color: 'var(--text-faint)', fontSize: 11, userSelect: 'none', flexShrink: 0,
            }}>{r.newNo ?? ''}</span>
            <span style={{
              width: 14, color: signColor, flexShrink: 0, textAlign: 'center',
              userSelect: 'none',
            }}>{sign}</span>
            <span style={{ flex: 1, color: 'var(--code-text)', whiteSpace: 'pre' }}>
              {r.text || '\u00a0'}
            </span>
          </div>
        )
      })}
    </div>
  )
}

/**
 * Preferred code-pane renderer: shows the PR diff with red/green coloring.
 * Falls back to full-file view if the task has no PR yet (e.g. still running)
 * or if the diff endpoint returns 404.
 */
function LiveFileDiff({
  taskId,
  path,
  fallback,
}: {
  taskId: string
  path: string
  fallback: string
}) {
  const { data, error, isLoading } = useSWR(
    ['task-diff', taskId, path],
    () => getTaskDiff(taskId, path),
    { revalidateOnFocus: false, shouldRetryOnError: false },
  )

  if (isLoading) {
    return (
      <div style={{
        display: 'flex', alignItems: 'center', justifyContent: 'center',
        height: '100%', color: 'var(--text-faint)', fontSize: 12.5,
        padding: 32, gap: 8,
      }}>
        <Spinner size={12} />
        Loading diff…
      </div>
    )
  }
  if (error || !data || !data.patch) {
    // No PR yet, or the file isn't in the PR (e.g. still streaming).
    return <LiveFileContent taskId={taskId} path={path} fallback={fallback} />
  }
  const rows = parsePatch(data.patch)
  return <DiffRenderer rows={rows} />
}

// ── ClarificationCard ────────────────────────────────────────────────────────
//
// Renders when the agent invoked `human.ask_clarification` and hasn't received
// an answer yet. The UI shows the question, any offered options as quick
// buttons, and a free-text reply field. Submitting calls
// POST /api/chat/tasks/{id}/respond, which resolves the agent's pending future.

function ClarificationCard({
  taskId,
  events,
  taskStatus,
}: {
  taskId: string
  events: AgentEvent[]
  taskStatus?: string
}) {
  const pending: PendingQuestion | null = useMemo(
    () => extractPendingQuestion(events, taskStatus), [events, taskStatus],
  )
  const [answer, setAnswer] = useState('')
  const [submitting, setSubmitting] = useState(false)
  const [error, setError] = useState<string | null>(null)

  // Clear local draft whenever a new question arrives or the current one is answered.
  useEffect(() => { setAnswer(''); setError(null) }, [pending?.question])

  if (!pending) return null

  const submit = async (value: string) => {
    const trimmed = value.trim()
    if (!trimmed) { setError('Please enter a reply.'); return }
    setSubmitting(true)
    setError(null)
    try {
      await respondToTask(taskId, trimmed)
      setAnswer('')
    } catch (e) {
      setError(e instanceof Error ? e.message : 'Failed to send reply')
    } finally {
      setSubmitting(false)
    }
  }

  return (
    <div
      role="dialog"
      aria-live="polite"
      style={{
        margin: '12px 20px 0',
        padding: '14px 16px',
        background: 'var(--accent-soft)',
        border: '1px solid var(--brand-teal-200)',
        borderRadius: 10,
      }}
    >
      <div style={{ display: 'flex', alignItems: 'center', gap: 8, marginBottom: 6 }}>
        <Pill tone="warn" size="xs" dot>Awaiting your input</Pill>
        <span style={{ fontSize: 11, color: 'var(--text-subtle)' }}>
          The agent paused to ask you a question.
        </span>
      </div>
      <div style={{ fontSize: 14, fontWeight: 500, color: 'var(--text)', marginBottom: 4 }}>
        {pending.question}
      </div>
      {pending.context && (
        <div style={{ fontSize: 12, color: 'var(--text-muted)', marginBottom: 10 }}>
          {pending.context}
        </div>
      )}

      {pending.options && pending.options.length > 0 && (
        <div style={{ display: 'flex', flexWrap: 'wrap', gap: 6, marginBottom: 10 }}>
          {pending.options.map((opt) => (
            <Btn
              key={opt}
              size="sm"
              tone="brand"
              disabled={submitting}
              onClick={() => submit(opt)}
            >
              {opt}
            </Btn>
          ))}
        </div>
      )}

      <form
        onSubmit={(e) => { e.preventDefault(); submit(answer) }}
        style={{ display: 'flex', gap: 8 }}
      >
        <input
          type="text"
          value={answer}
          onChange={(e) => setAnswer(e.target.value)}
          placeholder="Type your reply…"
          disabled={submitting}
          autoFocus
          aria-label="Reply to agent question"
          style={{
            flex: 1,
            padding: '8px 10px',
            fontSize: 13,
            border: '1px solid var(--border)',
            borderRadius: 6,
            background: 'var(--bg)',
            color: 'var(--text)',
            outline: 'none',
          }}
        />
        <Btn type="submit" tone="brand" size="sm" disabled={submitting || !answer.trim()}>
          {submitting ? 'Sending…' : 'Send'}
        </Btn>
      </form>
      {error && (
        <div style={{ marginTop: 6, fontSize: 12, color: 'var(--status-danger)' }}>
          {error}
        </div>
      )}
    </div>
  )
}


// ── MainCodePane ──────────────────────────────────────────────────────────────

function MainCodePane({ events, taskId }: { events: AgentEvent[]; taskId: string }) {
  const { edits, branch, prNumber, prUrl } = useMemo(
    () => extractWorkArtifacts(events), [events],
  )
  // Deduplicate by path — if the agent writes the same file twice, keep only
  // the latest content for that path. Preserve the order of first appearance.
  const uniqueEdits = useMemo(() => {
    const map = new Map<string, FileEdit>()
    const order: string[] = []
    for (const e of edits) {
      if (!map.has(e.path)) order.push(e.path)
      map.set(e.path, e)
    }
    return order.map((p) => map.get(p)!)
  }, [edits])

  const [selectedPath, setSelectedPath] = useState<string | null>(null)
  // Auto-select the most recently written file until the user picks one.
  const effectivePath = selectedPath ?? uniqueEdits[uniqueEdits.length - 1]?.path ?? null
  const selected = uniqueEdits.find((e) => e.path === effectivePath)

  return (
    <div
      style={{
        borderRight: '1px solid var(--panel-border)',
        display: 'flex',
        flexDirection: 'column',
        minHeight: 0,
        overflow: 'hidden',
      }}
    >
      {/* File tabs — each tab is a button; clicking swaps the code pane */}
      <div
        style={{
          display: 'flex',
          alignItems: 'stretch',
          borderBottom: '1px solid var(--panel-border)',
          background: 'var(--panel)',
          paddingLeft: 8,
          overflowX: 'auto',
        }}
      >
        {uniqueEdits.length > 0 ? uniqueEdits.map((e) => {
          const { dir, name } = splitDir(e.path)
          return (
            <FileTab
              key={e.path}
              file={name}
              dir={dir}
              active={e.path === effectivePath}
              dirty
              onClick={() => setSelectedPath(e.path)}
            />
          )
        }) : (
          <div style={{ padding: '10px 14px', fontSize: 12, color: 'var(--text-faint)' }}>
            No files written yet
          </div>
        )}
        <div style={{ flex: 1 }} />
        {(branch || prUrl) && (
          <div style={{
            display: 'flex', alignItems: 'center', gap: 8,
            padding: '0 12px', fontSize: 11.5, color: 'var(--text-subtle)',
            whiteSpace: 'nowrap',
          }}>
            <span className="gtx-mono">main</span>
            <ArrowRight size={10} />
            {prUrl ? (
              <a href={prUrl} target="_blank" rel="noreferrer"
                 className="gtx-mono"
                 style={{ color: 'var(--accent-600)', textDecoration: 'none' }}>
                {branch ?? 'branch'}{prNumber ? ` · PR #${prNumber}` : ''}
              </a>
            ) : (
              <span className="gtx-mono" style={{ color: 'var(--accent-600)' }}>{branch}</span>
            )}
          </div>
        )}
      </div>

      {/* Code view — shows whichever file tab is currently selected */}
      <div
        style={{
          flex: 1,
          overflow: 'auto',
          background: 'var(--code-bg)',
          fontFamily: 'Geist Mono, monospace',
          fontSize: 12.5,
          lineHeight: 1.65,
        }}
      >
        {!selected ? (
          <div style={{
            display: 'flex', alignItems: 'center', justifyContent: 'center',
            height: '100%', color: 'var(--text-faint)', fontSize: 12.5,
            padding: 32, textAlign: 'center',
          }}>
            The agent hasn&apos;t written any files yet. File contents will
            appear here as the workflow runs.
          </div>
        ) : (
          <LiveFileDiff
            taskId={taskId}
            path={selected.path}
            fallback={selected.content}
          />
        )}
      </div>

      {/* Terminal strip */}
      <div
        style={{
          borderTop: '1px solid var(--panel-border)',
          background: 'var(--bg-elev)',
        }}
      >
        <div
          style={{
            display: 'flex',
            alignItems: 'center',
            gap: 8,
            padding: '8px 14px',
            borderBottom: '1px solid var(--panel-border)',
            fontSize: 11.5,
            color: 'var(--text-muted)',
          }}
        >
          <Terminal size={12} />
          Terminal
          <span style={{ color: 'var(--text-faint)' }}>·</span>
          {(() => {
            const { runs } = extractWorkArtifacts(events)
            const latest = runs[runs.length - 1]
            return (
              <>
                <span className="gtx-mono" style={{ fontSize: 11 }}>
                  {runs.length > 0 ? `${runs.length} commands` : 'idle'}
                </span>
                <div style={{ flex: 1 }} />
                {latest?.exitCode != null && (
                  <Pill tone={latest.exitCode === 0 ? 'success' : 'danger'} size="xs" dot>
                    {latest.exitCode === 0 ? 'PASS' : `exit ${latest.exitCode}`}
                  </Pill>
                )}
              </>
            )
          })()}
        </div>
        <div
          className="gtx-mono"
          style={{
            padding: '10px 14px',
            fontSize: 11.5,
            lineHeight: 1.7,
            color: 'var(--text-muted)',
            maxHeight: 160,
            overflow: 'auto',
          }}
        >
          {(() => {
            const { runs } = extractWorkArtifacts(events)
            if (runs.length === 0) {
              return (
                <div style={{ color: 'var(--text-faint)' }}>
                  No shell commands executed yet.
                </div>
              )
            }
            return runs.slice(-6).map((r, i) => (
              <div key={i}>
                <div>
                  <span style={{ color: 'var(--accent-500)' }}>$</span>{' '}
                  {r.command}
                </div>
                {r.stdout && (
                  <div style={{ color: 'var(--text-subtle)', whiteSpace: 'pre-wrap' }}>
                    {r.stdout.split('\n').slice(0, 3).join('\n')}
                  </div>
                )}
                {r.stderr && (
                  <div style={{ color: 'var(--status-danger)', whiteSpace: 'pre-wrap' }}>
                    {r.stderr.split('\n').slice(0, 2).join('\n')}
                  </div>
                )}
              </div>
            ))
          })()}
        </div>
      </div>
    </div>
  )
}

// ── StepCard ──────────────────────────────────────────────────────────────────

// ── ToolStepCard ─────────────────────────────────────────────────────────────
//
// Replaces the raw `jira.get_issue ("{\"issue_key\": \"KAN-4\"}")` rendering
// with a friendly, icon-tagged summary. Full raw JSON moves into a
// collapsible "details" section — still auditable, no longer visual noise.

const TOOL_ICONS: Record<string, string> = {
  jira: '🎫', github: '🐙', git: '🌿', repo: '📁', file: '📝',
  code: '🔍', sandbox: '🧪', shell: '💻', human: '💬',
}

function describeTool(name: string, rawArgs: string): { title: string; subtitle?: string } {
  // Try to parse the args blob as JSON; if it's not JSON, use as-is.
  let args: Record<string, unknown> = {}
  if (typeof rawArgs === 'string') {
    const s = rawArgs.trim()
    if (s.startsWith('{')) {
      try { args = JSON.parse(s) } catch { /* fallthrough */ }
    }
  }
  const n = name.toLowerCase()
  const short = (v: unknown, max = 60) => {
    const s = String(v ?? '')
    return s.length > max ? s.slice(0, max) + '…' : s
  }
  if (n === 'jira.get_issue')       return { title: `Read Jira ticket`, subtitle: short(args.issue_key) }
  if (n === 'jira.add_comment')     return { title: `Comment on Jira`, subtitle: short(args.issue_key) }
  if (n === 'jira.update_status' || n === 'jira.transition_issue')
    return { title: `Move Jira ticket`, subtitle: `${args.issue_key ?? ''} → ${args.status ?? args.transition_name ?? ''}` }
  if (n === 'repo.list_files')      return { title: `List files`, subtitle: short(args.path || args.repo_full_name) }
  if (n === 'repo.read_file')       return { title: `Read file`, subtitle: short(args.path) }
  if (n === 'code.search' || n === 'repo.search_code')
    return { title: `Search code`, subtitle: short(args.query) }
  if (n === 'file.write' || n === 'repo.write_file')
    return { title: `Write file`, subtitle: short(args.path) }
  if (n === 'git.create_branch')    return { title: `Create branch`, subtitle: short(args.branch_name || args.branch) }
  if (n === 'github.create_pr' || n === 'bitbucket.create_pr' || n === 'bitbucket.create_pull_request')
    return { title: `Open pull request`, subtitle: short(args.title) }
  if (n === 'github.get_pr_diff' || n === 'bitbucket.get_pr_diff')
    return { title: `Read PR diff`, subtitle: `#${args.pr_number ?? '?'}` }
  if (n === 'github.post_pr_comment' || n === 'bitbucket.post_pr_comment')
    return { title: `Comment on PR`, subtitle: `#${args.pr_number ?? '?'}` }
  if (n === 'sandbox.execute' || n === 'shell.run')
    return { title: `Run command`, subtitle: short(args.command || args.cmd) }
  if (n === 'human.ask_clarification')
    return { title: `Ask for clarification`, subtitle: short(args.question) }
  // Fallback — just humanize the dotted name
  const pretty = name.replace(/[_.]/g, ' ').replace(/\b\w/g, (c) => c.toUpperCase())
  return { title: pretty, subtitle: Object.keys(args).length ? short(JSON.stringify(args), 80) : short(rawArgs, 80) }
}

function ToolStepCard({ step }: { step: AgentStep }) {
  const [expanded, setExpanded] = useState(false)
  const toolName = step.name ?? ''
  const { title, subtitle } = describeTool(toolName, step.args ?? '')
  const domain = (toolName.split('.')[0] ?? '').toLowerCase()
  const icon = TOOL_ICONS[domain] ?? '⚙️'
  const hasResult = step.result !== undefined && step.result !== ''
  const resultStr = typeof step.result === 'string' ? step.result : JSON.stringify(step.result, null, 2)
  const resultPreview = resultStr
    ? resultStr.length > 120 ? resultStr.slice(0, 120) + '…' : resultStr
    : 'Running…'
  return (
    <div style={{
      marginBottom: 8, padding: '8px 10px',
      background: 'var(--bg-muted)', border: '1px solid var(--border)',
      borderRadius: 6, animation: 'gtx-fadein .25s',
    }}>
      <button
        type="button"
        onClick={() => setExpanded((e) => !e)}
        aria-expanded={expanded}
        style={{
          display: 'flex', alignItems: 'center', gap: 8, width: '100%',
          border: 'none', background: 'transparent', padding: 0,
          cursor: 'pointer', textAlign: 'left', font: 'inherit',
        }}
      >
        <span style={{ fontSize: 13, width: 18, textAlign: 'center' }} aria-hidden>
          {icon}
        </span>
        <div style={{ flex: 1, minWidth: 0 }}>
          <div style={{ fontSize: 12.5, fontWeight: 500, color: 'var(--text)', lineHeight: 1.3 }}>
            {title}
            {subtitle && (
              <span className="gtx-mono" style={{
                fontWeight: 400, color: 'var(--text-subtle)', fontSize: 11.5, marginLeft: 8,
              }}>
                {subtitle}
              </span>
            )}
          </div>
          {hasResult && (
            <div style={{
              fontSize: 11, color: 'var(--text-faint)', marginTop: 2,
              whiteSpace: 'nowrap', overflow: 'hidden', textOverflow: 'ellipsis',
            }}>
              → {resultPreview}
            </div>
          )}
        </div>
        <span style={{ fontSize: 10, color: 'var(--text-faint)' }}>
          {expanded ? '▾' : '▸'}
        </span>
      </button>
      {expanded && (
        <div style={{
          marginTop: 8, padding: 8, background: 'var(--code-bg)',
          border: '1px solid var(--border)', borderRadius: 4,
          fontFamily: 'Geist Mono, monospace', fontSize: 10.5, lineHeight: 1.45,
          color: 'var(--text-muted)',
        }}>
          <div style={{
            fontSize: 9.5, fontWeight: 600, color: 'var(--text-subtle)',
            textTransform: 'uppercase', letterSpacing: 0.5, marginBottom: 4,
          }}>
            Tool · {toolName}
          </div>
          <div style={{ marginBottom: 8 }}>
            <div style={{ color: 'var(--text-faint)' }}>input</div>
            <pre style={{ margin: 0, whiteSpace: 'pre-wrap', wordBreak: 'break-word' }}>
              {step.args || '{}'}
            </pre>
          </div>
          {hasResult && (
            <div>
              <div style={{ color: 'var(--text-faint)' }}>output</div>
              <pre style={{ margin: 0, whiteSpace: 'pre-wrap', wordBreak: 'break-word' }}>
                {resultStr}
              </pre>
            </div>
          )}
        </div>
      )}
    </div>
  )
}

function StepCard({
  step,
  displayText,
  isLast,
  running,
}: {
  step: AgentStep
  displayText: string
  isLast: boolean
  running: boolean
}) {
  if (step.kind === 'think') {
    return (
      <div
        style={{
          display: 'flex',
          gap: 10,
          marginBottom: 14,
          animation: 'gtx-fadein .25s',
        }}
      >
        <div
          style={{
            width: 4,
            background: 'var(--accent-400)',
            borderRadius: 2,
            opacity: 0.4,
            flexShrink: 0,
          }}
        />
        <div
          style={{
            flex: 1,
            fontSize: 12.5,
            color: 'var(--text-muted)',
            lineHeight: 1.5,
          }}
        >
          {displayText}
          {isLast && running && (
            <span
              style={{
                animation: 'gtx-blink 1s step-end infinite',
                color: 'var(--accent-500)',
              }}
            >
              ▋
            </span>
          )}
        </div>
      </div>
    )
  }

  if (step.kind === 'tool') {
    return <ToolStepCard step={step} />
  }

  if (step.kind === 'edit') {
    return (
      <div
        style={{
          display: 'flex',
          gap: 10,
          marginBottom: 10,
          padding: 10,
          background: 'var(--accent-soft)',
          border: '1px solid var(--brand-teal-200)',
          borderRadius: 6,
          animation: 'gtx-fadein .25s',
        }}
      >
        <Code2 size={12} color="var(--accent-600)" style={{ marginTop: 2 }} />
        <div style={{ flex: 1, minWidth: 0 }}>
          <div
            className="gtx-mono"
            style={{
              fontSize: 11,
              color: 'var(--accent-600)',
              fontWeight: 500,
              marginBottom: 2,
            }}
          >
            {step.file}{' '}
            <span style={{ color: 'var(--text-subtle)' }}>{step.op}</span>
          </div>
          <div style={{ fontSize: 12, color: 'var(--text)' }}>{step.summary}</div>
        </div>
      </div>
    )
  }

  if (step.kind === 'response') {
    return (
      <div
        style={{
          marginBottom: 12,
          padding: '10px 12px',
          background: 'var(--code-bg)',
          border: '1px solid var(--border)',
          borderRadius: 6,
          animation: 'gtx-fadein .25s',
        }}
      >
        <div
          style={{
            display: 'flex',
            alignItems: 'center',
            gap: 6,
            marginBottom: 6,
            fontSize: 10.5,
            color: 'var(--text-subtle)',
            textTransform: 'uppercase',
            letterSpacing: 0.5,
            fontWeight: 600,
          }}
        >
          <Sparkles size={10} color="var(--accent-500)" />
          Claude Response
          {step.iteration != null && (
            <span style={{ fontWeight: 400 }}> · iteration {step.iteration}</span>
          )}
          {isLast && running && <StatusDot tone="success" pulse size={5} />}
        </div>
        <pre
          className="gtx-mono"
          style={{
            whiteSpace: 'pre-wrap',
            wordBreak: 'break-word',
            color: 'var(--text)',
            fontSize: 11.5,
            lineHeight: 1.55,
            margin: 0,
            maxHeight: 400,
            overflow: 'auto',
          }}
        >
          {displayText}
          {isLast && running && (
            <span
              style={{
                animation: 'gtx-blink 1s step-end infinite',
                color: 'var(--accent-500)',
              }}
            >
              ▋
            </span>
          )}
        </pre>
      </div>
    )
  }

  if (step.kind === 'cache') {
    const isHit = (step.cacheRead ?? 0) > 0
    return (
      <div
        style={{
          display: 'flex',
          alignItems: 'center',
          gap: 6,
          marginBottom: 6,
          padding: '4px 8px',
          fontSize: 10.5,
          color: isHit ? 'var(--status-success)' : 'var(--status-warn)',
          animation: 'gtx-fadein .25s',
        }}
      >
        <span style={{ fontSize: 9 }}>{isHit ? '●' : '○'}</span>
        <span className="gtx-mono">{step.text}</span>
      </div>
    )
  }

  return null
}

// ── ResponsePanel ─────────────────────────────────────────────────────────────

function ResponsePanel({
  text,
  iteration,
  isStreaming,
}: {
  text: string
  iteration?: number
  isStreaming: boolean
}) {
  const panelRef = useRef<HTMLPreElement>(null)

  // Auto-scroll to bottom while content is streaming in
  useEffect(() => {
    if (isStreaming && panelRef.current) {
      panelRef.current.scrollTop = panelRef.current.scrollHeight
    }
  }, [text, isStreaming])

  return (
    <div
      style={{
        marginBottom: 12,
        padding: '10px 12px',
        background: 'var(--code-bg)',
        border: '1px solid var(--border)',
        borderRadius: 6,
        animation: 'gtx-fadein .25s',
      }}
    >
      <div
        style={{
          display: 'flex',
          alignItems: 'center',
          gap: 6,
          marginBottom: 6,
          fontSize: 10.5,
          color: 'var(--text-subtle)',
          textTransform: 'uppercase',
          letterSpacing: 0.5,
          fontWeight: 600,
        }}
      >
        <Sparkles size={10} color="var(--accent-500)" />
        Claude Response
        {iteration != null && (
          <span style={{ fontWeight: 400 }}> · iteration {iteration}</span>
        )}
        {isStreaming && <StatusDot tone="success" pulse size={5} />}
      </div>
      <pre
        ref={panelRef}
        className="gtx-mono"
        style={{
          whiteSpace: 'pre-wrap',
          wordBreak: 'break-word',
          color: 'var(--text)',
          fontSize: 11.5,
          lineHeight: 1.55,
          margin: 0,
          maxHeight: 400,
          overflow: 'auto',
        }}
      >
        {text}
        {isStreaming && (
          <span
            style={{
              animation: 'gtx-blink 1s step-end infinite',
              color: 'var(--accent-500)',
            }}
          >
            ▋
          </span>
        )}
      </pre>
    </div>
  )
}

// ── ThinkingPanel ─────────────────────────────────────────────────────────────

function ThinkingPanel({
  steps,
  liveChar,
  feedRef,
  running,
  events,
  evaluations,
  isStreaming,
}: {
  steps: AgentStep[]
  liveChar: number
  feedRef: React.RefObject<HTMLDivElement | null>
  running: boolean
  events: AgentEvent[]
  evaluations: TaskEvaluation[]
  isStreaming: boolean
}) {
  return (
    <div
      style={{
        background: 'var(--panel)',
        display: 'flex',
        flexDirection: 'column',
        minHeight: 0,
      }}
    >
      <div
        style={{
          padding: '12px 16px',
          borderBottom: '1px solid var(--panel-border)',
          display: 'flex',
          alignItems: 'center',
          gap: 8,
        }}
      >
        <BrandMark size={14} />
        <span style={{ fontSize: 12.5, fontWeight: 500, color: 'var(--text)' }}>
          Thinking stream
        </span>
        {(running || isStreaming) && <StatusDot tone="success" pulse size={6} />}
        <div style={{ flex: 1 }} />
        <span style={{ fontSize: 10.5, color: 'var(--text-faint)' }}>
          {steps.length} steps
        </span>
      </div>
      <div
        ref={feedRef as React.RefObject<HTMLDivElement>}
        aria-live="polite"
        aria-label="Agent thinking stream"
        style={{ flex: 1, overflowY: 'auto', padding: '12px 16px' }}
      >
        {events.length > 0 && <SystemPromptPanel events={events} />}
        {steps.map((s, i) => {
          const isLast = i === steps.length - 1
          const displayText =
            isLast && s.kind === 'think'
              ? (s.text ?? '').slice(0, liveChar)
              : s.text ?? ''

          // Use ResponsePanel for response kind steps
          if (s.kind === 'response') {
            return (
              <ResponsePanel
                key={i}
                text={s.text ?? ''}
                iteration={s.iteration}
                isStreaming={isLast && isStreaming}
              />
            )
          }

          return (
            <StepCard
              key={i}
              step={s}
              displayText={displayText}
              isLast={isLast}
              running={running}
            />
          )
        })}
      </div>
      <EvalPanel evaluations={evaluations} />
    </div>
  )
}

// ── SystemPromptPanel ────────────────────────────────────────────────────────

function SystemPromptPanel({ events }: { events: AgentEvent[] }) {
  const [expanded, setExpanded] = useState(false)
  const promptEvent = events.find(e => e.type === 'prompt_sent')
  if (!promptEvent) return null

  const systemPrompt = promptEvent.data.system_prompt as string | undefined
  const userMessage = promptEvent.data.user_message as string | undefined
  const model = promptEvent.data.model as string | undefined

  return (
    <div style={{ marginBottom: 12, animation: 'gtx-fadein .25s' }}>
      <button
        onClick={() => setExpanded(e => !e)}
        aria-expanded={expanded}
        aria-controls="prompt-sent-detail"
        style={{
          display: 'flex', alignItems: 'center', gap: 6, width: '100%',
          padding: '8px 10px', background: 'var(--bg-muted)', border: '1px solid var(--border)',
          borderRadius: 6, cursor: 'pointer', fontFamily: 'inherit', textAlign: 'left',
        }}
      >
        <span style={{ fontSize: 11, color: 'var(--accent-600)', fontWeight: 500 }}>
          {expanded ? '▾' : '▸'} Prompt sent
        </span>
        {model && (
          <span className="gtx-mono" style={{ fontSize: 10, color: 'var(--text-faint)', marginLeft: 'auto' }}>
            {model}
          </span>
        )}
      </button>
      {expanded && (
        <div id="prompt-sent-detail" style={{ marginTop: 6, padding: '10px 12px', background: 'var(--code-bg)', border: '1px solid var(--border)', borderRadius: 6, fontSize: 11.5, lineHeight: 1.55 }}>
          {systemPrompt && (
            <div style={{ marginBottom: 10 }}>
              <div style={{ fontSize: 10, fontWeight: 600, color: 'var(--text-subtle)', textTransform: 'uppercase', letterSpacing: 0.5, marginBottom: 4 }}>System prompt</div>
              <pre className="gtx-mono" style={{ whiteSpace: 'pre-wrap', wordBreak: 'break-word', color: 'var(--text-muted)', fontSize: 10.5, lineHeight: 1.5, maxHeight: 200, overflow: 'auto', margin: 0 }}>
                {systemPrompt}
              </pre>
            </div>
          )}
          {userMessage && (
            <div>
              <div style={{ fontSize: 10, fontWeight: 600, color: 'var(--text-subtle)', textTransform: 'uppercase', letterSpacing: 0.5, marginBottom: 4 }}>User message</div>
              <pre className="gtx-mono" style={{ whiteSpace: 'pre-wrap', wordBreak: 'break-word', color: 'var(--text)', fontSize: 11, lineHeight: 1.5, margin: 0 }}>
                {userMessage}
              </pre>
            </div>
          )}
        </div>
      )}
    </div>
  )
}

// ── EvalPanel ────────────────────────────────────────────────────────────────

function EvalPanel({ evaluations }: { evaluations: TaskEvaluation[] }) {
  if (evaluations.length === 0) return null

  const avgScore = evaluations.reduce((s, e) => s + e.score, 0) / evaluations.length

  return (
    <div style={{ padding: '12px 16px', borderTop: '1px solid var(--panel-border)' }}>
      <div style={{ display: 'flex', alignItems: 'center', gap: 8, marginBottom: 10 }}>
        <span style={{ fontSize: 12.5, fontWeight: 500, color: 'var(--text)' }}>
          Evaluation
        </span>
        <Pill tone={avgScore >= 7 ? 'success' : avgScore >= 4 ? 'warn' : 'danger'} size="xs">
          {avgScore.toFixed(1)}/10
        </Pill>
      </div>
      <div style={{ display: 'flex', flexDirection: 'column', gap: 8 }}>
        {evaluations.map(ev => (
          <EvalDimension key={ev.id || ev.dimension} eval={ev} />
        ))}
      </div>
    </div>
  )
}

function EvalDimension({ eval: ev }: { eval: TaskEvaluation }) {
  const [expanded, setExpanded] = useState(false)
  const barColor = ev.score >= 7 ? 'var(--status-success)' : ev.score >= 4 ? 'var(--status-warn)' : 'var(--status-danger)'

  return (
    <div>
      <button
        onClick={() => setExpanded(e => !e)}
        aria-expanded={expanded}
        aria-label={`${ev.dimension} evaluation score: ${ev.score}/10`}
        style={{
          display: 'flex', alignItems: 'center', gap: 8, width: '100%',
          background: 'none', border: 'none', padding: '4px 0', cursor: 'pointer',
          fontFamily: 'inherit', textAlign: 'left',
        }}
      >
        <span style={{ fontSize: 11.5, color: 'var(--text-muted)', flex: 1, textTransform: 'capitalize' }}>
          {ev.dimension}
        </span>
        <div style={{ width: 60, height: 4, background: 'var(--bg-muted)', borderRadius: 2, overflow: 'hidden' }}>
          <div style={{ width: `${ev.score * 10}%`, height: '100%', background: barColor, borderRadius: 2 }} />
        </div>
        <span className="gtx-mono" style={{ fontSize: 10.5, color: 'var(--text-subtle)', width: 28, textAlign: 'right' }}>
          {ev.score}/10
        </span>
      </button>
      {expanded && (
        <div style={{ padding: '6px 0 6px 8px', fontSize: 11, color: 'var(--text-subtle)', lineHeight: 1.5 }}>
          {ev.reasoning && <div style={{ marginBottom: 4 }}>{ev.reasoning}</div>}
          {ev.strengths?.length > 0 && (
            <div style={{ color: 'var(--status-success)' }}>
              {ev.strengths.map((s, i) => <div key={i}>+ {s}</div>)}
            </div>
          )}
          {ev.weaknesses?.length > 0 && (
            <div style={{ color: 'var(--status-danger)', marginTop: 2 }}>
              {ev.weaknesses.map((w, i) => <div key={i}>− {w}</div>)}
            </div>
          )}
        </div>
      )}
    </div>
  )
}

// ── Event → AgentStep mapper ──────────────────────────────────────────────────

function mapEventsToSteps(events: AgentEvent[]): AgentStep[] {
  const steps: AgentStep[] = []
  let t = 0
  // Track accumulated streaming response text per iteration
  let activeResponseIdx = -1

  for (const event of events) {
    const { type, data } = event
    // If the backend already translated this event with a stepType, use it
    // to produce the correct AgentStep kind directly.
    const stepType = (event as unknown as Record<string, unknown>).stepType as string | undefined

    if (stepType === 'edit') {
      // File-edit events from translated tool_call_end
      const toolName = (data.tool as string) ?? 'edit'
      const resultData = data.result_data
        ? JSON.stringify(data.result_data).slice(0, 200)
        : undefined
      steps.push({
        kind: 'edit',
        t,
        file: (data.file_path as string) ?? toolName,
        op: resultData ?? '',
        summary: (data.summary as string) ?? `${toolName} completed`,
      })
      t += 300
      activeResponseIdx = -1
      continue
    }

    if (type === 'prompt_sent') {
      steps.push({
        kind: 'think',
        t,
        text: (data.user_message as string) || 'Starting task...',
      })
      t += 400
    } else if (type === 'tool_call_start') {
      // A tool call means the previous response step is done
      activeResponseIdx = -1
      steps.push({
        kind: 'tool',
        t,
        name: data.tool as string | undefined,
        args: JSON.stringify(data.input_data ?? '').slice(0, 100),
        result: undefined,
      })
      t += 300
    } else if (type === 'tool_call_end') {
      for (let i = steps.length - 1; i >= 0; i--) {
        if (steps[i].kind === 'tool' && steps[i].result === undefined) {
          steps[i] = {
            ...steps[i],
            result: data.result_data
              ? JSON.stringify(data.result_data).slice(0, 100)
              : 'Done',
          }
          break
        }
      }
    } else if (type === 'streaming_chunk') {
      // Accumulate streaming text into a single 'response' step
      const content = data.content as string | undefined
      if (content) {
        if (activeResponseIdx >= 0 && steps[activeResponseIdx]?.kind === 'response') {
          steps[activeResponseIdx] = {
            ...steps[activeResponseIdx],
            text: (steps[activeResponseIdx].text ?? '') + content,
            iteration: (data.iteration as number) ?? steps[activeResponseIdx].iteration,
          }
        } else {
          activeResponseIdx = steps.length
          steps.push({
            kind: 'response',
            t,
            text: content,
            iteration: data.iteration as number | undefined,
          })
          t += 100
        }
      }
    } else if (type === 'text_delta') {
      // Final complete response — replace accumulated streaming text
      const content = data.content as string | undefined
      if (content) {
        if (activeResponseIdx >= 0 && steps[activeResponseIdx]?.kind === 'response') {
          steps[activeResponseIdx] = {
            ...steps[activeResponseIdx],
            text: content,
          }
        } else {
          steps.push({ kind: 'response', t, text: content })
          t += 100
        }
        activeResponseIdx = -1
      }
    } else if (type === 'response_chunk') {
      const content = data.content as string | undefined
      if (content) {
        steps.push({ kind: 'think', t, text: content })
        t += 200
      }
    } else if (type === 'iteration') {
      // Show cache metrics inline
      const cacheRead = (data.cache_read_tokens as number) ?? 0
      const cacheWrite = (data.cache_creation_tokens as number) ?? 0
      if (cacheRead > 0 || cacheWrite > 0) {
        steps.push({
          kind: 'cache',
          t,
          iteration: data.iteration as number | undefined,
          cacheRead,
          cacheWrite,
          text: cacheRead > 0
            ? `Cache HIT: ${cacheRead.toLocaleString()} tokens saved`
            : `Cache WRITE: ${cacheWrite.toLocaleString()} tokens cached`,
        })
        t += 50
      }
    } else if (type === 'rag_indexing_start') {
      steps.push({ kind: 'think', t, text: 'Indexing repository for semantic search...' })
      t += 200
    } else if (type === 'rag_indexing_complete') {
      const chunks = data.chunks as number | undefined
      steps.push({ kind: 'think', t, text: `Repository indexed: ${chunks ?? '?'} chunks ready` })
      t += 200
    } else if (type === 'complete') {
      activeResponseIdx = -1
      const summary =
        (data.summary as string) ||
        (data.message as string) ||
        'Task completed.'
      steps.push({ kind: 'think', t, text: summary })
      t += 200
    }
  }

  return steps
}

// ── Page ──────────────────────────────────────────────────────────────────────

export default function CodingAgentPage({
  params,
}: {
  params: Promise<{ id: string }> | { id: string }
}) {
  // Next.js 14 may pass params as a plain object or a Promise depending on mode
  const resolvedParams = params instanceof Promise ? use(params) : params
  const { id } = resolvedParams

  const { data: task } = useSWR(`task-${id}`, () => getTask(id), {
    refreshInterval: (data) => (data?.status && TERMINAL_STATUSES.has(data.status) ? 0 : 5000),
  })
  const isTerminal = task?.status ? TERMINAL_STATUSES.has(task.status) : false
  const { data: events } = useSWR(`task-events-${id}`, () => getTaskEvents(id), {
    refreshInterval: isTerminal ? 0 : 3000,
  })
  const { data: evaluations } = useSWR(
    task?.status === 'completed' ? `task-evals-${id}` : null,
    () => getTaskEvaluations(id),
  )

  // T25: Specialists tab toggle. Hand-rolled (no headless-ui) per the
  // existing modal pattern in components/new-run-modal.tsx.
  const [activeTab, setActiveTab] = useState<TaskDetailTab>('overview')

  // WebSocket for live streaming
  const [wsEvents, setWsEvents] = useState<AgentEvent[]>([])
  const [controlPending, setControlPending] = useState<'pause' | 'resume' | 'stop' | null>(null)
  const [wsAuthError, setWsAuthError] = useState<
    { code: number; message: string } | null
  >(null)
  const wsRef = useRef<WebSocket | null>(null)

  useEffect(() => {
    if (task?.status && !ACTIVE_STATUSES.has(task.status)) return

    // WebSocket URL: prefer NEXT_PUBLIC_WS_URL (deploy-time), fall back to same origin.
    // Next.js rewrites do NOT proxy WebSocket upgrades — production requires a
    // dedicated upgrade-capable proxy (ALB listener rule / Nginx) or an explicit
    // env-configured URL.
    const explicit = process.env.NEXT_PUBLIC_WS_URL
    const protocol = window.location.protocol === 'https:' ? 'wss:' : 'ws:'
    const wsUrl = explicit
      ? `${explicit.replace(/\/$/, '')}/api/chat/stream/${id}`
      : `${protocol}//${window.location.host}/api/chat/stream/${id}`
    try {
      const ws = new WebSocket(wsUrl)
      wsRef.current = ws

      // First-message auth: send token immediately on open.
      // Token priority:
      //   1. session_token from localStorage (stashed by /login page)
      //   2. legacy api_key cookie (pre-Sprint-14 sessions)
      //   3. NEXT_PUBLIC_DEV_API_KEY env (local dev convenience)
      //   4. empty string → backend closes with 4401 (handled in ws.onclose)
      ws.onopen = () => {
        let token = ''
        try {
          token = localStorage.getItem('session_token') || ''
        } catch {}
        if (!token) {
          const match = document.cookie.match(/(?:^|; )api_key=([^;]*)/)
          token = match ? decodeURIComponent(match[1]) : (process.env.NEXT_PUBLIC_DEV_API_KEY || '')
        }
        ws.send(JSON.stringify({ type: 'auth', token }))
      }

      ws.onmessage = (ev) => {
        try {
          const event = JSON.parse(ev.data) as AgentEvent
          if (event.type === 'connected') return
          setWsEvents(prev => [...prev, event])
        } catch {}
      }

      ws.onerror = () => {}
      ws.onclose = (ev) => {
        wsRef.current = null
        // 4401: auth failed (bad/missing token). 4404: task not in team scope.
        if (ev.code === 4401) {
          setWsAuthError({
            code: 4401,
            message: 'Live stream disconnected: authentication failed. Reload after signing in.',
          })
        } else if (ev.code === 4404) {
          setWsAuthError({
            code: 4404,
            message: 'Live stream disconnected: this task does not belong to your team.',
          })
        }
      }

      return () => { ws.close(); wsRef.current = null }
    } catch {
      // WebSocket connection failed — fall back to polling
    }
  }, [id, task?.status])

  // Merge historical events + live WebSocket events
  const allEvents = useMemo(() => {
    const historical = events ?? []
    if (wsEvents.length === 0) return historical
    // Deduplicate by checking timestamps
    const historicalTs = new Set(historical.map(e => e.timestamp).filter(Boolean))
    const newEvents = wsEvents.filter(e => !e.timestamp || !historicalTs.has(e.timestamp))
    return [...historical, ...newEvents]
  }, [events, wsEvents])

  const realSteps = useMemo(
    () => (allEvents.length > 0 ? mapEventsToSteps(allEvents) : []),
    [allEvents],
  )

  const isRealMode = realSteps.length > 0
  // Demo fallback is only shown in explicit demo mode — otherwise an empty
  // stream shows an empty state rather than fake content.
  const steps = isRealMode ? realSteps : DEMO_MODE ? DEMO_STEPS : []

  // Demo animation state — only active in demo mode
  const [running, setRunning] = useState(true)
  const [idx, setIdx] = useState(isRealMode ? steps.length - 1 : 8)
  const [liveChar, setLiveChar] = useState(0)
  const feedRef = useRef<HTMLDivElement>(null!)

  // When real steps arrive, jump to the end immediately
  useEffect(() => {
    if (isRealMode) {
      setIdx(steps.length - 1)
      setRunning(false)
    }
  }, [isRealMode, steps.length])

  useEffect(() => {
    if (isRealMode) return
    if (!running) return
    if (idx >= DEMO_STEPS.length) return
    const step = DEMO_STEPS[idx]
    const text = step.text ?? step.summary ?? ''
    if (step.kind === 'think' && liveChar < text.length) {
      const t = setTimeout(() => setLiveChar((c) => Math.min(text.length, c + 3)), 18)
      return () => clearTimeout(t)
    }
    const delay = step.kind === 'think' ? 600 : 420
    const t = setTimeout(() => {
      setIdx((i) => i + 1)
      setLiveChar(0)
    }, delay)
    return () => clearTimeout(t)
  }, [isRealMode, running, idx, liveChar])

  useEffect(() => {
    if (feedRef.current) {
      feedRef.current.scrollTop = feedRef.current.scrollHeight
    }
  }, [idx, liveChar])

  const visible = steps.slice(0, idx + 1)
  const isComplete = isRealMode ? true : idx >= DEMO_STEPS.length

  // Streaming status: extract latest iteration metrics from events
  const streamingStatus = useMemo(() => {
    const iterationEvents = allEvents.filter(e => e.type === 'iteration')
    const hasComplete = allEvents.some(e => e.type === 'complete' || e.type === 'error')
    const isStreaming = allEvents.some(e => e.type === 'streaming_chunk') && !hasComplete
    if (iterationEvents.length === 0) return null
    const latest = iterationEvents[iterationEvents.length - 1]
    const totalCacheRead = iterationEvents.reduce(
      (sum, e) => sum + ((e.data.cache_read_tokens as number) ?? 0), 0,
    )
    return {
      iteration: latest.data.iteration as number,
      totalTokens: (latest.data.total_input_tokens as number ?? 0) + (latest.data.total_output_tokens as number ?? 0),
      totalCacheRead,
      isStreaming,
    }
  }, [allEvents])

  // Status pill helpers
  const statusTone = (s?: string): 'info' | 'success' | 'danger' | 'warn' => {
    if (!s) return 'info'
    const lower = s.toLowerCase()
    if (lower === 'completed' || lower === 'success') return 'success'
    if (lower === 'failed' || lower === 'error') return 'danger'
    if (lower === 'paused') return 'warn'
    return 'info'
  }

  const statusLabel = (): string => {
    if (task?.status) return task.status
    if (running && !isComplete) return 'Running · 8m 04s'
    if (isComplete) return 'Complete'
    return 'Paused'
  }

  return (
    <main style={{ flex: 1, display: 'flex', flexDirection: 'column', overflow: 'hidden' }}>
      {/* WebSocket auth error banner — shown on 4401 / 4404 close */}
      {wsAuthError && (
        <div
          role="alert"
          aria-live="assertive"
          data-testid="ws-auth-error-banner"
          style={{
            background: 'var(--status-warn-bg, #fef3c7)',
            color: 'var(--status-warn, #92400e)',
            borderBottom: '1px solid var(--status-warn, #b45309)',
            padding: '8px 16px',
            fontSize: 13,
            fontWeight: 500,
          }}
        >
          {wsAuthError.message} (code {wsAuthError.code})
        </div>
      )}

      {/* Header */}
      <div
        style={{
          display: 'flex',
          alignItems: 'center',
          justifyContent: 'space-between',
          padding: '12px 20px',
          borderBottom: '1px solid var(--panel-border)',
          background: 'var(--panel)',
        }}
      >
        <div>
          <div
            style={{
              fontSize: 11.5,
              color: 'var(--text-subtle)',
              marginBottom: 2,
            }}
          >
            Runs /{' '}
            <span className="gtx-mono" style={{ color: 'var(--text)' }}>
              {id}
            </span>
          </div>
          <div style={{ fontSize: 13, fontWeight: 500, color: 'var(--text)' }}>
            {task?.title ?? 'Fix race condition in ledger balance cache'}
          </div>
          {/* Real metadata row */}
          {task && (
            <div
              style={{
                display: 'flex',
                gap: 14,
                marginTop: 4,
                fontSize: 11,
                color: 'var(--text-faint)',
              }}
            >
              {task.iterations != null && (
                <span>
                  <span style={{ color: 'var(--text-subtle)' }}>Iterations:</span>{' '}
                  {task.iterations}
                </span>
              )}
              {task.tokenUsage != null && (
                <span>
                  <span style={{ color: 'var(--text-subtle)' }}>Tokens:</span>{' '}
                  {task.tokenUsage.toLocaleString()}
                </span>
              )}
              {task.costUsd != null && (
                <span>
                  <span style={{ color: 'var(--text-subtle)' }}>Cost:</span>{' '}
                  ${task.costUsd.toFixed(4)}
                </span>
              )}
            </div>
          )}
          {streamingStatus && (
            <div
              style={{
                display: 'flex',
                gap: 10,
                marginTop: 4,
                fontSize: 11,
                alignItems: 'center',
              }}
            >
              {streamingStatus.isStreaming && (
                <span style={{
                  display: 'inline-flex',
                  alignItems: 'center',
                  gap: 4,
                  color: 'var(--accent)',
                  fontWeight: 500,
                }}>
                  <Spinner size={9} color="currentColor" />
                  Generating… (iteration {streamingStatus.iteration}, {streamingStatus.totalTokens.toLocaleString()} tokens)
                </span>
              )}
              {!streamingStatus.isStreaming && streamingStatus.totalTokens > 0 && (
                <span style={{ color: 'var(--text-faint)' }}>
                  {streamingStatus.iteration} iterations · {streamingStatus.totalTokens.toLocaleString()} tokens
                </span>
              )}
              {streamingStatus.totalCacheRead > 0 && (
                <span style={{
                  display: 'inline-flex',
                  alignItems: 'center',
                  gap: 3,
                  padding: '1px 6px',
                  borderRadius: 4,
                  fontSize: 10,
                  fontWeight: 600,
                  background: 'rgba(34,197,94,0.12)',
                  color: '#22c55e',
                }}>
                  Cache: {streamingStatus.totalCacheRead.toLocaleString()} tokens saved
                </span>
              )}
            </div>
          )}
        </div>
        <div style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
          <Pill tone={statusTone(task?.status)} dot>
            {!isRealMode && running && !isComplete && (
              <Spinner size={9} color="currentColor" />
            )}
            {statusLabel()}
          </Pill>
          {isRealMode && !isTerminal ? (
            <>
              {task?.status === 'paused' ? (
                <Btn
                  size="sm"
                  icon={<Play size={11} />}
                  disabled={controlPending != null}
                  onClick={async () => {
                    setControlPending('resume')
                    try { await resumeTask(id) } finally { setControlPending(null) }
                  }}
                >
                  Resume
                </Btn>
              ) : (
                <Btn
                  size="sm"
                  icon={<Pause size={11} />}
                  disabled={controlPending != null}
                  onClick={async () => {
                    setControlPending('pause')
                    try { await pauseTask(id) } finally { setControlPending(null) }
                  }}
                >
                  Pause
                </Btn>
              )}
              <Btn
                size="sm"
                tone="ghost"
                icon={<X size={11} />}
                disabled={controlPending != null}
                onClick={async () => {
                  if (!window.confirm('Stop this run? The task will be marked cancelled.')) return
                  setControlPending('stop')
                  try { await stopTask(id) } finally { setControlPending(null) }
                }}
              >
                Stop
              </Btn>
            </>
          ) : (
            !isComplete && !isRealMode && DEMO_MODE && (
              <>
                <Btn
                  size="sm"
                  icon={running ? <Pause size={11} /> : <Play size={11} />}
                  onClick={() => setRunning((r) => !r)}
                >
                  {running ? 'Pause' : 'Resume'}
                </Btn>
                <Btn size="sm" tone="ghost" icon={<X size={11} />} onClick={() => { setRunning(false); setIdx(DEMO_STEPS.length) }}>
                  Stop
                </Btn>
              </>
            )
          )}
        </div>
      </div>

      {/* Clarification dialog — renders only when agent invoked human.ask_clarification */}
      <ClarificationCard taskId={id} events={allEvents} taskStatus={task?.status} />

      {/* T25: tab bar — Overview (existing split pane) / Specialists (new). */}
      <div
        role="tablist"
        aria-label="Task detail tabs"
        style={{
          display: 'flex',
          gap: 0,
          borderBottom: '1px solid var(--panel-border)',
          background: 'var(--panel)',
          paddingLeft: 20,
        }}
      >
        {([
          { id: 'overview', label: 'Overview' },
          { id: 'specialists', label: 'Specialists' },
        ] as { id: TaskDetailTab; label: string }[]).map(t => {
          const active = activeTab === t.id
          return (
            <button
              key={t.id}
              type="button"
              role="tab"
              aria-selected={active}
              data-testid={`tab-${t.id}`}
              onClick={() => setActiveTab(t.id)}
              style={{
                padding: '10px 14px',
                fontSize: 12,
                fontWeight: 500,
                background: 'transparent',
                border: 'none',
                borderBottom: active
                  ? '2px solid var(--accent-500)'
                  : '2px solid transparent',
                color: active ? 'var(--text)' : 'var(--text-muted)',
                cursor: 'pointer',
                marginBottom: -1,
              }}
            >
              {t.label}
            </button>
          )
        })}
      </div>

      {activeTab === 'specialists' ? (
        <div style={{ flex: 1, minHeight: 0, overflow: 'auto' }}>
          <SpecialistsTab taskId={id} />
        </div>
      ) : (
        /* Split pane (Overview) */
        <div
          style={{
            display: 'grid',
            gridTemplateColumns: '1fr 360px',
            flex: 1,
            minHeight: 0,
          }}
        >
          <MainCodePane events={allEvents} taskId={id} />
          <ThinkingPanel
            steps={visible}
            liveChar={isRealMode ? Infinity : liveChar}
            feedRef={feedRef}
            running={!isRealMode && running && !isComplete}
            events={allEvents}
            evaluations={evaluations ?? []}
            isStreaming={streamingStatus?.isStreaming ?? false}
          />
        </div>
      )}
    </main>
  )
}
