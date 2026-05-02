import React from 'react'

interface SpinnerProps {
  size?: number
  color?: string
}

export function Spinner({ size = 14, color }: SpinnerProps) {
  return (
    <svg
      width={size}
      height={size}
      viewBox="0 0 24 24"
      style={{ animation: 'gtx-spin .9s linear infinite', display: 'inline-block' }}
      aria-label="Loading"
    >
      <circle
        cx="12" cy="12" r="9"
        stroke="currentColor" strokeWidth="2.2" fill="none" strokeOpacity=".25"
        style={{ color: color || 'currentColor' }}
      />
      <path
        d="M21 12 A9 9 0 0 0 12 3"
        stroke={color || 'currentColor'} strokeWidth="2.2" fill="none" strokeLinecap="round"
      />
    </svg>
  )
}
