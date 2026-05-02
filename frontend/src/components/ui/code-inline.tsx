import React from 'react'

interface CodeInlineProps {
  children?: React.ReactNode
  style?: React.CSSProperties
}

export function CodeInline({ children, style }: CodeInlineProps) {
  return (
    <code
      className="gtx-mono"
      style={{
        fontSize: 'var(--gtx-fs-sm)',
        background: 'var(--code-bg)',
        color: 'var(--code-text)',
        padding: '1px 5px',
        borderRadius: 4,
        border: '1px solid var(--panel-border)',
        ...style,
      }}
    >
      {children}
    </code>
  )
}
