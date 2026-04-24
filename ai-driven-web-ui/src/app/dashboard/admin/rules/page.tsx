'use client'

import { useState } from 'react'
import { Save } from 'lucide-react'
import { Btn } from '@/components/ui/btn'
import { notify } from '@/lib/notify'

type SeverityThreshold = 'suggestion' | 'warning' | 'critical'

const IGNORE_PATHS = ['*.lock', 'vendor/*', '*.generated.*']

export default function RulesPage() {
  const [autoReview, setAutoReview] = useState(true)
  const [severity, setSeverity] = useState<SeverityThreshold>('warning')

  return (
    <main style={{ flex: 1, overflow: 'auto', padding: '24px 32px' }}>
      {/* Page header */}
      <div style={{ marginBottom: 24 }}>
        <h1
          style={{
            margin: 0,
            fontSize: 15,
            fontWeight: 600,
            letterSpacing: -0.2,
            color: 'var(--text)',
          }}
        >
          Review Rules
        </h1>
        <p style={{ margin: '4px 0 0', fontSize: 12.5, color: 'var(--text-subtle)' }}>
          Configure how the AI review agent analyzes pull requests.
        </p>
      </div>

      {/* Settings panel */}
      <div
        style={{
          background: 'var(--bg-elev)',
          border: '1px solid var(--panel-border)',
          borderRadius: 12,
          overflow: 'hidden',
          maxWidth: 600,
          boxShadow: 'var(--shadow-sm)',
        }}
      >
        {/* Auto-review toggle */}
        <div
          style={{
            padding: '16px 20px',
            borderBottom: '1px solid var(--panel-border)',
            display: 'flex',
            alignItems: 'center',
            justifyContent: 'space-between',
            gap: 16,
          }}
        >
          <div>
            <div style={{ fontSize: 13, fontWeight: 500, color: 'var(--text)' }}>
              Auto-review
            </div>
            <div style={{ fontSize: 11.5, color: 'var(--text-subtle)', marginTop: 2 }}>
              Automatically trigger AI review when a pull request is opened or updated.
            </div>
          </div>
          <button
            role="switch"
            aria-checked={autoReview}
            aria-label="Toggle auto-review"
            onClick={() => setAutoReview((v) => !v)}
            style={{
              flexShrink: 0,
              width: 40,
              height: 22,
              borderRadius: 11,
              border: 'none',
              cursor: 'pointer',
              background: autoReview ? 'var(--accent-500)' : 'var(--bg-muted)',
              position: 'relative',
              transition: 'background .15s',
              padding: 0,
            }}
          >
            <span
              style={{
                display: 'block',
                width: 16,
                height: 16,
                borderRadius: 8,
                background: '#fff',
                position: 'absolute',
                top: 3,
                left: autoReview ? 21 : 3,
                transition: 'left .15s',
                boxShadow: '0 1px 3px rgba(0,0,0,.25)',
              }}
            />
          </button>
        </div>

        {/* Severity threshold */}
        <div
          style={{
            padding: '16px 20px',
            borderBottom: '1px solid var(--panel-border)',
          }}
        >
          <div style={{ fontSize: 13, fontWeight: 500, color: 'var(--text)', marginBottom: 6 }}>
            Severity threshold
          </div>
          <div style={{ fontSize: 11.5, color: 'var(--text-subtle)', marginBottom: 12 }}>
            Only report comments at or above this severity level.
          </div>
          <div
            role="radiogroup"
            aria-label="Severity threshold"
            style={{ display: 'flex', gap: 8 }}
          >
            {(['suggestion', 'warning', 'critical'] as SeverityThreshold[]).map((s) => (
              <label
                key={s}
                style={{
                  display: 'inline-flex',
                  alignItems: 'center',
                  gap: 6,
                  padding: '5px 12px',
                  borderRadius: 6,
                  border: `1px solid ${severity === s ? 'var(--accent-500)' : 'var(--border)'}`,
                  background: severity === s ? 'var(--accent-soft)' : 'var(--bg-muted)',
                  color: severity === s ? 'var(--accent-600)' : 'var(--text-muted)',
                  fontSize: 12.5,
                  fontWeight: severity === s ? 500 : 400,
                  cursor: 'pointer',
                  transition: 'border-color .12s, background .12s',
                }}
              >
                <input
                  type="radio"
                  name="severity"
                  value={s}
                  checked={severity === s}
                  onChange={() => setSeverity(s)}
                  style={{ position: 'absolute', opacity: 0, pointerEvents: 'none' }}
                />
                {s.charAt(0).toUpperCase() + s.slice(1)}
              </label>
            ))}
          </div>
        </div>

        {/* Max comments per review */}
        <div
          style={{
            padding: '16px 20px',
            borderBottom: '1px solid var(--panel-border)',
            display: 'flex',
            alignItems: 'center',
            justifyContent: 'space-between',
          }}
        >
          <div>
            <div style={{ fontSize: 13, fontWeight: 500, color: 'var(--text)' }}>
              Max comments per review
            </div>
            <div style={{ fontSize: 11.5, color: 'var(--text-subtle)', marginTop: 2 }}>
              Limit the number of inline comments posted per PR review.
            </div>
          </div>
          <span
            style={{
              fontSize: 18,
              fontWeight: 600,
              color: 'var(--text)',
              fontVariantNumeric: 'tabular-nums',
            }}
          >
            20
          </span>
        </div>

        {/* Ignore paths */}
        <div style={{ padding: '16px 20px' }}>
          <div style={{ fontSize: 13, fontWeight: 500, color: 'var(--text)', marginBottom: 6 }}>
            Ignore paths
          </div>
          <div style={{ fontSize: 11.5, color: 'var(--text-subtle)', marginBottom: 10 }}>
            File patterns excluded from AI review (glob syntax).
          </div>
          <div style={{ display: 'flex', flexWrap: 'wrap', gap: 6 }}>
            {IGNORE_PATHS.map((p) => (
              <span
                key={p}
                className="gtx-mono"
                style={{
                  fontSize: 12,
                  padding: '3px 10px',
                  borderRadius: 4,
                  background: 'var(--bg-muted)',
                  border: '1px solid var(--border)',
                  color: 'var(--text-muted)',
                }}
              >
                {p}
              </span>
            ))}
          </div>
        </div>
      </div>

      {/* Save button */}
      <div style={{ marginTop: 20 }}>
        <Btn
          tone="brand"
          size="sm"
          icon={<Save size={13} />}
          onClick={() => notify('Settings saved.', 'success')}
        >
          Save changes
        </Btn>
      </div>
    </main>
  )
}
