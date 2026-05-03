'use client'

/**
 * Settings shell (PR 2).
 *
 * Renders the sub-navigation (`SettingsNav`) on the left and the active
 * panel's page on the right. Structured for future sub-pages (API keys,
 * Team, Notifications) without locking the content layout to a single
 * route — `/dashboard/settings/connections` is the only item today.
 */

import type { ReactNode } from 'react'
import { SettingsNav } from '@/components/settings/settings-nav'

export default function SettingsLayout({ children }: { children: ReactNode }) {
  return (
    <main
      style={{
        flex: 1,
        display: 'flex',
        minHeight: 0,
        background: 'var(--bg)',
      }}
    >
      <SettingsNav />
      <div
        style={{
          flex: 1,
          overflow: 'auto',
          padding: '24px 32px',
          minWidth: 0,
        }}
      >
        {children}
      </div>
    </main>
  )
}
