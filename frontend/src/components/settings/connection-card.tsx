'use client'

/**
 * <ConnectionCard> — renders one SCM provider's connection state and the
 * buttons appropriate for that state (PR 2).
 *
 * Four states, classified client-side:
 *   - Disconnected: `connection == null`
 *   - Expired:      `connection.expiresAt && new Date(expiresAt) < now`
 *   - Revoked:      most recent Test returned `{ok:false, reason:'revoked'}`
 *                   (session-local; cleared on SWR mutate)
 *   - Healthy:      everything else
 *
 * The card owns its own transient UI (Test spinner, Disconnect inline-
 * confirm with aria-live announcement). Parent owns toasts + SWR mutate.
 */

import { useCallback, useEffect, useRef, useState } from 'react'
import { CheckCircle2, Github, RefreshCw, AlertTriangle, Unlink2 } from 'lucide-react'
import { Btn } from '@/components/ui/btn'
import { Pill } from '@/components/ui/pill'
import { Spinner } from '@/components/ui/spinner'
import type {
  Connection,
  ConnectionTestResult,
  ScmProvider,
} from '@/lib/api'

interface ConnectionCardProps {
  provider: ScmProvider
  connection: Connection | null
  /** Session-local revocation flag (set by parent when Test returns
   *  `{ok:false, reason:'revoked'}`). Cleared on `mutate`. */
  revoked?: boolean
  onConnect: (scope: 'public_repo' | 'repo') => void
  onTest: () => Promise<ConnectionTestResult>
  onDisconnect: () => Promise<void>
  disabled?: boolean
}

const PROVIDER_META: Record<ScmProvider, {
  label: string
  accent: string
  icon: (size: number) => JSX.Element
}> = {
  github: {
    label: 'GitHub',
    accent: '#24292f',
    icon: (size) => <Github size={size} aria-hidden="true" />,
  },
  bitbucket: {
    label: 'Bitbucket',
    // Bitbucket blue — lucide-react doesn't ship a Bitbucket glyph, so we
    // render a simple "BB" badge using the accent color for visual parity
    // with the GitHub card.
    accent: '#2684ff',
    icon: (size) => (
      <span
        aria-hidden="true"
        style={{
          width: size,
          height: size,
          display: 'inline-flex',
          alignItems: 'center',
          justifyContent: 'center',
          background: '#2684ff',
          color: '#fff',
          borderRadius: 4,
          fontSize: size - 6,
          fontWeight: 700,
          fontFamily: 'Geist Mono, monospace',
          lineHeight: 1,
        }}
      >
        B
      </span>
    ),
  },
}

type CardState = 'disconnected' | 'healthy' | 'expired' | 'revoked'

function classify(connection: Connection | null, revoked: boolean): CardState {
  if (!connection) return 'disconnected'
  if (revoked) return 'revoked'
  if (connection.expiresAt && new Date(connection.expiresAt) < new Date()) {
    return 'expired'
  }
  return 'healthy'
}

function scopeLabel(scope: string): string {
  // Full `repo` scope grants public + private; `public_repo` is read/write
  // on public repos only. Describe the superset plainly — users otherwise
  // read "repo" as "only private repos" which is backwards.
  if (scope.includes('repo') && !scope.includes('public_repo')) {
    return 'full repository access (public + private)'
  }
  if (scope.includes('public_repo')) {
    return 'public repositories only'
  }
  return scope
}

function formatTimestamp(ts: string | null): string {
  if (!ts) return '—'
  try {
    return new Date(ts).toLocaleString()
  } catch {
    return ts
  }
}

export function ConnectionCard({
  provider,
  connection,
  revoked = false,
  onConnect,
  onTest,
  onDisconnect,
  disabled = false,
}: ConnectionCardProps) {
  const meta = PROVIDER_META[provider]
  const state = classify(connection, revoked)

  const [testing, setTesting] = useState(false)
  const [disconnecting, setDisconnecting] = useState(false)
  const [confirmOpen, setConfirmOpen] = useState(false)
  const cardRef = useRef<HTMLDivElement | null>(null)

  // Inline-confirm cancellation: Escape key + click-outside. No auto-revert
  // timer — a countdown is invisible to keyboard-only users (red-team fix).
  // Focus stays on the button after the transform; the browser keeps it
  // there naturally after a click, so no programmatic focus restore needed.
  useEffect(() => {
    if (!confirmOpen) return
    const onKey = (e: KeyboardEvent) => {
      if (e.key === 'Escape') {
        setConfirmOpen(false)
      }
    }
    const onClick = (e: MouseEvent) => {
      if (cardRef.current && !cardRef.current.contains(e.target as Node)) {
        setConfirmOpen(false)
      }
    }
    window.addEventListener('keydown', onKey)
    // Defer so the click that opened the confirm doesn't immediately close it.
    const id = window.setTimeout(() => {
      window.addEventListener('click', onClick)
    }, 0)
    return () => {
      window.removeEventListener('keydown', onKey)
      window.clearTimeout(id)
      window.removeEventListener('click', onClick)
    }
  }, [confirmOpen])

  const handleTest = useCallback(async () => {
    setTesting(true)
    try {
      await onTest()
    } finally {
      setTesting(false)
    }
  }, [onTest])

  const handleDisconnectClick = useCallback(() => {
    if (!confirmOpen) {
      setConfirmOpen(true)
      // Keep focus on the button so screen readers read the new aria-label
      // and keyboard users don't lose their place (red-team fix).
      return
    }
    setConfirmOpen(false)
    setDisconnecting(true)
    onDisconnect()
      .catch(() => {
        /* parent surfaces the toast — just reset local state */
      })
      .finally(() => {
        setDisconnecting(false)
      })
  }, [confirmOpen, onDisconnect])

  const ariaLabelDescribedState = [
    meta.label,
    state === 'disconnected'
      ? 'not connected'
      : connection
        ? `connected as @${connection.scmUsername}`
        : 'state unknown',
  ].join(' — ')

  return (
    <div
      ref={cardRef}
      data-testid={`connection-card-${provider}`}
      data-state={state}
      aria-label={ariaLabelDescribedState}
      style={{
        position: 'relative',
        background: 'var(--bg-elev)',
        border: '1px solid var(--panel-border)',
        borderRadius: 12,
        padding: 20,
        boxShadow: 'var(--shadow-sm)',
        display: 'flex',
        flexDirection: 'column',
        gap: 14,
      }}
    >
      <div style={{ display: 'flex', alignItems: 'center', gap: 12 }}>
        <div
          aria-hidden="true"
          style={{
            width: 36,
            height: 36,
            borderRadius: 8,
            background: state === 'disconnected' ? 'var(--bg-muted)' : 'var(--accent-soft)',
            color: meta.accent,
            display: 'inline-flex',
            alignItems: 'center',
            justifyContent: 'center',
            flexShrink: 0,
          }}
        >
          {meta.icon(20)}
        </div>
        <div style={{ flex: 1, minWidth: 0 }}>
          <div
            style={{
              fontSize: 14,
              fontWeight: 600,
              color: 'var(--text)',
              display: 'flex',
              alignItems: 'center',
              gap: 8,
              flexWrap: 'wrap',
            }}
          >
            {meta.label}
            <StateBadge state={state} />
          </div>
          <div
            style={{
              fontSize: 12,
              color: 'var(--text-subtle)',
              marginTop: 2,
            }}
          >
            {state === 'disconnected'
              ? 'Connect your account so the agent can act as you on pull requests.'
              : connection
                ? `@${connection.scmUsername}${connection.scmEmail ? ` · ${connection.scmEmail}` : ''}`
                : ''}
          </div>
        </div>
      </div>

      {connection && state !== 'disconnected' && (
        <div
          style={{
            fontSize: 12,
            color: 'var(--text-muted)',
            background: 'var(--bg-muted)',
            borderRadius: 6,
            border: '1px solid var(--panel-border)',
            padding: '10px 12px',
            display: 'grid',
            gridTemplateColumns: 'max-content 1fr',
            gap: '4px 12px',
          }}
        >
          <span style={{ color: 'var(--text-subtle)' }}>Scope</span>
          <span>{scopeLabel(connection.scope)}</span>
          <span style={{ color: 'var(--text-subtle)' }}>Connected</span>
          <span>{formatTimestamp(connection.connectedAt)}</span>
          <span style={{ color: 'var(--text-subtle)' }}>Last used</span>
          <span>{formatTimestamp(connection.lastUsedAt)}</span>
          {connection.expiresAt && (
            <>
              <span style={{ color: 'var(--text-subtle)' }}>Expires</span>
              <span>{formatTimestamp(connection.expiresAt)}</span>
            </>
          )}
        </div>
      )}

      <div
        style={{
          display: 'flex',
          flexWrap: 'wrap',
          gap: 8,
          justifyContent: 'flex-end',
          alignItems: 'center',
        }}
      >
        {state === 'disconnected' && (
          <Btn
            tone="brand"
            size="sm"
            icon={<CheckCircle2 size={13} />}
            onClick={() => onConnect('public_repo')}
            disabled={disabled}
          >
            Connect {meta.label}
          </Btn>
        )}

        {(state === 'expired' || state === 'revoked') && (
          <>
            <Btn
              tone="brand"
              size="sm"
              icon={<RefreshCw size={13} />}
              onClick={() =>
                onConnect(hasRepoScope(connection?.scope) ? 'repo' : 'public_repo')
              }
              disabled={disabled}
            >
              Reconnect
            </Btn>
            <DisconnectButton
              confirmOpen={confirmOpen}
              disabled={disabled || disconnecting}
              onClick={handleDisconnectClick}
              providerLabel={meta.label}
            />
          </>
        )}

        {state === 'healthy' && connection && (
          <>
            {provider === 'github' && !hasRepoScope(connection.scope) && (
              <Btn
                tone="ghost"
                size="sm"
                onClick={() => onConnect('repo')}
                disabled={disabled}
              >
                Upgrade to full access
              </Btn>
            )}
            <Btn
              tone="ghost"
              size="sm"
              icon={testing ? <Spinner size={12} /> : <RefreshCw size={13} />}
              onClick={handleTest}
              disabled={disabled || testing}
            >
              {testing ? 'Testing…' : 'Test'}
            </Btn>
            <DisconnectButton
              confirmOpen={confirmOpen}
              disabled={disabled || disconnecting}
              onClick={handleDisconnectClick}
              providerLabel={meta.label}
            />
          </>
        )}
      </div>

      {/* Polite live region — announces the confirm-mode toggle to screen
          readers without stealing focus. Hidden visually; text is the
          authoritative announcement. */}
      <span
        aria-live="polite"
        aria-atomic="true"
        style={{
          position: 'absolute',
          width: 1,
          height: 1,
          overflow: 'hidden',
          clip: 'rect(0 0 0 0)',
          whiteSpace: 'nowrap',
        }}
      >
        {confirmOpen
          ? `Click again to confirm disconnecting ${meta.label}`
          : ''}
      </span>
    </div>
  )
}

/**
 * Native <button> — we need full ARIA support (aria-live on the label
 * would ideally sit on the button itself per the red-team fix, but
 * aria-live on an interactive element is iffy). The parent card has an
 * accompanying visually-hidden polite live region that announces the
 * state flip; this button's aria-label also updates so the control's
 * accessible name reflects the current action.
 */
function DisconnectButton({
  confirmOpen,
  disabled,
  onClick,
  providerLabel,
}: {
  confirmOpen: boolean
  disabled: boolean
  onClick: () => void
  providerLabel: string
}) {
  const label = confirmOpen
    ? `Click again to confirm disconnecting ${providerLabel}`
    : `Disconnect ${providerLabel}`
  const bg = confirmOpen ? 'var(--status-danger-bg)' : 'transparent'
  const fg = confirmOpen ? 'var(--status-danger)' : 'var(--text)'
  const border = confirmOpen ? 'var(--status-danger)' : 'var(--border)'
  return (
    <button
      type="button"
      onClick={onClick}
      disabled={disabled}
      aria-label={label}
      data-testid="disconnect-btn"
      data-confirm-open={confirmOpen ? 'true' : 'false'}
      style={{
        display: 'inline-flex',
        alignItems: 'center',
        justifyContent: 'center',
        gap: 6,
        minHeight: 28,
        padding: '0 10px',
        fontSize: 12.5,
        fontWeight: 500,
        background: bg,
        color: fg,
        border: `1px solid ${border}`,
        borderRadius: 8,
        cursor: disabled ? 'not-allowed' : 'pointer',
        opacity: disabled ? 0.5 : 1,
        fontFamily: 'inherit',
        whiteSpace: 'nowrap',
        transition: 'background .15s, border-color .15s, color .15s',
      }}
    >
      <Unlink2 size={13} aria-hidden="true" />
      {confirmOpen ? 'Click again to confirm' : 'Disconnect'}
    </button>
  )
}

function hasRepoScope(scope?: string | null): boolean {
  // Full `repo` scope is a distinct token in the space-separated scope
  // string, not a substring of `public_repo`. Match token-exact.
  if (!scope) return false
  return scope.split(/\s+/).includes('repo')
}

function StateBadge({ state }: { state: CardState }) {
  switch (state) {
    case 'healthy':
      return (
        <Pill tone="success" size="xs" dot>
          Connected
        </Pill>
      )
    case 'expired':
      return (
        <Pill tone="warn" size="xs" icon={<AlertTriangle size={10} aria-hidden="true" />}>
          Expired
        </Pill>
      )
    case 'revoked':
      return (
        <Pill tone="danger" size="xs" icon={<AlertTriangle size={10} aria-hidden="true" />}>
          Revoked
        </Pill>
      )
    case 'disconnected':
    default:
      return (
        <Pill tone="default" size="xs">
          Not connected
        </Pill>
      )
  }
}
