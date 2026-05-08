'use client'

import { useState } from 'react'
import Link from 'next/link'
import useSWR from 'swr'
import { Plus, RefreshCw } from 'lucide-react'
import { getTasks, Task } from '@/lib/api'
import { Pill } from '@/components/ui/pill'
import { Btn } from '@/components/ui/btn'
import { Spinner } from '@/components/ui/spinner'
import { NewRunModal } from '@/components/new-run-modal'
import { ErrorBoundary, ErrorMessage } from '@/components/ui/error-boundary'
import { EmptyState } from '@/components/ui/empty-state'

// ── Formatters ────────────────────────────────────────────────────────────────

function formatDuration(ms?: number): string {
  if (ms == null) return '—'
  const totalSeconds = Math.floor(ms / 1000)
  const minutes = Math.floor(totalSeconds / 60)
  const seconds = totalSeconds % 60
  if (minutes === 0) return `${seconds}s`
  return `${minutes}m ${seconds}s`
}

function formatCost(usd?: number): string {
  if (usd == null) return '—'
  return `$${usd.toFixed(2)}`
}

function formatTokens(tokens?: number): string {
  if (tokens == null) return '—'
  return tokens.toLocaleString('en-US')
}

function formatRelativeTime(iso: string): string {
  const now = Date.now()
  const then = new Date(iso).getTime()
  const diffMs = now - then
  const diffSec = Math.floor(diffMs / 1000)
  if (diffSec < 60) return `${diffSec}s ago`
  const diffMin = Math.floor(diffSec / 60)
  if (diffMin < 60) return `${diffMin}m ago`
  const diffHrs = Math.floor(diffMin / 60)
  if (diffHrs < 24) return `${diffHrs}h ago`
  const diffDays = Math.floor(diffHrs / 24)
  return `${diffDays}d ago`
}

// ── Status mapping ────────────────────────────────────────────────────────────

type PillTone = 'success' | 'danger' | 'info' | 'default'

interface StatusConfig {
  tone: PillTone
  label: string
  pulse: boolean
}

function getStatusConfig(status: string): StatusConfig {
  switch (status.toLowerCase()) {
    case 'completed':
      return { tone: 'success', label: 'Completed', pulse: false }
    case 'failed':
      return { tone: 'danger', label: 'Failed', pulse: false }
    case 'running':
    case 'coding':
    case 'planning':
      return { tone: 'info', label: status.charAt(0).toUpperCase() + status.slice(1), pulse: true }
    case 'queued':
      return { tone: 'default', label: 'Queued', pulse: false }
    default:
      return { tone: 'default', label: status, pulse: false }
  }
}

// ── TaskRow ───────────────────────────────────────────────────────────────────

function TaskRow({ task }: { task: Task }) {
  const [hover, setHover] = useState(false)
  const st = getStatusConfig(task.status)

  return (
    <Link
      href={`/dashboard/tasks/${task.id}`}
      style={{ textDecoration: 'none' }}
      aria-label={`View task ${task.jiraKey ?? task.id}: ${task.title}`}
    >
      <div
        onMouseEnter={() => setHover(true)}
        onMouseLeave={() => setHover(false)}
        style={{
          display: 'grid',
          gridTemplateColumns: '120px 1fr 90px 80px 70px 60px 60px 80px',
          gap: 12,
          alignItems: 'center',
          padding: '13px 16px',
          background: hover ? 'var(--bg-hover)' : 'var(--bg-elev)',
          border: `1px solid ${hover ? 'var(--brand-teal-200)' : 'var(--panel-border)'}`,
          borderRadius: 10,
          cursor: 'pointer',
          transition: 'border-color .15s, background .15s, box-shadow .15s',
          boxShadow: hover ? 'var(--shadow-md)' : 'var(--shadow-sm)',
        }}
      >
        {/* Status */}
        <div>
          <Pill tone={st.tone} dot={!st.pulse} size="sm">
            {st.pulse && <Spinner size={9} color="currentColor" />}
            {st.label}
          </Pill>
        </div>

        {/* Title */}
        <div style={{ minWidth: 0 }}>
          <div
            style={{
              fontSize: 13.5,
              fontWeight: 500,
              color: 'var(--text)',
              overflow: 'hidden',
              textOverflow: 'ellipsis',
              whiteSpace: 'nowrap',
            }}
          >
            {task.title}
          </div>
          {task.prUrl && (
            <div
              style={{
                fontSize: 11,
                color: 'var(--accent-600)',
                marginTop: 2,
                overflow: 'hidden',
                textOverflow: 'ellipsis',
                whiteSpace: 'nowrap',
              }}
            >
              PR opened
            </div>
          )}
        </div>

        {/* Jira key */}
        <div>
          {task.jiraKey ? (
            task.jiraUrl ? (
              <a
                href={task.jiraUrl}
                target="_blank"
                rel="noopener noreferrer"
                onClick={(e) => e.stopPropagation()}
                className="gtx-mono"
                style={{
                  fontSize: 11.5,
                  color: 'var(--accent-600)',
                  fontWeight: 500,
                  textDecoration: 'none',
                }}
              >
                {task.jiraKey}
              </a>
            ) : (
              <span
                className="gtx-mono"
                style={{ fontSize: 11.5, color: 'var(--text-muted)', fontWeight: 500 }}
              >
                {task.jiraKey}
              </span>
            )
          ) : (
            <span style={{ fontSize: 11.5, color: 'var(--text-faint)' }}>—</span>
          )}
        </div>

        {/* Duration */}
        <div
          className="gtx-mono"
          style={{ fontSize: 12, color: 'var(--text-muted)', fontVariantNumeric: 'tabular-nums' }}
        >
          {formatDuration(task.durationMs)}
        </div>

        {/* Tokens */}
        <div
          className="gtx-mono"
          style={{ fontSize: 12, color: 'var(--text-muted)', fontVariantNumeric: 'tabular-nums' }}
        >
          {formatTokens(task.tokenUsage)}
        </div>

        {/* Cost */}
        <div
          className="gtx-mono"
          style={{ fontSize: 12, color: 'var(--text-muted)', fontVariantNumeric: 'tabular-nums' }}
        >
          {formatCost(task.costUsd)}
        </div>

        {/* Iterations */}
        <div
          className="gtx-mono"
          style={{ fontSize: 12, color: 'var(--text-muted)', fontVariantNumeric: 'tabular-nums', textAlign: 'center' }}
        >
          {task.iterations ?? '—'}
        </div>

        {/* Created */}
        <div style={{ fontSize: 11.5, color: 'var(--text-subtle)', textAlign: 'right' }}>
          {formatRelativeTime(task.createdAt)}
        </div>
      </div>
    </Link>
  )
}

// ── Column header ─────────────────────────────────────────────────────────────

/**
 * ClarificationBanner — surfaces any coding tasks currently paused on
 * a human.ask_clarification call. Rendered at the top of the Coding
 * Agent list page as a compact notice with per-task links; the full
 * form (questions + Submit answers) lives on the task detail page so
 * there's a single source of truth for answering.
 */
function ClarificationBanner({ tasks }: { tasks: Task[] }) {
  const paused = tasks.filter((t) => t.status === 'awaiting_input')
  if (paused.length === 0) return null
  return (
    <div
      role="region"
      aria-label="Coding tasks awaiting your input"
      style={{
        margin: '12px 20px 0',
        padding: '10px 14px',
        borderRadius: 8,
        background: 'var(--accent-soft)',
        border: '1px solid var(--brand-teal-200)',
        display: 'flex',
        flexDirection: 'column',
        gap: 6,
      }}
    >
      <div style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
        <Pill tone="warn" size="xs" dot>Awaiting your input</Pill>
        <span style={{ fontSize: 12, color: 'var(--text)' }}>
          {paused.length} coding {paused.length === 1 ? 'run is' : 'runs are'} paused on a clarification.
        </span>
      </div>
      <ul style={{ margin: 0, padding: 0, listStyle: 'none', display: 'flex', flexDirection: 'column', gap: 4 }}>
        {paused.map((t) => (
          <li key={t.id} style={{ fontSize: 12.5 }}>
            <Link
              href={`/dashboard/tasks/${t.id}`}
              style={{ color: 'var(--brand-teal-700)', textDecoration: 'none', fontWeight: 600, marginRight: 8 }}
            >
              {t.jiraKey ?? t.id.slice(0, 8)}
            </Link>
            <span style={{ color: 'var(--text-muted)' }}>
              {t.title || 'untitled'} — open the task to answer.
            </span>
          </li>
        ))}
      </ul>
    </div>
  )
}

function TableHeader() {
  return (
    <div
      style={{
        display: 'grid',
        gridTemplateColumns: '120px 1fr 90px 80px 70px 60px 60px 80px',
        gap: 12,
        padding: '6px 16px',
        fontSize: 11,
        fontWeight: 500,
        color: 'var(--text-subtle)',
        letterSpacing: 0.3,
        textTransform: 'uppercase',
      }}
    >
      <div>Status</div>
      <div>Title</div>
      <div>Jira</div>
      <div>Duration</div>
      <div>Tokens</div>
      <div>Cost</div>
      <div style={{ textAlign: 'center' }}>Iters</div>
      <div style={{ textAlign: 'right' }}>Created</div>
    </div>
  )
}

// ── Page ──────────────────────────────────────────────────────────────────────

export default function TasksPage() {
  const [showNewRun, setShowNewRun] = useState(false)
  const { data, isLoading, error, mutate } = useSWR('tasks', getTasks, { refreshInterval: 10000 })

  const codingTasks = data?.filter((t) => t.agentType === 'coding') ?? []
  const displayTasks = codingTasks.length > 0 ? codingTasks : (data ?? [])

  return (
    <ErrorBoundary>
    <main style={{ flex: 1, display: 'flex', flexDirection: 'column', overflow: 'auto' }}>
      {/* Header — matches Shell Header pattern */}
      <header
        style={{
          borderBottom: '1px solid var(--panel-border)',
          background: 'var(--bg)',
          padding: '0 20px',
        }}
      >
        <div style={{ display: 'flex', alignItems: 'center', gap: 12, height: 52 }}>
          <div style={{ flex: 1, minWidth: 0 }}>
            <div style={{ display: 'flex', alignItems: 'baseline', gap: 10 }}>
              <h1 style={{ margin: 0, fontSize: 17, fontWeight: 600, letterSpacing: -0.3, color: 'var(--text)' }}>
                Coding Agent
              </h1>
              <span style={{ fontSize: 12.5, color: 'var(--text-subtle)' }}>
                Active and recent coding runs
              </span>
            </div>
          </div>
          <div style={{ display: 'flex', alignItems: 'center', gap: 6 }}>
            <Btn size="sm" tone="ghost" icon={<RefreshCw size={12} />} onClick={() => mutate()}>
              Refresh
            </Btn>
            <Btn size="sm" tone="brand" icon={<Plus size={12} />} onClick={() => setShowNewRun(true)}>
              New run
            </Btn>
          </div>
        </div>
      </header>

      {/* HITL banner — any coding task currently paused on a clarification */}
      <ClarificationBanner tasks={codingTasks} />

      {/* Content */}
      <div style={{ padding: '16px 20px 24px', flex: 1 }} aria-live="polite" aria-atomic="false">
        {error ? (
          <ErrorMessage message="Failed to load tasks" onRetry={() => mutate()} />
        ) : isLoading ? (
          <div
            style={{
              display: 'flex',
              alignItems: 'center',
              justifyContent: 'center',
              height: 200,
              color: 'var(--text-muted)',
            }}
            role="status"
            aria-label="Loading tasks"
          >
            <Spinner size={24} />
          </div>
        ) : displayTasks.length === 0 ? (
          <EmptyState
            title="No coding tasks yet"
            description="Trigger one from Slack, the Jira webhook, or click 'New run' above."
            action={{ label: 'New run', onClick: () => setShowNewRun(true) }}
          />
        ) : (
          <div style={{ display: 'flex', flexDirection: 'column', gap: 4 }}>
            <TableHeader />
            <div
              style={{ display: 'flex', flexDirection: 'column', gap: 6 }}
              role="list"
              aria-label="Coding tasks"
            >
              {displayTasks.map((task) => (
                <div key={task.id} role="listitem">
                  <TaskRow task={task} />
                </div>
              ))}
            </div>
          </div>
        )}
      </div>
      <NewRunModal open={showNewRun} onClose={() => setShowNewRun(false)} />
    </main>
    </ErrorBoundary>
  )
}
