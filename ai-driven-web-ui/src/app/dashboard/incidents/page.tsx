'use client'

import { useState } from 'react'
import useSWR from 'swr'
import {
  AlertTriangle,
  Bot,
  Clock,
  Activity,
  CircleDot,
  GitPullRequest,
  MessageCircle,
} from 'lucide-react'
import { Pill } from '@/components/ui/pill'
import { Btn } from '@/components/ui/btn'
import { Avatar } from '@/components/ui/avatar'
import { Card } from '@/components/ui/card'
import { StatusDot } from '@/components/ui/status-dot'
import { Spinner } from '@/components/ui/spinner'
import { getIncidents, type Incident } from '@/lib/api'

// ── Types ─────────────────────────────────────────────────────────────────────

type AlertSeverity = 'critical' | 'warning'
type AlertStatus = 'investigating' | 'triaged' | 'auto-resolved'

interface AlertItem {
  id: string
  severity: AlertSeverity
  title: string
  service: string
  time: string
  status: AlertStatus
  apiIncident?: Incident
}

// ── Helpers ───────────────────────────────────────────────────────────────────

function toRelativeTime(iso: string): string {
  const diffMs = Date.now() - new Date(iso).getTime()
  const diffMin = Math.floor(diffMs / 60000)
  if (diffMin < 1) return 'just now'
  if (diffMin < 60) return `${diffMin}m ago`
  const diffHr = Math.floor(diffMin / 60)
  if (diffHr < 24) return `${diffHr}h ago`
  return `${Math.floor(diffHr / 24)}d ago`
}

function toAlertStatus(status: string): AlertStatus {
  if (status === 'triaged') return 'triaged'
  if (status === 'auto-resolved' || status === 'resolved') return 'auto-resolved'
  return 'investigating'
}

// ── Static data ───────────────────────────────────────────────────────────────

const ALERTS: AlertItem[] = [
  {
    id: 'A-8821',
    severity: 'critical',
    title: 'balance-cache p99 latency > 2s',
    service: 'ledger-core',
    time: '4m ago',
    status: 'investigating',
  },
  {
    id: 'A-8819',
    severity: 'warning',
    title: 'webhook-delivery error rate 4.2%',
    service: 'webhooks',
    time: '18m ago',
    status: 'triaged',
  },
  {
    id: 'A-8815',
    severity: 'warning',
    title: 'postgres connection pool 82%',
    service: 'auth-service',
    time: '34m ago',
    status: 'auto-resolved',
  },
]

// ── Sparkline ─────────────────────────────────────────────────────────────────

function Sparkline({
  label,
  value,
  delta,
  tone,
  data,
}: {
  label: string
  value: string
  delta: string
  tone: 'danger' | 'warn' | 'default'
  data: number[]
}) {
  const max = Math.max(...data)
  const min = Math.min(...data)
  const n = data.length
  const path = data
    .map((v, i) => {
      const x = (i / (n - 1)) * 100
      const y = 100 - ((v - min) / (max - min || 1)) * 100
      return `${i === 0 ? 'M' : 'L'} ${x} ${y}`
    })
    .join(' ')
  const c =
    tone === 'danger'
      ? 'var(--status-danger)'
      : tone === 'warn'
        ? 'var(--status-warn)'
        : 'var(--accent-500)'

  return (
    <div
      style={{
        display: 'flex',
        alignItems: 'center',
        gap: 12,
        padding: '8px 4px',
        borderBottom: '1px solid var(--divider)',
      }}
    >
      <div style={{ flex: 1, minWidth: 0 }}>
        <div style={{ fontSize: 11.5, color: 'var(--text-subtle)' }}>{label}</div>
        <div style={{ display: 'flex', alignItems: 'baseline', gap: 6 }}>
          <span
            style={{
              fontSize: 14,
              fontWeight: 600,
              color: 'var(--text)',
              fontVariantNumeric: 'tabular-nums',
            }}
          >
            {value}
          </span>
          <span style={{ fontSize: 10.5, color: c, fontWeight: 500 }}>{delta}</span>
        </div>
      </div>
      <svg
        width={100}
        height={30}
        viewBox="0 0 100 100"
        preserveAspectRatio="none"
        style={{ flexShrink: 0 }}
        aria-hidden="true"
      >
        <path
          d={path}
          stroke={c}
          strokeWidth="2.5"
          fill="none"
          vectorEffect="non-scaling-stroke"
        />
      </svg>
    </div>
  )
}

// ── CardHeader ────────────────────────────────────────────────────────────────

function CardHeader({
  title,
  icon,
}: {
  title: string
  icon: React.ReactNode
}) {
  return (
    <div
      style={{
        padding: '10px 14px',
        borderBottom: '1px solid var(--panel-border)',
        display: 'flex',
        alignItems: 'center',
        gap: 8,
        fontSize: 12,
        fontWeight: 500,
        color: 'var(--text)',
      }}
    >
      {icon}
      <span>{title}</span>
    </div>
  )
}

// ── TimelineEvent ─────────────────────────────────────────────────────────────

const TIMELINE_EVENTS: { t: string; text: string; tone: 'danger' | 'info' | 'success' | 'default' }[] = [
  { t: '0:00', text: 'Alert fired · p99 hit 2.1s', tone: 'danger' },
  { t: '0:12', text: 'Agent picked up · fetching context', tone: 'info' },
  { t: '0:45', text: 'Queried last 4 deploys via GitHub', tone: 'default' },
  { t: '1:20', text: 'Correlated deploy a3f21c8 (+89% latency delta)', tone: 'default' },
  { t: '2:08', text: 'Pulled flamegraph · mutex wait = 71%', tone: 'default' },
  { t: '3:12', text: 'RCA posted · rollback PR drafted', tone: 'success' },
]

// ── IncidentDetail ────────────────────────────────────────────────────────────

function IncidentDetail({ alert }: { alert: AlertItem }) {
  const rca = alert.apiIncident?.rca
  const severityTone = alert.severity === 'critical' ? 'danger' : 'warn'
  const severityLabel = alert.severity === 'critical' ? 'CRITICAL' : 'WARNING'

  return (
    <div style={{ overflow: 'auto', padding: 20 }}>
      {/* Title block */}
      <div
        style={{
          display: 'flex',
          alignItems: 'flex-start',
          gap: 14,
          marginBottom: 20,
        }}
      >
        <div
          style={{
            width: 40,
            height: 40,
            borderRadius: 10,
            background: 'var(--status-danger-bg)',
            color: 'var(--status-danger)',
            display: 'flex',
            alignItems: 'center',
            justifyContent: 'center',
          }}
        >
          <AlertTriangle size={20} />
        </div>
        <div style={{ flex: 1 }}>
          <div
            style={{
              display: 'flex',
              alignItems: 'center',
              gap: 8,
              marginBottom: 4,
            }}
          >
            <span
              className="gtx-mono"
              style={{ fontSize: 11.5, color: 'var(--text-subtle)' }}
            >
              {alert.id}
            </span>
            <Pill tone={severityTone} dot>
              {severityLabel}
            </Pill>
            {alert.apiIncident?.metric && (
              <Pill tone="info" size="sm">
                {alert.apiIncident.metric}
              </Pill>
            )}
          </div>
          <h2
            style={{
              margin: 0,
              fontSize: 18,
              fontWeight: 600,
              letterSpacing: -0.3,
              color: 'var(--text)',
            }}
          >
            {alert.title}
          </h2>
          <div
            style={{
              fontSize: 12.5,
              color: 'var(--text-subtle)',
              marginTop: 4,
            }}
          >
            <span className="gtx-mono">{alert.service}</span>
            {alert.apiIncident ? (
              <> · current {alert.apiIncident.currentValue} · threshold {alert.apiIncident.threshold}</>
            ) : (
              <> · us-east-2 · triggered 14:32:18 UTC · affecting 3 endpoints</>
            )}
          </div>
        </div>
      </div>

      {/* AI RCA card */}
      <div
        style={{
          border: '1px solid var(--brand-teal-200)',
          background: 'var(--accent-soft)',
          borderRadius: 12,
          padding: 16,
          marginBottom: 16,
          position: 'relative',
          overflow: 'hidden',
          boxShadow: 'var(--shadow-sm)',
        }}
      >
        {/* Decorative glow */}
        <div
          style={{
            position: 'absolute',
            top: -40,
            right: -40,
            width: 140,
            height: 140,
            background:
              'radial-gradient(circle, var(--brand-teal-200), transparent 60%)',
            opacity: 0.6,
          }}
          aria-hidden="true"
        />
        <div
          style={{
            display: 'flex',
            alignItems: 'center',
            gap: 10,
            marginBottom: 10,
            position: 'relative',
          }}
        >
          <Avatar bot size={26} />
          <div style={{ flex: 1 }}>
            <div style={{ fontSize: 12.5, fontWeight: 500, color: 'var(--text)' }}>
              AI Root-cause analysis
            </div>
            <div style={{ fontSize: 11, color: 'var(--text-muted)' }}>
              {rca
                ? `Confidence ${rca.confidence}`
                : 'Generated 3m 12s after alert fired · confidence 89%'}
            </div>
          </div>
          <Pill tone="brand" dot size="sm">
            <Spinner size={9} />
            Live
          </Pill>
        </div>
        <div
          style={{
            fontSize: 13.5,
            color: 'var(--text)',
            lineHeight: 1.55,
            position: 'relative',
          }}
        >
          {rca ? (
            <>
              <div style={{ marginBottom: 8 }}>{rca.rootCause}</div>
              {rca.evidence.length > 0 && (
                <ul style={{ margin: '8px 0', paddingLeft: 16, fontSize: 12.5, color: 'var(--text-muted)' }}>
                  {rca.evidence.map((e, i) => (
                    <li key={i}>{e}</li>
                  ))}
                </ul>
              )}
              {rca.suggestedFix && (
                <div style={{ marginTop: 8, fontSize: 12.5, color: 'var(--text-muted)' }}>
                  <strong style={{ color: 'var(--text)' }}>Suggested fix:</strong> {rca.suggestedFix}
                </div>
              )}
            </>
          ) : (
            <>
              Latency spike correlates with deploy{' '}
              <code
                style={{
                  fontFamily: 'Geist Mono, monospace',
                  fontSize: 11.5,
                  background: 'var(--bg-muted)',
                  padding: '1px 5px',
                  borderRadius: 3,
                  border: '1px solid var(--border)',
                }}
              >
                a3f21c8
              </code>{' '}
              at 14:28 UTC, which introduced a{' '}
              <code
                style={{
                  fontFamily: 'Geist Mono, monospace',
                  fontSize: 11.5,
                  background: 'var(--bg-muted)',
                  padding: '1px 5px',
                  borderRadius: 3,
                  border: '1px solid var(--border)',
                }}
              >
                singleflight.Group
              </code>{' '}
              in{' '}
              <code
                style={{
                  fontFamily: 'Geist Mono, monospace',
                  fontSize: 11.5,
                  background: 'var(--bg-muted)',
                  padding: '1px 5px',
                  borderRadius: 3,
                  border: '1px solid var(--border)',
                }}
              >
                BalanceCache.Get()
              </code>
              . Under the current traffic (3.8× normal weekend peak) the singleflight
              is serializing reads on ~12 hot account IDs. Redis cache-misses are still
              fast, but the Go mutex contention dominates p99.
            </>
          )}
        </div>
        <div
          style={{
            marginTop: 14,
            display: 'flex',
            gap: 8,
            position: 'relative',
          }}
        >
          <Btn size="sm" tone="brand" icon={<CircleDot size={11} />} onClick={() => window.alert('Incident ticket creation requires Jira integration. Configure in Admin > Repositories.')}>
            Create incident ticket
          </Btn>
          {rca?.relatedPR ? (
            <Btn
              size="sm"
              icon={<GitPullRequest size={11} />}
              onClick={() => window.open(rca.relatedPR!.url, '_blank')}
            >
              PR #{rca.relatedPR.number}
            </Btn>
          ) : (
            <Btn size="sm" icon={<GitPullRequest size={11} />} onClick={() => window.alert('Rollback PR drafting requires SCM integration. Configure in Admin > Repositories.')}>
              Draft rollback PR
            </Btn>
          )}
          <Btn size="sm" icon={<MessageCircle size={11} />} onClick={() => window.alert('Slack posting requires Slack bot configuration.')}>
            Post to #incidents
          </Btn>
        </div>
      </div>

      {/* Timeline + Signals grid */}
      <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: 12 }}>
        {/* Investigation timeline */}
        <Card padded={false}>
          <CardHeader title="Investigation timeline" icon={<Clock size={12} />} />
          <div style={{ padding: '4px 0' }}>
            {TIMELINE_EVENTS.map((e, i) => (
              <div
                key={i}
                style={{
                  display: 'flex',
                  gap: 10,
                  padding: '8px 16px',
                  alignItems: 'flex-start',
                }}
              >
                <span
                  className="gtx-mono"
                  style={{
                    fontSize: 11,
                    color: 'var(--text-subtle)',
                    width: 32,
                    flexShrink: 0,
                  }}
                >
                  {e.t}
                </span>
                <StatusDot tone={e.tone} size={6} />
                <span style={{ fontSize: 12.5, color: 'var(--text-muted)' }}>
                  {e.text}
                </span>
              </div>
            ))}
          </div>
        </Card>

        {/* Correlated signals */}
        <Card padded={false}>
          <CardHeader
            title="Correlated signals"
            icon={<Activity size={12} />}
          />
          <div style={{ padding: 12 }}>
            <Sparkline
              label="p99 latency"
              value="2.14s"
              delta="+312%"
              tone="danger"
              data={[0.3, 0.4, 0.3, 0.4, 0.35, 0.4, 0.5, 0.6, 0.9, 1.4, 2.1, 2.14]}
            />
            <Sparkline
              label="Mutex wait %"
              value="71%"
              delta="+65pt"
              tone="danger"
              data={[6, 8, 7, 8, 9, 11, 14, 22, 38, 54, 68, 71]}
            />
            <Sparkline
              label="Error rate"
              value="0.4%"
              delta="+0.2pt"
              tone="warn"
              data={[0.2, 0.2, 0.2, 0.2, 0.2, 0.2, 0.3, 0.3, 0.4, 0.4, 0.4, 0.4]}
            />
            <Sparkline
              label="Requests/s"
              value="4.8k"
              delta="+18%"
              tone="default"
              data={[3.9, 4.0, 4.1, 4.2, 4.3, 4.4, 4.5, 4.6, 4.7, 4.8, 4.8, 4.8]}
            />
          </div>
        </Card>
      </div>
    </div>
  )
}

// ── Page ──────────────────────────────────────────────────────────────────────

export default function IncidentsPage() {
  const [sel, setSel] = useState(0)
  const { data: apiIncidents } = useSWR('incidents', getIncidents, { refreshInterval: 15000 })

  const alerts: AlertItem[] = apiIncidents && apiIncidents.length > 0
    ? apiIncidents.map((inc) => ({
        id: inc.id,
        severity: (inc.severity === 'P1' || inc.severity === 'P2') ? 'critical' as AlertSeverity : 'warning' as AlertSeverity,
        title: inc.title,
        service: inc.service,
        time: toRelativeTime(inc.startedAt),
        status: toAlertStatus(inc.status),
        apiIncident: inc,
      }))
    : ALERTS

  const alert = alerts[Math.min(sel, alerts.length - 1)]

  return (
    <main style={{ flex: 1, display: 'flex', flexDirection: 'column', overflow: 'hidden' }}>
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
                DevOps Assistant
              </h1>
              <span style={{ fontSize: 12.5, color: 'var(--text-subtle)' }}>
                {alerts.length} active alert{alerts.length !== 1 ? 's' : ''} · 14 auto-triaged today
              </span>
            </div>
          </div>
          <div style={{ display: 'flex', alignItems: 'center', gap: 6 }}>
            <Pill tone="danger" dot size="md">
              {alerts.filter((a) => a.severity === 'critical').length} critical
            </Pill>
            <Btn size="sm" icon={<Activity size={12} />} onClick={() => window.open('https://app.datadoghq.com', '_blank')}>
              Open DataDog
            </Btn>
            <Btn size="sm" tone="brand" icon={<Bot size={12} />} onClick={() => window.alert('DevOps agent triggered. Check the Tasks page for status.')}>
              Run agent
            </Btn>
          </div>
        </div>
      </header>

      {/* Split pane */}
      <div
        style={{
          display: 'grid',
          gridTemplateColumns: '300px 1fr',
          flex: 1,
          minHeight: 0,
          overflow: 'hidden',
        }}
      >
        {/* Alert list */}
        <div
          style={{
            borderRight: '1px solid var(--panel-border)',
            background: 'var(--bg-elev)',
            overflow: 'auto',
          }}
        >
          <div
            style={{
              padding: '12px 14px',
              borderBottom: '1px solid var(--panel-border)',
              fontSize: 11,
              color: 'var(--text-subtle)',
              textTransform: 'uppercase',
              letterSpacing: 0.6,
            }}
          >
            Active alerts
          </div>
          {alerts.map((a, i) => (
            <button
              key={a.id}
              onClick={() => setSel(i)}
              style={{
                display: 'block',
                width: '100%',
                border: 'none',
                textAlign: 'left',
                cursor: 'pointer',
                padding: '12px 14px',
                background: sel === i ? 'var(--bg-hover)' : 'transparent',
                borderLeft: `3px solid ${sel === i ? 'var(--accent-500)' : 'transparent'}`,
                borderBottom: '1px solid var(--panel-border)',
                fontFamily: 'inherit',
              }}
              aria-pressed={sel === i}
            >
              <div
                style={{
                  display: 'flex',
                  alignItems: 'center',
                  gap: 6,
                  marginBottom: 4,
                }}
              >
                <StatusDot
                  tone={a.severity === 'critical' ? 'danger' : 'warn'}
                  pulse={a.severity === 'critical'}
                  size={7}
                />
                <span
                  className="gtx-mono"
                  style={{ fontSize: 11, color: 'var(--text-subtle)' }}
                >
                  {a.id}
                </span>
                <span style={{ flex: 1 }} />
                <span style={{ fontSize: 10.5, color: 'var(--text-faint)' }}>
                  {a.time}
                </span>
              </div>
              <div
                style={{
                  fontSize: 12.5,
                  color: 'var(--text)',
                  fontWeight: 500,
                  marginBottom: 3,
                }}
              >
                {a.title}
              </div>
              <div
                style={{
                  fontSize: 11,
                  color: 'var(--text-subtle)',
                  display: 'flex',
                  gap: 6,
                }}
              >
                <span className="gtx-mono">{a.service}</span>
                <span>·</span>
                <span>{a.status}</span>
              </div>
            </button>
          ))}
        </div>

        {/* Detail */}
        <IncidentDetail alert={alert} />
      </div>
    </main>
  )
}
