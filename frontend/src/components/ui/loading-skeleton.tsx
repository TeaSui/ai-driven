'use client'

import React from 'react'

const pulseKeyframes = `
@keyframes skeleton-pulse {
  0%, 100% { opacity: 0.4; }
  50% { opacity: 0.8; }
}
`

export function Skeleton({ width, height = 16, borderRadius = 6, style }: {
  width?: number | string
  height?: number | string
  borderRadius?: number
  style?: React.CSSProperties
}) {
  return (
    <>
      <style>{pulseKeyframes}</style>
      <div
        aria-hidden
        style={{
          width: width ?? '100%',
          height,
          borderRadius,
          background: 'var(--skeleton, #e5e7eb)',
          animation: 'skeleton-pulse 1.5s ease-in-out infinite',
          ...style,
        }}
      />
    </>
  )
}

export function StatSkeleton() {
  return (
    <div style={{ display: 'flex', flexDirection: 'column', gap: 8, padding: 16 }}>
      <Skeleton width={80} height={12} />
      <Skeleton width={120} height={28} />
      <Skeleton width={60} height={10} />
    </div>
  )
}

export function TableSkeleton({ rows = 5, cols = 4 }: { rows?: number; cols?: number }) {
  return (
    <div style={{ display: 'flex', flexDirection: 'column', gap: 12, padding: 16 }}>
      {/* Header */}
      <div style={{ display: 'flex', gap: 16 }}>
        {Array.from({ length: cols }).map((_, i) => (
          <Skeleton key={i} width={`${100 / cols}%`} height={14} />
        ))}
      </div>
      {/* Rows */}
      {Array.from({ length: rows }).map((_, r) => (
        <div key={r} style={{ display: 'flex', gap: 16 }}>
          {Array.from({ length: cols }).map((_, c) => (
            <Skeleton key={c} width={`${100 / cols}%`} height={18} />
          ))}
        </div>
      ))}
    </div>
  )
}

export function CardSkeleton() {
  return (
    <div style={{
      padding: 20, borderRadius: 10,
      border: '1px solid var(--panel-border)',
      background: 'var(--panel-bg)',
    }}>
      <Skeleton width="60%" height={18} />
      <div style={{ marginTop: 12 }}>
        <Skeleton height={12} />
      </div>
      <div style={{ marginTop: 8 }}>
        <Skeleton width="80%" height={12} />
      </div>
    </div>
  )
}
