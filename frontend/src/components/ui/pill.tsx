import React from 'react'

type PillTone = 'default' | 'brand' | 'success' | 'warn' | 'danger' | 'info' | 'ghost' | 'solid'
type PillSize = 'xs' | 'sm' | 'md'

interface PillProps {
  tone?: PillTone
  size?: PillSize
  children?: React.ReactNode
  style?: React.CSSProperties
  icon?: React.ReactNode
  dot?: boolean
}

const TONES: Record<PillTone, { bg: string; fg: string; bd: string }> = {
  default: { bg: 'var(--bg-muted)',          fg: 'var(--text-muted)',       bd: 'var(--border)' },
  brand:   { bg: 'var(--accent-soft)',        fg: 'var(--brand-teal-700)',   bd: 'var(--brand-teal-200)' },
  success: { bg: 'var(--status-success-bg)', fg: 'var(--status-success)',   bd: 'transparent' },
  warn:    { bg: 'var(--status-warn-bg)',     fg: 'var(--status-warn)',      bd: 'transparent' },
  danger:  { bg: 'var(--status-danger-bg)',  fg: 'var(--status-danger)',    bd: 'transparent' },
  info:    { bg: 'var(--status-info-bg)',     fg: 'var(--status-info)',      bd: 'transparent' },
  ghost:   { bg: 'transparent',              fg: 'var(--text-muted)',       bd: 'var(--border)' },
  solid:   { bg: 'var(--text)',              fg: 'var(--bg)',               bd: 'transparent' },
}

const SIZES: Record<PillSize, { fs: number; pd: string; h: number }> = {
  xs: { fs: 10.5, pd: '2px 6px',  h: 18 },
  sm: { fs: 11,   pd: '2px 8px',  h: 20 },
  md: { fs: 12.5, pd: '4px 10px', h: 24 },
}

export function Pill({ tone = 'default', size = 'sm', children, style, icon, dot }: PillProps) {
  const t = TONES[tone] ?? TONES.default
  const sz = SIZES[size]

  return (
    <span
      style={{
        display: 'inline-flex', alignItems: 'center', gap: 5,
        fontSize: sz.fs, fontWeight: 500, height: sz.h, padding: sz.pd,
        background: t.bg, color: t.fg, border: `1px solid ${t.bd}`,
        borderRadius: 999, letterSpacing: 0.1, whiteSpace: 'nowrap',
        lineHeight: 1,
        ...style,
      }}
    >
      {dot && (
        <span
          aria-hidden="true"
          style={{
            width: 6, height: 6, borderRadius: 3,
            background: t.fg, display: 'inline-block',
          }}
        />
      )}
      {icon}
      {children}
    </span>
  )
}
