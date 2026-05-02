'use client'

import { useRouter } from 'next/navigation'
import useSWR from 'swr'
import { getTasks, type Task } from '@/lib/api'
import { Pill } from '@/components/ui/pill'
import { Spinner } from '@/components/ui/spinner'

// ── Helpers ───────────────────────────────────────────────────────────────────

function formatDuration(ms?: number): string {
  if (ms == null) return '—'
  if (ms < 1000) return `${ms}ms`
  const s = Math.floor(ms / 1000)
  if (s < 60) return `${s}s`
  const m = Math.floor(s / 60)
  const rem = s % 60
  return `${m}m ${rem}s`
}

function formatCost(usd?: number): string {
  if (usd == null) return '—'
  return `$${usd.toFixed(4)}`
}

function formatTokens(n?: number): string {
  if (n == null) return '—'
  return n.toLocaleString()
}

function formatCreatedAt(iso: string): string {
  try {
    return new Intl.DateTimeFormat('en-SG', {
      month: 'short',
      day: 'numeric',
      hour: '2-digit',
      minute: '2-digit',
      hour12: false,
    }).format(new Date(iso))
  } catch {
    return iso
  }
}

type StatusTone = 'info' | 'success' | 'danger' | 'warn' | 'default'

function statusTone(status: string): StatusTone {
  const s = status.toLowerCase()
  if (s === 'completed' || s === 'success') return 'success'
  if (s === 'failed' || s === 'error') return 'danger'
  if (s === 'paused') return 'warn'
  if (s === 'running' || s === 'in_progress') return 'info'
  return 'default'
}

function statusLabel(status: string): string {
  const s = status.toLowerCase()
  if (s === 'in_progress') return 'Running'
  return status.charAt(0).toUpperCase() + status.slice(1)
}

// ── Page ──────────────────────────────────────────────────────────────────────

export default function RunsPage() {
  const router = useRouter()
  const { data: tasks, isLoading, error } = useSWR('all-tasks', getTasks, {
    refreshInterval: 15000,
  })

  const sorted = tasks
    ? [...tasks].sort(
        (a, b) => new Date(b.createdAt).getTime() - new Date(a.createdAt).getTime()
      )
    : []

  return (
    <main style={{ flex: 1, overflow: 'auto', padding: '24px 32px' }}>
      {/* Page header */}
      <div style={{ marginBottom: 20 }}>
        <h1
          style={{
            margin: 0,
            fontSize: 15,
            fontWeight: 600,
            letterSpacing: -0.2,
            color: 'var(--text)',
          }}
        >
          Run History
        </h1>
        <p style={{ margin: '4px 0 0', fontSize: 12.5, color: 'var(--text-subtle)' }}>
          All agent runs, sorted by most recent.
        </p>
      </div>

      {/* Table card */}
      <div
        style={{
          background: 'var(--bg-elev)',
          border: '1px solid var(--panel-border)',
          borderRadius: 12,
          overflow: 'hidden',
          boxShadow: 'var(--shadow-sm)',
        }}
      >
        {isLoading ? (
          <div
            style={{
              padding: '48px 16px',
              display: 'flex',
              alignItems: 'center',
              justifyContent: 'center',
              gap: 10,
              color: 'var(--text-muted)',
              fontSize: 13,
            }}
          >
            <Spinner size={16} />
            Loading runs…
          </div>
        ) : error ? (
          <div
            style={{
              padding: '48px 16px',
              textAlign: 'center',
              fontSize: 13,
              color: 'var(--status-danger)',
            }}
          >
            Failed to load run history.
          </div>
        ) : sorted.length === 0 ? (
          <div
            style={{
              padding: '48px 16px',
              textAlign: 'center',
              fontSize: 13,
              color: 'var(--text-muted)',
            }}
          >
            No runs found.
          </div>
        ) : (
          <table
            style={{
              width: '100%',
              borderCollapse: 'collapse',
              fontSize: 12.5,
            }}
          >
            <thead>
              <tr
                style={{
                  borderBottom: '1px solid var(--panel-border)',
                  background: 'var(--bg-muted)',
                }}
              >
                {['Status', 'Agent Type', 'Title', 'Duration', 'Tokens', 'Cost', 'Created'].map((h) => (
                  <th
                    key={h}
                    style={{
                      padding: '8px 14px',
                      textAlign: 'left',
                      fontSize: 11,
                      fontWeight: 600,
                      letterSpacing: 0.4,
                      textTransform: 'uppercase',
                      color: 'var(--text-subtle)',
                      whiteSpace: 'nowrap',
                    }}
                  >
                    {h}
                  </th>
                ))}
              </tr>
            </thead>
            <tbody>
              {sorted.map((task: Task, i) => (
                <tr
                  key={task.id}
                  onClick={() => router.push(`/dashboard/tasks/${task.id}`)}
                  style={{
                    borderBottom:
                      i < sorted.length - 1 ? '1px solid var(--divider)' : 'none',
                    cursor: 'pointer',
                    transition: 'background .1s',
                  }}
                  onMouseEnter={(e) => {
                    ;(e.currentTarget as HTMLTableRowElement).style.background =
                      'var(--bg-hover)'
                  }}
                  onMouseLeave={(e) => {
                    ;(e.currentTarget as HTMLTableRowElement).style.background = ''
                  }}
                >
                  <td style={{ padding: '10px 14px' }}>
                    <Pill tone={statusTone(task.status)} size="xs" dot>
                      {statusLabel(task.status)}
                    </Pill>
                  </td>
                  <td style={{ padding: '10px 14px' }}>
                    <span
                      className="gtx-mono"
                      style={{ fontSize: 11.5, color: 'var(--text-subtle)' }}
                    >
                      {task.agentType}
                    </span>
                  </td>
                  <td
                    style={{
                      padding: '10px 14px',
                      color: 'var(--text)',
                      fontWeight: 500,
                      maxWidth: 320,
                      overflow: 'hidden',
                      textOverflow: 'ellipsis',
                      whiteSpace: 'nowrap',
                    }}
                  >
                    {task.title}
                    {task.jiraKey && (
                      <span
                        className="gtx-mono"
                        style={{
                          marginLeft: 8,
                          fontSize: 11,
                          color: 'var(--text-faint)',
                          fontWeight: 400,
                        }}
                      >
                        {task.jiraKey}
                      </span>
                    )}
                  </td>
                  <td
                    style={{
                      padding: '10px 14px',
                      color: 'var(--text-muted)',
                      fontVariantNumeric: 'tabular-nums',
                      whiteSpace: 'nowrap',
                    }}
                  >
                    {formatDuration(task.durationMs)}
                  </td>
                  <td
                    style={{
                      padding: '10px 14px',
                      color: 'var(--text-muted)',
                      fontVariantNumeric: 'tabular-nums',
                    }}
                  >
                    {formatTokens(task.tokenUsage)}
                  </td>
                  <td
                    style={{
                      padding: '10px 14px',
                      color: 'var(--text-muted)',
                      fontVariantNumeric: 'tabular-nums',
                    }}
                  >
                    {formatCost(task.costUsd)}
                  </td>
                  <td
                    style={{
                      padding: '10px 14px',
                      color: 'var(--text-faint)',
                      fontSize: 12,
                      whiteSpace: 'nowrap',
                    }}
                  >
                    {formatCreatedAt(task.createdAt)}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
      </div>
    </main>
  )
}
