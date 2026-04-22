import React from 'react'

interface BrandMarkProps {
  size?: number
  stroke?: string
}

export function BrandMark({ size = 22, stroke }: BrandMarkProps) {
  const c = stroke || 'var(--accent-400)'
  return (
    <svg
      width={size}
      height={size}
      viewBox="0 0 24 24"
      style={{ display: 'block' }}
      aria-hidden="true"
    >
      <path d="M4 4 L12 12 L20 4"  stroke={c} strokeWidth="3" fill="none" strokeLinecap="square"/>
      <path d="M4 20 L12 12 L20 20" stroke={c} strokeWidth="3" fill="none" strokeLinecap="square"/>
      <path d="M12 12 L12 17"       stroke={c} strokeWidth="3" fill="none" strokeLinecap="square"/>
    </svg>
  )
}

interface WordmarkProps {
  compact?: boolean
}

export function Wordmark({ compact }: WordmarkProps) {
  return (
    <div style={{ display: 'inline-flex', alignItems: 'center', gap: 8 }}>
      <BrandMark size={18} />
      {!compact && (
        <span
          style={{
            fontSize: 14,
            fontWeight: 600,
            letterSpacing: -0.3,
            color: 'var(--text)',
          }}
        >
          GoTyme<span style={{ color: 'var(--accent-500)' }}>·X</span> AI
        </span>
      )}
    </div>
  )
}
