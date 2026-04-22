import { cookies } from 'next/headers'
import { redirect } from 'next/navigation'

export default async function RootPage() {
  const cookieStore = await cookies()
  const hasKey = cookieStore.has('api_key')
  redirect(hasKey ? '/dashboard' : '/login')
}
