'use client'

import React, { useCallback } from 'react'
import { FileCode, MessageSquare, Check, AlertCircle, ExternalLink, Clock } from 'lucide-react'
import { Pill } from '@/components/ui/pill'
import { Btn } from '@/components/ui/btn'
import type { ReviewDetail } from '@/lib/api'

// ── Helpers ───────────────────────────────────────────────────────────────────

function formatAge(iso?: string): string {
  if (!iso) return ''
  const diff = Date.now() - new Date(iso).getTime()
  const minutes = Math.floor(diff / 60_000)
  if (minutes < 60) return `${minutes}m ago`
  const hours = Math.floor(minutes / 60)
  if (hours < 24) return `${hours}h ago`
  const days = Math.floor(hours / 24)
  return `${days}d ago`
}

type StatusTone = 'default' | 'brand' | 'success' | 'warn' | 'danger' | 'info' | 'ghost' | 'solid'

function statusTone(status: string): StatusTone {
  switch (status) {
    case 'approved':
      return 'success'
    case 'merged':
      return 'brand'
    case 'changes_requested':
      return 'warn'
    case 'in_progress':
      return 'info'
    case 'pending':
    default:
      return 'default'
  }
}

function statusLabel(status: string): string {
  switch (status) {
    case 'changes_requested':
      return 'Changes Requested'
    case 'in_progress':
      return 'In Progress'
    default:
      return status.charAt(0).toUpperCase() + status.slice(1)
  }
}

// ── ReviewCard ────────────────────────────────────────────────────────────────

export interface ReviewCardProps {
  review: ReviewDetail
  /** Whether this card is keyboard-focused (highlighted) */
  focused?: boolean
  onApprove?: (id: string) => void
  onRequestChanges?: (id: string) => void
  onClick?: (id: string) => void
}

export function ReviewCard({
  review,
  focused = false,
  onApprove,
  onRequestChanges,
  onClick,
}: ReviewCardProps) {
  const totalComments = review.files.reduce((acc, f) => acc + f.comments.length, 0)
  const fileCount = review.files.length
  const age = formatAge(review.createdAt)

  const handleKeyDown = useCallback(
    (e: React.KeyboardEvent) => {
      if (e.key === 'Enter' || e.key === ' ') {
        e.preventDefault()
        onClick?.(review.id)
      }
    },
    [onClick, review.id],
  )

  const isCompleted =
    review.status === 'approved' ||
    review.status === 'changes_requested' ||
    review.status === 'merged'

  return (
    <div
      role="article"
      aria-label={`Review: PR #${review.prNumber} — ${review.title}`}
      tabIndex={0}
      onKeyDown={handleKeyDown}
      style={{
        background: 'var(--bg-elev)',
        border: `1px solid ${focused ? 'var(--accent-500)' : 'var(--panel-border)'}`,
        borderRadius: 10,
        padding: '14px 16px',
        cursor: 'pointer',
        outline: focused ? '2px solid var(--accent-500)' : 'none',
        outlineOffset: 2,
        boxShadow: focused ? '0 0 0 3px var(--accent-soft)' : 'var(--shadow-sm)',
        transition: 'border-color .15s, box-shadow .15s',
      }}
      onClick={() => onClick?.(review.id)}
    >
      {/* Top row: PR title + status badge */}
      <div
        style={{
          display: 'flex',
          alignItems: 'flex-start',
          gap: 10,
          marginBottom: 8,
        }}
      >
        <div style={{ flex: 1, minWidth: 0 }}>
          <div
            style={{
              fontSize: 13,
              fontWeight: 600,
              color: 'var(--text)',
              marginBottom: 2,
              whiteSpace: 'nowrap',
              overflow: 'hidden',
              textOverflow: 'ellipsis',
            }}
          >
            PR #{review.prNumber} — {review.title}
          </div>
          <div
            style={{
              fontSize: 11.5,
              color: 'var(--text-subtle)',
              fontFamily: 'Geist Mono, monospace',
            }}
          >
            {review.repo}
          </div>
        </div>
        <Pill tone={statusTone(review.status)} size="xs" dot>
          {statusLabel(review.status)}
        </Pill>
      </div>

      {/* Meta row: files, comments, age */}
      <div
        style={{
          display: 'flex',
          alignItems: 'center',
          gap: 14,
          marginBottom: 12,
          fontSize: 11.5,
          color: 'var(--text-muted)',
        }}
      >
        <span
          style={{ display: 'inline-flex', alignItems: 'center', gap: 4 }}
          aria-label={`${fileCount} file${fileCount !== 1 ? 's' : ''} changed`}
        >
          <FileCode size={11} />
          {fileCount} file{fileCount !== 1 ? 's' : ''}
        </span>
        <span
          style={{ display: 'inline-flex', alignItems: 'center', gap: 4 }}
          aria-label={`${totalComments} comment${totalComments !== 1 ? 's' : ''}`}
        >
          <MessageSquare size={11} />
          {totalComments} comment{totalComments !== 1 ? 's' : ''}
        </span>
        {age && (
          <span
            style={{ display: 'inline-flex', alignItems: 'center', gap: 4 }}
            aria-label={`Created ${age}`}
          >
            <Clock size={11} />
            {age}
          </span>
        )}
        <div style={{ flex: 1 }} />
        {review.prUrl && (
          <a
            href={review.prUrl}
            target="_blank"
            rel="noopener noreferrer"
            aria-label="Open PR in GitHub (opens in new tab)"
            onClick={(e) => e.stopPropagation()}
            style={{
              display: 'inline-flex',
              alignItems: 'center',
              gap: 3,
              color: 'var(--accent-600)',
              textDecoration: 'none',
              fontSize: 11,
            }}
          >
            <ExternalLink size={10} />
            Open PR
          </a>
        )}
      </div>

      {/* Action buttons — only for non-completed reviews */}
      {!isCompleted && (
        <div
          style={{ display: 'flex', gap: 6 }}
          role="group"
          aria-label="Review actions"
          onClick={(e) => e.stopPropagation()}
        >
          <Btn
            size="xs"
            tone="ghost"
            icon={<Check size={10} />}
            onClick={() => onApprove?.(review.id)}
            title={`Approve PR #${review.prNumber}`}
          >
            Approve
          </Btn>
          <Btn
            size="xs"
            tone="bare"
            icon={<AlertCircle size={10} />}
            onClick={() => onRequestChanges?.(review.id)}
            title={`Request changes on PR #${review.prNumber}`}
          >
            Request Changes
          </Btn>
        </div>
      )}
    </div>
  )
}
