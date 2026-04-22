import React from 'react'

interface KbdProps {
  children?: React.ReactNode
}

export function Kbd({ children }: KbdProps) {
  return (
    <kbd
      style={{
        fontFamily: 'Geist Mono, monospace',
        fontSize: 10.5,
        padding: '1px 5px',
        borderRadius: 3,
        background: 'var(--bg-muted)',
        color: 'var(--text-muted)',
        border: '1px solid var(--border)',
        borderBottomWidth: 2,
        minWidth: 16,
        display: 'inline-block',
        textAlign: 'center',
      }}
    >
      {children}
    </kbd>
  )
}
