'use client'

import { useState } from 'react'
import { ChevronDown, ChevronRight } from 'lucide-react'
import { Pill } from '@/components/ui/pill'
import type { Artifact, QAFailure } from '@/lib/api'

// ── Verdict pill ─────────────────────────────────────────────────────────────

const VERDICT_TONE: Record<
  string,
  'success' | 'warn' | 'danger' | 'default'
> = {
  pass: 'success',
  pass_with_notes: 'warn',
  block: 'danger',
  error: 'default',
  not_implemented: 'default',
}

function verdictLabel(v: string): string {
  switch (v) {
    case 'pass':
      return 'Pass'
    case 'pass_with_notes':
      return 'Pass w/ notes'
    case 'block':
      return 'Block'
    case 'error':
      return 'Error'
    case 'not_implemented':
      return 'Not implemented'
    default:
      return v
  }
}

function VerdictPill({ verdict }: { verdict: string }) {
  const tone = VERDICT_TONE[verdict] ?? 'default'
  return (
    <Pill tone={tone} size="sm" dot>
      {verdictLabel(verdict)}
    </Pill>
  )
}

// ── Role display ─────────────────────────────────────────────────────────────

const ROLE_DISPLAY: Record<string, string> = {
  business_analyst: 'Business Analyst',
  security: 'Security',
  tech_lead: 'Tech Lead',
  qa: 'QA',
  devops: 'DevOps',
  data_engineer: 'Data Engineer',
  mobile: 'Mobile',
  frontend: 'Frontend',
  backend: 'Backend',
}

function roleDisplayName(role: string): string {
  return (
    ROLE_DISPLAY[role] ??
    role
      .split('_')
      .map(w => w[0]?.toUpperCase() + w.slice(1))
      .join(' ')
  )
}

// ── Cost formatter ──────────────────────────────────────────────────────────

function formatCost(costUsd: string): string {
  const n = Number(costUsd)
  if (!Number.isFinite(n)) return `$${costUsd}`
  return `$${n.toFixed(4)}`
}

// ── ArtifactCard ────────────────────────────────────────────────────────────

export function ArtifactCard({ artifact }: { artifact: Artifact }) {
  const [open, setOpen] = useState(false)

  return (
    <div
      data-testid="artifact-card"
      className="rounded-lg border border-[var(--panel-border)] bg-[var(--panel)]"
      style={{ padding: 14, marginBottom: 12 }}
    >
      <div
        style={{
          display: 'flex',
          alignItems: 'center',
          gap: 10,
          flexWrap: 'wrap',
          marginBottom: 8,
        }}
      >
        <Pill tone="brand" size="sm">
          {roleDisplayName(artifact.role)}
        </Pill>
        <VerdictPill verdict={artifact.verdict} />
        <span
          style={{
            fontSize: 11,
            color: 'var(--text-faint)',
            marginLeft: 'auto',
          }}
        >
          {formatCost(artifact.costUsd)}
          <span style={{ opacity: 0.5, margin: '0 6px' }}>·</span>
          {artifact.inputTokens.toLocaleString()} in /{' '}
          {artifact.outputTokens.toLocaleString()} out
        </span>
      </div>

      {artifact.reasoning && (
        <div
          style={{
            fontSize: 12.5,
            color: 'var(--text)',
            lineHeight: 1.5,
            whiteSpace: 'pre-wrap',
            marginBottom: 8,
          }}
        >
          {artifact.reasoning}
        </div>
      )}

      <button
        type="button"
        onClick={() => setOpen(o => !o)}
        aria-expanded={open}
        aria-label={open ? 'Hide payload' : 'Show payload'}
        style={{
          display: 'inline-flex',
          alignItems: 'center',
          gap: 4,
          fontSize: 11,
          color: 'var(--text-muted)',
          background: 'transparent',
          border: 'none',
          padding: 0,
          cursor: 'pointer',
        }}
      >
        {open ? <ChevronDown size={12} /> : <ChevronRight size={12} />}
        Payload
      </button>

      {open && (
        <pre
          data-testid="artifact-payload"
          style={{
            marginTop: 8,
            fontSize: 11,
            background: 'var(--bg-muted)',
            border: '1px solid var(--panel-border)',
            borderRadius: 6,
            padding: 10,
            overflow: 'auto',
            maxHeight: 320,
            fontFamily:
              'ui-monospace, SFMono-Regular, Menlo, Consolas, monospace',
          }}
        >
          {JSON.stringify(artifact.payload, null, 2)}
        </pre>
      )}
    </div>
  )
}

// ── QAFailureCard ───────────────────────────────────────────────────────────

const SEVERITY_TONE: Record<string, 'danger' | 'warn' | 'default'> = {
  critical: 'danger',
  high: 'danger',
  medium: 'warn',
  low: 'default',
}

export function QAFailureCard({ failure }: { failure: QAFailure }) {
  const [open, setOpen] = useState(false)
  const title = failure.criterion || failure.rule || 'QA failure'
  const severity = failure.severity
  const tone = severity ? SEVERITY_TONE[severity] ?? 'default' : undefined

  return (
    <div
      data-testid="qa-failure-card"
      className="rounded-md border border-[var(--panel-border)] bg-[var(--panel)]"
      style={{ padding: 12, marginBottom: 8 }}
    >
      <div
        style={{
          display: 'flex',
          alignItems: 'center',
          gap: 8,
          marginBottom: 4,
        }}
      >
        <span style={{ fontSize: 12.5, fontWeight: 600, color: 'var(--text)' }}>
          {title}
        </span>
        {severity && tone && (
          <Pill tone={tone} size="xs">
            {severity}
          </Pill>
        )}
        {failure.kind && (
          <span style={{ fontSize: 10.5, color: 'var(--text-faint)' }}>
            {failure.kind}
          </span>
        )}
      </div>
      {failure.reason && (
        <div
          style={{
            fontSize: 12,
            color: 'var(--text-muted)',
            lineHeight: 1.5,
            marginBottom: failure.evidence ? 6 : 0,
          }}
        >
          {failure.reason}
        </div>
      )}
      {failure.evidence && (
        <>
          <button
            type="button"
            onClick={() => setOpen(o => !o)}
            aria-expanded={open}
            style={{
              display: 'inline-flex',
              alignItems: 'center',
              gap: 4,
              fontSize: 10.5,
              color: 'var(--text-faint)',
              background: 'transparent',
              border: 'none',
              padding: 0,
              cursor: 'pointer',
            }}
          >
            {open ? <ChevronDown size={11} /> : <ChevronRight size={11} />}
            Evidence
          </button>
          {open && (
            <pre
              style={{
                marginTop: 6,
                fontSize: 10.5,
                background: 'var(--bg-muted)',
                border: '1px solid var(--panel-border)',
                borderRadius: 4,
                padding: 8,
                overflow: 'auto',
                maxHeight: 200,
                fontFamily:
                  'ui-monospace, SFMono-Regular, Menlo, Consolas, monospace',
              }}
            >
              {failure.evidence}
            </pre>
          )}
        </>
      )}
    </div>
  )
}
