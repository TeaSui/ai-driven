'use client'

import { useEffect, useState } from 'react'
import useSWR from 'swr'
import { Sidebar } from '@/components/layout/sidebar'
import { BudgetBar } from '@/components/ui/budget-bar'
import { getDashboardSummary, type DashboardSummary } from '@/lib/api'

/** SWR fetcher wrapping the typed API helper. */
const fetchDashboard = (): Promise<DashboardSummary> => getDashboardSummary()

export default function DashboardLayout({
  children,
}: {
  children: React.ReactNode
}) {
  const [debug, setDebug] = useState(false)

  useEffect(() => {
    fetch('/health')
      .then(r => r.json())
      .then(data => setDebug(!!data.debug))
      .catch(() => {})
  }, [])

  const { data: summary } = useSWR<DashboardSummary>(
    'dashboard-summary',
    fetchDashboard,
    {
      refreshInterval: 60_000,   // re-fetch every 60 s to keep budget live
      revalidateOnFocus: false,  // avoid flicker when switching tabs
      shouldRetryOnError: false, // budget bar is non-critical; don't spam on error
    }
  )

  const hasBudgetData =
    summary !== undefined &&
    typeof summary.costToday === 'number' &&
    typeof summary.dailyBudget === 'number' &&
    typeof summary.budgetUsagePercent === 'number'

  return (
    <>
      <Sidebar />
      {/* id="main-content" is the skip-link target — WCAG 2.4.1 Bypass Blocks */}
      <div
        id="main-content"
        style={{
          flex: 1,
          display: 'flex',
          flexDirection: 'column',
          minWidth: 0,
          background: 'var(--bg)',
          overflow: 'hidden',
        }}
      >
        {/* Debug mode banner — must stay on top */}
        {debug && (
          <div
            role="alert"
            style={{
              background: '#dc2626',
              color: '#fff',
              padding: '8px 16px',
              fontSize: 13,
              fontWeight: 600,
              textAlign: 'center',
              position: 'sticky',
              top: 0,
              zIndex: 1000,
            }}
          >
            Debug mode active — authentication is bypassed. Do not use in production.
          </div>
        )}

        {/* Budget burn-down header bar */}
        {hasBudgetData && (
          <div
            aria-label="Daily budget usage"
            style={{
              display: 'flex',
              alignItems: 'center',
              justifyContent: 'flex-end',
              height: 30,
              borderBottom: '1px solid var(--divider)',
              background: 'var(--panel)',
              position: 'sticky',
              top: debug ? 37 : 0,  // sit below debug banner when it's visible
              zIndex: 999,
              flexShrink: 0,
              overflow: 'hidden',
            }}
          >
            <BudgetBar
              costToday={summary!.costToday}
              dailyBudget={summary!.dailyBudget}
              budgetUsagePercent={summary!.budgetUsagePercent}
            />
          </div>
        )}

        {children}
      </div>
    </>
  )
}
