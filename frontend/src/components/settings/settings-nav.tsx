'use client'

/**
 * Left-hand sub-navigation for the Settings shell (PR 2).
 *
 * Single item today ("Connections"). Keeping the item array + the
 * <nav> wrapper instead of hardcoding one link so that adding "API keys"
 * or "Team" later is a one-line edit.
 */

import Link from 'next/link'
import { usePathname } from 'next/navigation'
import { Link2 } from 'lucide-react'
import type { ReactNode } from 'react'

interface SettingsNavItem {
  id: string
  label: string
  href: string
  icon: ReactNode
}

const ITEMS: SettingsNavItem[] = [
  {
    id: 'connections',
    label: 'Connections',
    href: '/dashboard/settings/connections',
    icon: <Link2 size={14} aria-hidden="true" />,
  },
]

export function SettingsNav() {
  const pathname = usePathname()

  return (
    <nav
      aria-label="Settings navigation"
      style={{
        width: 208,
        flexShrink: 0,
        borderRight: '1px solid var(--panel-border)',
        background: 'var(--panel)',
        padding: '20px 12px',
        display: 'flex',
        flexDirection: 'column',
        gap: 2,
      }}
    >
      <div
        style={{
          padding: '0 8px 12px',
          fontSize: 10.5,
          fontWeight: 600,
          letterSpacing: 0.8,
          color: 'var(--text-faint)',
          textTransform: 'uppercase',
        }}
      >
        Settings
      </div>
      {ITEMS.map((item) => {
        const active = pathname?.startsWith(item.href) ?? false
        return (
          <Link
            key={item.id}
            href={item.href}
            aria-current={active ? 'page' : undefined}
            style={{
              display: 'flex',
              alignItems: 'center',
              gap: 9,
              padding: '8px 10px',
              borderRadius: 8,
              fontSize: 12.8,
              fontWeight: active ? 600 : 400,
              background: active ? 'var(--accent-soft)' : 'transparent',
              color: active ? 'var(--brand-teal-700)' : 'var(--text-muted)',
              borderLeft: active
                ? '2px solid var(--accent-500)'
                : '2px solid transparent',
              textDecoration: 'none',
              transition: 'background .15s, color .15s',
              // 44x44 touch target per WCAG 2.2 AA — pad the row so it's
              // easy to hit without blowing up the visual density.
              minHeight: 32,
            }}
          >
            <span
              aria-hidden="true"
              style={{
                width: 15,
                height: 15,
                display: 'inline-flex',
                alignItems: 'center',
                justifyContent: 'center',
                color: active ? 'var(--accent-500)' : 'currentColor',
              }}
            >
              {item.icon}
            </span>
            <span style={{ flex: 1 }}>{item.label}</span>
          </Link>
        )
      })}
    </nav>
  )
}
