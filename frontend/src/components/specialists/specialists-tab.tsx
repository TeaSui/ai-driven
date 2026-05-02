'use client'

import useSWR from 'swr'
import { getTaskArtifacts } from '@/lib/api'
import type { TaskArtifactsResponse } from '@/lib/api'
import { ArtifactCard, QAFailureCard } from './artifact-card'
import { OverrideButton } from './override-button'

export function swrKeyForArtifacts(taskId: string): string {
  return `task-artifacts-${taskId}`
}

export function SpecialistsTab({ taskId }: { taskId: string }) {
  const { data, error, isLoading } = useSWR<TaskArtifactsResponse>(
    swrKeyForArtifacts(taskId),
    () => getTaskArtifacts(taskId),
    { refreshInterval: 10_000 },
  )

  if (isLoading) {
    return (
      <div style={{ padding: 20, fontSize: 12, color: 'var(--text-muted)' }}>
        Loading specialist artifacts…
      </div>
    )
  }

  if (error) {
    return (
      <div
        role="alert"
        style={{
          padding: 20,
          fontSize: 12,
          color: 'var(--status-danger)',
        }}
      >
        Failed to load artifacts: {String(error?.message ?? error)}
      </div>
    )
  }

  if (!data) return null

  const hasArtifacts = data.artifacts.length > 0
  const hasQAFailures = data.qaFailures && data.qaFailures.length > 0

  return (
    <div style={{ padding: 20, overflow: 'auto' }}>
      <div
        style={{
          display: 'flex',
          alignItems: 'center',
          justifyContent: 'space-between',
          marginBottom: 16,
        }}
      >
        <div>
          <h3
            style={{
              fontSize: 13,
              fontWeight: 600,
              color: 'var(--text)',
              margin: 0,
            }}
          >
            Specialist artifacts
          </h3>
          {data.classifierTier && (
            <div
              style={{
                fontSize: 11,
                color: 'var(--text-faint)',
                marginTop: 2,
              }}
            >
              Classifier tier: {data.classifierTier}
            </div>
          )}
        </div>
        {data.canOverride && (
          <OverrideButton
            taskId={taskId}
            actions={data.overrideActions}
            artifactsKey={swrKeyForArtifacts(taskId)}
          />
        )}
      </div>

      {hasQAFailures && (
        <section style={{ marginBottom: 24 }}>
          <div
            style={{
              fontSize: 12,
              fontWeight: 600,
              color: 'var(--status-danger)',
              marginBottom: 8,
              textTransform: 'uppercase',
              letterSpacing: 0.3,
            }}
          >
            QA failures ({data.qaFailures!.length})
          </div>
          {data.qaFailures!.map((f, i) => (
            <QAFailureCard key={i} failure={f} />
          ))}
        </section>
      )}

      {!hasArtifacts ? (
        <div
          style={{
            padding: 16,
            fontSize: 12,
            color: 'var(--text-muted)',
            border: '1px dashed var(--panel-border)',
            borderRadius: 8,
            textAlign: 'center',
          }}
        >
          No specialist artifacts recorded for this task yet.
        </div>
      ) : (
        data.artifacts.map(a => <ArtifactCard key={a.id} artifact={a} />)
      )}
    </div>
  )
}
