import React from 'react'

interface AvatarProps {
  name?: string
  size?: number
  color?: string
  bot?: boolean
  style?: React.CSSProperties
}

const PALETTE = ['#86B8BE', '#C79A6B', '#9E8AB0', '#7BA582', '#C98484', '#6B8FA8', '#B5A06B']

function stableColor(name: string): string {
  const idx = [...name].reduce((a, c) => a + c.charCodeAt(0), 0) % PALETTE.length
  return PALETTE[idx]
}

export function Avatar({ name, size = 24, color, bot, style }: AvatarProps) {
  const initials = name
    ? name.split(/\s+/).map(w => w[0]).slice(0, 2).join('').toUpperCase()
    : '?'
  const bg = color || (name ? stableColor(name) : PALETTE[0])

  if (bot) {
    return (
      <div
        aria-label="AI agent"
        style={{
          width: size,
          height: size,
          borderRadius: size / 4,
          background: 'var(--brand-ink)',
          color: 'var(--brand-teal-400)',
          display: 'inline-flex',
          alignItems: 'center',
          justifyContent: 'center',
          fontSize: size * 0.42,
          fontWeight: 700,
          boxShadow: 'inset 0 0 0 1px var(--brand-teal-700)',
          fontFamily: 'Geist Mono, monospace',
          flexShrink: 0,
          ...style,
        }}
      >
        ✦
      </div>
    )
  }

  return (
    <div
      aria-label={name ?? 'User avatar'}
      style={{
        width: size,
        height: size,
        borderRadius: '50%',
        background: bg,
        color: '#fff',
        display: 'inline-flex',
        alignItems: 'center',
        justifyContent: 'center',
        fontSize: size * 0.38,
        fontWeight: 600,
        letterSpacing: 0.2,
        flexShrink: 0,
        ...style,
      }}
    >
      {initials}
    </div>
  )
}
