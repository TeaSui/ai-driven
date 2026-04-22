'use client'

import { FormEvent, useState } from 'react'
import { useRouter } from 'next/navigation'
import { setApiKey } from '@/lib/auth'

export default function LoginPage() {
  const router = useRouter()
  const [key, setKey] = useState('')
  const [error, setError] = useState('')
  const [loading, setLoading] = useState(false)

  async function handleSubmit(e: FormEvent) {
    e.preventDefault()
    if (!key.trim()) return

    setLoading(true)
    setError('')

    try {
      // Validate the API key by hitting /health with it
      const res = await fetch('/api/admin/dashboard', {
        headers: { 'X-API-Key': key.trim() },
      })
      if (res.status === 401) {
        setError('Invalid API key. Please check and try again.')
        setLoading(false)
        return
      }
      // Key is valid — store it and redirect
      setApiKey(key.trim())
      router.push('/dashboard')
    } catch {
      setError('Unable to reach the server. Please try again.')
      setLoading(false)
    }
  }

  return (
    <main style={styles.page} id="main-content">
      <div style={styles.card}>
        <h1 style={styles.logo}>
          GoTyme<span style={styles.logoAccent}>X</span> AI
        </h1>
        <p style={styles.subtitle}>Enter your team API key to continue</p>

        <form onSubmit={handleSubmit} style={styles.form} noValidate>
          <label htmlFor="api-key" style={styles.label}>API Key</label>
          <input
            id="api-key"
            type="password"
            placeholder="aidrv_..."
            value={key}
            onChange={e => setKey(e.target.value)}
            autoFocus
            autoComplete="current-password"
            aria-required="true"
            aria-describedby={error ? 'login-error' : undefined}
            aria-invalid={error ? true : undefined}
            style={styles.input}
          />
          {error && (
            <div id="login-error" role="alert" style={styles.error}>
              {error}
            </div>
          )}
          <button
            type="submit"
            disabled={loading || !key.trim()}
            aria-busy={loading}
            style={styles.button}
          >
            {loading ? 'Verifying...' : 'Sign in'}
          </button>
        </form>

        <p style={styles.hint}>
          API keys are issued by your team administrator via the platform CLI or admin panel.
        </p>
      </div>
    </main>
  )
}

const styles: Record<string, React.CSSProperties> = {
  page: {
    display: 'flex',
    alignItems: 'center',
    justifyContent: 'center',
    minHeight: '100vh',
    background: 'var(--bg-muted)',
    fontFamily: 'inherit',
  },
  card: {
    width: 380,
    padding: '40px 32px',
    background: 'var(--panel)',
    border: '1px solid var(--panel-border)',
    borderRadius: 12,
    boxShadow: 'var(--shadow-md)',
    textAlign: 'center' as const,
  },
  logo: {
    fontSize: 28,
    fontWeight: 700,
    color: 'var(--text)',
    marginBottom: 4,
  },
  logoAccent: {
    color: 'var(--accent-500)',
  },
  subtitle: {
    fontSize: 14,
    color: 'var(--text-muted)',
    margin: '0 0 24px',
  },
  form: {
    display: 'flex',
    flexDirection: 'column' as const,
    gap: 12,
    textAlign: 'left' as const,
  },
  label: {
    fontSize: 13,
    fontWeight: 500,
    color: 'var(--text)',
  },
  input: {
    padding: '10px 12px',
    fontSize: 14,
    border: '1px solid var(--border)',
    borderRadius: 8,
    outline: 'none',
    fontFamily: 'monospace',
    background: 'var(--bg)',
    color: 'var(--text)',
  },
  error: {
    fontSize: 13,
    color: 'var(--status-danger)',
    margin: 0,
  },
  button: {
    padding: '10px 0',
    fontSize: 14,
    fontWeight: 600,
    background: 'var(--accent-500)',
    color: 'var(--text-inverse)',
    border: 'none',
    borderRadius: 8,
    cursor: 'pointer',
    marginTop: 4,
    // 44px min height for touch target (WCAG 2.2)
    minHeight: 44,
  },
  hint: {
    fontSize: 12,
    color: 'var(--text-faint)',
    marginTop: 20,
  },
}
