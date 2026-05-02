'use client'

import React, { useState } from 'react'

type BtnTone = 'primary' | 'brand' | 'ghost' | 'subtle' | 'bare' | 'danger'
type BtnSize = 'xs' | 'sm' | 'md' | 'lg'

interface BtnProps {
  tone?: BtnTone
  size?: BtnSize
  children?: React.ReactNode
  icon?: React.ReactNode
  iconRight?: React.ReactNode
  onClick?: () => void
  style?: React.CSSProperties
  active?: boolean
  disabled?: boolean
  /** Visible tooltip text (also used as accessible name when no children) */
  title?: string
  /** Explicit accessible name — use for icon-only buttons */
  'aria-label'?: string
  type?: 'button' | 'submit' | 'reset'
}

const TONES: Record<BtnTone, { bg: string; fg: string; bd: string; hover: string; shadow?: string }> = {
  primary: { bg: 'var(--text)',            fg: 'var(--bg)',            bd: 'var(--text)',          hover: 'var(--brand-teal-800)' },
  brand:   { bg: 'var(--accent-500)',      fg: '#fff',                 bd: 'var(--accent-600)',    hover: 'var(--accent-600)', shadow: '0 2px 8px rgba(95,160,168,0.3)' },
  ghost:   { bg: 'transparent',           fg: 'var(--text)',          bd: 'var(--border)',        hover: 'var(--bg-hover)' },
  subtle:  { bg: 'var(--bg-muted)',        fg: 'var(--text)',          bd: 'transparent',          hover: 'var(--bg-hover)' },
  bare:    { bg: 'transparent',           fg: 'var(--text-muted)',    bd: 'transparent',          hover: 'var(--bg-hover)' },
  danger:  { bg: 'var(--status-danger-bg)', fg: 'var(--status-danger)', bd: 'transparent',        hover: 'var(--status-danger-bg)' },
}

const SIZES: Record<BtnSize, { fs: number; h: number; px: number; icon: number }> = {
  xs: { fs: 11.5, h: 22, px: 8,  icon: 11 },
  sm: { fs: 12.5, h: 28, px: 10, icon: 13 },
  md: { fs: 13,   h: 32, px: 12, icon: 14 },
  lg: { fs: 14,   h: 38, px: 16, icon: 16 },
}

export function Btn({
  tone = 'ghost',
  size = 'md',
  children,
  icon,
  iconRight,
  onClick,
  style,
  active,
  disabled,
  title,
  'aria-label': ariaLabel,
  type = 'button',
}: BtnProps) {
  const [hover, setHover] = useState(false)
  const t = TONES[tone]
  const s = SIZES[size]

  return (
    <button
      type={type}
      title={title}
      aria-label={ariaLabel ?? (children == null && title ? title : undefined)}
      disabled={disabled}
      onMouseEnter={() => setHover(true)}
      onMouseLeave={() => setHover(false)}
      onClick={onClick}
      style={{
        display: 'inline-flex',
        alignItems: 'center',
        justifyContent: 'center',
        gap: 6,
        height: s.h,
        padding: `0 ${s.px}px`,
        fontSize: s.fs,
        fontWeight: 500,
        background: active ? t.hover : (hover ? t.hover : t.bg),
        color: t.fg,
        border: `1px solid ${t.bd}`,
        borderRadius: 8,
        cursor: disabled ? 'not-allowed' : 'pointer',
        opacity: disabled ? 0.5 : 1,
        fontFamily: 'inherit',
        whiteSpace: 'nowrap',
        transition: 'background .15s, border-color .15s, box-shadow .15s, transform .15s',
        boxShadow: t.shadow && !disabled ? (hover ? t.shadow.replace('0.3', '0.45') : t.shadow) : undefined,
        transform: hover && t.shadow && !disabled ? 'translateY(-0.5px)' : undefined,
        ...style,
      }}
    >
      {icon && (
        <span style={{ display: 'inline-flex', width: s.icon, height: s.icon }}>
          {icon}
        </span>
      )}
      {children}
      {iconRight && (
        <span style={{ display: 'inline-flex', width: s.icon, height: s.icon }}>
          {iconRight}
        </span>
      )}
    </button>
  )
}
