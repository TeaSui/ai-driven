'use client'

import { useState } from 'react'
import {
  AlertTriangle,
  Activity,
  TrendingUp,
  Clock,
  Sparkles,
  Database,
  Server,
  Network,
  ExternalLink,
  CheckCircle2,
  Circle,
} from 'lucide-react'
import { Card } from '@/components/ui/card'
import { Badge, AgentBadge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { cn } from '@/lib/cn'

// MOCK — wire to real API
type Severity = 'p1' | 'p2' | 'p3'
type IncidentStatus = 'active' | 'triaging' | 'mitigated' | 'resolved'

interface Incident {
  id: string
  title: string
  severity: Severity
  status: IncidentStatus
  service: string
  startedAt: string
  metric: string
  currentValue: string
  threshold: string
  rca?: {
    rootCause: string
    confidence: 'high' | 'medium' | 'low'
    evidence: string[]
    suggestedFix: string
    relatedPR?: { number: number; url: string }
  }
}

const MOCK_INCIDENTS: Incident[] = [
  {
    id: 'INC-3041',
    title: 'Elevated 5xx on /accounts endpoint',
    severity: 'p1',
    status: 'triaging',
    service: 'banking/core-svc',
    startedAt: new Date(Date.now() - 1000 * 60 * 14).toISOString(),
    metric: 'http_5xx_rate',
    currentValue: '8.4%',
    threshold: '< 0.5%',
    rca: {
      rootCause:
        'Connection pool exhaustion on postgres-primary. Active connections climbed from 42 to 198 over the last 20 minutes after deploy of `banking/core-svc@v2.41.3`. The new account-balance serializer opens a fresh connection per request instead of using the shared pool.',
      confidence: 'high',
      evidence: [
        'Deploy of banking/core-svc@v2.41.3 at 14:02 UTC correlates with 5xx spike starting 14:06 UTC',
        'Diff in src/serializers/account-balance.ts (PR #2310) replaces `pool.query(...)` with `new Client(...).query(...)`',
        'pg_stat_activity shows 198 active connections (pool max: 100)',
        'Same endpoint last degraded on 2024-03-18 with the same root cause — flagged as regression',
      ],
      suggestedFix:
        'Revert PR #2310 or patch src/serializers/account-balance.ts to use the shared pool. Draft patch available.',
      relatedPR: {
        number: 2310,
        url: 'https://github.com/banking/core-svc/pull/2310',
      },
    },
  },
  {
    id: 'INC-3040',
    title: 'p95 latency breach on /payments/charge',
    severity: 'p2',
    status: 'active',
    service: 'platform/payment-gateway',
    startedAt: new Date(Date.now() - 1000 * 60 * 38).toISOString(),
    metric: 'p95_latency_ms',
    currentValue: '1,240 ms',
    threshold: '< 800 ms',
  },
  {
    id: 'INC-3038',
    title: 'Redis memory pressure — eviction started',
    severity: 'p2',
    status: 'mitigated',
    service: 'platform/api',
    startedAt: new Date(Date.now() - 1000 * 60 * 72).toISOString(),
    metric: 'redis_used_memory_pct',
    currentValue: '67%',
    threshold: '< 80%',
  },
  {
    id: 'INC-3035',
    title: 'OTEL export failures on payment-gateway',
    severity: 'p3',
    status: 'resolved',
    service: 'platform/payment-gateway',
    startedAt: new Date(Date.now() - 1000 * 60 * 180).toISOString(),
    metric: 'otel_export_error_rate',
    currentValue: '0%',
    threshold: '< 1%',
  },
]

const SERVICE_ICON: Record<string, React.ElementType> = {
  'banking/core-svc': Database,
  'platform/payment-gateway': Network,
  'platform/api': Server,
}

const SEVERITY_CONFIG: Record<
  Severity,
  { label: string; color: string; bg: string }
> = {
  p1: {
    label: 'P1',
    color: 'text-red-400',
    bg: 'bg-red-500/15 border-red-500/40',
  },
  p2: {
    label: 'P2',
    color: 'text-amber-400',
    bg: 'bg-amber-500/15 border-amber-500/40',
  },
  p3: {
    label: 'P3',
    color: 'text-slate-400',
    bg: 'bg-slate-500/15 border-slate-500/40',
  },
}

const STATUS_CONFIG: Record<IncidentStatus, { label: string; color: string }> =
  {
    active: { label: 'Active', color: 'text-red-400' },
    triaging: { label: 'Triaging', color: 'text-amber-400' },
    mitigated: { label: 'Mitigated', color: 'text-cyan-400' },
    resolved: { label: 'Resolved', color: 'text-emerald-400' },
  }

function formatElapsed(iso: string) {
  const ms = Date.now() - new Date(iso).getTime()
  const m = Math.floor(ms / 60000)
  if (m < 60) return `${m}m ago`
  return `${Math.floor(m / 60)}h ${m % 60}m ago`
}

function IncidentRow({
  incident,
  onSelect,
  selected,
}: {
  incident: Incident
  onSelect: () => void
  selected: boolean
}) {
  const sev = SEVERITY_CONFIG[incident.severity]
  const status = STATUS_CONFIG[incident.status]
  const ServiceIcon = SERVICE_ICON[incident.service] ?? Server

  return (
    <button
      onClick={onSelect}
      className={cn(
        'w-full text-left rounded-lg border p-3 transition-colors',
        selected
          ? 'border-brand bg-brand/5'
          : 'border-slate-700/50 bg-surface hover:bg-surface-base',
      )}
    >
      <div className="flex items-start gap-3">
        <span
          className={cn(
            'inline-flex items-center justify-center w-8 h-8 rounded-md border flex-shrink-0 text-xs font-bold',
            sev.bg,
            sev.color,
          )}
        >
          {sev.label}
        </span>
        <div className="flex-1 min-w-0">
          <div className="flex items-center justify-between gap-2 mb-1">
            <span className="text-xs font-mono text-slate-500">
              {incident.id}
            </span>
            <span className={cn('text-[10px] font-medium', status.color)}>
              {incident.status === 'active' && (
                <span className="inline-block w-1.5 h-1.5 rounded-full bg-current animate-pulse-dot mr-1.5" />
              )}
              {status.label}
            </span>
          </div>
          <p className="text-sm text-slate-200 leading-tight mb-1.5">
            {incident.title}
          </p>
          <div className="flex items-center gap-2 text-[11px] text-slate-500">
            <ServiceIcon className="h-3 w-3" />
            <span className="font-mono truncate">{incident.service}</span>
            <span>·</span>
            <Clock className="h-3 w-3" />
            <span>{formatElapsed(incident.startedAt)}</span>
          </div>
        </div>
      </div>
    </button>
  )
}

function TimelineStep({
  icon: Icon,
  title,
  detail,
  state,
}: {
  icon: React.ElementType
  title: string
  detail?: string
  state: 'done' | 'current' | 'pending'
}) {
  return (
    <div className="flex gap-3">
      <div className="flex flex-col items-center">
        <div
          className={cn(
            'w-7 h-7 rounded-full flex items-center justify-center flex-shrink-0',
            state === 'done' && 'bg-emerald-500/20 text-emerald-400',
            state === 'current' && 'bg-amber-500/20 text-amber-400',
            state === 'pending' && 'bg-slate-700/30 text-slate-500',
          )}
        >
          {state === 'done' ? (
            <CheckCircle2 className="h-3.5 w-3.5" />
          ) : state === 'current' ? (
            <Icon className="h-3.5 w-3.5 animate-pulse-dot" />
          ) : (
            <Icon className="h-3.5 w-3.5" />
          )}
        </div>
        <div
          className={cn(
            'w-px flex-1 my-1',
            state === 'done' ? 'bg-emerald-500/40' : 'bg-slate-700/50',
          )}
        />
      </div>
      <div className="pb-4 flex-1">
        <p
          className={cn(
            'text-sm font-medium',
            state === 'done' && 'text-slate-300',
            state === 'current' && 'text-slate-100',
            state === 'pending' && 'text-slate-500',
          )}
        >
          {title}
        </p>
        {detail && (
          <p className="text-xs text-slate-500 mt-0.5 leading-relaxed">
            {detail}
          </p>
        )}
      </div>
    </div>
  )
}

function IncidentDetail({ incident }: { incident: Incident }) {
  const sev = SEVERITY_CONFIG[incident.severity]

  return (
    <div className="space-y-4">
      {/* Header */}
      <Card>
        <div className="flex items-start gap-4">
          <span
            className={cn(
              'inline-flex items-center justify-center w-10 h-10 rounded-md border flex-shrink-0 text-sm font-bold',
              sev.bg,
              sev.color,
            )}
          >
            {sev.label}
          </span>
          <div className="flex-1 min-w-0">
            <div className="flex items-center gap-2 mb-1">
              <span className="text-xs font-mono text-slate-500">
                {incident.id}
              </span>
              <span className="text-xs text-slate-500">·</span>
              <span className="text-xs text-slate-400 font-mono">
                {incident.service}
              </span>
            </div>
            <h2 className="text-base font-semibold text-slate-100">
              {incident.title}
            </h2>
            <div className="grid grid-cols-3 gap-4 mt-4">
              <div>
                <p className="text-[10px] uppercase tracking-wider text-slate-500 mb-1">
                  Metric
                </p>
                <p className="text-xs font-mono text-slate-300">
                  {incident.metric}
                </p>
              </div>
              <div>
                <p className="text-[10px] uppercase tracking-wider text-slate-500 mb-1">
                  Current
                </p>
                <p className="text-sm text-red-400 font-semibold tabular-nums">
                  {incident.currentValue}
                </p>
              </div>
              <div>
                <p className="text-[10px] uppercase tracking-wider text-slate-500 mb-1">
                  Threshold
                </p>
                <p className="text-sm text-slate-300 tabular-nums">
                  {incident.threshold}
                </p>
              </div>
            </div>
          </div>
        </div>
      </Card>

      {/* Agent triage */}
      {incident.rca ? (
        <Card>
          <div className="flex items-center gap-2 mb-4">
            <Sparkles className="h-4 w-4 text-brand-light" />
            <h3 className="text-sm font-semibold text-slate-100">
              AI root-cause analysis
            </h3>
            <AgentBadge type="devops" />
            <span className="ml-auto text-[10px] text-slate-500 uppercase tracking-wider">
              {incident.rca.confidence} confidence
            </span>
          </div>

          {/* Timeline */}
          <div className="mb-5">
            <TimelineStep
              icon={AlertTriangle}
              title="Alert fired"
              detail={`DataDog monitor triggered at ${new Date(incident.startedAt).toLocaleTimeString('en-GB', { hour: '2-digit', minute: '2-digit' })}`}
              state="done"
            />
            <TimelineStep
              icon={Activity}
              title="Agent dispatched"
              detail="Pulled last 30m of metrics, logs, recent deploys"
              state="done"
            />
            <TimelineStep
              icon={TrendingUp}
              title="Correlated deploy"
              detail="Found strong correlation with banking/core-svc@v2.41.3 deploy"
              state="done"
            />
            <TimelineStep
              icon={Sparkles}
              title="Posting RCA to incident channel"
              detail="Slack + PagerDuty update in progress"
              state="current"
            />
          </div>

          {/* Root cause */}
          <div className="rounded-lg border border-brand/30 bg-brand/5 p-4 mb-4">
            <p className="text-[10px] uppercase tracking-wider text-brand-light font-semibold mb-2">
              Hypothesized root cause
            </p>
            <p className="text-sm text-slate-200 leading-relaxed">
              {incident.rca.rootCause}
            </p>
          </div>

          {/* Evidence */}
          <div className="mb-4">
            <p className="text-[10px] uppercase tracking-wider text-slate-500 font-semibold mb-2">
              Evidence
            </p>
            <ul className="space-y-2">
              {incident.rca.evidence.map((e, i) => (
                <li
                  key={i}
                  className="flex items-start gap-2 text-xs text-slate-300 leading-relaxed"
                >
                  <Circle className="h-1.5 w-1.5 fill-brand-light text-brand-light mt-1.5 flex-shrink-0" />
                  <span>{e}</span>
                </li>
              ))}
            </ul>
          </div>

          {/* Suggested fix */}
          <div className="rounded-lg border border-emerald-500/30 bg-emerald-500/5 p-4">
            <p className="text-[10px] uppercase tracking-wider text-emerald-400 font-semibold mb-2">
              Suggested mitigation
            </p>
            <p className="text-sm text-slate-200 leading-relaxed mb-3">
              {incident.rca.suggestedFix}
            </p>
            {incident.rca.relatedPR && (
              <a
                href={incident.rca.relatedPR.url}
                target="_blank"
                rel="noopener noreferrer"
                className="inline-flex items-center gap-1.5 text-xs text-emerald-400 hover:underline font-medium"
              >
                Open PR #{incident.rca.relatedPR.number}
                <ExternalLink className="h-3 w-3" />
              </a>
            )}
          </div>

          {/* Actions */}
          <div className="flex gap-2 mt-5 pt-5 border-t border-slate-700/50">
            <Button variant="primary" size="sm">
              Acknowledge RCA
            </Button>
            <Button variant="secondary" size="sm">
              Request revert
            </Button>
            <Button variant="ghost" size="sm">
              Dismiss
            </Button>
          </div>
        </Card>
      ) : (
        <Card>
          <div className="flex items-center gap-2 text-slate-400">
            <Sparkles className="h-4 w-4 text-slate-500 animate-pulse-dot" />
            <p className="text-sm">Agent is gathering evidence…</p>
          </div>
        </Card>
      )}
    </div>
  )
}

export default function IncidentsPage() {
  const [selectedId, setSelectedId] = useState(MOCK_INCIDENTS[0].id)
  const [filter, setFilter] = useState<'all' | 'active'>('all')

  const filtered =
    filter === 'active'
      ? MOCK_INCIDENTS.filter(
          (i) => i.status === 'active' || i.status === 'triaging',
        )
      : MOCK_INCIDENTS

  const selected = MOCK_INCIDENTS.find((i) => i.id === selectedId)

  const activeCount = MOCK_INCIDENTS.filter(
    (i) => i.status === 'active' || i.status === 'triaging',
  ).length

  return (
    <div className="space-y-6">
      {/* Header */}
      <div className="flex items-center justify-between">
        <div>
          <h1 className="text-xl font-bold text-slate-100">Incidents</h1>
          <p className="text-xs text-slate-500 mt-1">
            {activeCount} active · AI triage enabled
          </p>
        </div>
        <div className="flex gap-1 rounded-lg bg-surface-base p-1 border border-slate-700/50">
          <button
            onClick={() => setFilter('all')}
            className={cn(
              'px-3 py-1 text-xs rounded font-medium transition-colors',
              filter === 'all'
                ? 'bg-brand/20 text-brand-light'
                : 'text-slate-500 hover:text-slate-300',
            )}
          >
            All
          </button>
          <button
            onClick={() => setFilter('active')}
            className={cn(
              'px-3 py-1 text-xs rounded font-medium transition-colors',
              filter === 'active'
                ? 'bg-brand/20 text-brand-light'
                : 'text-slate-500 hover:text-slate-300',
            )}
          >
            Active only
          </button>
        </div>
      </div>

      {/* Two-pane */}
      <div className="grid grid-cols-1 lg:grid-cols-12 gap-6">
        {/* Left list */}
        <div className="lg:col-span-4 space-y-2">
          {filtered.map((inc) => (
            <IncidentRow
              key={inc.id}
              incident={inc}
              onSelect={() => setSelectedId(inc.id)}
              selected={inc.id === selectedId}
            />
          ))}
        </div>

        {/* Right detail */}
        <div className="lg:col-span-8">
          {selected ? (
            <IncidentDetail incident={selected} />
          ) : (
            <Card>
              <p className="text-xs text-slate-500 text-center py-8">
                Select an incident
              </p>
            </Card>
          )}
        </div>
      </div>
    </div>
  )
}
