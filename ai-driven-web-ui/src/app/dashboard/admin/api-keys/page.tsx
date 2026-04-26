'use client'

import { useCallback, useEffect, useState } from 'react'
import { KeyRound, RotateCcw, X, Copy, Check } from 'lucide-react'
import { Btn } from '@/components/ui/btn'
import { Spinner } from '@/components/ui/spinner'
import { Pill } from '@/components/ui/pill'
import { notify } from '@/lib/notify'
import { getMe, rotateApiKey, type MeResponse, type KeyRotateResponse } from '@/lib/api'

/**
 * API-key management (Sprint 9 Phase 5).
 *
 * Shows the current team's single API key (raw key is never stored so we can
 * only show a masked stub) and offers rotation. Rotation returns a new raw key
 * that's shown exactly once in a copy-on-reveal modal — if the user loses it
 * they have to rotate again.
 *
 * Notably NOT in scope here: multiple keys per team (platform is one-key-per-
 * team today), per-member keys (team_members.api_key_hash is nullable and the
 * member-key flow isn't wired end-to-end), SSO / Cognito (Sprint 9 Phase 2,
 * separate).
 */
export default function ApiKeysPage() {
  const [me, setMe] = useState<MeResponse | null>(null)
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<string | null>(null)
  const [rotating, setRotating] = useState(false)
  const [confirmOpen, setConfirmOpen] = useState(false)
  const [rotated, setRotated] = useState<KeyRotateResponse | null>(null)

  useEffect(() => {
    getMe()
      .then((data) => {
        setMe(data)
        setLoading(false)
      })
      .catch(() => {
        setError('Failed to load team info.')
        setLoading(false)
      })
  }, [])

  const doRotate = useCallback(async () => {
    if (!me) return
    setRotating(true)
    try {
      const result = await rotateApiKey(me.teamId)
      setRotated(result)
      setConfirmOpen(false)
      notify('API key rotated. Copy the new key now — it will not be shown again.', 'success')
    } catch (ex) {
      notify(ex instanceof Error ? ex.message : 'Rotation failed.', 'danger')
    } finally {
      setRotating(false)
    }
  }, [me])

  return (
    <main style={{ flex: 1, overflow: 'auto', padding: '24px 32px' }}>
      <div style={{ marginBottom: 20 }}>
        <h1
          style={{
            margin: 0,
            fontSize: 15,
            fontWeight: 600,
            letterSpacing: -0.2,
            color: 'var(--text)',
          }}
        >
          API keys
        </h1>
        <p style={{ margin: '4px 0 0', fontSize: 12.5, color: 'var(--text-subtle)' }}>
          Manage the team's API key. Keys are hashed server-side and never
          retrievable after creation — rotate if a key is lost or compromised.
        </p>
      </div>

      {loading ? (
        <div
          style={{
            padding: '48px 16px',
            display: 'flex',
            alignItems: 'center',
            justifyContent: 'center',
            gap: 10,
            color: 'var(--text-muted)',
            fontSize: 13,
          }}
        >
          <Spinner size={16} /> Loading…
        </div>
      ) : error ? (
        <div
          style={{
            padding: '24px',
            textAlign: 'center',
            fontSize: 13,
            color: 'var(--status-danger)',
          }}
        >
          {error}
        </div>
      ) : me ? (
        <TeamKeyCard
          me={me}
          onRotate={() => setConfirmOpen(true)}
          rotating={rotating}
        />
      ) : null}

      {confirmOpen && (
        <ConfirmRotateDialog
          onCancel={() => setConfirmOpen(false)}
          onConfirm={doRotate}
          busy={rotating}
        />
      )}

      {rotated && (
        <NewKeyRevealDialog rotated={rotated} onClose={() => setRotated(null)} />
      )}
    </main>
  )
}

// ── Team-key card ────────────────────────────────────────────────────────────

function TeamKeyCard({
  me,
  onRotate,
  rotating,
}: {
  me: MeResponse
  onRotate: () => void
  rotating: boolean
}) {
  return (
    <div
      data-testid="team-key-card"
      style={{
        background: 'var(--bg-elev)',
        border: '1px solid var(--panel-border)',
        borderRadius: 12,
        padding: 20,
        maxWidth: 640,
        boxShadow: 'var(--shadow-sm)',
      }}
    >
      <div style={{ display: 'flex', alignItems: 'center', gap: 10, marginBottom: 14 }}>
        <KeyRound size={16} color="var(--text-muted)" />
        <div style={{ flex: 1 }}>
          <div style={{ fontSize: 13.5, fontWeight: 500, color: 'var(--text)' }}>
            {me.teamName}
          </div>
          <div style={{ fontSize: 11.5, color: 'var(--text-subtle)', fontFamily: 'Geist Mono, monospace' }}>
            team_id = {me.teamId}
          </div>
        </div>
        <Pill tone="success" size="xs" dot>
          Active
        </Pill>
      </div>

      <div
        style={{
          fontSize: 12,
          color: 'var(--text-muted)',
          background: 'var(--bg-muted)',
          padding: '10px 12px',
          borderRadius: 6,
          border: '1px solid var(--panel-border)',
          marginBottom: 16,
        }}
      >
        <div style={{ fontSize: 11, color: 'var(--text-subtle)', marginBottom: 4 }}>
          CURRENT KEY (hashed server-side)
        </div>
        <div className="gtx-mono" style={{ fontSize: 13, color: 'var(--text)' }}>
          aidrv_········_·············
        </div>
        <div style={{ fontSize: 11, color: 'var(--text-faint)', marginTop: 6 }}>
          Raw keys are never stored. If you lost yours, rotate below to mint a
          new one. The old key keeps working for 48 hours to avoid breaking CI.
        </div>
      </div>

      <div style={{ display: 'flex', justifyContent: 'flex-end' }}>
        <Btn
          tone="brand"
          size="sm"
          icon={<RotateCcw size={13} />}
          onClick={onRotate}
          disabled={rotating}
          data-testid="rotate-key-btn"
        >
          Rotate key
        </Btn>
      </div>
    </div>
  )
}

// ── Confirm rotate dialog ────────────────────────────────────────────────────

function ConfirmRotateDialog({
  onCancel,
  onConfirm,
  busy,
}: {
  onCancel: () => void
  onConfirm: () => void
  busy: boolean
}) {
  return (
    <div
      role="dialog"
      aria-modal="true"
      aria-labelledby="rotate-title"
      data-testid="rotate-confirm-dialog"
      onClick={(e) => e.target === e.currentTarget && !busy && onCancel()}
      style={{
        position: 'fixed',
        inset: 0,
        background: 'rgba(0,0,0,0.4)',
        display: 'flex',
        alignItems: 'center',
        justifyContent: 'center',
        zIndex: 1000,
      }}
    >
      <div
        style={{
          width: 440,
          maxWidth: '92vw',
          background: 'var(--panel)',
          border: '1px solid var(--panel-border)',
          borderRadius: 12,
          padding: 20,
          boxShadow: '0 10px 30px rgba(0,0,0,0.2)',
        }}
      >
        <h2 id="rotate-title" style={{ margin: 0, fontSize: 14, fontWeight: 600 }}>
          Rotate API key?
        </h2>
        <div
          style={{
            marginTop: 10,
            fontSize: 12.5,
            color: 'var(--text-muted)',
            lineHeight: 1.5,
          }}
        >
          A new key will be minted and shown exactly once. The current key
          keeps working for 48 hours as a grace window for CI / webhooks —
          after that it stops authenticating.
        </div>
        <div
          style={{
            display: 'flex',
            justifyContent: 'flex-end',
            gap: 8,
            marginTop: 18,
          }}
        >
          <Btn tone="ghost" size="sm" onClick={onCancel} disabled={busy}>
            Cancel
          </Btn>
          <Btn tone="brand" size="sm" onClick={onConfirm} disabled={busy}>
            {busy ? 'Rotating…' : 'Rotate now'}
          </Btn>
        </div>
      </div>
    </div>
  )
}

// ── Copy-on-reveal new key dialog ────────────────────────────────────────────

function NewKeyRevealDialog({
  rotated,
  onClose,
}: {
  rotated: KeyRotateResponse
  onClose: () => void
}) {
  const [copied, setCopied] = useState(false)
  const copy = async () => {
    try {
      await navigator.clipboard.writeText(rotated.apiKey)
      setCopied(true)
      setTimeout(() => setCopied(false), 2000)
    } catch {
      notify('Copy failed — select and copy manually.', 'warn')
    }
  }
  const grace = new Date(rotated.oldKeyExpiresAt).toLocaleString()
  return (
    <div
      role="dialog"
      aria-modal="true"
      aria-labelledby="reveal-title"
      data-testid="new-key-dialog"
      style={{
        position: 'fixed',
        inset: 0,
        background: 'rgba(0,0,0,0.5)',
        display: 'flex',
        alignItems: 'center',
        justifyContent: 'center',
        zIndex: 1001,
      }}
    >
      <div
        style={{
          width: 520,
          maxWidth: '92vw',
          background: 'var(--panel)',
          border: '1px solid var(--panel-border)',
          borderRadius: 12,
          boxShadow: '0 10px 30px rgba(0,0,0,0.3)',
          padding: 20,
        }}
      >
        <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
          <h2 id="reveal-title" style={{ margin: 0, fontSize: 14, fontWeight: 600 }}>
            Your new API key
          </h2>
          <button
            type="button"
            aria-label="Close"
            onClick={onClose}
            style={{
              background: 'transparent',
              border: 'none',
              cursor: 'pointer',
              color: 'var(--text-muted)',
              padding: 4,
              display: 'inline-flex',
            }}
          >
            <X size={16} />
          </button>
        </div>

        <div
          style={{
            marginTop: 12,
            fontSize: 12.5,
            color: 'var(--status-warn, #b45309)',
            background: 'var(--status-warn-bg, rgba(234,179,8,0.12))',
            padding: '8px 12px',
            borderRadius: 6,
            border: '1px solid var(--status-warn, #eab308)',
          }}
        >
          <strong>Copy this now.</strong> It will never be shown again. If you
          lose it, rotate again to mint a new one.
        </div>

        <div
          style={{
            marginTop: 14,
            display: 'flex',
            alignItems: 'center',
            gap: 8,
          }}
        >
          <code
            data-testid="new-key-value"
            className="gtx-mono"
            style={{
              flex: 1,
              fontSize: 12.5,
              padding: '10px 12px',
              background: 'var(--bg-muted)',
              border: '1px solid var(--panel-border)',
              borderRadius: 6,
              overflow: 'auto',
              color: 'var(--text)',
              whiteSpace: 'nowrap',
            }}
          >
            {rotated.apiKey}
          </code>
          <Btn
            tone="ghost"
            size="sm"
            icon={copied ? <Check size={13} /> : <Copy size={13} />}
            onClick={copy}
          >
            {copied ? 'Copied' : 'Copy'}
          </Btn>
        </div>

        <div style={{ marginTop: 14, fontSize: 11.5, color: 'var(--text-subtle)' }}>
          Old key stays valid until <strong>{grace}</strong> (48h grace period).
          After that it stops authenticating.
        </div>

        <div
          style={{
            display: 'flex',
            justifyContent: 'flex-end',
            gap: 8,
            marginTop: 18,
          }}
        >
          <Btn tone="brand" size="sm" onClick={onClose}>
            Done
          </Btn>
        </div>
      </div>
    </div>
  )
}
