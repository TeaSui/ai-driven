import React from 'react'

interface CardProps {
  children?: React.ReactNode
  style?: React.CSSProperties
  padded?: boolean
  elevated?: boolean
}

export function Card({ children, style, padded = true, elevated }: CardProps) {
  return (
    <div
      style={{
        background: 'var(--bg-elev)',
        border: '1px solid var(--panel-border)',
        borderRadius: 12,
        boxShadow: elevated ? 'var(--shadow-md)' : 'var(--shadow-sm)',
        padding: padded ? 16 : 0,
        ...style,
      }}
    >
      {children}
    </div>
  )
}
