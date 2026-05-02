'use client'

import { useEffect, useState } from 'react'
import { useRouter } from 'next/navigation'
import { getRepos } from '@/lib/api'

const ONBOARDED_KEY = 'onboarded'

export function getOnboardedFlag(): boolean {
  if (typeof localStorage === 'undefined') return false
  return localStorage.getItem(ONBOARDED_KEY) === 'true'
}

export function setOnboardedFlag(): void {
  if (typeof localStorage !== 'undefined') {
    localStorage.setItem(ONBOARDED_KEY, 'true')
  }
}

/**
 * Checks whether the current user needs to be shown the onboarding wizard.
 * Redirects to /dashboard/onboarding if:
 *   - The `onboarded` localStorage flag is not set AND
 *   - The repos list from GET /api/admin/repos is empty
 *
 * Returns `{ checked: boolean }` — `checked` becomes true once the async
 * check has completed so callers can avoid rendering stale UI.
 */
export function useOnboardingCheck() {
  const router = useRouter()
  const [checked, setChecked] = useState(false)

  useEffect(() => {
    // Already onboarded — skip the API call
    if (getOnboardedFlag()) {
      setChecked(true)
      return
    }

    let cancelled = false
    getRepos()
      .then(repos => {
        if (cancelled) return
        if (repos.length === 0) {
          router.replace('/dashboard/onboarding')
        } else {
          // Has repos — mark as onboarded so we never check again
          setOnboardedFlag()
        }
        // Always set checked so the page renders (even if redirecting,
        // the current page should still show content briefly).
        setChecked(true)
      })
      .catch(() => {
        // On error: do not redirect — let the dashboard show normally
        if (!cancelled) setChecked(true)
      })

    return () => {
      cancelled = true
    }
  }, [router])

  return { checked }
}
