'use client'

/**
 * Settings > Connections (PR 2).
 *
 * Exposes each provider's OAuth connection state + actions (Connect / Test /
 * Disconnect / Reconnect / Upgrade). Uses the bare-array endpoint at
 * `GET /api/admin/me/connections`.
 *
 * Callback round-trip:
 *   `/api/auth/scm/<provider>/callback` always 302s back here with either
 *   `?connected=<provider>` on success or `?error=<CODE>[&scmUsername=<n>]`
 *   on failure. On mount we read the query params, surface a toast, then
 *   `window.location.replace` the pathname to strip them — router.replace
 *   leaves a back-button entry that'd re-fire the toast.
 */

import { useEffect, useRef } from 'react'
import useSWR from 'swr'
import { ConnectionCard } from '@/components/settings/connection-card'
import { notify } from '@/lib/notify'
import {
  disconnectConnection,
  getMyConnections,
  testConnection,
  type Connection,
  type ScmProvider,
} from '@/lib/api'

const PROVIDERS: ScmProvider[] = ['github', 'bitbucket']

/**
 * Map a backend error code → human-readable toast text. `scmUsername` is
 * populated for the SCM_IDENTITY_CLAIMED case (the OAuth callback appends
 * it to the query string so we can name the conflicting user).
 */
function errorMessage(
  code: string,
  provider: string,
  scmUsername: string | null,
): string {
  const providerLabel = provider === 'bitbucket' ? 'Bitbucket' : 'GitHub'
  switch (code) {
    case 'OAUTH_INVALID_STATE':
      return 'Session expired — please try again.'
    case 'OAUTH_INVALID_REQUEST':
      return 'Invalid OAuth request.'
    case 'OAUTH_TOKEN_EXCHANGE_FAILED':
      return `${providerLabel} rejected the authorization code.`
    case 'OAUTH_USER_FETCH_FAILED':
      return `Could not fetch user profile from ${providerLabel}.`
    case 'OAUTH_NOT_CONFIGURED':
      return 'SCM OAuth not configured. Ask your workspace owner.'
    case 'SCM_IDENTITY_CLAIMED':
      return scmUsername
        ? `Another member of your team is already connected as @${scmUsername}.`
        : 'Another member of your team is already connected with this account.'
    case 'INTERNAL_ERROR':
      return 'Something went wrong. Please try again.'
    default:
      return 'Connection failed. Please try again.'
  }
}

export default function ConnectionsPage() {
  const { data, mutate, isLoading, error } = useSWR<Connection[]>(
    'me/connections',
    () => getMyConnections(),
    {
      revalidateOnFocus: true, // reconnect after user returns from github.com
      shouldRetryOnError: false,
    },
  )

  // Parse ?connected / ?error from the OAuth callback exactly once per mount.
  // Guard against re-running in React strict-mode double invocation.
  const handledRef = useRef(false)
  // Surface the "connected as @..." toast once — only after a successful
  // callback *and* after the refresh has a row for the provider.
  const pendingConnectRef = useRef<ScmProvider | null>(null)
  useEffect(() => {
    if (handledRef.current) return
    if (typeof window === 'undefined') return
    const params = new URLSearchParams(window.location.search)
    const connected = params.get('connected')
    const errorCode = params.get('error')
    const scmUsername = params.get('scmUsername')
    const provider = (connected ?? params.get('provider') ?? 'github') as ScmProvider

    if (!connected && !errorCode) {
      handledRef.current = true
      return
    }
    handledRef.current = true

    if (errorCode) {
      notify(errorMessage(errorCode, provider, scmUsername), 'danger', 6000)
    } else if (connected === 'github' || connected === 'bitbucket') {
      // Arm the success toast — the effect watching `data` fires it once
      // the refresh surfaces a row for this provider.
      pendingConnectRef.current = connected
      void mutate()
    }

    // Strip the query string so a refresh doesn't re-toast. Using the
    // history API directly (not `router.replace`) avoids a back-button
    // entry pointing at the post-OAuth state.
    window.history.replaceState(
      {},
      '',
      `${window.location.pathname}${window.location.hash}`,
    )
  }, [mutate])

  useEffect(() => {
    if (!data || !pendingConnectRef.current) return
    const provider = pendingConnectRef.current
    const match = data.find((c) => c.provider === provider)
    if (match) {
      notify(
        `${provider === 'bitbucket' ? 'Bitbucket' : 'GitHub'} connected as @${match.scmUsername}.`,
        'success',
        4000,
      )
      pendingConnectRef.current = null
    }
  }, [data])

  const handleConnect = (provider: ScmProvider) =>
    (scope: 'public_repo' | 'repo') => {
      // Full-navigation — fetch won't follow the 302 chain to the provider.
      // The callback returns with `?connected=<provider>` which is what
      // arms the post-connect success toast (see the mount effect above).
      window.location.href = `/api/auth/scm/${provider}/authorize?scope=${scope}`
    }

  const handleTest = (provider: ScmProvider) => async () => {
    try {
      const result = await testConnection(provider)
      if (result.ok) {
        notify(
          `${provider === 'bitbucket' ? 'Bitbucket' : 'GitHub'} connection is healthy${
            result.scmUsername ? ` (@${result.scmUsername})` : ''
          }.`,
          'success',
        )
      } else {
        notify(
          result.reason
            ? `${provider === 'bitbucket' ? 'Bitbucket' : 'GitHub'} says: ${result.reason}`
            : `${provider === 'bitbucket' ? 'Bitbucket' : 'GitHub'} connection is not healthy.`,
          'warn',
        )
      }
      return result
    } catch (ex) {
      const msg = ex instanceof Error ? ex.message : 'Test failed.'
      notify(msg, 'danger')
      throw ex
    }
  }

  const handleDisconnect = (provider: ScmProvider) => async () => {
    try {
      await disconnectConnection(provider)
      await mutate()
      notify(
        `${provider === 'bitbucket' ? 'Bitbucket' : 'GitHub'} disconnected.`,
        'success',
      )
    } catch (ex) {
      const msg = ex instanceof Error ? ex.message : 'Disconnect failed.'
      notify(msg, 'danger')
      throw ex
    }
  }

  const byProvider = new Map<ScmProvider, Connection>()
  for (const c of data ?? []) {
    byProvider.set(c.provider, c)
  }

  return (
    <div style={{ maxWidth: 760 }}>
      <header style={{ marginBottom: 20 }}>
        <h1
          style={{
            margin: 0,
            fontSize: 15,
            fontWeight: 600,
            letterSpacing: -0.2,
            color: 'var(--text)',
          }}
        >
          Connections
        </h1>
        <p style={{ margin: '4px 0 0', fontSize: 12.5, color: 'var(--text-subtle)' }}>
          Link the agent to your GitHub or Bitbucket account so reviews and PR
          actions run under your identity instead of a shared bot.
        </p>
      </header>

      {error && (
        <div
          role="alert"
          style={{
            padding: '12px 14px',
            background: 'var(--status-danger-bg)',
            color: 'var(--status-danger)',
            borderRadius: 8,
            fontSize: 13,
            marginBottom: 16,
          }}
        >
          Failed to load connections. Refresh the page or contact support if
          the problem persists.
        </div>
      )}

      <div
        style={{
          display: 'flex',
          flexDirection: 'column',
          gap: 12,
          opacity: isLoading && !data ? 0.6 : 1,
        }}
        aria-busy={isLoading && !data ? 'true' : 'false'}
      >
        {PROVIDERS.map((provider) => (
          <ConnectionCard
            key={provider}
            provider={provider}
            connection={byProvider.get(provider) ?? null}
            onConnect={handleConnect(provider)}
            onTest={handleTest(provider)}
            onDisconnect={handleDisconnect(provider)}
          />
        ))}
      </div>
    </div>
  )
}
