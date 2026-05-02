'use client'

import { useTheme } from '@/hooks/use-theme'

function IconSun({ size = 14 }: { size?: number }) {
  return (
    <svg
      width={size}
      height={size}
      viewBox="0 0 16 16"
      fill="none"
      stroke="currentColor"
      strokeWidth={1.5}
      strokeLinecap="round"
      strokeLinejoin="round"
      aria-hidden="true"
      style={{ display: 'inline-block', flexShrink: 0 }}
    >
      <circle cx="8" cy="8" r="3" />
      <path d="M8 1.5V3M8 13v1.5M1.5 8H3M13 8h1.5M3.4 3.4l1.1 1.1M11.5 11.5l1.1 1.1M11.5 4.5l-1.1 1.1M4.6 11.5l-1.1 1.1" />
    </svg>
  )
}

function IconMoon({ size = 14 }: { size?: number }) {
  return (
    <svg
      width={size}
      height={size}
      viewBox="0 0 16 16"
      fill="none"
      stroke="currentColor"
      strokeWidth={1.5}
      strokeLinecap="round"
      strokeLinejoin="round"
      aria-hidden="true"
      style={{ display: 'inline-block', flexShrink: 0 }}
    >
      <path d="M13.5 9.5A6 6 0 016.5 2.5a6 6 0 000 11 6 6 0 007-4z" />
    </svg>
  )
}

interface ThemeToggleProps {
  /** When true, render only the icon with no label text */
  iconOnly?: boolean
}

export function ThemeToggle({ iconOnly = false }: ThemeToggleProps) {
  const { theme, toggleTheme } = useTheme()
  const isDark = theme === 'dark'
  const label = isDark ? 'Switch to light mode' : 'Switch to dark mode'

  return (
    <button
      type="button"
      onClick={toggleTheme}
      aria-label={label}
      title={label}
      style={{
        display: 'inline-flex',
        alignItems: 'center',
        justifyContent: 'center',
        gap: iconOnly ? 0 : 6,
        // 44x44px minimum touch target (WCAG 2.2 AA)
        minWidth: 44,
        minHeight: 44,
        width: iconOnly ? 44 : undefined,
        padding: iconOnly ? 0 : '0 10px',
        border: 'none',
        background: 'transparent',
        color: 'var(--text-muted)',
        borderRadius: 8,
        cursor: 'pointer',
        fontSize: 12,
        fontFamily: 'inherit',
        transition: 'color .15s, background .15s',
      }}
      onMouseEnter={e => {
        ;(e.currentTarget as HTMLButtonElement).style.background = 'var(--bg-hover)'
        ;(e.currentTarget as HTMLButtonElement).style.color = 'var(--text)'
      }}
      onMouseLeave={e => {
        ;(e.currentTarget as HTMLButtonElement).style.background = 'transparent'
        ;(e.currentTarget as HTMLButtonElement).style.color = 'var(--text-muted)'
      }}
    >
      {isDark ? <IconSun size={14} /> : <IconMoon size={14} />}
      {!iconOnly && <span>{isDark ? 'Light' : 'Dark'}</span>}
    </button>
  )
}
