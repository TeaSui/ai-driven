'use client'

import React, { useEffect, useRef } from 'react'
import { X } from 'lucide-react'
import { Kbd } from '@/components/ui/kbd'

// ── Types ─────────────────────────────────────────────────────────────────────

interface ShortcutEntry {
  keys: string[]
  description: string
}

interface ShortcutGroup {
  label: string
  shortcuts: ShortcutEntry[]
}

// ── Default shortcut groups for the reviews page ───────────────────────────

export const REVIEW_SHORTCUTS: ShortcutGroup[] = [
  {
    label: 'Navigation',
    shortcuts: [
      { keys: ['j'], description: 'Move to next review' },
      { keys: ['k'], description: 'Move to previous review' },
      { keys: ['o'], description: 'Open current review detail' },
      { keys: ['Enter'], description: 'Open current review detail' },
    ],
  },
  {
    label: 'Actions',
    shortcuts: [
      { keys: ['a'], description: 'Approve current review' },
      { keys: ['r'], description: 'Request changes on current review' },
    ],
  },
  {
    label: 'Help',
    shortcuts: [{ keys: ['?'], description: 'Show / hide this help overlay' }],
  },
]

// ── KeyboardShortcutHelp ─────────────────────────────────────────────────────

interface KeyboardShortcutHelpProps {
  open: boolean
  onClose: () => void
  groups?: ShortcutGroup[]
}

export function KeyboardShortcutHelp({
  open,
  onClose,
  groups = REVIEW_SHORTCUTS,
}: KeyboardShortcutHelpProps) {
  const dialogRef = useRef<HTMLDivElement>(null)
  const closeBtnRef = useRef<HTMLButtonElement>(null)

  // Trap focus inside modal when open
  useEffect(() => {
    if (!open) return
    const previouslyFocused = document.activeElement as HTMLElement | null
    closeBtnRef.current?.focus()

    const handleKeyDown = (e: KeyboardEvent) => {
      if (e.key === 'Escape' || e.key === '?') {
        e.preventDefault()
        onClose()
      }
      // Simple focus trap: keep Tab within the dialog
      if (e.key === 'Tab' && dialogRef.current) {
        const focusable = dialogRef.current.querySelectorAll<HTMLElement>(
          'button, [href], input, [tabindex]:not([tabindex="-1"])',
        )
        const first = focusable[0]
        const last = focusable[focusable.length - 1]
        if (e.shiftKey && document.activeElement === first) {
          e.preventDefault()
          last?.focus()
        } else if (!e.shiftKey && document.activeElement === last) {
          e.preventDefault()
          first?.focus()
        }
      }
    }

    document.addEventListener('keydown', handleKeyDown)
    return () => {
      document.removeEventListener('keydown', handleKeyDown)
      previouslyFocused?.focus()
    }
  }, [open, onClose])

  if (!open) return null

  return (
    // Backdrop
    <div
      role="presentation"
      onClick={onClose}
      style={{
        position: 'fixed',
        inset: 0,
        background: 'rgba(0, 0, 0, 0.5)',
        display: 'flex',
        alignItems: 'center',
        justifyContent: 'center',
        zIndex: 1000,
      }}
    >
      {/* Dialog */}
      <div
        ref={dialogRef}
        role="dialog"
        aria-modal="true"
        aria-labelledby="kbd-help-title"
        onClick={(e) => e.stopPropagation()}
        style={{
          background: 'var(--bg-elev)',
          border: '1px solid var(--panel-border)',
          borderRadius: 14,
          boxShadow: 'var(--shadow-md)',
          padding: '20px 24px',
          width: 380,
          maxWidth: '90vw',
          maxHeight: '80vh',
          overflowY: 'auto',
        }}
      >
        {/* Header */}
        <div
          style={{
            display: 'flex',
            alignItems: 'center',
            marginBottom: 18,
          }}
        >
          <h2
            id="kbd-help-title"
            style={{
              flex: 1,
              margin: 0,
              fontSize: 15,
              fontWeight: 600,
              color: 'var(--text)',
            }}
          >
            Keyboard Shortcuts
          </h2>
          <button
            ref={closeBtnRef}
            type="button"
            onClick={onClose}
            title="Close keyboard shortcut help"
            aria-label="Close keyboard shortcut help"
            style={{
              display: 'inline-flex',
              alignItems: 'center',
              justifyContent: 'center',
              width: 28,
              height: 28,
              padding: 0,
              background: 'transparent',
              border: '1px solid transparent',
              borderRadius: 6,
              cursor: 'pointer',
              color: 'var(--text-muted)',
            }}
          >
            <X size={13} />
          </button>
        </div>

        {/* Shortcut groups */}
        {groups.map((group) => (
          <div key={group.label} style={{ marginBottom: 20 }}>
            <div
              style={{
                fontSize: 10.5,
                fontWeight: 500,
                color: 'var(--text-subtle)',
                textTransform: 'uppercase',
                letterSpacing: 0.7,
                marginBottom: 8,
              }}
            >
              {group.label}
            </div>
            <div style={{ display: 'flex', flexDirection: 'column', gap: 6 }}>
              {group.shortcuts.map((s, i) => (
                <div
                  key={i}
                  style={{
                    display: 'flex',
                    alignItems: 'center',
                    gap: 8,
                  }}
                >
                  <div
                    style={{ display: 'flex', gap: 4, minWidth: 60 }}
                    aria-label={`Keys: ${s.keys.join(', ')}`}
                  >
                    {s.keys.map((k) => (
                      <Kbd key={k}>{k}</Kbd>
                    ))}
                  </div>
                  <span style={{ fontSize: 12.5, color: 'var(--text-muted)' }}>
                    {s.description}
                  </span>
                </div>
              ))}
            </div>
          </div>
        ))}

        {/* Footer hint */}
        <div
          style={{
            marginTop: 4,
            paddingTop: 14,
            borderTop: '1px solid var(--panel-border)',
            fontSize: 11,
            color: 'var(--text-faint)',
            textAlign: 'center',
          }}
        >
          Shortcuts are disabled when a text input is focused.
        </div>
      </div>
    </div>
  )
}
