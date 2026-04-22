'use client'

import useSWR from 'swr'
import Link from 'next/link'
import {
  ArrowUpRight,
  TrendingUp,
  CheckCircle2,
  DollarSign,
  Zap,
} from 'lucide-react'
import {
  getDashboardSummary,
  getUsageHistory,
  getUsageByAgent,
  getTasks,
} from '@/lib/api'
import { Card } from '@/components/ui/card'
import { StatusBadge, AgentBadge } from '@/components/ui/badge'
import { Skeleton } from '@/components/ui/skeleton'
import { cn } from '@/lib/cn'
import { AGENT_CONFIG, type AgentType } from '@/lib/constants'
import { formatTokens, formatCost } from '@/lib/format'

// ---- KPI card ----

function KpiCard({
  label,
  value,
  delta,
  icon: Icon,
  accent = 'text-brand-light',
  isLoading,
}: {
  label: string
  value: string | number
  delta?: { value: string; positive?: boolean }
  icon: React.ElementType
  accent?: string
  isLoading?: boolean
}) {
  return (
    <Card className="p-5">
      <div className="flex items-start justify-between">
        <div>
          <p className="text-[10px] uppercase tracking-wider text-slate-500 font-medium">
            {label}
          </p>
          {isLoading ? (
            <Skeleton className="h-8 w-20 mt-2" />
          ) : (
            <p className="mt-2 text-2xl font-semibold text-slate-100 tabular-nums">
              {value}
            </p>
          )}
          {delta && !isLoading && (
            <p
              className={cn(
                'mt-1 text-xs flex items-center gap-1',
                delta.positive ? 'text-emerald-400' : 'text-slate-500',
              )}
            >
              {delta.positive && <TrendingUp className="h-3 w-3" />}
              {delta.value}
            </p>
          )}
        </div>
        <div
          className={cn(
            'rounded-lg p-2 bg-surface-elevated/50',
            accent,
          )}
        >
          <Icon className="h-4 w-4" />
        </div>
      </div>
    </Card>
  )
}

// ---- Agent pill ----

function AgentCountPill({ type, count }: { type: string; count: number }) {
  const config = AGENT_CONFIG[type as AgentType]
  if (!config) return null
  return (
    <div
      className={cn(
        'inline-flex items-center gap-2 rounded-lg border px-3 py-2 text-sm',
        'border-slate-700/50 bg-surface-base',
      )}
    >
      <span
        className={cn(
          'w-1.5 h-1.5 rounded-full',
          count > 0 ? 'bg-current animate-pulse-dot' : 'bg-slate-700',
          config.color,
        )}
      />
      <span className={config.color}>{config.label}</span>
      <span className="text-slate-500">·</span>
      <span className="text-slate-200 tabular-nums font-medium">{count}</span>
    </div>
  )
}

// ---- Usage bar row ----

function UsageBarRow({
  label,
  valueIn,
  valueOut,
  cost,
  max,
}: {
  label: string
  valueIn: number
  valueOut: number
  cost: number
  max: number
}) {
  const pctIn = max > 0 ? (valueIn / max) * 100 : 0
  const pctOut = max > 0 ? (valueOut / max) * 100 : 0
  return (
    <div className="space-y-1.5">
      <div className="flex items-center justify-between text-xs">
        <span className="text-slate-300 capitalize">{label}</span>
        <span className="text-slate-500 tabular-nums">
          {formatTokens(valueIn + valueOut)} · {formatCost(cost)}
        </span>
      </div>
      <div className="relative h-1.5 w-full rounded-full bg-surface-base overflow-hidden flex">
        <div
          className="h-full bg-brand-light"
          style={{ width: `${pctIn}%` }}
          aria-label={`Input ${formatTokens(valueIn)}`}
        />
        <div
          className="h-full bg-brand/50"
          style={{ width: `${pctOut}%` }}
          aria-label={`Output ${formatTokens(valueOut)}`}
        />
      </div>
    </div>
  )
}

// ---- Page ----

export default function DashboardPage() {
  const { data: summary, isLoading: summaryLoading } = useSWR(
    'dashboard-summary',
    getDashboardSummary,
    { refreshInterval: 10000 },
  )
  const { data: usage } = useSWR('usage-history', () => getUsageHistory(7), {
    refreshInterval: 30000,
  })
  const { data: agentUsage } = useSWR('usage-by-agent', getUsageByAgent, {
    refreshInterval: 30000,
  })
  const { data: tasks } = useSWR('recent-tasks', getTasks, {
    refreshInterval: 10000,
  })

  const recentTasks = tasks?.slice(0, 8) ?? []
  const deltaToday =
    summary && summary.tasksYesterday > 0
      ? summary.tasksToday - summary.tasksYesterday
      : null

  const maxAgentTokens = Math.max(
    1,
    ...(agentUsage?.map((a) => a.totalTokens) ?? [0]),
  )

  return (
    <div className="space-y-6">
      {/* Header */}
      <div>
        <h1 className="text-xl font-bold text-slate-100">Dashboard</h1>
        <p className="text-xs text-slate-500 mt-1">
          AI agent platform overview
        </p>
      </div>

      {/* KPI row */}
      <div className="grid grid-cols-2 lg:grid-cols-4 gap-4">
        <KpiCard
          label="Tasks Today"
          value={summary?.tasksToday ?? 0}
          delta={
            deltaToday !== null
              ? {
                  value: `${deltaToday >= 0 ? '+' : ''}${deltaToday} vs yesterday`,
                  positive: deltaToday > 0,
                }
              : undefined
          }
          icon={Zap}
          accent="text-brand-light"
          isLoading={summaryLoading}
        />
        <KpiCard
          label="Success Rate"
          value={summary ? `${summary.successRate}%` : '—'}
          icon={CheckCircle2}
          accent="text-emerald-400"
          isLoading={summaryLoading}
        />
        <KpiCard
          label="Cost Today"
          value={summary ? `$${summary.costToday.toFixed(2)}` : '—'}
          delta={
            summary
              ? {
                  value: `Budget ${summary.budgetUsagePercent}% of $${summary.dailyBudget}`,
                }
              : undefined
          }
          icon={DollarSign}
          accent="text-amber-400"
          isLoading={summaryLoading}
        />
        <KpiCard
          label="Total Tokens"
          value={
            summary
              ? formatTokens(
                  summary.totalTokens.input + summary.totalTokens.output,
                )
              : '—'
          }
          delta={
            summary
              ? {
                  value: `${formatTokens(summary.totalTokens.input)} in · ${formatTokens(summary.totalTokens.output)} out`,
                }
              : undefined
          }
          icon={TrendingUp}
          accent="text-cyan-400"
          isLoading={summaryLoading}
        />
      </div>

      {/* Active agents */}
      {summary && (
        <Card>
          <div className="flex items-center justify-between mb-3">
            <h2 className="text-sm font-semibold text-slate-100">
              Active agents
            </h2>
            <span className="text-[10px] text-slate-500 uppercase tracking-wider">
              live
            </span>
          </div>
          <div className="flex flex-wrap gap-2">
            {Object.entries(summary.activeAgents).map(([type, count]) => (
              <AgentCountPill
                key={type}
                type={type}
                count={count as number}
              />
            ))}
          </div>
        </Card>
      )}

      {/* Two-column: Usage by agent + 7-day history */}
      <div className="grid grid-cols-1 lg:grid-cols-2 gap-6">
        <Card>
          <div className="flex items-center justify-between mb-4">
            <h2 className="text-sm font-semibold text-slate-100">
              Usage by agent
            </h2>
            <span className="text-[10px] text-slate-500">Today</span>
          </div>
          {!agentUsage || agentUsage.length === 0 ? (
            <p className="text-xs text-slate-500 py-6 text-center">
              No agent activity today.
            </p>
          ) : (
            <div className="space-y-4">
              {agentUsage.map((a) => (
                <UsageBarRow
                  key={a.agentType}
                  label={a.agentType}
                  valueIn={a.totalTokens * 0.7}
                  valueOut={a.totalTokens * 0.3}
                  cost={a.totalCost}
                  max={maxAgentTokens}
                />
              ))}
            </div>
          )}
          <div className="flex items-center gap-3 pt-4 mt-4 border-t border-slate-700/50 text-[10px] text-slate-500">
            <span className="inline-flex items-center gap-1.5">
              <span className="w-2 h-2 rounded-sm bg-brand-light" /> Input
            </span>
            <span className="inline-flex items-center gap-1.5">
              <span className="w-2 h-2 rounded-sm bg-brand/50" /> Output
            </span>
          </div>
        </Card>

        <Card>
          <div className="flex items-center justify-between mb-4">
            <h2 className="text-sm font-semibold text-slate-100">
              7-day usage
            </h2>
            <span className="text-[10px] text-slate-500">
              {usage?.length ?? 0} days
            </span>
          </div>
          {!usage || usage.length === 0 ? (
            <p className="text-xs text-slate-500 py-6 text-center">
              No usage data.
            </p>
          ) : (
            <div className="space-y-2.5">
              {usage.map((d) => {
                const total = d.inputTokens + d.outputTokens
                const maxDay = Math.max(
                  ...usage.map((u) => u.inputTokens + u.outputTokens),
                )
                const pct = maxDay > 0 ? (total / maxDay) * 100 : 0
                return (
                  <div key={d.date} className="group">
                    <div className="flex items-center justify-between text-xs mb-1">
                      <span className="text-slate-400 font-mono">
                        {d.date.slice(5)}
                      </span>
                      <div className="flex gap-3 text-slate-500 tabular-nums">
                        <span>{formatTokens(total)}</span>
                        <span className="text-slate-300">
                          {formatCost(d.costUsd)}
                        </span>
                      </div>
                    </div>
                    <div className="h-1 w-full rounded-full bg-surface-base overflow-hidden">
                      <div
                        className="h-full bg-gradient-to-r from-brand to-brand-light transition-all"
                        style={{ width: `${pct}%` }}
                      />
                    </div>
                  </div>
                )
              })}
            </div>
          )}
        </Card>
      </div>

      {/* Recent tasks */}
      <Card>
        <div className="flex items-center justify-between mb-4">
          <h2 className="text-sm font-semibold text-slate-100">
            Recent tasks
          </h2>
          <Link
            href="/dashboard/tasks"
            className="text-xs text-brand-light hover:underline inline-flex items-center gap-1"
          >
            View all <ArrowUpRight className="h-3 w-3" />
          </Link>
        </div>
        {recentTasks.length === 0 ? (
          <p className="text-xs text-slate-500 py-6 text-center">
            No tasks yet.
          </p>
        ) : (
          <div className="space-y-1">
            {recentTasks.map((t) => (
              <Link
                key={t.id}
                href={`/dashboard/tasks/${t.id}`}
                className="flex items-center gap-3 rounded-lg px-3 py-2.5 hover:bg-surface-base transition-colors group"
              >
                <StatusBadge status={t.status} />
                <span className="text-sm text-slate-200 truncate flex-1 group-hover:text-white transition-colors">
                  {t.title}
                </span>
                <AgentBadge type={t.agentType} />
                {t.durationMs != null && (
                  <span className="text-xs text-slate-500 tabular-nums w-14 text-right">
                    {(t.durationMs / 1000).toFixed(1)}s
                  </span>
                )}
                {t.costUsd != null && (
                  <span className="text-xs text-slate-500 tabular-nums w-16 text-right">
                    {formatCost(t.costUsd)}
                  </span>
                )}
              </Link>
            ))}
          </div>
        )}
      </Card>
    </div>
  )
}
