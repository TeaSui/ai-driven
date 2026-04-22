import React from 'react'

export type StatusTone = 'success' | 'warn' | 'danger' | 'info' | 'idle' | 'default'

interface StatusDotProps {
  tone?: StatusTone
  size?: number
  pulse?: boolean
}

const COLORS: Record<StatusTone, string> = {
  success: 'var(--status-success)',
  warn:    'var(--status-warn)',
  danger:  'var(--status-danger)',
  info:    'var(--status-info)',
  idle:    'var(--text-faint)',
  default: 'var(--text-faint)',
}

export function StatusDot({ tone = 'success', size = 8, pulse }: StatusDotProps) {
  const c = COLORS[tone]
  return (
    <span
      style={{ position: 'relative', display: 'inline-block', width: size, height: size }}
      aria-label={tone}
    >
      <span style={{ position: 'absolute', inset: 0, background: c, borderRadius: '50%' }} />
      {pulse && (
        <span style={{
          position: 'absolute', inset: 0,
          background: c, borderRadius: '50%',
          animation: 'gtx-ring 1.6s ease-out infinite',
        }} />
      )}
    </span>
  )
}
