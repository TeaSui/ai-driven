'use client'

import { FormEvent, useState } from 'react'
import { useRouter } from 'next/navigation'
import { login } from '@/lib/auth'

export default function LoginPage() {
  const router = useRouter()
  const [key, setKey] = useState('')
  const [email, setEmail] = useState('')
  const [error, setError] = useState('')
  const [loading, setLoading] = useState(false)

  async function handleSubmit(e: FormEvent) {
    e.preventDefault()
    if (!key.trim()) return

    setLoading(true)
    setError('')

    try {
      // Exchange the API key for a short-lived session token. The backend
      // sets an HttpOnly session_token cookie on success; the page just
      // needs to navigate.
      await login(key.trim(), email.trim() || undefined)
      router.push('/dashboard')
    } catch (ex) {
      setError(ex instanceof Error ? ex.message : 'Sign-in failed. Please try again.')
      setLoading(false)
    }
  }

  return (
    <main style={styles.page} id="main-content">
      <div style={styles.card}>
        <h1 style={styles.logo}>
          GoTyme<span style={styles.logoAccent}>X</span> AI
        </h1>
        <p style={styles.subtitle}>Sign in with your team API key</p>

        <form onSubmit={handleSubmit} style={styles.form} noValidate>
          <label htmlFor="email" style={styles.label}>
            Email <span style={styles.optional}>(optional, for audit log)</span>
          </label>
          <input
            id="email"
            type="email"
            placeholder="you@company.com"
            value={email}
            onChange={e => setEmail(e.target.value)}
            autoComplete="email"
            style={styles.input}
          />

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
            {loading ? 'Signing in...' : 'Sign in'}
          </button>
        </form>

        <p style={styles.hint}>
          API keys are issued by your team administrator via the platform CLI or admin panel.
          Your email is used only for the audit log and is not part of authentication.
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
  optional: {
    fontSize: 11,
    color: 'var(--text-faint)',
    fontWeight: 400,
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
