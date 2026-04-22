'use client'

import { useEffect, useState } from 'react'
import { GitBranch, Plus } from 'lucide-react'
import { Pill } from '@/components/ui/pill'
import { Btn } from '@/components/ui/btn'
import { Spinner } from '@/components/ui/spinner'

interface Repo {
  id: string
  name: string
  scmType: string
  status: 'active' | 'inactive'
  lastSynced?: string
}

function formatLastSynced(ts?: string): string {
  if (!ts) return '—'
  try {
    const ms = Date.now() - new Date(ts).getTime()
    const m = Math.floor(ms / 60000)
    if (m < 60) return `${m}m ago`
    const h = Math.floor(m / 60)
    if (h < 24) return `${h}h ago`
    return `${Math.floor(h / 24)}d ago`
  } catch {
    return ts
  }
}

export default function ReposPage() {
  const [repos, setRepos] = useState<Repo[]>([])
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<string | null>(null)

  useEffect(() => {
    fetch('/api/admin/repos')
      .then((r) => r.json())
      .then((data) => {
        setRepos(Array.isArray(data) ? data : data.repos ?? [])
        setLoading(false)
      })
      .catch(() => {
        setError('Failed to load repositories.')
        setLoading(false)
      })
  }, [])

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
          onClick={() => alert('Add repository form coming soon.')}
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
                {['Repository', 'SCM Type', 'Status', 'Last Synced'].map((h) => (
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
              {repos.map((repo, i) => (
                <tr
                  key={repo.id}
                  style={{
                    borderBottom:
                      i < repos.length - 1 ? '1px solid var(--divider)' : 'none',
                  }}
                >
                  <td style={{ padding: '10px 14px', color: 'var(--text)', fontWeight: 500 }}>
                    <div style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
                      <GitBranch size={13} color="var(--text-muted)" />
                      <span>{repo.name}</span>
                    </div>
                  </td>
                  <td style={{ padding: '10px 14px', color: 'var(--text-muted)' }}>
                    <span
                      className="gtx-mono"
                      style={{ fontSize: 12, color: 'var(--text-subtle)' }}
                    >
                      {repo.scmType}
                    </span>
                  </td>
                  <td style={{ padding: '10px 14px' }}>
                    <Pill tone={repo.status === 'active' ? 'success' : 'default'} size="xs" dot>
                      {repo.status === 'active' ? 'Active' : 'Inactive'}
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
                </tr>
              ))}
            </tbody>
          </table>
        )}
      </div>
    </main>
  )
}
