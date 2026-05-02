'use client'

import { useCallback, useEffect, useState } from 'react'
import { GitBranch, Plus, Trash2, Undo2, X } from 'lucide-react'
import { Pill } from '@/components/ui/pill'
import { Btn } from '@/components/ui/btn'
import { Spinner } from '@/components/ui/spinner'
import { notify } from '@/lib/notify'
import {
  createRepo,
  deleteRepo,
  getRepos,
  reactivateRepo,
  type Repo,
} from '@/lib/api'

function formatLastSynced(ts?: string): string {
  if (!ts) return '—'
  try {
    const ms = Date.now() - new Date(ts).getTime()
    const m = Math.floor(ms / 60000)
    if (m < 1) return 'just now'
    if (m < 60) return `${m}m ago`
    const h = Math.floor(m / 60)
    if (h < 24) return `${h}h ago`
    return `${Math.floor(h / 24)}d ago`
  } catch {
    return ts
  }
}

function statusOf(repo: Repo): 'active' | 'inactive' {
  if (repo.status) return repo.status
  if (repo.isActive === false) return 'inactive'
  return 'active'
}

export default function ReposPage() {
  const [repos, setRepos] = useState<Repo[]>([])
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<string | null>(null)
  const [modalOpen, setModalOpen] = useState(false)
  const [pendingDelete, setPendingDelete] = useState<Repo | null>(null)
  const [rowBusy, setRowBusy] = useState<Record<string, boolean>>({})

  const refresh = useCallback(async () => {
    setLoading(true)
    setError(null)
    try {
      const list = await getRepos()
      setRepos(list)
    } catch {
      setError('Failed to load repositories.')
    } finally {
      setLoading(false)
    }
  }, [])

  useEffect(() => {
    refresh()
  }, [refresh])

  const handleCreated = useCallback(
    (created: Repo) => {
      setModalOpen(false)
      notify(`Repository ${created.fullName} added.`, 'success')
      refresh()
    },
    [refresh],
  )

  const handleReactivate = useCallback(
    async (repo: Repo) => {
      setRowBusy((m) => ({ ...m, [repo.id]: true }))
      try {
        await reactivateRepo(repo.id)
        notify(`Repository ${repo.fullName} reactivated.`, 'success')
        await refresh()
      } catch (ex) {
        notify(
          ex instanceof Error ? ex.message : 'Failed to reactivate repository.',
          'danger',
        )
      } finally {
        setRowBusy((m) => {
          const next = { ...m }
          delete next[repo.id]
          return next
        })
      }
    },
    [refresh],
  )

  const handleDeleteConfirmed = useCallback(
    async (repo: Repo) => {
      setRowBusy((m) => ({ ...m, [repo.id]: true }))
      try {
        // Soft-delete only from the UI. Hard-delete is an operator task
        // (psql) and stays off the user-facing path on purpose — see
        // docs/implementation/11-code-review-sidebar.md (soft vs hard
        // discussion) and the ?hard=true backend path in admin/router.py.
        await deleteRepo(repo.id)
        notify(`Repository ${repo.fullName} deactivated.`, 'success')
        setPendingDelete(null)
        await refresh()
      } catch (ex) {
        notify(
          ex instanceof Error ? ex.message : 'Failed to delete repository.',
          'danger',
        )
      } finally {
        setRowBusy((m) => {
          const next = { ...m }
          delete next[repo.id]
          return next
        })
      }
    },
    [refresh],
  )

  return (
    <main style={{ flex: 1, overflow: 'auto', padding: '24px 32px' }}>
      {/* Page header */}
      <div
        style={{
          display: 'flex',
          alignItems: 'center',
          justifyContent: 'space-between',
          marginBottom: 20,
        }}
      >
        <div>
          <h1
            style={{
              margin: 0,
              fontSize: 15,
              fontWeight: 600,
              letterSpacing: -0.2,
              color: 'var(--text)',
            }}
          >
            Repositories
          </h1>
          <p style={{ margin: '4px 0 0', fontSize: 12.5, color: 'var(--text-subtle)' }}>
            Connected source control repositories used by the AI agents.
          </p>
        </div>
        <Btn
          tone="brand"
          size="sm"
          icon={<Plus size={13} />}
          onClick={() => setModalOpen(true)}
        >
          Add repository
        </Btn>
      </div>

      {/* Table card */}
      <div
        style={{
          background: 'var(--bg-elev)',
          border: '1px solid var(--panel-border)',
          borderRadius: 12,
          overflow: 'hidden',
          boxShadow: 'var(--shadow-sm)',
        }}
      >
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
            <Spinner size={16} />
            Loading repositories…
          </div>
        ) : error ? (
          <div
            style={{
              padding: '48px 16px',
              textAlign: 'center',
              fontSize: 13,
              color: 'var(--status-danger)',
            }}
          >
            {error}
          </div>
        ) : repos.length === 0 ? (
          <div
            data-testid="repos-empty-state"
            style={{
              padding: '48px 16px',
              textAlign: 'center',
              fontSize: 13,
              color: 'var(--text-muted)',
            }}
          >
            No repositories configured. Add one to start using AI agents.
          </div>
        ) : (
          <table
            data-testid="repos-table"
            style={{
              width: '100%',
              borderCollapse: 'collapse',
              fontSize: 12.5,
            }}
          >
            <thead>
              <tr
                style={{
                  borderBottom: '1px solid var(--panel-border)',
                  background: 'var(--bg-muted)',
                }}
              >
                {['Repository', 'SCM Type', 'Default branch', 'Status', 'Added', 'Actions'].map((h) => (
                  <th
                    key={h}
                    style={{
                      padding: '8px 14px',
                      textAlign: 'left',
                      fontSize: 11,
                      fontWeight: 600,
                      letterSpacing: 0.4,
                      textTransform: 'uppercase',
                      color: 'var(--text-subtle)',
                    }}
                  >
                    {h}
                  </th>
                ))}
              </tr>
            </thead>
            <tbody>
              {repos.map((repo, i) => {
                const st = statusOf(repo)
                return (
                  <tr
                    key={repo.id}
                    data-testid={`repo-row-${repo.fullName}`}
                    style={{
                      borderBottom:
                        i < repos.length - 1 ? '1px solid var(--divider)' : 'none',
                    }}
                  >
                    <td style={{ padding: '10px 14px', color: 'var(--text)', fontWeight: 500 }}>
                      <div style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
                        <GitBranch size={13} color="var(--text-muted)" />
                        <span>{repo.fullName}</span>
                      </div>
                    </td>
                    <td style={{ padding: '10px 14px' }}>
                      <span
                        className="gtx-mono"
                        style={{ fontSize: 12, color: 'var(--text-subtle)' }}
                      >
                        {repo.scmType ?? '—'}
                      </span>
                    </td>
                    <td
                      style={{
                        padding: '10px 14px',
                        fontSize: 12,
                        color: 'var(--text-subtle)',
                      }}
                      className="gtx-mono"
                    >
                      {repo.defaultBranch ?? '—'}
                    </td>
                    <td style={{ padding: '10px 14px' }}>
                      <Pill tone={st === 'active' ? 'success' : 'default'} size="xs" dot>
                        {st === 'active' ? 'Active' : 'Inactive'}
                      </Pill>
                    </td>
                    <td
                      style={{
                        padding: '10px 14px',
                        color: 'var(--text-faint)',
                        fontSize: 12,
                        fontVariantNumeric: 'tabular-nums',
                      }}
                    >
                      {formatLastSynced(repo.lastSynced)}
                    </td>
                    <td style={{ padding: '10px 14px', whiteSpace: 'nowrap' }}>
                      {st === 'active' ? (
                        <button
                          type="button"
                          data-testid={`repo-deactivate-${repo.fullName}`}
                          aria-label={`Deactivate ${repo.fullName}`}
                          onClick={() => setPendingDelete(repo)}
                          disabled={!!rowBusy[repo.id]}
                          style={{
                            background: 'transparent',
                            border: '1px solid var(--panel-border)',
                            borderRadius: 6,
                            padding: '4px 8px',
                            cursor: rowBusy[repo.id] ? 'not-allowed' : 'pointer',
                            color: 'var(--status-danger, #dc2626)',
                            display: 'inline-flex',
                            alignItems: 'center',
                            gap: 4,
                            fontSize: 12,
                          }}
                        >
                          <Trash2 size={12} />
                          Deactivate
                        </button>
                      ) : (
                        <button
                          type="button"
                          data-testid={`repo-reactivate-${repo.fullName}`}
                          aria-label={`Reactivate ${repo.fullName}`}
                          onClick={() => handleReactivate(repo)}
                          disabled={!!rowBusy[repo.id]}
                          style={{
                            background: 'transparent',
                            border: '1px solid var(--panel-border)',
                            borderRadius: 6,
                            padding: '4px 8px',
                            cursor: rowBusy[repo.id] ? 'not-allowed' : 'pointer',
                            color: 'var(--text-muted)',
                            display: 'inline-flex',
                            alignItems: 'center',
                            gap: 4,
                            fontSize: 12,
                          }}
                        >
                          <Undo2 size={12} />
                          Reactivate
                        </button>
                      )}
                    </td>
                  </tr>
                )
              })}
            </tbody>
          </table>
        )}
      </div>

      {modalOpen && (
        <AddRepoModal
          onClose={() => setModalOpen(false)}
          onCreated={handleCreated}
          existingNames={new Set(repos.map((r) => r.fullName))}
        />
      )}

      {pendingDelete && (
        <ConfirmDeleteDialog
          repo={pendingDelete}
          busy={!!rowBusy[pendingDelete.id]}
          onCancel={() => setPendingDelete(null)}
          onConfirm={() => handleDeleteConfirmed(pendingDelete)}
        />
      )}
    </main>
  )
}

// ── Add-repository modal ─────────────────────────────────────────────────────

function AddRepoModal({
  onClose,
  onCreated,
  existingNames,
}: {
  onClose: () => void
  onCreated: (repo: Repo) => void
  existingNames: Set<string>
}) {
  const [fullName, setFullName] = useState('')
  const [scmType, setScmType] = useState<'github' | 'bitbucket'>('github')
  const [defaultBranch, setDefaultBranch] = useState('main')
  const [submitting, setSubmitting] = useState(false)
  const [error, setError] = useState<string | null>(null)

  // Client-side validation matching the backend regex (alphanumeric, dot,
  // dash, underscore — one `/` between org and repo).
  const FULL_NAME_RE = /^[A-Za-z0-9_.-]+\/[A-Za-z0-9_.-]+$/

  const submit = async (e: React.FormEvent) => {
    e.preventDefault()
    setError(null)
    const name = fullName.trim()
    if (!FULL_NAME_RE.test(name)) {
      setError('Full name must be in "org/repo" format (letters, numbers, dot, dash, underscore only).')
      return
    }
    if (existingNames.has(name)) {
      setError(`Repository "${name}" is already registered.`)
      return
    }
    setSubmitting(true)
    try {
      const created = await createRepo({
        fullName: name,
        scmType,
        defaultBranch: defaultBranch.trim() || 'main',
      })
      onCreated(created)
    } catch (ex) {
      setError(ex instanceof Error ? ex.message : 'Failed to create repository.')
      setSubmitting(false)
    }
  }

  return (
    <div
      role="dialog"
      aria-modal="true"
      aria-labelledby="add-repo-title"
      data-testid="add-repo-modal"
      onClick={(e) => {
        if (e.target === e.currentTarget && !submitting) onClose()
      }}
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
      <form
        onSubmit={submit}
        style={{
          width: 440,
          maxWidth: '92vw',
          background: 'var(--panel)',
          border: '1px solid var(--panel-border)',
          borderRadius: 12,
          boxShadow: '0 10px 30px rgba(0,0,0,0.2)',
        }}
      >
        {/* Header */}
        <div
          style={{
            display: 'flex',
            alignItems: 'center',
            justifyContent: 'space-between',
            padding: '14px 18px',
            borderBottom: '1px solid var(--panel-border)',
          }}
        >
          <h2 id="add-repo-title" style={{ margin: 0, fontSize: 14, fontWeight: 600 }}>
            Add repository
          </h2>
          <button
            type="button"
            aria-label="Close"
            onClick={onClose}
            disabled={submitting}
            style={{
              background: 'transparent',
              border: 'none',
              cursor: submitting ? 'not-allowed' : 'pointer',
              color: 'var(--text-muted)',
              padding: 4,
              display: 'inline-flex',
            }}
          >
            <X size={16} />
          </button>
        </div>

        {/* Body */}
        <div style={{ padding: '18px', display: 'flex', flexDirection: 'column', gap: 14 }}>
          {/* SCM provider */}
          <fieldset
            style={{ border: 'none', padding: 0, margin: 0, display: 'flex', gap: 10 }}
          >
            <legend
              style={{
                fontSize: 11,
                fontWeight: 600,
                color: 'var(--text-subtle)',
                textTransform: 'uppercase',
                letterSpacing: 0.4,
                marginBottom: 6,
              }}
            >
              SCM provider
            </legend>
            {(['github', 'bitbucket'] as const).map((p) => (
              <label
                key={p}
                style={{
                  flex: 1,
                  display: 'flex',
                  alignItems: 'center',
                  gap: 8,
                  padding: '8px 12px',
                  border: `1px solid ${scmType === p ? 'var(--accent-500)' : 'var(--panel-border)'}`,
                  borderRadius: 8,
                  background: scmType === p ? 'var(--accent-50, transparent)' : 'transparent',
                  cursor: 'pointer',
                  fontSize: 13,
                }}
              >
                <input
                  type="radio"
                  name="scmType"
                  value={p}
                  checked={scmType === p}
                  onChange={() => setScmType(p)}
                  style={{ margin: 0 }}
                />
                <span style={{ textTransform: 'capitalize' }}>{p}</span>
              </label>
            ))}
          </fieldset>

          <label style={{ display: 'flex', flexDirection: 'column', gap: 4 }}>
            <span
              style={{
                fontSize: 11,
                fontWeight: 600,
                color: 'var(--text-subtle)',
                textTransform: 'uppercase',
                letterSpacing: 0.4,
              }}
            >
              Full name
            </span>
            <input
              type="text"
              value={fullName}
              onChange={(e) => setFullName(e.target.value)}
              placeholder={scmType === 'bitbucket' ? 'workspace/repo' : 'org/repo'}
              required
              autoFocus
              style={{
                padding: '8px 10px',
                fontSize: 13,
                border: '1px solid var(--panel-border)',
                borderRadius: 6,
                background: 'var(--bg-elev)',
                color: 'var(--text)',
                fontFamily: 'Geist Mono, monospace',
              }}
            />
            <span style={{ fontSize: 11, color: 'var(--text-faint)' }}>
              Example:{' '}
              {scmType === 'bitbucket'
                ? 'origination1/ai-driven'
                : 'MinnMinn/skills-introduction-to-github'}
            </span>
          </label>

          <label style={{ display: 'flex', flexDirection: 'column', gap: 4 }}>
            <span
              style={{
                fontSize: 11,
                fontWeight: 600,
                color: 'var(--text-subtle)',
                textTransform: 'uppercase',
                letterSpacing: 0.4,
              }}
            >
              Default branch
            </span>
            <input
              type="text"
              value={defaultBranch}
              onChange={(e) => setDefaultBranch(e.target.value)}
              placeholder="main"
              style={{
                padding: '8px 10px',
                fontSize: 13,
                border: '1px solid var(--panel-border)',
                borderRadius: 6,
                background: 'var(--bg-elev)',
                color: 'var(--text)',
                fontFamily: 'Geist Mono, monospace',
              }}
            />
          </label>

          {error && (
            <div
              role="alert"
              data-testid="add-repo-error"
              style={{
                padding: '8px 10px',
                fontSize: 12,
                color: 'var(--status-danger)',
                background: 'var(--status-danger-bg, rgba(220,38,38,0.08))',
                border: '1px solid var(--status-danger, #dc2626)',
                borderRadius: 6,
              }}
            >
              {error}
            </div>
          )}
        </div>

        {/* Footer */}
        <div
          style={{
            display: 'flex',
            justifyContent: 'flex-end',
            gap: 8,
            padding: '12px 18px',
            borderTop: '1px solid var(--panel-border)',
          }}
        >
          <Btn tone="ghost" size="sm" onClick={onClose} disabled={submitting}>
            Cancel
          </Btn>
          <Btn
            tone="brand"
            size="sm"
            type="submit"
            disabled={submitting || !fullName.trim()}
          >
            {submitting ? 'Adding…' : 'Add repository'}
          </Btn>
        </div>
      </form>
    </div>
  )
}

// ── Confirm-delete dialog ────────────────────────────────────────────────────

function ConfirmDeleteDialog({
  repo,
  busy,
  onCancel,
  onConfirm,
}: {
  repo: Repo
  busy: boolean
  onCancel: () => void
  onConfirm: () => void
}) {
  return (
    <div
      role="dialog"
      aria-modal="true"
      aria-labelledby="delete-repo-title"
      data-testid="delete-repo-dialog"
      onClick={(e) => {
        if (e.target === e.currentTarget && !busy) onCancel()
      }}
      style={{
        position: 'fixed',
        inset: 0,
        background: 'rgba(0,0,0,0.4)',
        display: 'flex',
        alignItems: 'center',
        justifyContent: 'center',
        zIndex: 1001,
      }}
    >
      <div
        style={{
          width: 460,
          maxWidth: '92vw',
          background: 'var(--panel)',
          border: '1px solid var(--panel-border)',
          borderRadius: 12,
          boxShadow: '0 10px 30px rgba(0,0,0,0.2)',
          padding: 20,
        }}
      >
        <h2 id="delete-repo-title" style={{ margin: 0, fontSize: 14, fontWeight: 600 }}>
          Deactivate repository?
        </h2>

        <div
          style={{
            marginTop: 10,
            fontSize: 12.5,
            color: 'var(--text-muted)',
            lineHeight: 1.5,
          }}
        >
          Mark <strong>{repo.fullName}</strong> as inactive. Task history is
          preserved and you can reactivate from this page any time.
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
          <Btn
            tone="brand"
            size="sm"
            onClick={onConfirm}
            disabled={busy}
          >
            {busy ? 'Working…' : 'Deactivate'}
          </Btn>
        </div>
      </div>
    </div>
  )
}
