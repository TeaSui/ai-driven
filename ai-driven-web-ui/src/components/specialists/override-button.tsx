'use client'

import { useState } from 'react'
import { useSWRConfig } from 'swr'
import { Btn } from '@/components/ui/btn'
import { notify } from '@/lib/notify'
import {
  OverrideError,
  overrideTaskBlock,
  type OverrideAction,
} from '@/lib/api'

const ACTION_LABEL: Record<OverrideAction, string> = {
  rerun_coding: 'Rerun coding',
  rerun_qa: 'Rerun QA',
  force_pass: 'Force pass',
  reap: 'Reap (mark failed)',
}

const REASON_MAX = 500

interface Props {
  taskId: string
  actions: OverrideAction[]
  /**
   * SWR cache key for the artifacts envelope — we mutate() it on success
   * so the Specialists tab re-fetches the updated canOverride / status.
   */
  artifactsKey: string
}

/**
 * Admin "Override" dropdown + reason modal for T24 overrides.
 *
 * Rendered only when the backend says `canOverride=true`; backend is
 * authoritative. `force_pass` triggers a second confirm dialog because
 * it bypasses QA correctness gates entirely.
 */
export function OverrideButton({ taskId, actions, artifactsKey }: Props) {
  const [menuOpen, setMenuOpen] = useState(false)
  const [activeAction, setActiveAction] = useState<OverrideAction | null>(null)
  const [reason, setReason] = useState('')
  const [submitting, setSubmitting] = useState(false)
  const { mutate } = useSWRConfig()

  const openModal = (action: OverrideAction) => {
    setActiveAction(action)
    setReason('')
    setMenuOpen(false)
  }

  const closeModal = () => {
    setActiveAction(null)
    setReason('')
  }

  const submit = async () => {
    if (!activeAction) return
    const trimmed = reason.trim()
    if (trimmed.length === 0) {
      notify('Reason is required.', 'warn')
      return
    }
    if (trimmed.length > REASON_MAX) {
      notify(`Reason too long (max ${REASON_MAX} chars).`, 'warn')
      return
    }

    // force_pass secondary confirm — the ONLY action that bypasses QA
    // correctness gates. Make the operator type their intent out loud.
    if (activeAction === 'force_pass') {
      const ok = window.confirm(
        'Skip QA and mark this task complete?\n\n' +
          'This bypasses correctness gates. The task will be marked as ' +
          'completed via admin force-pass, and the override will be recorded ' +
          'in the audit log.',
      )
      if (!ok) return
    }

    setSubmitting(true)
    try {
      await overrideTaskBlock(taskId, activeAction, trimmed)
      notify('Override applied.', 'success')
      // Refresh both the task envelope and the artifacts envelope so
      // the Specialists tab reflects the new status + canOverride flag.
      await Promise.all([
        mutate(`task-${taskId}`),
        mutate(artifactsKey),
      ])
      closeModal()
    } catch (err) {
      if (err instanceof OverrideError) {
        if (err.status === 409) {
          notify('Task status changed, refresh and try again.', 'warn')
        } else if (err.status === 403) {
          notify('Admin role required.', 'danger')
        } else if (err.status === 400) {
          notify(`Override rejected: ${err.detail}`, 'warn')
        } else {
          notify(`Override failed: ${err.detail}`, 'danger')
        }
      } else {
        notify(
          `Override failed: ${(err as Error)?.message ?? 'unknown error'}`,
          'danger',
        )
      }
    } finally {
      setSubmitting(false)
    }
  }

  if (actions.length === 0) return null

  return (
    <div style={{ position: 'relative', display: 'inline-block' }}>
      <button
        type="button"
        data-testid="override-button"
        onClick={() => setMenuOpen(o => !o)}
        aria-haspopup="menu"
        aria-expanded={menuOpen}
        style={{
          padding: '4px 10px',
          fontSize: 12.5,
          height: 28,
          background: 'transparent',
          color: 'var(--text)',
          border: '1px solid var(--border)',
          borderRadius: 6,
          cursor: 'pointer',
          fontFamily: 'inherit',
        }}
      >
        Override…
      </button>
      {menuOpen && (
        <div
          role="menu"
          data-testid="override-menu"
          style={{
            position: 'absolute',
            right: 0,
            top: 'calc(100% + 4px)',
            zIndex: 20,
            minWidth: 180,
            background: 'var(--panel)',
            border: '1px solid var(--panel-border)',
            borderRadius: 6,
            boxShadow: '0 8px 24px rgba(0,0,0,0.12)',
            padding: 4,
          }}
        >
          {actions.map(a => (
            <button
              key={a}
              role="menuitem"
              type="button"
              data-testid={`override-action-${a}`}
              onClick={() => openModal(a)}
              style={{
                display: 'block',
                width: '100%',
                textAlign: 'left',
                padding: '8px 10px',
                fontSize: 12.5,
                background: 'transparent',
                border: 'none',
                borderRadius: 4,
                color: 'var(--text)',
                cursor: 'pointer',
              }}
            >
              {ACTION_LABEL[a]}
            </button>
          ))}
        </div>
      )}

      {activeAction && (
        <div
          role="dialog"
          aria-modal="true"
          aria-label={`Override: ${ACTION_LABEL[activeAction]}`}
          data-testid="override-modal"
          style={{
            position: 'fixed',
            inset: 0,
            background: 'rgba(0,0,0,0.4)',
            display: 'flex',
            alignItems: 'center',
            justifyContent: 'center',
            zIndex: 50,
          }}
          onClick={e => {
            if (e.target === e.currentTarget && !submitting) closeModal()
          }}
        >
          <div
            style={{
              background: 'var(--panel)',
              border: '1px solid var(--panel-border)',
              borderRadius: 8,
              padding: 20,
              width: 'min(520px, 94vw)',
              boxShadow: '0 12px 40px rgba(0,0,0,0.2)',
            }}
          >
            <h2
              style={{
                fontSize: 14,
                fontWeight: 600,
                margin: 0,
                marginBottom: 6,
                color: 'var(--text)',
              }}
            >
              {ACTION_LABEL[activeAction]}
            </h2>
            <p
              style={{
                fontSize: 12,
                color: 'var(--text-muted)',
                margin: 0,
                marginBottom: 12,
                lineHeight: 1.5,
              }}
            >
              Reason (required, ≤ {REASON_MAX} chars) — recorded in the
              override audit log.
            </p>
            <textarea
              data-testid="override-reason"
              value={reason}
              onChange={e => setReason(e.target.value)}
              maxLength={REASON_MAX}
              rows={4}
              autoFocus
              placeholder="e.g. QA false positive on the password-reset AC — see Slack thread…"
              style={{
                width: '100%',
                fontSize: 12.5,
                padding: 10,
                borderRadius: 6,
                border: '1px solid var(--panel-border)',
                background: 'var(--bg)',
                color: 'var(--text)',
                resize: 'vertical',
                fontFamily: 'inherit',
              }}
            />
            <div
              style={{
                display: 'flex',
                justifyContent: 'space-between',
                alignItems: 'center',
                marginTop: 8,
              }}
            >
              <span
                style={{ fontSize: 10.5, color: 'var(--text-faint)' }}
                aria-live="polite"
              >
                {reason.length}/{REASON_MAX}
              </span>
              <div style={{ display: 'flex', gap: 8 }}>
                <Btn
                  size="sm"
                  tone="ghost"
                  disabled={submitting}
                  onClick={closeModal}
                >
                  Cancel
                </Btn>
                <button
                  type="button"
                  data-testid="override-submit"
                  disabled={submitting || reason.trim().length === 0}
                  onClick={submit}
                  style={{
                    padding: '4px 12px',
                    fontSize: 12.5,
                    height: 28,
                    background: 'var(--text)',
                    color: 'var(--bg)',
                    border: '1px solid var(--text)',
                    borderRadius: 6,
                    cursor:
                      submitting || reason.trim().length === 0
                        ? 'not-allowed'
                        : 'pointer',
                    opacity:
                      submitting || reason.trim().length === 0 ? 0.5 : 1,
                    fontFamily: 'inherit',
                  }}
                >
                  {submitting ? 'Applying…' : 'Apply override'}
                </button>
              </div>
            </div>
          </div>
        </div>
      )}
    </div>
  )
}
