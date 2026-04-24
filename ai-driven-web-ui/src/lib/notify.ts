// Non-blocking, accessible replacement for window.alert().
// Renders a top-right toast with aria-live that auto-dismisses. No React context
// required — safe to call from anywhere including event handlers.

type Tone = 'info' | 'success' | 'warn' | 'danger'

const TONE_COLOR: Record<Tone, { bg: string; fg: string; bd: string }> = {
  info:    { bg: 'rgba(14,165,233,0.12)',  fg: '#0369a1', bd: '#0ea5e9' },
  success: { bg: 'rgba(34,197,94,0.12)',   fg: '#15803d', bd: '#22c55e' },
  warn:    { bg: 'rgba(234,179,8,0.14)',   fg: '#854d0e', bd: '#eab308' },
  danger:  { bg: 'rgba(239,68,68,0.12)',   fg: '#991b1b', bd: '#ef4444' },
}

let container: HTMLDivElement | null = null

function ensureContainer(): HTMLDivElement | null {
  if (typeof document === 'undefined') return null
  if (container && document.body.contains(container)) return container
  container = document.createElement('div')
  container.setAttribute('aria-live', 'polite')
  container.setAttribute('role', 'status')
  Object.assign(container.style, {
    position: 'fixed',
    top: '16px',
    right: '16px',
    zIndex: '9999',
    display: 'flex',
    flexDirection: 'column',
    gap: '8px',
    pointerEvents: 'none',
  } satisfies Partial<CSSStyleDeclaration>)
  document.body.appendChild(container)
  return container
}

export function notify(message: string, tone: Tone = 'info', durationMs = 4000): void {
  const root = ensureContainer()
  if (!root) return
  const c = TONE_COLOR[tone]
  const el = document.createElement('div')
  el.textContent = message
  Object.assign(el.style, {
    padding: '10px 14px',
    background: c.bg,
    color: c.fg,
    border: `1px solid ${c.bd}`,
    borderRadius: '8px',
    fontSize: '13px',
    maxWidth: '360px',
    boxShadow: '0 8px 24px rgba(0,0,0,0.12)',
    pointerEvents: 'auto',
    transition: 'opacity .2s ease',
  } satisfies Partial<CSSStyleDeclaration>)
  root.appendChild(el)
  setTimeout(() => {
    el.style.opacity = '0'
    setTimeout(() => el.remove(), 220)
  }, durationMs)
}
