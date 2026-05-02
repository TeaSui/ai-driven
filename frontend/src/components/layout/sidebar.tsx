'use client'

import { useEffect, useState } from 'react'
import Link from 'next/link'
import { usePathname, useRouter } from 'next/navigation'
import useSWR from 'swr'
import { useSidebar } from './sidebar-context'
import { authFetch, logout as doLogout } from '@/lib/auth'
import { getDashboardSummary, type DashboardSummary } from '@/lib/api'
import { BrandMark, Wordmark } from '@/components/ui/brand-mark'
import { Avatar } from '@/components/ui/avatar'
import { StatusDot } from '@/components/ui/status-dot'
import { Kbd } from '@/components/ui/kbd'
import { ThemeToggle } from '@/components/ui/theme-toggle'

// Icon SVG paths (ported from ui/icons.jsx)
function IconSearch({ size = 13 }: { size?: number }) {
  return (
    <svg width={size} height={size} viewBox="0 0 16 16" fill="none"
      stroke="currentColor" strokeWidth={1.5} strokeLinecap="round" strokeLinejoin="round"
      style={{ display: 'inline-block', flexShrink: 0 }}>
      <circle cx="7" cy="7" r="4.5"/>
      <path d="M13 13l-2.7-2.7"/>
    </svg>
  )
}

function IconMessageSquare({ size = 15 }: { size?: number }) {
  return (
    <svg width={size} height={size} viewBox="0 0 16 16" fill="none"
      stroke="currentColor" strokeWidth={1.5} strokeLinecap="round" strokeLinejoin="round"
      style={{ display: 'inline-block', flexShrink: 0 }}>
      <path d="M14 10a2 2 0 01-2 2H5l-3 3V4a2 2 0 012-2h8a2 2 0 012 2v6z"/>
    </svg>
  )
}

function IconZap({ size = 15 }: { size?: number }) {
  return (
    <svg width={size} height={size} viewBox="0 0 16 16" fill="none"
      stroke="currentColor" strokeWidth={1.5} strokeLinecap="round" strokeLinejoin="round"
      style={{ display: 'inline-block', flexShrink: 0 }}>
      <path d="M8.5 1.5L3 9h4l-.5 5.5L12 7H8l.5-5.5z"/>
    </svg>
  )
}

function IconBot({ size = 15 }: { size?: number }) {
  return (
    <svg width={size} height={size} viewBox="0 0 16 16" fill="none"
      stroke="currentColor" strokeWidth={1.5} strokeLinecap="round" strokeLinejoin="round"
      style={{ display: 'inline-block', flexShrink: 0 }}>
      <rect x="3" y="5" width="10" height="8" rx="2"/>
      <circle cx="6" cy="9" r=".8" fill="currentColor"/>
      <circle cx="10" cy="9" r=".8" fill="currentColor"/>
      <path d="M8 3v2"/>
      <path d="M1 9h2"/>
      <path d="M13 9h2"/>
    </svg>
  )
}

function IconGitPr({ size = 15 }: { size?: number }) {
  return (
    <svg width={size} height={size} viewBox="0 0 16 16" fill="none"
      stroke="currentColor" strokeWidth={1.5} strokeLinecap="round" strokeLinejoin="round"
      style={{ display: 'inline-block', flexShrink: 0 }}>
      <circle cx="4" cy="3.5" r="1.2"/>
      <circle cx="4" cy="12.5" r="1.2"/>
      <circle cx="12" cy="12.5" r="1.2"/>
      <path d="M4 4.7v6.6"/>
      <path d="M12 11.3V7a2 2 0 00-2-2H7.5"/>
      <path d="M9 3l-1.5 2L9 7"/>
    </svg>
  )
}

function IconAlert({ size = 15 }: { size?: number }) {
  return (
    <svg width={size} height={size} viewBox="0 0 16 16" fill="none"
      stroke="currentColor" strokeWidth={1.5} strokeLinecap="round" strokeLinejoin="round"
      style={{ display: 'inline-block', flexShrink: 0 }}>
      <path d="M8 2l6.5 11H1.5L8 2z"/>
      <path d="M8 6.5v3.5"/>
      <circle cx="8" cy="11.5" r=".8" fill="currentColor"/>
    </svg>
  )
}

function IconEye({ size = 15 }: { size?: number }) {
  return (
    <svg width={size} height={size} viewBox="0 0 16 16" fill="none"
      stroke="currentColor" strokeWidth={1.5} strokeLinecap="round" strokeLinejoin="round"
      style={{ display: 'inline-block', flexShrink: 0 }}>
      <path d="M1.5 8S4 3.5 8 3.5 14.5 8 14.5 8 12 12.5 8 12.5 1.5 8 1.5 8z"/>
      <circle cx="8" cy="8" r="2"/>
    </svg>
  )
}

function IconSlack({ size = 15 }: { size?: number }) {
  return (
    <svg width={size} height={size} viewBox="0 0 16 16" fill="none"
      stroke="currentColor" strokeWidth={1.5} strokeLinecap="round" strokeLinejoin="round"
      style={{ display: 'inline-block', flexShrink: 0 }}>
      <rect x="6.5" y="1.5" width="3" height="6" rx="1.5"/>
      <rect x="6.5" y="8.5" width="3" height="6" rx="1.5"/>
      <rect x="1.5" y="6.5" width="6" height="3" rx="1.5"/>
      <rect x="8.5" y="6.5" width="6" height="3" rx="1.5"/>
    </svg>
  )
}

function IconFolder({ size = 14 }: { size?: number }) {
  return (
    <svg width={size} height={size} viewBox="0 0 16 16" fill="none"
      stroke="currentColor" strokeWidth={1.5} strokeLinecap="round" strokeLinejoin="round"
      style={{ display: 'inline-block', flexShrink: 0 }}>
      <path d="M2 4a1 1 0 011-1h3l2 2h5a1 1 0 011 1v6a1 1 0 01-1 1H3a1 1 0 01-1-1z"/>
    </svg>
  )
}

function IconShield({ size = 14 }: { size?: number }) {
  return (
    <svg width={size} height={size} viewBox="0 0 16 16" fill="none"
      stroke="currentColor" strokeWidth={1.5} strokeLinecap="round" strokeLinejoin="round"
      style={{ display: 'inline-block', flexShrink: 0 }}>
      <path d="M8 1.5L13.5 4v4c0 3.5-2.5 5.5-5.5 6.5-3-1-5.5-3-5.5-6.5V4L8 1.5z"/>
    </svg>
  )
}

function IconClock({ size = 14 }: { size?: number }) {
  return (
    <svg width={size} height={size} viewBox="0 0 16 16" fill="none"
      stroke="currentColor" strokeWidth={1.5} strokeLinecap="round" strokeLinejoin="round"
      style={{ display: 'inline-block', flexShrink: 0 }}>
      <circle cx="8" cy="8" r="6"/>
      <path d="M8 4.5V8l2.5 1.5"/>
    </svg>
  )
}

function IconKey({ size = 14 }: { size?: number }) {
  return (
    <svg width={size} height={size} viewBox="0 0 16 16" fill="none"
      stroke="currentColor" strokeWidth={1.5} strokeLinecap="round" strokeLinejoin="round"
      style={{ display: 'inline-block', flexShrink: 0 }}>
      <circle cx="5.5" cy="10.5" r="2.5"/>
      <path d="M7 9l6-6M11 5l1.5 1.5M13 3l1.5 1.5"/>
    </svg>
  )
}

function IconSettings({ size = 13 }: { size?: number }) {
  return (
    <svg width={size} height={size} viewBox="0 0 16 16" fill="none"
      stroke="currentColor" strokeWidth={1.5} strokeLinecap="round" strokeLinejoin="round"
      style={{ display: 'inline-block', flexShrink: 0 }}>
      <circle cx="8" cy="8" r="2"/>
      <path d="M13.5 8a5.5 5.5 0 00-.1-1l1.3-1-1.3-2.2-1.6.5A5.5 5.5 0 0010.5 3L10 1.4H6L5.5 3a5.5 5.5 0 00-1.3.8l-1.6-.5L1.3 5.5l1.3 1A5.5 5.5 0 002.5 8v1l-1.3 1 1.3 2.2 1.6-.5c.4.3.8.6 1.3.8L6 14.6h4l.5-1.6c.5-.2.9-.5 1.3-.8l1.6.5 1.3-2.2-1.3-1v-1z"/>
    </svg>
  )
}

interface NavItem {
  id: string
  label: string
  href: string
  icon: React.ReactNode
  count?: number
  tone?: 'warn'
  pulse?: boolean
}

interface SubNavItem {
  id: string
  label: string
  href: string
  icon: React.ReactNode
}

function buildNavItems(summary?: DashboardSummary): NavItem[] {
  const active = summary?.activeAgents
  const totalActive = active ? Object.values(active).reduce((a, b) => a + b, 0) : 0
  const codingActive = active?.coding ?? 0
  const reviewActive = active?.review ?? 0
  const devopsActive = active?.devops ?? 0

  return [
    { id: 'timeline', label: 'Timeline',     href: '/dashboard/timeline',  icon: <IconZap size={15}/>,   count: totalActive || undefined },
    { id: 'agent',    label: 'Coding Agent', href: '/dashboard/tasks',     icon: <IconBot size={15}/>,   pulse: codingActive > 0, count: codingActive || undefined },
    { id: 'review',   label: 'Code Review',  href: '/dashboard/reviews',   icon: <IconGitPr size={15}/>, count: reviewActive || undefined },
    { id: 'devops',   label: 'DevOps',       href: '/dashboard/incidents', icon: <IconAlert size={15}/>, count: devopsActive || undefined, tone: devopsActive > 0 ? 'warn' : undefined },
    { id: 'metrics',  label: 'Metrics',      href: '/dashboard/insights',  icon: <IconEye size={15}/> },
    { id: 'slack',    label: 'Slack',        href: '/dashboard/slack',     icon: <IconSlack size={15}/> },
  ]
}

const SUB_ITEMS: SubNavItem[] = [
  { id: 'repos', label: 'Repositories', href: '/dashboard/admin/repos', icon: <IconFolder size={14}/> },
  { id: 'rules', label: 'Review rules', href: '/dashboard/admin/rules', icon: <IconShield size={14}/> },
  { id: 'keys',  label: 'API keys',      href: '/dashboard/admin/api-keys', icon: <IconKey size={14}/> },
  { id: 'runs',  label: 'Run history',  href: '/dashboard/admin/runs',  icon: <IconClock size={14}/> },
]

function NavRow({
  item,
  active,
  collapsed,
  muted,
}: {
  item: NavItem | SubNavItem
  active?: boolean
  collapsed?: boolean
  muted?: boolean
}) {
  const navItem = item as NavItem
  const toneColor = navItem.tone === 'warn' ? 'var(--status-warn)' : 'var(--text-muted)'

  return (
    <Link
      href={item.href}
      title={collapsed ? item.label : undefined}
      aria-label={collapsed ? item.label : undefined}
      aria-current={active ? 'page' : undefined}
      style={{
        display: 'flex',
        alignItems: 'center',
        gap: 9,
        width: '100%',
        padding: collapsed ? '8px' : '7px 10px',
        background: active ? 'var(--accent-soft)' : 'transparent',
        color: active ? 'var(--brand-teal-700)' : (muted ? 'var(--text-subtle)' : 'var(--text-muted)'),
        borderRadius: 8,
        cursor: 'pointer',
        fontSize: 12.8,
        fontWeight: active ? 600 : 400,
        fontFamily: 'inherit',
        textAlign: 'left',
        justifyContent: collapsed ? 'center' : 'flex-start',
        position: 'relative',
        transition: 'background .15s, color .15s',
        textDecoration: 'none',
        borderLeft: active ? '2px solid var(--accent-500)' : '2px solid transparent',
      }}
    >
      <span
        aria-hidden="true"
        style={{
          width: 15, height: 15, display: 'inline-flex', alignItems: 'center', justifyContent: 'center',
          color: active ? 'var(--accent-500)' : 'currentColor',
        }}
      >
        {item.icon}
      </span>
      {!collapsed && <span style={{ flex: 1 }}>{item.label}</span>}
      {!collapsed && navItem.count != null && (
        <span style={{
          fontSize: 10.5,
          color: toneColor,
          fontVariantNumeric: 'tabular-nums',
          fontWeight: 500,
          padding: '1px 5px',
          borderRadius: 4,
          background: navItem.tone === 'warn' ? 'var(--status-warn-bg)' : 'var(--bg-muted)',
        }}>
          {navItem.count}
        </span>
      )}
      {!collapsed && navItem.pulse && <StatusDot tone="success" pulse size={6} />}
      {collapsed && navItem.pulse && (
        <span style={{ position: 'absolute', top: 6, right: 6 }}>
          <StatusDot tone="success" pulse size={6} />
        </span>
      )}
    </Link>
  )
}

export function Sidebar() {
  const { collapsed } = useSidebar()
  const pathname = usePathname()
  const router = useRouter()
  const w = collapsed ? 56 : 220

  const { data: summary } = useSWR<DashboardSummary>('sidebar-summary', getDashboardSummary, {
    refreshInterval: 30_000,
    revalidateOnFocus: false,
    shouldRetryOnError: false,
  })
  const NAV_ITEMS = buildNavItems(summary)

  const [teamName, setTeamName] = useState<string | null>(null)

  useEffect(() => {
    authFetch('/api/admin/me')
      .then(r => r.ok ? r.json() : null)
      .then(data => { if (data?.teamName) setTeamName(data.teamName) })
      .catch(() => {})
  }, [])

  async function handleLogout() {
    // Clears the session_token cookie server-side, revokes the Redis entry,
    // and also drops the legacy api_key cookie for belt-and-braces.
    await doLogout()
    router.push('/login')
  }

  function isActive(item: NavItem): boolean {
    return pathname.startsWith(item.href)
  }

  return (
    <aside
      aria-label="Main navigation"
      style={{
        width: w,
        flexShrink: 0,
        borderRight: '1px solid var(--panel-border)',
        background: 'linear-gradient(180deg, var(--panel) 0%, var(--bg) 100%)',
        display: 'flex',
        flexDirection: 'column',
        transition: 'width .22s cubic-bezier(.2,.7,.3,1)',
        overflow: 'hidden',
        height: '100%',
        boxShadow: '1px 0 8px rgba(10,20,22,0.03)',
      }}
    >
      {/* Wordmark */}
      <div style={{
        padding: '14px 14px 12px',
        display: 'flex',
        alignItems: 'center',
        justifyContent: 'space-between',
        height: 52,
        flexShrink: 0,
      }}>
        <Link href="/dashboard" style={{ textDecoration: 'none' }}>
          <Wordmark compact={collapsed} />
        </Link>
      </div>

      {/* Nav scroll area */}
      <nav style={{ padding: '0 8px', flex: 1, overflowY: 'auto' }}>
        {!collapsed && (
          <div
            role="button"
            tabIndex={0}
            aria-label="Search (Command K)"
            onKeyDown={(e) => { if (e.key === 'Enter' || e.key === ' ') e.currentTarget.click() }}
            style={{
              display: 'flex', alignItems: 'center', gap: 8,
              margin: '4px 4px 10px',
              padding: '6px 8px', borderRadius: 6,
              background: 'var(--bg-muted)',
              border: '1px solid var(--border)',
              fontSize: 12, color: 'var(--text-muted)',
              cursor: 'pointer',
            }}
          >
            <IconSearch size={13} aria-hidden="true" />
            <span style={{ flex: 1 }}>Search…</span>
            <Kbd>⌘K</Kbd>
          </div>
        )}

        {NAV_ITEMS.map(n => (
          <NavRow key={n.id} item={n} active={isActive(n)} collapsed={collapsed} />
        ))}

        {!collapsed && (
          <>
            <div
              role="separator"
              style={{
                padding: '16px 10px 6px',
                fontSize: 10.5, fontWeight: 600,
                letterSpacing: 0.8,
                color: 'var(--text-faint)',
                textTransform: 'uppercase',
              }}
            >
              Configure
            </div>
            {SUB_ITEMS.map(s => (
              <NavRow key={s.id} item={s} collapsed={collapsed} muted />
            ))}
          </>
        )}
      </nav>

      {/* Team profile + theme toggle */}
      <div style={{ padding: 10, borderTop: '1px solid var(--panel-border)', flexShrink: 0 }}>
        {collapsed ? (
          <div style={{ display: 'flex', flexDirection: 'column', alignItems: 'center', gap: 4 }}>
            <Avatar name={teamName ?? 'Team'} size={28} />
            <ThemeToggle iconOnly />
          </div>
        ) : (
          <div style={{ display: 'flex', alignItems: 'center', gap: 9, padding: '4px 6px' }}>
            <Avatar name={teamName ?? 'Team'} size={28} />
            <div style={{ flex: 1, minWidth: 0 }}>
              <div style={{ fontSize: 12.5, fontWeight: 500, color: 'var(--text)', overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>
                {teamName ?? 'Loading...'}
              </div>
              <div style={{ fontSize: 11, color: 'var(--text-subtle)' }}>Team</div>
            </div>
            <ThemeToggle iconOnly />
            <button
              aria-label="Sign out"
              data-testid="sidebar-sign-out"
              onClick={handleLogout}
              style={{
                display: 'inline-flex', alignItems: 'center', justifyContent: 'center',
                width: 44, height: 44, border: 'none',
                background: 'transparent', color: 'var(--text-muted)',
                borderRadius: 6, cursor: 'pointer',
              }}
            >
              <IconSettings size={13} />
            </button>
          </div>
        )}
      </div>
    </aside>
  )
}
