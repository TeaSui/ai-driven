// Auth utilities — API key stored in a cookie named "api_key"

const COOKIE_NAME = 'api_key'

export function getApiKey(): string | null {
  if (typeof document === 'undefined') return null
  const match = document.cookie.match(new RegExp(`(?:^|; )${COOKIE_NAME}=([^;]*)`))
  return match ? decodeURIComponent(match[1]) : null
}

export function setApiKey(key: string): void {
  // HttpOnly is not possible from JS, but SameSite=Strict + Secure in prod
  const secure = window.location.protocol === 'https:' ? '; Secure' : ''
  document.cookie = `${COOKIE_NAME}=${encodeURIComponent(key)}; path=/; SameSite=Strict; max-age=604800${secure}`
}

export function clearApiKey(): void {
  document.cookie = `${COOKIE_NAME}=; path=/; max-age=0`
}

/** Fetch wrapper that injects the X-API-Key header. */
export function authFetch(url: string, init?: RequestInit): Promise<Response> {
  const key = getApiKey()
  const headers = new Headers(init?.headers)
  if (key) headers.set('X-API-Key', key)
  return fetch(url, { ...init, headers })
}
