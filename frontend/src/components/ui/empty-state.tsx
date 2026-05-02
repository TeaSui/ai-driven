'use client'

import React from 'react'

interface EmptyStateProps {
  icon?: React.ReactNode
  title: string
  description: string
  action?: { label: string; onClick: () => void }
}

export function EmptyState({ icon, title, description, action }: EmptyStateProps) {
  return (
    <div
      role="status"
      style={{
        display: 'flex',
        flexDirection: 'column',
        alignItems: 'center',
        justifyContent: 'center',
        padding: '48px 24px',
        textAlign: 'center',
        color: 'var(--text-muted)',
      }}
    >
      {icon && <div style={{ fontSize: 40, marginBottom: 16, opacity: 0.5 }}>{icon}</div>}
      <h3 style={{ margin: 0, fontSize: 16, fontWeight: 600, color: 'var(--text)' }}>{title}</h3>
      <p style={{ margin: '8px 0 0', fontSize: 14, maxWidth: 360, lineHeight: 1.5 }}>{description}</p>
      {action && (
        <button
          onClick={action.onClick}
          style={{
            marginTop: 16,
            padding: '8px 16px',
            fontSize: 13,
            fontWeight: 500,
            background: 'var(--accent-500, #6366f1)',
            color: '#fff',
            border: 'none',
            borderRadius: 6,
            cursor: 'pointer',
            minHeight: 36,
          }}
        >
          {action.label}
        </button>
      )}
    </div>
  )
}
