'use client'

import { useState } from 'react'
import useSWR, { useSWRConfig } from 'swr'
import { getPipelineRuns, type PipelineRun as ApiPipelineRun } from '@/lib/api'
import {
  Code2,
  GitPullRequest,
  Check,
  Filter,
  RefreshCw,
  Plus,
  Github,
  GitBranch,
} from 'lucide-react'
import { Pill } from '@/components/ui/pill'
import { Btn } from '@/components/ui/btn'
import { Avatar } from '@/components/ui/avatar'
import { StatusDot } from '@/components/ui/status-dot'
import { Spinner } from '@/components/ui/spinner'
import { NewRunModal } from '@/components/new-run-modal'

// ── Custom icons (matching prototype) ─────────────────────────────────────────

function IcJira({ size = 14, color = 'currentColor' }: { size?: number; color?: string }) {
  return (
    <svg width={size} height={size} viewBox="0 0 16 16" fill="none"
      stroke={color} strokeWidth={1.5} strokeLinecap="round" strokeLinejoin="round"
      style={{ display: 'inline-block', flexShrink: 0 }}>
      <rect x="2.5" y="2.5" width="11" height="11" rx="1"/>
      <path d="M5 8l2.2 2.2L11 6.5"/>
    </svg>
  )
}

function IcCode({ size = 14, color = 'currentColor' }: { size?: number; color?: string }) {
  return (
    <svg width={size} height={size} viewBox="0 0 16 16" fill="none"
      stroke={color} strokeWidth={1.5} strokeLinecap="round" strokeLinejoin="round"
      style={{ display: 'inline-block', flexShrink: 0 }}>
      <path d="M5 4l-3 4 3 4"/>
      <path d="M11 4l3 4-3 4"/>
    </svg>
  )
}

function IcAws({ size = 14, color = 'currentColor' }: { size?: number; color?: string }) {
  return (
    <svg width={size} height={size} viewBox="0 0 16 16" fill="none"
      stroke={color} strokeWidth={1.5} strokeLinecap="round" strokeLinejoin="round"
      style={{ display: 'inline-block', flexShrink: 0 }}>
      <path d="M2 6c0 2.5 2.5 4.5 6 4.5S14 8.5 14 6"/>
      <path d="M4 10.5c1 1 2.5 2 4 2s3-1 4-2"/>
      <ellipse cx="8" cy="4" rx="4" ry="1.8"/>
    </svg>
  )
}

function IcDatadog({ size = 14, color = 'currentColor' }: { size?: number; color?: string }) {
  return (
    <svg width={size} height={size} viewBox="0 0 16 16" fill="none"
      stroke={color} strokeWidth={1.5} strokeLinecap="round" strokeLinejoin="round"
      style={{ display: 'inline-block', flexShrink: 0 }}>
      <path d="M2 8l4.5-4.5 3 3L12 4v5l-3 3-3-3L3 11"/>
    </svg>
  )
}

// ── Types ─────────────────────────────────────────────────────────────────────

type RunStatus = 'deployed' | 'merged' | 'reviewing' | 'coding' | 'blocked' | 'cancelled' | 'pr_open'

interface PipelineRun {
  id: string
  title: string
  repo: string
  branch?: string
  status: RunStatus
  /** 1-based stage index, 1=Jira … 5=Monitor */
  stage: number
  assignee: string
  updated: string
  duration: string
  pr?: string
  author?: string
  files?: number
  plus?: number
  minus?: number
  reviews?: { approved: number; comments: number }
  active?: boolean
  filesChanged?: number
  additions?: number
  deletions?: number
  prUrl?: string
}

// ── API → local run mapping ───────────────────────────────────────────────────

const STAGE_INDEX: Record<string, number> = {
  jira: 1,
  code: 2,
  review: 3,
  deploy: 4,
  monitor: 5,
}

function formatDuration(ms: number): string {
  const totalSeconds = Math.floor(ms / 1000)
  const minutes = Math.floor(totalSeconds / 60)
  const seconds = totalSeconds % 60
  return `${minutes}m ${seconds}s`
}

function formatRelative(ms: number): string {
  const minutes = Math.floor(ms / 60000)
  if (minutes < 1) return 'now'
  if (minutes < 60) return `${minutes}m ago`
  return `${Math.floor(minutes / 60)}h ago`
}

function deriveStatus(stage: string, stageStatus: string, assignee?: string): RunStatus {
  if (stageStatus === 'cancelled') return 'cancelled'
  if (stageStatus === 'failed') return 'blocked'
  if (stageStatus === 'passed') {
    // Terminal stage determines final status label
    if (stage === 'monitor' || stage === 'deploy') return 'deployed'
    if (stage === 'review') return 'pr_open'  // coding agent done = PR ready for review
    return assignee && assignee !== 'AI Agent' ? 'merged' : 'deployed'
  }
  if (stageStatus === 'queued') return 'coding'
  // active / running — map based on current stage
  if (stage === 'review') return 'reviewing'
  if (stage === 'deploy') return 'deployed'
  return 'coding'
}

function mapApiRun(r: ApiPipelineRun): PipelineRun {
  const assignee = r.assignee ?? 'AI Agent'
  return {
    // Use the backend UUID as the React key so two tasks sharing a Jira key
    // (e.g. the same ticket worked twice) don't collide.
    id: r.id,
    title: r.title,
    repo: r.repo ?? '',
    branch: r.branch ?? `agent/${r.jiraKey ?? r.id.slice(0, 8)}`,
    status: deriveStatus(r.stage, r.stageStatus, assignee),
    stage: STAGE_INDEX[r.stage] ?? 1,
    assignee,
    updated: formatRelative(r.durationMs),
    duration: formatDuration(r.durationMs),
    pr: r.prNumber != null ? `#${r.prNumber}` : undefined,
    prUrl: r.prUrl,
    active: r.stageStatus === 'active' || r.stageStatus === 'running' || r.stageStatus === 'queued',
    filesChanged: r.filesChanged,
    additions: r.additions,
    deletions: r.deletions,
  }
}

// ── Stage definitions ─────────────────────────────────────────────────────────

const STAGES = [
  { id: 'jira', label: 'Jira', icon: <IcJira size={11} /> },
  { id: 'code', label: 'Code', icon: <IcCode size={11} /> },
  { id: 'review', label: 'Review', icon: <GitPullRequest size={11} /> },
  { id: 'deploy', label: 'Deploy', icon: <IcAws size={11} /> },
  { id: 'monitor', label: 'Monitor', icon: <IcDatadog size={11} /> },
]

// ── StagePipeline ─────────────────────────────────────────────────────────────

function StagePipeline({
  stage,
  active,
  blocked,
}: {
  stage: number
  active?: boolean
  blocked?: boolean
}) {
  return (
    <div style={{ display: 'flex', alignItems: 'center' }}>
      {STAGES.map((s, i) => {
        const done = i < stage
        const current = i === stage - 1
        const pending = i >= stage

        const color = blocked && current
          ? 'var(--status-danger)'
          : current && active
            ? 'var(--accent-500)'
            : done
              ? 'var(--status-success)'
              : 'var(--text-faint)'

        const bg = blocked && current
          ? 'var(--status-danger-bg)'
          : current && active
            ? 'var(--accent-soft)'
            : done
              ? 'var(--status-success-bg)'
              : 'var(--bg-muted)'

        return (
          <span key={s.id} style={{ display: 'contents' }}>
            <div
              title={s.label}
              style={{
                width: 22,
                height: 22,
                borderRadius: 11,
                background: bg,
                color,
                border: `1px solid ${pending ? 'var(--border)' : 'transparent'}`,
                display: 'flex',
                alignItems: 'center',
                justifyContent: 'center',
                flexShrink: 0,
                position: 'relative',
              }}
            >
              {done ? <Check size={10} /> : s.icon}
              {current && active && (
                <span
                  style={{
                    position: 'absolute',
                    inset: -3,
                    borderRadius: 14,
                    border: `1.5px solid ${color}`,
                    animation: 'gtx-ring 1.8s ease-out infinite',
                    opacity: 0.5,
                  }}
                />
              )}
            </div>
            {i < STAGES.length - 1 && (
              <div
                style={{
                  flex: 1,
                  height: 2,
                  background:
                    i < stage - 1 ? 'var(--status-success)' : 'var(--border)',
                  margin: '0 2px',
                }}
              />
            )}
          </span>
        )
      })}
    </div>
  )
}

// ── TimelineStats ─────────────────────────────────────────────────────────────

function TimelineStats({ runs }: { runs: PipelineRun[] }) {
  const activeCount = runs.filter(r => r.active).length
  const completedRuns = runs.filter(r => !r.active)
  const successCount = runs.filter(r => r.status === 'merged' || r.status === 'deployed').length
  const mergedByHuman = runs.filter(r => r.status === 'merged').length
  const withPr = runs.filter(r => r.pr).length
  const total = runs.length
  // Merge rate: successful completions (merged + deployed) out of all finished runs
  const mergeRate = completedRuns.length > 0 ? Math.round((successCount / completedRuns.length) * 100) : 0

  // Avg cycle time: only count completed runs (active tasks are still accumulating time)
  function parseDuration(d: string): number {
    const m = d.match(/(\d+)m/)
    const s = d.match(/(\d+)s/)
    return (m ? parseInt(m[1]) * 60 : 0) + (s ? parseInt(s[1]) : 0)
  }
  const durations = completedRuns.map(r => parseDuration(r.duration)).filter(d => d > 0)
  const avgSec = durations.length > 0 ? Math.round(durations.reduce((a, b) => a + b, 0) / durations.length) : 0
  const avgMin = Math.floor(avgSec / 60)

  // Rough estimate: each merged PR saves ~0.5 engineering hours
  const hoursSaved = Math.round(completedRuns.length * 0.5)

  const stats = [
    { label: 'Tickets in flight', value: String(activeCount), delta: `of ${total}`, tone: 'brand' as const },
    { label: 'PRs opened', value: String(withPr), delta: `${completedRuns.length} completed`, tone: 'success' as const },
    { label: 'Merge rate', value: `${mergeRate}%`, delta: `${mergedByHuman} merged`, tone: 'success' as const },
    { label: 'Avg cycle time', value: `${avgMin}m`, delta: `${durations.length} runs`, tone: 'success' as const },
    { label: 'Engineering hours saved', value: `${hoursSaved}h`, delta: 'estimated', tone: 'default' as const },
  ]
  return (
    <div
      style={{
        display: 'grid',
        gridTemplateColumns: 'repeat(5, 1fr)',
        gap: 1,
        background: 'var(--panel-border)',
        borderBottom: '1px solid var(--panel-border)',
      }}
    >
      {stats.map((s) => (
        <div key={s.label} style={{ background: 'var(--bg)', padding: '16px 20px' }}>
          <div style={{ fontSize: 11.5, color: 'var(--text-subtle)', marginBottom: 4 }}>
            {s.label}
          </div>
          <div style={{ display: 'flex', alignItems: 'baseline', gap: 8 }}>
            <div
              style={{
                fontSize: 24,
                fontWeight: 600,
                color: 'var(--text)',
                letterSpacing: -0.6,
                fontVariantNumeric: 'tabular-nums',
              }}
            >
              {s.value}
            </div>
            <div
              style={{
                fontSize: 11,
                fontWeight: 500,
                color:
                  s.tone === 'success'
                    ? 'var(--status-success)'
                    : s.tone === 'brand'
                      ? 'var(--accent-600)'
                      : 'var(--text-subtle)',
              }}
            >
              {s.delta}
            </div>
          </div>
        </div>
      ))}
    </div>
  )
}

// ── TimelineLegend ────────────────────────────────────────────────────────────

function TimelineLegend() {
  return (
    <div
      style={{
        display: 'flex',
        alignItems: 'center',
        gap: 14,
        padding: '14px 0 10px',
        fontSize: 11,
        color: 'var(--text-subtle)',
      }}
    >
      <span>Stages:</span>
      {STAGES.map((s) => (
        <span
          key={s.id}
          style={{ display: 'inline-flex', alignItems: 'center', gap: 5 }}
        >
          <span
            style={{
              width: 16,
              height: 16,
              borderRadius: 4,
              border: '1px solid var(--border)',
              display: 'inline-flex',
              alignItems: 'center',
              justifyContent: 'center',
              color: 'var(--text-muted)',
            }}
          >
            {s.icon}
          </span>
          {s.label}
        </span>
      ))}
    </div>
  )
}

// ── TimelineRow ───────────────────────────────────────────────────────────────

const STATUS_MAP: Record<RunStatus, { tone: 'success' | 'info' | 'danger' | 'default'; label: string }> = {
  deployed: { tone: 'success', label: 'Deployed' },
  merged: { tone: 'success', label: 'Merged' },
  pr_open: { tone: 'info', label: 'PR Open' },
  reviewing: { tone: 'info', label: 'In review' },
  coding: { tone: 'info', label: 'Coding' },
  blocked: { tone: 'danger', label: 'Blocked' },
  cancelled: { tone: 'default', label: 'Cancelled' },
}

function TimelineRow({
  run,
  onOpen,
}: {
  run: PipelineRun
  onOpen?: () => void
}) {
  const [hover, setHover] = useState(false)
  const st = STATUS_MAP[run.status]

  return (
    <div
      onClick={onOpen}
      onMouseEnter={() => setHover(true)}
      onMouseLeave={() => setHover(false)}
      style={{
        display: 'grid',
        gridTemplateColumns: '110px 1fr 260px 180px 120px',
        gap: 16,
        alignItems: 'center',
        padding: '14px 16px',
        background: 'var(--panel)',
        border: `1px solid ${hover ? 'var(--border-strong)' : 'var(--panel-border)'}`,
        borderRadius: 8,
        cursor: 'pointer',
        transition: 'border-color .1s, background .1s',
      }}
      aria-label={`Open run ${run.id}: ${run.title}`}
    >
      {/* Ticket ID */}
      <div style={{ display: 'flex', alignItems: 'center', gap: 6 }}>
        <IcJira size={13} color="var(--accent-500)" />
        <span
          className="gtx-mono"
          style={{ fontSize: 12, color: 'var(--text)', fontWeight: 500 }}
        >
          {run.id}
        </span>
      </div>

      {/* Title + repo */}
      <div style={{ minWidth: 0 }}>
        <div
          style={{
            fontSize: 13.5,
            color: 'var(--text)',
            fontWeight: 500,
            overflow: 'hidden',
            textOverflow: 'ellipsis',
            whiteSpace: 'nowrap',
            marginBottom: 3,
          }}
        >
          {run.title}
        </div>
        <div
          style={{
            display: 'flex',
            alignItems: 'center',
            gap: 10,
            fontSize: 11.5,
            color: 'var(--text-subtle)',
          }}
        >
          <span style={{ display: 'inline-flex', alignItems: 'center', gap: 4 }}>
            <Github size={11} />
            {run.repo}
          </span>
          {run.branch && (
            <span
              className="gtx-mono"
              style={{ display: 'inline-flex', alignItems: 'center', gap: 3 }}
            >
              <GitBranch size={10} />
              {run.branch}
            </span>
          )}
          {run.pr && (
            run.prUrl ? (
              <a
                href={run.prUrl}
                target="_blank"
                rel="noopener noreferrer"
                className="gtx-mono"
                style={{ color: 'var(--accent-600)', textDecoration: 'none' }}
                onClick={(e) => e.stopPropagation()}
                aria-label={`Open pull request ${run.pr}`}
              >
                {run.pr}
              </a>
            ) : (
              <span className="gtx-mono" style={{ color: 'var(--accent-600)' }}>
                {run.pr}
              </span>
            )
          )}
        </div>
      </div>

      {/* Stage pipeline */}
      <StagePipeline
        stage={run.stage}
        active={run.active}
        blocked={run.status === 'blocked'}
      />

      {/* Author + diff */}
      <div
        style={{
          display: 'flex',
          alignItems: 'center',
          gap: 10,
          fontSize: 11.5,
          color: 'var(--text-subtle)',
        }}
      >
        {run.assignee === 'AI Agent' ? (
          <span style={{ display: 'inline-flex', alignItems: 'center', gap: 5 }}>
            <Avatar bot size={18} />
            <span style={{ color: 'var(--text-muted)' }}>Agent</span>
          </span>
        ) : (
          <span style={{ display: 'inline-flex', alignItems: 'center', gap: 5 }}>
            <Avatar name={run.assignee} size={18} />
            <span>{run.assignee.split(' ')[0]}</span>
          </span>
        )}
        {run.additions != null && run.deletions != null && (
          <>
            <span style={{ color: 'var(--text-faint)' }}>·</span>
            <span
              className="gtx-mono"
              style={{ color: '#2e7d32', fontSize: 12, fontFamily: 'monospace' }}
            >
              +{run.additions}
            </span>
            <span
              className="gtx-mono"
              style={{ color: '#c62828', fontSize: 12, fontFamily: 'monospace' }}
            >
              -{run.deletions}
            </span>
          </>
        )}
      </div>

      {/* Status pill */}
      <div style={{ display: 'flex', alignItems: 'center', gap: 8, justifySelf: 'end' }}>
        <Pill tone={st.tone} dot={run.active}>
          {run.active && <Spinner size={9} color="currentColor" />}
          {st.label}
        </Pill>
      </div>
    </div>
  )
}

// ── Page ──────────────────────────────────────────────────────────────────────

type FilterTab = 'all' | 'active' | 'merged' | 'blocked'

export default function TimelinePage() {
  const [filter, setFilter] = useState<FilterTab>('all')
  const [showNewRun, setShowNewRun] = useState(false)
  const [agentFilter, setAgentFilter] = useState<string | null>(null)
  const [showFilterMenu, setShowFilterMenu] = useState(false)
  const { mutate } = useSWRConfig()
  const { data: apiRuns, isValidating, mutate: revalidate } = useSWR('pipeline-runs', getPipelineRuns, { refreshInterval: 10000 })

  const allRuns: PipelineRun[] = apiRuns && apiRuns.length > 0 ? apiRuns.map(mapApiRun) : []
  const runs: PipelineRun[] = agentFilter
    ? allRuns.filter((r) => (r.assignee || '').toLowerCase().includes(agentFilter.toLowerCase()))
    : allRuns

  const isSuccess = (r: PipelineRun) => r.status === 'merged' || r.status === 'deployed' || r.status === 'pr_open'

  const filtered =
    filter === 'all'
      ? runs
      : filter === 'active'
        ? runs.filter((r) => r.active)
        : filter === 'merged'
          ? runs.filter(isSuccess)
          : runs.filter((r) => r.status === 'blocked')

  const tabs: { id: FilterTab; label: string; count: number; icon?: React.ReactNode }[] = [
    { id: 'all', label: 'All', count: runs.length },
    {
      id: 'active',
      label: 'Active',
      count: runs.filter((r) => r.active).length,
      icon: <StatusDot tone="success" pulse size={6} />,
    },
    { id: 'merged', label: 'Merged', count: runs.filter(isSuccess).length },
    { id: 'blocked', label: 'Blocked', count: runs.filter((r) => r.status === 'blocked').length },
  ]

  return (
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
                Timeline
              </h1>
              <span style={{ fontSize: 12.5, color: 'var(--text-subtle)' }}>
                Live view · {new Set(runs.map(r => r.repo).filter(Boolean)).size} repos · {runs.filter(r => r.active).length} active runs
              </span>
            </div>
          </div>
          <div style={{ display: 'flex', alignItems: 'center', gap: 6 }}>
            <div style={{ position: 'relative' }}>
              <Btn
                size="sm"
                icon={<Filter size={12} />}
                onClick={() => setShowFilterMenu((v) => !v)}
                tone={agentFilter ? 'brand' : undefined}
              >
                {agentFilter ? `Agent: ${agentFilter}` : 'Filter'}
              </Btn>
              {showFilterMenu && (
                <div
                  style={{
                    position: 'absolute',
                    top: 'calc(100% + 4px)',
                    right: 0,
                    background: 'var(--panel)',
                    border: '1px solid var(--panel-border)',
                    borderRadius: 8,
                    padding: 4,
                    minWidth: 180,
                    boxShadow: '0 4px 12px rgba(0,0,0,0.08)',
                    zIndex: 50,
                  }}
                >
                  {[null, 'AI Agent', 'Coding', 'Review', 'DevOps'].map((a) => (
                    <button
                      key={a ?? 'all'}
                      onClick={() => { setAgentFilter(a); setShowFilterMenu(false) }}
                      style={{
                        display: 'block', width: '100%', textAlign: 'left',
                        padding: '6px 10px', fontSize: 12, borderRadius: 6,
                        background: agentFilter === a ? 'var(--accent-soft)' : 'transparent',
                        color: 'var(--text)', border: 'none', cursor: 'pointer',
                      }}
                    >
                      {a ?? 'All agents'}
                    </button>
                  ))}
                </div>
              )}
            </div>
            <Btn
              size="sm"
              icon={<RefreshCw size={12} style={isValidating ? { animation: 'gtx-spin 0.8s linear infinite' } : undefined} />}
              onClick={() => revalidate()}
            >
              {isValidating ? 'Refreshing…' : 'Refresh'}
            </Btn>
            <Btn size="sm" tone="brand" icon={<Plus size={12} />} onClick={() => setShowNewRun(true)}>New run</Btn>
          </div>
        </div>
      </header>

      {/* Tabs */}
      <div
        style={{
          display: 'flex',
          gap: 2,
          padding: '0 20px',
          marginTop: -1,
          borderBottom: '1px solid var(--panel-border)',
        }}
      >
        {tabs.map((t) => (
          <button
            key={t.id}
            onClick={() => setFilter(t.id)}
            style={{
              display: 'inline-flex',
              alignItems: 'center',
              gap: 6,
              padding: '10px 14px',
              border: 'none',
              borderBottom: `2px solid ${filter === t.id ? 'var(--accent-500)' : 'transparent'}`,
              background: 'transparent',
              fontSize: 12.5,
              fontWeight: filter === t.id ? 500 : 400,
              color: filter === t.id ? 'var(--text)' : 'var(--text-muted)',
              cursor: 'pointer',
              fontFamily: 'inherit',
              transition: 'color .1s',
            }}
          >
            {t.icon}
            {t.label}
            <span
              style={{
                fontSize: 10.5,
                fontWeight: 400,
                color: 'var(--text-faint)',
                background: 'var(--bg-muted)',
                padding: '1px 5px',
                borderRadius: 8,
              }}
            >
              {t.count}
            </span>
          </button>
        ))}
      </div>

      {/* Stats strip */}
      <TimelineStats runs={runs} />

      {/* Runs list */}
      <div style={{ padding: '0 20px 20px' }}>
        <TimelineLegend />
        <div style={{ display: 'flex', flexDirection: 'column', gap: 8 }}>
          {filtered.length === 0 ? (
            <div
              style={{
                padding: '32px 16px',
                textAlign: 'center',
                fontSize: 13,
                color: 'var(--text-muted)',
              }}
            >
              No runs match the selected filter.
            </div>
          ) : (
            filtered.map((r) => <TimelineRow key={r.id} run={r} />)
          )}
        </div>
      </div>
      <NewRunModal open={showNewRun} onClose={() => setShowNewRun(false)} />
    </main>
  )
}
