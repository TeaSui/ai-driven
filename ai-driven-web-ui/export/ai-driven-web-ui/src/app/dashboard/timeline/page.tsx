'use client'

import { useState } from 'react'
import Link from 'next/link'
import {
  CircleDot,
  GitPullRequest,
  Rocket,
  Activity,
  Code2,
  ChevronRight,
  Clock,
  ExternalLink,
} from 'lucide-react'
import { Card } from '@/components/ui/card'
import { Badge, StatusBadge, AgentBadge } from '@/components/ui/badge'
import { cn } from '@/lib/cn'

// MOCK — wire to real API when backend endpoints exist
type Stage = 'jira' | 'code' | 'review' | 'deploy' | 'monitor'

interface PipelineRun {
  id: string
  jiraKey: string
  title: string
  repo: string
  branch: string
  agent: 'coding' | 'review' | 'workflow' | 'devops'
  stage: Stage
  stageStatus: 'running' | 'passed' | 'failed' | 'queued'
  prUrl?: string
  prNumber?: number
  startedAt: string
  durationMs: number
}

const MOCK_RUNS: PipelineRun[] = [
  {
    id: 'run_01',
    jiraKey: 'PLAT-4218',
    title: 'Add rate limiting to /api/chat/send',
    repo: 'platform/api',
    branch: 'agent/plat-4218-rate-limit',
    agent: 'coding',
    stage: 'review',
    stageStatus: 'running',
    prUrl: 'https://github.com/platform/api/pull/847',
    prNumber: 847,
    startedAt: new Date(Date.now() - 1000 * 60 * 7).toISOString(),
    durationMs: 1000 * 60 * 7,
  },
  {
    id: 'run_02',
    jiraKey: 'BANK-1092',
    title: 'Fix nullable check in account balance serializer',
    repo: 'banking/core-svc',
    branch: 'agent/bank-1092-balance',
    agent: 'coding',
    stage: 'deploy',
    stageStatus: 'running',
    prUrl: 'https://github.com/banking/core-svc/pull/2310',
    prNumber: 2310,
    startedAt: new Date(Date.now() - 1000 * 60 * 23).toISOString(),
    durationMs: 1000 * 60 * 23,
  },
  {
    id: 'run_03',
    jiraKey: 'PLAT-4201',
    title: 'Instrument OTEL spans on payment-gateway',
    repo: 'platform/payment-gateway',
    branch: 'agent/plat-4201-otel',
    agent: 'coding',
    stage: 'monitor',
    stageStatus: 'passed',
    prUrl: 'https://github.com/platform/payment-gateway/pull/412',
    prNumber: 412,
    startedAt: new Date(Date.now() - 1000 * 60 * 58).toISOString(),
    durationMs: 1000 * 60 * 51,
  },
  {
    id: 'run_04',
    jiraKey: 'DEVX-318',
    title: 'Migrate build scripts to pnpm workspaces',
    repo: 'platform/devx-tools',
    branch: 'agent/devx-318-pnpm',
    agent: 'coding',
    stage: 'code',
    stageStatus: 'running',
    startedAt: new Date(Date.now() - 1000 * 60 * 3).toISOString(),
    durationMs: 1000 * 60 * 3,
  },
  {
    id: 'run_05',
    jiraKey: 'BANK-1105',
    title: 'Triage: elevated 5xx on /accounts endpoint',
    repo: 'banking/core-svc',
    branch: '—',
    agent: 'devops',
    stage: 'monitor',
    stageStatus: 'failed',
    startedAt: new Date(Date.now() - 1000 * 60 * 14).toISOString(),
    durationMs: 1000 * 60 * 14,
  },
  {
    id: 'run_06',
    jiraKey: 'PLAT-4230',
    title: 'Upgrade @anthropic-ai/sdk to 0.32.0',
    repo: 'platform/api',
    branch: 'agent/plat-4230-sdk-upgrade',
    agent: 'workflow',
    stage: 'jira',
    stageStatus: 'queued',
    startedAt: new Date(Date.now() - 1000 * 60 * 1).toISOString(),
    durationMs: 0,
  },
]

const STAGES: { id: Stage; label: string; icon: React.ElementType }[] = [
  { id: 'jira', label: 'Jira', icon: CircleDot },
  { id: 'code', label: 'Code', icon: Code2 },
  { id: 'review', label: 'Review', icon: GitPullRequest },
  { id: 'deploy', label: 'Deploy', icon: Rocket },
  { id: 'monitor', label: 'Monitor', icon: Activity },
]

function formatDuration(ms: number) {
  if (ms < 60000) return `${Math.floor(ms / 1000)}s`
  const m = Math.floor(ms / 60000)
  if (m < 60) return `${m}m`
  return `${Math.floor(m / 60)}h ${m % 60}m`
}

function StageDot({
  stage,
  currentStage,
  stageStatus,
}: {
  stage: Stage
  currentStage: Stage
  stageStatus: PipelineRun['stageStatus']
}) {
  const currentIdx = STAGES.findIndex((s) => s.id === currentStage)
  const thisIdx = STAGES.findIndex((s) => s.id === stage)
  const isDone = thisIdx < currentIdx
  const isCurrent = thisIdx === currentIdx
  const isFuture = thisIdx > currentIdx

  let color = 'bg-slate-700'
  if (isDone) color = 'bg-brand'
  if (isCurrent) {
    color =
      stageStatus === 'failed'
        ? 'bg-red-500'
        : stageStatus === 'passed'
          ? 'bg-emerald-500'
          : 'bg-amber-400 animate-pulse-dot'
  }

  return (
    <div className="flex items-center gap-1.5 flex-shrink-0">
      <div
        className={cn(
          'w-2 h-2 rounded-full',
          color,
          isFuture && 'opacity-30',
        )}
      />
      {thisIdx < STAGES.length - 1 && (
        <div
          className={cn(
            'w-6 h-px',
            isDone ? 'bg-brand/50' : 'bg-slate-700',
          )}
        />
      )}
    </div>
  )
}

function PipelineRow({ run }: { run: PipelineRun }) {
  const stageLabel = STAGES.find((s) => s.id === run.stage)?.label ?? run.stage
  const statusLabel =
    run.stageStatus === 'running'
      ? `${stageLabel}…`
      : run.stageStatus === 'passed'
        ? `${stageLabel} ✓`
        : run.stageStatus === 'failed'
          ? `${stageLabel} failed`
          : `Queued: ${stageLabel}`

  const statusColor =
    run.stageStatus === 'running'
      ? 'text-amber-400'
      : run.stageStatus === 'passed'
        ? 'text-emerald-400'
        : run.stageStatus === 'failed'
          ? 'text-red-400'
          : 'text-slate-500'

  return (
    <Link
      href={`/dashboard/tasks/${run.id}`}
      className="block group"
    >
      <div className="grid grid-cols-[auto_1fr_auto_auto_auto] gap-4 items-center px-4 py-3 rounded-lg hover:bg-surface-base transition-colors">
        {/* Jira key */}
        <span className="text-xs font-mono text-slate-500 w-20 tabular-nums">
          {run.jiraKey}
        </span>

        {/* Title + repo */}
        <div className="min-w-0">
          <p className="text-sm text-slate-200 truncate group-hover:text-white transition-colors">
            {run.title}
          </p>
          <div className="flex items-center gap-2 mt-0.5 text-[11px] text-slate-500">
            <span className="truncate">{run.repo}</span>
            {run.prNumber && (
              <>
                <span>·</span>
                <span className="text-brand-light">#{run.prNumber}</span>
              </>
            )}
          </div>
        </div>

        {/* Pipeline dots */}
        <div className="hidden md:flex items-center">
          {STAGES.map((s) => (
            <StageDot
              key={s.id}
              stage={s.id}
              currentStage={run.stage}
              stageStatus={run.stageStatus}
            />
          ))}
        </div>

        {/* Stage status */}
        <span className={cn('text-xs font-medium w-24 text-right', statusColor)}>
          {statusLabel}
        </span>

        {/* Duration + agent */}
        <div className="flex items-center gap-3">
          <span className="text-[11px] text-slate-500 tabular-nums w-12 text-right inline-flex items-center gap-1 justify-end">
            <Clock className="h-3 w-3" />
            {formatDuration(run.durationMs)}
          </span>
          <AgentBadge type={run.agent} />
          <ChevronRight className="h-4 w-4 text-slate-600 group-hover:text-slate-400 transition-colors" />
        </div>
      </div>
    </Link>
  )
}

export default function TimelinePage() {
  const [stageFilter, setStageFilter] = useState<Stage | 'all'>('all')

  const filtered =
    stageFilter === 'all'
      ? MOCK_RUNS
      : MOCK_RUNS.filter((r) => r.stage === stageFilter)

  const countByStage = STAGES.reduce(
    (acc, s) => {
      acc[s.id] = MOCK_RUNS.filter((r) => r.stage === s.id).length
      return acc
    },
    {} as Record<Stage, number>,
  )

  return (
    <div className="space-y-6">
      {/* Header */}
      <div className="flex items-center justify-between">
        <div>
          <h1 className="text-xl font-bold text-slate-100">Pipeline timeline</h1>
          <p className="text-xs text-slate-500 mt-1">
            Jira → Code → Review → Deploy → Monitor · {MOCK_RUNS.length} active runs
          </p>
        </div>
      </div>

      {/* Stage filter chips */}
      <div className="flex flex-wrap gap-2">
        <button
          onClick={() => setStageFilter('all')}
          className={cn(
            'inline-flex items-center gap-2 rounded-lg border px-3 py-1.5 text-xs font-medium transition-colors',
            stageFilter === 'all'
              ? 'border-brand bg-brand/10 text-brand-light'
              : 'border-slate-700/50 bg-surface text-slate-400 hover:text-slate-200',
          )}
        >
          All stages
          <span className="text-[10px] tabular-nums text-slate-500">
            {MOCK_RUNS.length}
          </span>
        </button>
        {STAGES.map((s) => {
          const Icon = s.icon
          const active = stageFilter === s.id
          return (
            <button
              key={s.id}
              onClick={() => setStageFilter(s.id)}
              className={cn(
                'inline-flex items-center gap-2 rounded-lg border px-3 py-1.5 text-xs font-medium transition-colors',
                active
                  ? 'border-brand bg-brand/10 text-brand-light'
                  : 'border-slate-700/50 bg-surface text-slate-400 hover:text-slate-200',
              )}
            >
              <Icon className="h-3.5 w-3.5" />
              {s.label}
              <span className="text-[10px] tabular-nums text-slate-500">
                {countByStage[s.id]}
              </span>
            </button>
          )
        })}
      </div>

      {/* Pipeline legend */}
      <Card className="p-4">
        <div className="flex items-center justify-between flex-wrap gap-3">
          <div className="flex items-center gap-6 text-xs text-slate-400">
            {STAGES.map((s, i) => {
              const Icon = s.icon
              return (
                <div key={s.id} className="flex items-center gap-1.5">
                  <Icon className="h-3.5 w-3.5 text-brand-light" />
                  <span>{s.label}</span>
                  {i < STAGES.length - 1 && (
                    <ChevronRight className="h-3 w-3 text-slate-600 ml-3" />
                  )}
                </div>
              )
            })}
          </div>
          <div className="flex items-center gap-3 text-[10px] text-slate-500">
            <span className="inline-flex items-center gap-1.5">
              <span className="w-1.5 h-1.5 rounded-full bg-brand" /> Done
            </span>
            <span className="inline-flex items-center gap-1.5">
              <span className="w-1.5 h-1.5 rounded-full bg-amber-400 animate-pulse-dot" />
              Running
            </span>
            <span className="inline-flex items-center gap-1.5">
              <span className="w-1.5 h-1.5 rounded-full bg-emerald-500" /> Passed
            </span>
            <span className="inline-flex items-center gap-1.5">
              <span className="w-1.5 h-1.5 rounded-full bg-red-500" /> Failed
            </span>
          </div>
        </div>
      </Card>

      {/* Rows */}
      <Card className="p-2">
        <div className="space-y-0.5">
          {filtered.map((run) => (
            <PipelineRow key={run.id} run={run} />
          ))}
        </div>
      </Card>
    </div>
  )
}
