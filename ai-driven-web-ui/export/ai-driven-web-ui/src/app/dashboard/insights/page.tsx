'use client'

import { useState } from 'react'
import {
  TrendingUp,
  Clock,
  GitPullRequest,
  Zap,
  ChevronRight,
} from 'lucide-react'
import { Card } from '@/components/ui/card'
import { cn } from '@/lib/cn'
import { CHART_COLORS } from '@/lib/constants'

// MOCK — wire to real API
const HOURS_SAVED_BY_WEEK = [
  { week: 'W34', human: 120, agent: 18 },
  { week: 'W35', human: 134, agent: 41 },
  { week: 'W36', human: 118, agent: 67 },
  { week: 'W37', human: 104, agent: 92 },
  { week: 'W38', human: 88, agent: 128 },
  { week: 'W39', human: 72, agent: 161 },
  { week: 'W40', human: 56, agent: 189 },
  { week: 'W41', human: 48, agent: 214 },
]

const PR_SPLIT = [
  { type: 'Agent-authored', value: 214, color: CHART_COLORS.teal },
  { type: 'Agent-assisted', value: 98, color: CHART_COLORS.cyan },
  { type: 'Human-only', value: 48, color: CHART_COLORS.slate },
]

const TOP_REPOS = [
  { repo: 'platform/api', prs: 64, hours: 48, success: 94 },
  { repo: 'banking/core-svc', prs: 52, hours: 41, success: 91 },
  { repo: 'platform/payment-gateway', prs: 38, hours: 31, success: 97 },
  { repo: 'platform/devx-tools', prs: 28, hours: 19, success: 88 },
  { repo: 'banking/ledger', prs: 22, hours: 18, success: 95 },
]

// 7 days × 24 hours activity heatmap
const HEATMAP = Array.from({ length: 7 }, (_, day) =>
  Array.from({ length: 24 }, (_, hour) => {
    // Simulate work-hour concentration
    const workHour = hour >= 8 && hour <= 19
    const weekend = day >= 5
    const base = weekend ? 0.1 : workHour ? 0.6 : 0.15
    const noise = Math.sin(day * 7 + hour) * 0.3 + 0.3
    return Math.max(0, Math.min(1, base + noise * 0.4))
  }),
)

const DAY_LABELS = ['Mon', 'Tue', 'Wed', 'Thu', 'Fri', 'Sat', 'Sun']

function KpiTile({
  label,
  value,
  delta,
  icon: Icon,
}: {
  label: string
  value: string
  delta: string
  icon: React.ElementType
}) {
  return (
    <Card>
      <div className="flex items-start justify-between">
        <div>
          <p className="text-[10px] uppercase tracking-wider text-slate-500 font-medium">
            {label}
          </p>
          <p className="mt-2 text-3xl font-semibold text-slate-100 tabular-nums">
            {value}
          </p>
          <p className="mt-1 text-xs text-emerald-400 flex items-center gap-1">
            <TrendingUp className="h-3 w-3" />
            {delta}
          </p>
        </div>
        <div className="rounded-lg p-2 bg-brand/10 text-brand-light">
          <Icon className="h-4 w-4" />
        </div>
      </div>
    </Card>
  )
}

function HoursSavedChart() {
  const maxH = Math.max(
    ...HOURS_SAVED_BY_WEEK.map((w) => w.human + w.agent),
  )
  return (
    <Card>
      <div className="flex items-center justify-between mb-6">
        <div>
          <h3 className="text-sm font-semibold text-slate-100">
            Hours saved, last 8 weeks
          </h3>
          <p className="text-[11px] text-slate-500 mt-0.5">
            Agent work replacing or augmenting human engineering time
          </p>
        </div>
        <div className="flex items-center gap-3 text-[10px] text-slate-500">
          <span className="inline-flex items-center gap-1.5">
            <span className="w-2 h-2 rounded-sm bg-brand" /> Agent
          </span>
          <span className="inline-flex items-center gap-1.5">
            <span className="w-2 h-2 rounded-sm bg-slate-700" /> Human
          </span>
        </div>
      </div>
      <div className="flex items-end gap-3 h-48">
        {HOURS_SAVED_BY_WEEK.map((w) => {
          const total = w.human + w.agent
          const totalPct = (total / maxH) * 100
          const agentPct = (w.agent / total) * 100
          return (
            <div key={w.week} className="flex-1 flex flex-col items-center gap-2">
              <div
                className="w-full rounded-t-sm relative overflow-hidden bg-slate-700"
                style={{ height: `${totalPct}%` }}
              >
                <div
                  className="absolute bottom-0 left-0 right-0 bg-brand"
                  style={{ height: `${agentPct}%` }}
                />
              </div>
              <span className="text-[10px] text-slate-500 font-mono">{w.week}</span>
            </div>
          )
        })}
      </div>
    </Card>
  )
}

function PRSplitDonut() {
  const total = PR_SPLIT.reduce((acc, p) => acc + p.value, 0)
  let offset = 0
  const circ = 2 * Math.PI * 48
  return (
    <Card>
      <h3 className="text-sm font-semibold text-slate-100 mb-6">
        PRs this month
      </h3>
      <div className="flex items-center gap-6">
        <svg viewBox="0 0 120 120" className="w-32 h-32 -rotate-90 flex-shrink-0">
          <circle
            cx="60"
            cy="60"
            r="48"
            stroke="#2A3A4A"
            strokeWidth="12"
            fill="none"
          />
          {PR_SPLIT.map((p) => {
            const pct = p.value / total
            const dash = circ * pct
            const gap = circ - dash
            const el = (
              <circle
                key={p.type}
                cx="60"
                cy="60"
                r="48"
                stroke={p.color}
                strokeWidth="12"
                fill="none"
                strokeDasharray={`${dash} ${gap}`}
                strokeDashoffset={-offset}
                strokeLinecap="butt"
              />
            )
            offset += dash
            return el
          })}
          <text
            x="60"
            y="62"
            textAnchor="middle"
            className="fill-slate-100 text-lg font-semibold"
            transform="rotate(90 60 60)"
            style={{ fontSize: '18px' }}
          >
            {total}
          </text>
        </svg>
        <div className="flex-1 space-y-3">
          {PR_SPLIT.map((p) => (
            <div key={p.type} className="flex items-center gap-3 text-sm">
              <span
                className="w-2.5 h-2.5 rounded-sm flex-shrink-0"
                style={{ background: p.color }}
              />
              <span className="text-slate-300 flex-1">{p.type}</span>
              <span className="text-slate-200 tabular-nums font-medium">
                {p.value}
              </span>
              <span className="text-slate-500 text-xs tabular-nums w-10 text-right">
                {Math.round((p.value / total) * 100)}%
              </span>
            </div>
          ))}
        </div>
      </div>
    </Card>
  )
}

function ActivityHeatmap() {
  return (
    <Card>
      <div className="flex items-center justify-between mb-4">
        <div>
          <h3 className="text-sm font-semibold text-slate-100">
            Agent activity heatmap
          </h3>
          <p className="text-[11px] text-slate-500 mt-0.5">
            Last 7 days · hourly task volume
          </p>
        </div>
      </div>
      <div className="flex gap-2">
        <div className="flex flex-col gap-1 pt-4">
          {DAY_LABELS.map((d) => (
            <div
              key={d}
              className="text-[10px] text-slate-500 font-mono h-4 flex items-center"
            >
              {d}
            </div>
          ))}
        </div>
        <div className="flex-1">
          <div className="flex justify-between text-[10px] text-slate-500 font-mono mb-1 px-0.5">
            <span>0</span>
            <span>6</span>
            <span>12</span>
            <span>18</span>
            <span>23</span>
          </div>
          <div className="flex flex-col gap-1">
            {HEATMAP.map((day, di) => (
              <div key={di} className="flex gap-1">
                {day.map((v, hi) => (
                  <div
                    key={hi}
                    className="flex-1 h-4 rounded-sm"
                    style={{
                      background:
                        v < 0.1
                          ? '#1A2332'
                          : `rgba(125, 175, 180, ${Math.min(1, v * 1.1)})`,
                    }}
                    title={`${DAY_LABELS[di]} ${hi}:00 · ${Math.round(v * 100)}%`}
                  />
                ))}
              </div>
            ))}
          </div>
        </div>
      </div>
      <div className="flex items-center justify-end gap-2 mt-3 text-[10px] text-slate-500">
        <span>Less</span>
        <div className="flex gap-0.5">
          {[0.1, 0.3, 0.5, 0.7, 0.9].map((v) => (
            <div
              key={v}
              className="w-3 h-3 rounded-sm"
              style={{ background: `rgba(125, 175, 180, ${v})` }}
            />
          ))}
        </div>
        <span>More</span>
      </div>
    </Card>
  )
}

function TopReposTable() {
  const maxPrs = Math.max(...TOP_REPOS.map((r) => r.prs))
  return (
    <Card>
      <div className="flex items-center justify-between mb-4">
        <h3 className="text-sm font-semibold text-slate-100">
          Top repositories
        </h3>
        <span className="text-[10px] text-slate-500 uppercase tracking-wider">
          This month
        </span>
      </div>
      <div className="space-y-3">
        {TOP_REPOS.map((r) => (
          <div key={r.repo} className="group">
            <div className="flex items-center justify-between text-sm mb-1.5">
              <span className="text-slate-200 font-mono text-xs truncate">
                {r.repo}
              </span>
              <div className="flex gap-4 text-xs text-slate-500 tabular-nums">
                <span>{r.prs} PRs</span>
                <span className="text-slate-400">{r.hours}h saved</span>
                <span
                  className={cn(
                    'font-medium',
                    r.success >= 95
                      ? 'text-emerald-400'
                      : r.success >= 90
                        ? 'text-brand-light'
                        : 'text-amber-400',
                  )}
                >
                  {r.success}%
                </span>
              </div>
            </div>
            <div className="h-1 w-full rounded-full bg-surface-base overflow-hidden">
              <div
                className="h-full bg-gradient-to-r from-brand to-brand-light"
                style={{ width: `${(r.prs / maxPrs) * 100}%` }}
              />
            </div>
          </div>
        ))}
      </div>
    </Card>
  )
}

export default function InsightsPage() {
  const [range, setRange] = useState<'7d' | '30d' | '90d'>('30d')

  return (
    <div className="space-y-6">
      {/* Header */}
      <div className="flex items-center justify-between">
        <div>
          <h1 className="text-xl font-bold text-slate-100">Insights</h1>
          <p className="text-xs text-slate-500 mt-1">
            Engineering productivity & agent impact
          </p>
        </div>
        <div className="flex gap-1 rounded-lg bg-surface-base p-1 border border-slate-700/50">
          {(['7d', '30d', '90d'] as const).map((r) => (
            <button
              key={r}
              onClick={() => setRange(r)}
              className={cn(
                'px-3 py-1 text-xs rounded font-medium transition-colors',
                range === r
                  ? 'bg-brand/20 text-brand-light'
                  : 'text-slate-500 hover:text-slate-300',
              )}
            >
              {r}
            </button>
          ))}
        </div>
      </div>

      {/* KPI row */}
      <div className="grid grid-cols-2 lg:grid-cols-4 gap-4">
        <KpiTile
          label="Hours saved"
          value="961"
          delta="+18% vs prev period"
          icon={Clock}
        />
        <KpiTile
          label="PRs merged"
          value="312"
          delta="+24% vs prev period"
          icon={GitPullRequest}
        />
        <KpiTile
          label="Avg cycle time"
          value="3.2h"
          delta="−41% vs prev period"
          icon={Zap}
        />
        <KpiTile
          label="Success rate"
          value="93%"
          delta="+2.1pt vs prev period"
          icon={TrendingUp}
        />
      </div>

      {/* Charts */}
      <div className="grid grid-cols-1 lg:grid-cols-2 gap-6">
        <HoursSavedChart />
        <PRSplitDonut />
      </div>

      <div className="grid grid-cols-1 lg:grid-cols-2 gap-6">
        <ActivityHeatmap />
        <TopReposTable />
      </div>
    </div>
  )
}
