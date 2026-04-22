'use client'

import React, { useId } from 'react'

export interface BudgetBarProps {
  costToday: number
  dailyBudget: number
  budgetUsagePercent: number
}

/** Return the CSS color tokens for a given usage percentage. */
function getColorTokens(pct: number): { fill: string; track: string; text: string } {
  if (pct >= 95) {
    return {
      fill: 'var(--status-danger)',
      track: 'var(--status-danger-bg)',
      text: 'var(--status-danger)',
    }
  }
  if (pct >= 80) {
    // orange — interpolate between warn and danger tokens
    return {
      fill: '#d97706',  // amber-600 — sits between warn and danger
      track: 'rgba(217,119,6,0.12)',
      text: '#d97706',
    }
  }
  if (pct >= 60) {
    return {
      fill: 'var(--status-warn)',
      track: 'var(--status-warn-bg)',
      text: 'var(--status-warn)',
    }
  }
  return {
    fill: 'var(--status-success)',
    track: 'var(--status-success-bg)',
    text: 'var(--status-success)',
  }
}

function formatUsd(value: number): string {
  return new Intl.NumberFormat('en-US', {
    style: 'currency',
    currency: 'USD',
    minimumFractionDigits: 2,
    maximumFractionDigits: 2,
  }).format(value)
}

/**
 * Compact budget burn-down bar for dashboard header.
 * Height: 28–32 px. Adapts to dark/light via CSS custom properties.
 *
 * Accessibility:
 * - Rendered as a <meter> element (semantic progress meter)
 * - aria-label describes context
 * - Tooltip visible on keyboard focus as well as hover
 */
export function BudgetBar({ costToday, dailyBudget, budgetUsagePercent }: BudgetBarProps) {
  const tooltipId = useId()

  // Guard: no budget configured
  if (!dailyBudget || dailyBudget <= 0) {
    return (
      <div
        role="status"
        aria-label="Budget: not configured"
        style={{
          display: 'flex',
          alignItems: 'center',
          gap: 8,
          padding: '0 16px',
          height: 30,
          fontSize: 11.5,
          color: 'var(--text-faint)',
          flexShrink: 0,
        }}
      >
        No daily budget configured
      </div>
    )
  }

  const clampedPct = Math.min(100, Math.max(0, budgetUsagePercent))
  const tokens = getColorTokens(clampedPct)
  const tooltipText = `Daily budget: ${formatUsd(dailyBudget)} · Spent today: ${formatUsd(costToday)} · ${clampedPct.toFixed(1)}% used`

  return (
    <div
      style={{
        display: 'flex',
        alignItems: 'center',
        gap: 8,
        padding: '0 16px',
        height: 30,
        minWidth: 0,
        flexShrink: 1,
      }}
    >
      {/* Label */}
      <span
        style={{
          fontSize: 11.5,
          fontWeight: 500,
          color: 'var(--text-subtle)',
          whiteSpace: 'nowrap',
          flexShrink: 0,
        }}
      >
        Budget
      </span>

      {/* Progress track — tooltip wrapper */}
      <div
        role="group"
        aria-describedby={tooltipId}
        style={{
          position: 'relative',
          display: 'flex',
          alignItems: 'center',
          gap: 6,
          minWidth: 0,
          flex: '1 1 120px',
          maxWidth: 220,
        }}
      >
        {/* Accessible meter element */}
        <meter
          value={clampedPct}
          min={0}
          max={100}
          low={60}
          high={80}
          optimum={0}
          aria-label={`Budget usage: ${clampedPct.toFixed(0)}%`}
          aria-describedby={tooltipId}
          tabIndex={0}
          style={{
            /* Reset browser meter appearance */
            WebkitAppearance: 'none',
            appearance: 'none',
            width: '100%',
            height: 6,
            borderRadius: 3,
            border: 'none',
            background: tokens.track,
            cursor: 'default',
            // Focus ring for keyboard nav
            outline: 'none',
          }}
          className="budget-meter"
        />

        {/* Custom fill overlay — drawn on top of the meter via pseudo-sibling */}
        <div
          aria-hidden="true"
          style={{
            position: 'absolute',
            left: 0,
            top: '50%',
            transform: 'translateY(-50%)',
            width: `${clampedPct}%`,
            maxWidth: '100%',
            height: 6,
            borderRadius: 3,
            background: tokens.fill,
            transition: 'width 0.4s ease, background 0.3s ease',
            pointerEvents: 'none',
          }}
        />

        {/* Tooltip (visually hidden until hover/focus) */}
        <div
          id={tooltipId}
          role="tooltip"
          style={{
            position: 'absolute',
            bottom: 'calc(100% + 8px)',
            left: '50%',
            transform: 'translateX(-50%)',
            background: 'var(--text)',
            color: 'var(--bg)',
            fontSize: 11,
            fontWeight: 500,
            padding: '4px 8px',
            borderRadius: 5,
            whiteSpace: 'nowrap',
            pointerEvents: 'none',
            opacity: 0,
            transition: 'opacity 0.15s ease',
            zIndex: 1100,
          }}
          className="budget-tooltip"
        >
          {tooltipText}
          <span
            aria-hidden="true"
            style={{
              position: 'absolute',
              top: '100%',
              left: '50%',
              transform: 'translateX(-50%)',
              borderWidth: '4px 4px 0',
              borderStyle: 'solid',
              borderColor: 'var(--text) transparent transparent',
            }}
          />
        </div>
      </div>

      {/* Text label */}
      <span
        style={{
          fontSize: 11.5,
          fontWeight: 500,
          color: tokens.text,
          whiteSpace: 'nowrap',
          flexShrink: 0,
          fontVariantNumeric: 'tabular-nums',
          overflow: 'hidden',
          textOverflow: 'ellipsis',
          maxWidth: 160,
        }}
        title={tooltipText}
      >
        {formatUsd(costToday)}&thinsp;/&thinsp;{formatUsd(dailyBudget)}&ensp;({clampedPct.toFixed(0)}%)
      </span>

      {/* Inline CSS for tooltip show/hide and meter fill */}
      <style>{`
        .budget-meter::-webkit-meter-bar {
          background: transparent;
          border: none;
        }
        .budget-meter::-webkit-meter-optimum-value,
        .budget-meter::-webkit-meter-suboptimum-value,
        .budget-meter::-webkit-meter-even-less-good-value {
          background: transparent;
        }
        [role="group"]:hover .budget-tooltip,
        .budget-meter:focus ~ .budget-tooltip,
        [role="group"]:focus-within .budget-tooltip {
          opacity: 1;
        }
      `}</style>
    </div>
  )
}
