import { redirect } from 'next/navigation'

/**
 * `/dashboard/settings` has no content of its own — redirect to the first
 * pane. Kept as a server component so the redirect happens before the
 * shell renders, avoiding a flash of the nav pointing at nothing.
 */
export default function SettingsIndexPage(): never {
  redirect('/dashboard/settings/connections')
}
