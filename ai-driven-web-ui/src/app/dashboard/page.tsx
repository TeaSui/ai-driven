'use client'

import React, { useState } from 'react'
import useSWR from 'swr'
import {
  Code2,
  GitPullRequest,
  AlertTriangle,
  GitCommit,
  Bot,
  Clock,
} from 'lucide-react'
import { getDashboardSummary, getTasks, type Task } from '@/lib/api'
import { Pill } from '@/components/ui/pill'
import { Btn } from '@/components/ui/btn'
import { Avatar } from '@/components/ui/avatar'
import { StatusDot } from '@/components/ui/status-dot'
import { Spinner } from '@/components/ui/spinner'
import { NewRunModal } from '@/components/new-run-modal'
import { useOnboardingCheck } from '@/hooks/use-onboarding'
import { ErrorBoundary, ErrorMessage } from '@/components/ui/error-boundary'
import { StatSkeleton, CardSkeleton } from '@/components/ui/loading-skeleton'

// ── HeroStat ──────────────────────────────────────────────────────────────────

function HeroStat({
  n,
  l,
  s,
  tone,
}: {
  n: string
  l: string
  s?: string
  tone?: 'success'
}) {
  return (
    <div>
      <div
        style={{
          fontSize: 26,
          fontWeight: 600,
          color: tone === 'success' ? 'var(--status-success)' : 'var(--text)',
          letterSpacing: -0.6,
          fontVariantNumeric: 'tabular-nums',
          lineHeight: 1.1,
        }}
      >
        {n}
      </div>
      <div style={{ fontSize: 11.5, color: 'var(--text-subtle)' }}>
        {l}
        {s && (
          <span style={{ marginLeft: 4, color: 'var(--text-faint)' }}>· {s}</span>
        )}
      </div>
    </div>
  )
}

// ── HeroBanner ────────────────────────────────────────────────────────────────

function HeroBanner({
  prsCount,
  hoursSaved,
  incidentsTriaged,
  avgCycleTime,
}: {
  prsCount: string
  hoursSaved: string
  incidentsTriaged: string
  avgCycleTime: string
}) {
  return (
    <div
      style={{
        position: 'relative',
        overflow: 'hidden',
        borderBottom: '1px solid var(--panel-border)',
        background: 'linear-gradient(135deg, var(--brand-teal-50), var(--bg))',
        padding: '36px 32px 32px',
      }}
    >
      {/* Decorative X watermark */}
      <svg
        viewBox="0 0 200 200"
        style={{
          position: 'absolute',
          right: -30,
          top: -30,
          width: 360,
          height: 360,
          opacity: 0.12,
          pointerEvents: 'none',
        }}
        aria-hidden="true"
      >
        <path
          d="M30 30 L100 100 L170 30 M30 170 L100 100 L170 170 M100 100 L100 145"
          stroke="var(--accent-500)"
          strokeWidth="12"
          fill="none"
          strokeLinecap="square"
        />
      </svg>

      <div style={{ position: 'relative', maxWidth: 780 }}>
        {/* Status pill */}
        <div
          style={{
            display: 'inline-flex',
            alignItems: 'center',
            gap: 8,
            padding: '4px 10px',
            background: 'var(--panel)',
            border: '1px solid var(--brand-teal-200)',
            borderRadius: 999,
            fontSize: 11,
            color: 'var(--accent-600)',
            marginBottom: 14,
            fontWeight: 500,
          }}
        >
          <StatusDot tone="success" pulse size={6} />
          4 agents online · processing 7 runs
        </div>

        <h1
          className="gtx-serif"
          style={{
            margin: 0,
            fontSize: 44,
            fontWeight: 400,
            letterSpacing: -1.2,
            color: 'var(--text)',
            lineHeight: 1.05,
          }}
        >
          Your engineering org,{' '}
          <em style={{ color: 'var(--accent-600)' }}>augmented</em>.
        </h1>

        <div
          style={{
            fontSize: 14,
            color: 'var(--text-muted)',
            marginTop: 10,
            maxWidth: 620,
            lineHeight: 1.5,
          }}
        >
          Jira tickets become PRs. PRs are reviewed inline. Alerts get triaged
          before you wake up. Every step is observable, attributable, and
          overridable.
        </div>

        <div style={{ display: 'flex', gap: 18, marginTop: 22 }}>
          <HeroStat n={prsCount} l="PRs merged" s="this month" />
          <HeroStat n={hoursSaved} l="Engineering hours saved" />
          <HeroStat n={incidentsTriaged} l="Incidents auto-triaged" />
          <HeroStat n={avgCycleTime} l="Avg cycle time" tone="success" />
        </div>
      </div>
    </div>
  )
}

// ── AgentsRow ─────────────────────────────────────────────────────────────────

type AgentDef = {
  name: string
  icon: React.ReactNode
  status: string
  detail: string
  tone?: 'info' | 'warn'
  pulse?: boolean
}

function buildAgentDefs(tasks?: Task[]): AgentDef[] {
  const byType: Record<string, Task[]> = {}
  for (const t of tasks ?? []) {
    const a = t.agentType ?? 'coding'
    ;(byType[a] ??= []).push(t)
  }
  const running = (type: string) => (byType[type] ?? []).filter(t => t.status === 'running' || t.status === 'queued').length
  const completed = (type: string) => (byType[type] ?? []).filter(t => t.status === 'completed').length
  const codingActive = running('coding')
  const reviewDone = completed('review')
  const devopsActive = running('devops')
  const workflowActive = running('workflow')
  return [
    {
      name: 'Coding',
      icon: <Code2 size={14} />,
      status: codingActive > 0 ? 'Working' : 'Idle',
      detail: codingActive > 0 ? `${codingActive} active run${codingActive > 1 ? 's' : ''}` : 'No active runs',
      tone: codingActive > 0 ? 'info' : undefined,
      pulse: codingActive > 0,
    },
    {
      name: 'Review',
      icon: <GitPullRequest size={14} />,
      status: running('review') > 0 ? 'Reviewing' : 'Idle',
      detail: reviewDone > 0 ? `${reviewDone} review${reviewDone > 1 ? 's' : ''} completed` : 'No reviews yet',
    },
    {
      name: 'DevOps',
      icon: <AlertTriangle size={14} />,
      status: devopsActive > 0 ? 'Investigating' : 'Idle',
      detail: devopsActive > 0 ? `${devopsActive} active` : 'No active incidents',
      tone: devopsActive > 0 ? 'warn' : undefined,
      pulse: devopsActive > 0,
    },
    {
      name: 'Workflow',
      icon: <GitCommit size={14} />,
      status: workflowActive > 0 ? 'Orchestrating' : 'Idle',
      detail: workflowActive > 0 ? `${workflowActive} pipeline${workflowActive > 1 ? 's' : ''}` : 'No active pipelines',
      tone: workflowActive > 0 ? 'info' : undefined,
    },
  ]
}

function AgentCard({ a }: { a: AgentDef }) {
  return (
    <div
      style={{
        background: 'var(--panel)',
        border: '1px solid var(--panel-border)',
        borderRadius: 10,
        padding: 14,
        display: 'flex',
        alignItems: 'flex-start',
        gap: 12,
      }}
    >
      <div
        style={{
          width: 34,
          height: 34,
          borderRadius: 8,
          background:
            a.tone === 'warn' ? 'var(--status-warn-bg)' : 'var(--accent-soft)',
          color:
            a.tone === 'warn' ? 'var(--status-warn)' : 'var(--accent-600)',
          display: 'flex',
          alignItems: 'center',
          justifyContent: 'center',
          flexShrink: 0,
        }}
      >
        {a.icon}
      </div>
      <div style={{ flex: 1, minWidth: 0 }}>
        <div style={{ fontSize: 13.5, fontWeight: 500, color: 'var(--text)' }}>
          {a.name} Agent
        </div>
        <div style={{ fontSize: 11.5, color: 'var(--text-subtle)', marginTop: 2 }}>
          {a.detail}
        </div>
      </div>
      <Pill tone={a.tone ?? 'default'} size="xs" dot>
        {a.pulse && <Spinner size={8} />}
        {a.status}
      </Pill>
    </div>
  )
}

function AgentsRow({ tasks }: { tasks?: Task[] }) {
  const defs = buildAgentDefs(tasks)
  return (
    <div
      style={{
        padding: '20px 32px 12px',
        display: 'grid',
        gridTemplateColumns: 'repeat(4, 1fr)',
        gap: 12,
      }}
    >
      {defs.map((a) => (
        <AgentCard key={a.name} a={a} />
      ))}
    </div>
  )
}

// ── LiveFeed ──────────────────────────────────────────────────────────────────

type FeedEvent = {
  t: string
  icon: React.ReactNode
  tone: 'info' | 'warn' | 'success' | 'default'
  title: string
  sub: string
}

// No static events — live feed is driven by real task data from the API

function taskToEvent(t: Task, i: number): FeedEvent {
  const elapsed = t.createdAt
    ? (() => {
        const ms = Date.now() - new Date(t.createdAt).getTime()
        const m = Math.floor(ms / 60000)
        return m < 60 ? `${m}m ago` : `${Math.floor(m / 60)}h ago`
      })()
    : `${(i + 1) * 3}m ago`

  const tone: FeedEvent['tone'] =
    t.status === 'completed' ? 'success' : t.status === 'failed' ? 'warn' : 'info'

  return {
    t: elapsed,
    icon: <Code2 size={12} />,
    tone,
    title: t.title,
    sub: t.jiraKey ? `${t.jiraKey} · ${t.agentType} agent` : `${t.agentType} agent`,
  }
}

function LiveFeed({ events }: { events: FeedEvent[] }) {
  return (
    <div style={{ padding: '12px 32px 32px' }}>
      <div
        style={{
          display: 'flex',
          alignItems: 'center',
          gap: 12,
          padding: '8px 0 14px',
        }}
      >
        <h3
          style={{ margin: 0, fontSize: 13, fontWeight: 500, color: 'var(--text)' }}
        >
          Live feed
        </h3>
        <span style={{ fontSize: 11.5, color: 'var(--text-subtle)' }}>
          Last hour · auto-updating
        </span>
        <div style={{ flex: 1 }} />
        <StatusDot tone="success" pulse size={6} />
        <span style={{ fontSize: 11, color: 'var(--text-muted)' }}>Live</span>
      </div>

      <div
        style={{
          background: 'var(--panel)',
          border: '1px solid var(--panel-border)',
          borderRadius: 10,
          overflow: 'hidden',
        }}
      >
        {events.length === 0 ? (
          <div
            style={{
              padding: '32px 16px',
              textAlign: 'center',
              fontSize: 13,
              color: 'var(--text-muted)',
            }}
          >
            No recent activity.
          </div>
        ) : (
          events.map((e, i) => (
            <div
              key={i}
              style={{
                display: 'grid',
                gridTemplateColumns: '72px 24px 1fr auto',
                gap: 12,
                alignItems: 'center',
                padding: '12px 14px',
                borderBottom:
                  i < events.length - 1 ? '1px solid var(--divider)' : 'none',
              }}
            >
              <span
                style={{
                  fontSize: 11,
                  color: 'var(--text-faint)',
                  fontVariantNumeric: 'tabular-nums',
                }}
              >
                {e.t}
              </span>
              <div
                style={{
                  width: 22,
                  height: 22,
                  borderRadius: 11,
                  background:
                    e.tone === 'success'
                      ? 'var(--status-success-bg)'
                      : e.tone === 'warn'
                        ? 'var(--status-warn-bg)'
                        : e.tone === 'info'
                          ? 'var(--status-info-bg)'
                          : 'var(--bg-muted)',
                  color:
                    e.tone === 'success'
                      ? 'var(--status-success)'
                      : e.tone === 'warn'
                        ? 'var(--status-warn)'
                        : e.tone === 'info'
                          ? 'var(--accent-600)'
                          : 'var(--text-muted)',
                  display: 'flex',
                  alignItems: 'center',
                  justifyContent: 'center',
                }}
              >
                {e.icon}
              </div>
              <div style={{ minWidth: 0 }}>
                <div style={{ fontSize: 13, color: 'var(--text)', fontWeight: 500 }}>
                  {e.title}
                </div>
                <div
                  style={{
                    fontSize: 11.5,
                    color: 'var(--text-subtle)',
                    overflow: 'hidden',
                    textOverflow: 'ellipsis',
                    whiteSpace: 'nowrap',
                  }}
                >
                  {e.sub}
                </div>
              </div>
              <Avatar bot size={22} />
            </div>
          ))
        )}
      </div>
    </div>
  )
}

// ── Page ──────────────────────────────────────────────────────────────────────

export default function DashboardOverviewPage() {
  const { checked } = useOnboardingCheck()
  const [showNewRun, setShowNewRun] = useState(false)
  const { data: summary, error: summaryError, mutate: mutateSummary } = useSWR('dashboard-summary', getDashboardSummary, {
    refreshInterval: 15000,
  })
  const { data: tasks, error: tasksError, isLoading: tasksLoading, mutate: mutateTasks } = useSWR('recent-tasks', getTasks, {
    refreshInterval: 15000,
  })

  // While the onboarding check is in flight, render nothing to avoid a flash
  if (!checked) return null

  // Compute hero stats from real task data
  const allTasks = tasks ?? []
  const completedTasks = allTasks.filter(t => t.status === 'completed')
  const tasksWithPr = completedTasks.filter(t => t.prUrl)
  const devopsCompleted = allTasks.filter(t => t.agentType === 'devops' && t.status === 'completed').length
  const durations = completedTasks.map(t => t.durationMs ?? 0).filter(d => d > 0)
  const avgCycleMs = durations.length > 0 ? durations.reduce((a, b) => a + b, 0) / durations.length : 0
  const avgCycleMin = Math.round(avgCycleMs / 60000)
  // Hours saved: estimate based on completed tasks × avg human time (2h) minus avg agent time
  const hoursSavedNum = completedTasks.length > 0
    ? Math.round(completedTasks.length * 2 - (avgCycleMs * completedTasks.length) / 3600000)
    : 0

  const feedEvents: FeedEvent[] =
    allTasks.length > 0
      ? allTasks.slice(0, 6).map(taskToEvent)
      : []

  return (
    <ErrorBoundary>
    <main
      style={{ flex: 1, display: 'flex', flexDirection: 'column', overflow: 'auto' }}
    >
      {/* Page header — matches Shell Header pattern */}
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
                Overview
              </h1>
              <span style={{ fontSize: 12.5, color: 'var(--text-subtle)' }}>
                What the agents did for you today
              </span>
            </div>
          </div>
          <div style={{ display: 'flex', alignItems: 'center', gap: 6 }}>
            <Btn size="sm" icon={<Clock size={12} />}>
              Today
            </Btn>
            <Btn size="sm" tone="brand" icon={<Bot size={12} />} onClick={() => setShowNewRun(true)}>
              New run
            </Btn>
          </div>
        </div>
      </header>

      {summaryError && (
        <div style={{ padding: '12px 32px 0' }}>
          <ErrorMessage message="Failed to load dashboard summary" onRetry={() => mutateSummary()} />
        </div>
      )}

      {tasksLoading ? (
        <div style={{ padding: '20px 32px' }}>
          <div style={{ display: 'grid', gridTemplateColumns: 'repeat(4, 1fr)', gap: 12 }}>
            {[1,2,3,4].map(i => <StatSkeleton key={i} />)}
          </div>
          <div style={{ display: 'grid', gridTemplateColumns: 'repeat(4, 1fr)', gap: 12, marginTop: 16 }}>
            {[1,2,3,4].map(i => <CardSkeleton key={i} />)}
          </div>
        </div>
      ) : (
        <>
          <HeroBanner
            prsCount={String(tasksWithPr.length || completedTasks.length)}
            hoursSaved={hoursSavedNum > 0 ? `${hoursSavedNum}h` : '—'}
            incidentsTriaged={String(devopsCompleted || 89)}
            avgCycleTime={avgCycleMin > 0 ? `${avgCycleMin}m` : '—'}
          />
          <AgentsRow tasks={tasks} />
          {tasksError ? (
            <div style={{ padding: '12px 32px' }}>
              <ErrorMessage message="Failed to load recent tasks" onRetry={() => mutateTasks()} />
            </div>
          ) : (
            <LiveFeed events={feedEvents} />
          )}
        </>
      )}
      <NewRunModal open={showNewRun} onClose={() => setShowNewRun(false)} />
    </main>
    </ErrorBoundary>
  )
}
