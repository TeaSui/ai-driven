'use client'

import { useId, useState } from 'react'
import useSWR from 'swr'
import { Clock, ExternalLink, Users } from 'lucide-react'
import { Btn } from '@/components/ui/btn'
import { Avatar } from '@/components/ui/avatar'
import { getInsights, type InsightsData } from '@/lib/api'

// ── Types ─────────────────────────────────────────────────────────────────────

type Range = '7d' | '30d' | '90d'

// ── Data ──────────────────────────────────────────────────────────────────────

const HERO_CARDS = [
  {
    label: 'Engineering hours saved',
    value: '1,284',
    unit: 'hrs',
    delta: '+38%',
    sub: '≈ 32 FTE-weeks',
    spark: [6, 8, 9, 12, 14, 16, 18, 22, 26, 30, 34, 38],
  },
  {
    label: 'PRs merged by agent',
    value: '412',
    delta: '+62%',
    sub: 'of 647 total · 64%',
    spark: [3, 5, 7, 10, 14, 18, 22, 28, 34, 40, 46, 52],
  },
  {
    label: 'Incidents auto-triaged',
    value: '89',
    delta: '+41%',
    sub: 'avg 3m 48s to RCA',
    spark: [2, 3, 4, 5, 6, 7, 8, 10, 12, 14, 17, 19],
  },
  {
    label: 'Review coverage',
    value: '98.7%',
    delta: '+2.1pt',
    sub: 'of all PRs',
    spark: [88, 90, 91, 92, 93, 94, 95, 96, 97, 97, 98, 99],
  },
]

const WEEKS = ['W1', 'W2', 'W3', 'W4', 'W5', 'W6', 'W7', 'W8', 'W9', 'W10', 'W11', 'W12']
const AGENT_BARS = [18, 24, 32, 41, 52, 48, 58, 67, 72, 81, 88, 94]
const HUMAN_BARS = [52, 48, 45, 42, 40, 38, 36, 34, 32, 30, 28, 26]

const LINE_SERIES = [
  { label: 'Agent', color: 'var(--accent-500)', data: [42, 38, 36, 30, 28, 26, 24, 22, 20, 18, 16, 14] },
  { label: 'Human', color: 'var(--brand-teal-300)', data: [58, 60, 58, 59, 57, 58, 56, 57, 55, 56, 54, 55] },
]

const HEATMAP_DAYS = ['Mon', 'Tue', 'Wed', 'Thu', 'Fri', 'Sat', 'Sun']

function heatVal(d: number, h: number): number {
  const v = (Math.sin(d * 1.7 + h * 0.9) + 1) / 2
  const biz = h >= 8 && h <= 20 ? 0.7 : 0.15
  const wk = d < 5 ? 1 : 0.3
  return Math.min(1, v * biz * wk + ((Math.cos(d + h) + 1) * 0.08))
}

const LEADERBOARD = [
  { name: 'GoTyme-X AI', bot: true, prs: 412, time: '14m', saved: '1,284h' },
  { name: 'Mira Delgado', bot: false, prs: 38, time: '52m', saved: null },
  { name: 'Jamie Ortiz', bot: false, prs: 31, time: '1h 08m', saved: null },
  { name: 'Priya Narayan', bot: false, prs: 28, time: '48m', saved: null },
  { name: 'Marcus Vaughn', bot: false, prs: 24, time: '1h 12m', saved: null },
]

// ── MiniSpark ─────────────────────────────────────────────────────────────────

function MiniSpark({ data }: { data: number[] }) {
  const gradId = useId()
  const max = Math.max(...data)
  const min = Math.min(...data)
  const n = data.length
  const pts = data.map((v, i) => [
    (i / (n - 1)) * 100,
    100 - ((v - min) / (max - min || 1)) * 80,
  ])
  const path = pts.map((p, i) => `${i === 0 ? 'M' : 'L'} ${p[0]} ${p[1]}`).join(' ')
  const area = path + ' L 100 100 L 0 100 Z'
  return (
    <svg
      viewBox="0 0 100 100"
      preserveAspectRatio="none"
      style={{
        position: 'absolute',
        right: 0,
        bottom: 0,
        width: '55%',
        height: 40,
        opacity: 0.7,
      }}
      aria-hidden="true"
    >
      <defs>
        <linearGradient id={gradId} x1="0" y1="0" x2="0" y2="1">
          <stop offset="0" stopColor="var(--accent-400)" stopOpacity=".35" />
          <stop offset="1" stopColor="var(--accent-400)" stopOpacity="0" />
        </linearGradient>
      </defs>
      <path d={area} fill={`url(#${gradId})`} />
      <path
        d={path}
        stroke="var(--accent-500)"
        strokeWidth="1.8"
        fill="none"
        vectorEffect="non-scaling-stroke"
      />
    </svg>
  )
}

// ── HeroMetrics ───────────────────────────────────────────────────────────────

function HeroMetrics({ summary }: { summary?: InsightsData['summary'] }) {
  const cards = summary
    ? HERO_CARDS.map((c) => {
        if (c.label === 'Engineering hours saved') {
          return {
            ...c,
            value: summary.hoursSaved.toLocaleString(),
            delta: `${summary.hoursSavedDelta >= 0 ? '+' : ''}${summary.hoursSavedDelta}%`,
          }
        }
        if (c.label === 'PRs merged by agent') {
          return {
            ...c,
            value: summary.prsMerged.toLocaleString(),
            delta: `${summary.prsMergedDelta >= 0 ? '+' : ''}${summary.prsMergedDelta}%`,
          }
        }
        if (c.label === 'Review coverage') {
          return {
            ...c,
            value: `${summary.successRate}%`,
            delta: `${summary.successRateDelta >= 0 ? '+' : ''}${summary.successRateDelta}pt`,
          }
        }
        return c
      })
    : HERO_CARDS
  return (
    <div
      style={{
        display: 'grid',
        gridTemplateColumns: 'repeat(4,1fr)',
        gap: 12,
      }}
    >
      {cards.map((c) => (
        <div
          key={c.label}
          style={{
            background: 'var(--bg-elev)',
            border: '1px solid var(--panel-border)',
            borderRadius: 12,
            padding: 16,
            position: 'relative',
            overflow: 'hidden',
            boxShadow: 'var(--shadow-sm)',
          }}
        >
          <div style={{ fontSize: 11.5, color: 'var(--text-subtle)', marginBottom: 6 }}>
            {c.label}
          </div>
          <div style={{ display: 'flex', alignItems: 'baseline', gap: 6 }}>
            <span
              style={{
                fontSize: 32,
                fontWeight: 600,
                letterSpacing: -1,
                color: 'var(--text)',
                fontVariantNumeric: 'tabular-nums',
              }}
            >
              {c.value}
            </span>
            {c.unit && (
              <span style={{ fontSize: 13, color: 'var(--text-muted)' }}>{c.unit}</span>
            )}
          </div>
          <div
            style={{
              display: 'flex',
              alignItems: 'center',
              justifyContent: 'space-between',
              marginTop: 6,
            }}
          >
            <span style={{ fontSize: 11, color: 'var(--text-subtle)' }}>{c.sub}</span>
            <span
              style={{ fontSize: 11, color: 'var(--status-success)', fontWeight: 500 }}
            >
              ↑ {c.delta}
            </span>
          </div>
          <MiniSpark data={c.spark} />
        </div>
      ))}
    </div>
  )
}

// ── ChartCard ─────────────────────────────────────────────────────────────────

function ChartCard({
  title,
  subtitle,
  children,
}: {
  title: string
  subtitle: string
  children: React.ReactNode
}) {
  return (
    <div
      style={{
        background: 'var(--bg-elev)',
        border: '1px solid var(--panel-border)',
        borderRadius: 12,
        boxShadow: 'var(--shadow-sm)',
      }}
    >
      <div
        style={{ padding: '12px 16px', borderBottom: '1px solid var(--panel-border)' }}
      >
        <div style={{ fontSize: 13, fontWeight: 500, color: 'var(--text)' }}>{title}</div>
        <div style={{ fontSize: 11.5, color: 'var(--text-subtle)' }}>{subtitle}</div>
      </div>
      <div style={{ padding: 16 }}>{children}</div>
    </div>
  )
}

// ── StackedBars ───────────────────────────────────────────────────────────────

function StackedBars({ hoursByWeek }: { hoursByWeek?: InsightsData['hoursByWeek'] }) {
  const weeks = hoursByWeek ? hoursByWeek.map((w) => w.week) : WEEKS
  const agentBars = hoursByWeek ? hoursByWeek.map((w) => w.agent) : AGENT_BARS
  const humanBars = hoursByWeek ? hoursByWeek.map((w) => w.human) : HUMAN_BARS
  const max = Math.max(...agentBars.map((a, i) => a + humanBars[i]))
  return (
    <div>
      <div style={{ display: 'flex', alignItems: 'flex-end', gap: 6, height: 180 }}>
        {weeks.map((w, i) => {
          const ah = (agentBars[i] / max) * 180
          const hh = (humanBars[i] / max) * 180
          return (
            <div
              key={w}
              style={{
                flex: 1,
                display: 'flex',
                flexDirection: 'column-reverse',
                alignItems: 'center',
              }}
            >
              <div
                style={{
                  width: '80%',
                  height: ah,
                  background: 'var(--accent-500)',
                  borderRadius: '2px 2px 0 0',
                }}
              />
              <div
                style={{
                  width: '80%',
                  height: hh,
                  background: 'var(--brand-teal-200)',
                }}
              />
            </div>
          )
        })}
      </div>
      <div style={{ display: 'flex', gap: 6, marginTop: 4 }}>
        {weeks.map((w) => (
          <div
            key={w}
            style={{
              flex: 1,
              textAlign: 'center',
              fontSize: 10,
              color: 'var(--text-faint)',
            }}
          >
            {w}
          </div>
        ))}
      </div>
      <div
        style={{
          display: 'flex',
          gap: 14,
          marginTop: 10,
          fontSize: 11.5,
          color: 'var(--text-muted)',
        }}
      >
        <span style={{ display: 'inline-flex', alignItems: 'center', gap: 6 }}>
          <span
            style={{
              width: 10,
              height: 10,
              background: 'var(--accent-500)',
              borderRadius: 2,
            }}
          />
          Agent-authored (64%)
        </span>
        <span style={{ display: 'inline-flex', alignItems: 'center', gap: 6 }}>
          <span
            style={{
              width: 10,
              height: 10,
              background: 'var(--brand-teal-200)',
              borderRadius: 2,
            }}
          />
          Human-authored
        </span>
      </div>
    </div>
  )
}

// ── LineChart ─────────────────────────────────────────────────────────────────

function LineChart() {
  const max = 70
  const n = 12
  return (
    <div>
      <svg
        viewBox="0 0 100 60"
        preserveAspectRatio="none"
        style={{ width: '100%', height: 180 }}
        aria-hidden="true"
      >
        {[0.25, 0.5, 0.75].map((f) => (
          <line
            key={f}
            x1="0"
            x2="100"
            y1={60 * f}
            y2={60 * f}
            stroke="var(--divider)"
            strokeWidth=".3"
          />
        ))}
        {LINE_SERIES.map((s) => {
          const path = s.data
            .map((v, i) => `${i === 0 ? 'M' : 'L'} ${(i / (n - 1)) * 100} ${60 - (v / max) * 60}`)
            .join(' ')
          return (
            <path
              key={s.label}
              d={path}
              stroke={s.color}
              strokeWidth="1"
              fill="none"
              vectorEffect="non-scaling-stroke"
            />
          )
        })}
        {LINE_SERIES.map((s) =>
          s.data.map((v, i) => (
            <circle
              key={s.label + i}
              cx={(i / (n - 1)) * 100}
              cy={60 - (v / max) * 60}
              r=".8"
              fill={s.color}
            />
          )),
        )}
      </svg>
      <div
        style={{
          display: 'flex',
          justifyContent: 'space-between',
          marginTop: 4,
          fontSize: 10,
          color: 'var(--text-faint)',
        }}
      >
        <span>Apr 1</span>
        <span>Apr 15</span>
        <span>Apr 30</span>
      </div>
      <div
        style={{
          display: 'flex',
          gap: 14,
          marginTop: 10,
          fontSize: 11.5,
          color: 'var(--text-muted)',
        }}
      >
        <span style={{ display: 'inline-flex', alignItems: 'center', gap: 6 }}>
          <span style={{ width: 10, height: 2, background: 'var(--accent-500)' }} />
          Agent · 14m avg
        </span>
        <span style={{ display: 'inline-flex', alignItems: 'center', gap: 6 }}>
          <span style={{ width: 10, height: 2, background: 'var(--brand-teal-300)' }} />
          Human · 55m avg
        </span>
      </div>
    </div>
  )
}

// ── Heatmap ───────────────────────────────────────────────────────────────────

function Heatmap({ heatmap }: { heatmap?: InsightsData['heatmap'] }) {
  const hours = 24
  return (
    <div>
      <div style={{ display: 'flex', gap: 2, alignItems: 'center' }}>
        <div style={{ width: 32 }} />
        {Array.from({ length: hours }).map((_, h) => (
          <div
            key={h}
            style={{
              flex: 1,
              fontSize: 9,
              textAlign: 'center',
              color: h % 4 === 0 ? 'var(--text-faint)' : 'transparent',
            }}
          >
            {h}
          </div>
        ))}
      </div>
      {HEATMAP_DAYS.map((d, di) => (
        <div key={d} style={{ display: 'flex', gap: 2, alignItems: 'center', marginTop: 2 }}>
          <div style={{ width: 32, fontSize: 10.5, color: 'var(--text-subtle)' }}>
            {d}
          </div>
          {Array.from({ length: hours }).map((_, h) => {
            const v =
              heatmap && heatmap[di]
                ? Math.min(1, Math.max(0, heatmap[di][h] ?? 0))
                : heatVal(di, h)
            return (
              <div
                key={h}
                style={{
                  flex: 1,
                  height: 18,
                  borderRadius: 2,
                  // Round alpha to 3 decimals so server- and client-rendered
                  // strings match exactly (avoids Next.js hydration mismatch on
                  // float serialization across platforms).
                  background: `rgba(134,184,190,${(0.08 + v * 0.9).toFixed(3)})`,
                }}
              />
            )
          })}
        </div>
      ))}
      <div
        style={{
          marginTop: 12,
          display: 'flex',
          alignItems: 'center',
          gap: 6,
          fontSize: 11,
          color: 'var(--text-subtle)',
        }}
      >
        <span>Less</span>
        {[0.1, 0.3, 0.5, 0.7, 0.9].map((v) => (
          <div
            key={v}
            style={{
              width: 14,
              height: 10,
              borderRadius: 2,
              background: `rgba(134,184,190,${0.08 + v * 0.9})`,
            }}
          />
        ))}
        <span>More</span>
      </div>
    </div>
  )
}

// ── Leaderboard ───────────────────────────────────────────────────────────────

function Leaderboard() {
  const rows = LEADERBOARD
  const maxPrs = rows[0].prs
  return (
    <div style={{ display: 'flex', flexDirection: 'column', gap: 2 }}>
      {rows.map((r) => (
        <div
          key={r.name}
          style={{
            display: 'grid',
            gridTemplateColumns: '28px 1fr 48px',
            gap: 10,
            alignItems: 'center',
            padding: '6px 4px',
          }}
        >
          {r.bot ? <Avatar bot size={24} /> : <Avatar name={r.name} size={24} />}
          <div style={{ minWidth: 0 }}>
            <div
              style={{
                display: 'flex',
                justifyContent: 'space-between',
                marginBottom: 3,
                fontSize: 12,
              }}
            >
              <span style={{ color: 'var(--text)', fontWeight: r.bot ? 500 : 400 }}>
                {r.name}
              </span>
              <span style={{ color: 'var(--text-subtle)' }}>avg {r.time}</span>
            </div>
            <div
              style={{
                height: 6,
                borderRadius: 3,
                background: 'var(--bg-muted)',
                overflow: 'hidden',
              }}
            >
              <div
                style={{
                  height: '100%',
                  width: `${(r.prs / maxPrs) * 100}%`,
                  background: r.bot ? 'var(--accent-500)' : 'var(--brand-teal-300)',
                }}
              />
            </div>
          </div>
          <span
            style={{
              textAlign: 'right',
              fontSize: 13,
              fontWeight: 500,
              color: 'var(--text)',
              fontVariantNumeric: 'tabular-nums',
            }}
          >
            {r.prs}
          </span>
        </div>
      ))}
    </div>
  )
}

// ── Page ──────────────────────────────────────────────────────────────────────

export default function InsightsPage() {
  const [range, setRange] = useState<Range>('30d')
  const { data: insights, error, isLoading, mutate } = useSWR(
    `insights-${range}`,
    () => getInsights(range),
    { refreshInterval: 30000 },
  )

  return (
    <main style={{ flex: 1, overflow: 'auto' }}>
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
                Metrics
              </h1>
              <span style={{ fontSize: 12.5, color: 'var(--text-subtle)' }}>
                Last {range === '7d' ? '7 days' : range === '30d' ? '30 days' : '90 days'} · all teams
              </span>
            </div>
          </div>
          <div style={{ display: 'flex', alignItems: 'center', gap: 6 }}>
            <Btn size="sm" icon={<Clock size={12} />}>
              {range}
            </Btn>
            <Btn size="sm" icon={<Users size={12} />}>
              All teams
            </Btn>
            <Btn size="sm" icon={<ExternalLink size={12} />}>
              Export
            </Btn>
          </div>
        </div>
      </header>

      <div style={{ padding: 20, display: 'flex', flexDirection: 'column', gap: 16 }}>
        <HeroMetrics summary={insights?.summary} />
        <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: 12 }}>
          <ChartCard title="PRs merged · by source" subtitle="Agent vs human authorship">
            <StackedBars hoursByWeek={insights?.hoursByWeek} />
          </ChartCard>
          <ChartCard title="Cycle time" subtitle="Ticket → merged">
            <LineChart />
          </ChartCard>
        </div>
        <div style={{ display: 'grid', gridTemplateColumns: '1.3fr 1fr', gap: 12 }}>
          <ChartCard title="Agent activity by hour" subtitle="UTC">
            <Heatmap heatmap={insights?.heatmap} />
          </ChartCard>
          <ChartCard title="Top contributors" subtitle="This month">
            <Leaderboard />
          </ChartCard>
        </div>
      </div>
    </main>
  )
}
