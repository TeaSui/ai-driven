'use client'

import { useState, useEffect, useRef, useCallback } from 'react'
import { useRouter } from 'next/navigation'
import useSWR, { mutate as swrMutate } from 'swr'
import {
  FileCode,
  Sparkles,
  Check,
  CircleDot,
  Activity,
  ExternalLink,
} from 'lucide-react'
import { Pill } from '@/components/ui/pill'
import { Btn } from '@/components/ui/btn'
import { Avatar } from '@/components/ui/avatar'
import { Spinner } from '@/components/ui/spinner'
import { BrandMark } from '@/components/ui/brand-mark'
import { ReviewCard } from '@/components/reviews/review-card'
import { KeyboardShortcutHelp } from '@/components/ui/keyboard-shortcut-help'
import {
  getReviews,
  approveReview,
  requestChanges,
  type ReviewDetail,
} from '@/lib/api'

// ── Types ─────────────────────────────────────────────────────────────────────

type Severity = 'warn' | 'info' | 'nit'

interface ReviewComment {
  line: number
  severity: Severity
  title: string
  body: string
  confidence: number
  category: string
  suggestion?: string
}

/** Top-level tab for the queue view */
type QueueTab = 'all' | 'awaiting' | 'completed'

/** Tab for the detail panel */
type ReviewTab = 'conversation' | 'review' | 'commits' | 'checks'

// ── Helpers ───────────────────────────────────────────────────────────────────

function toLocalSeverity(severity: string): Severity {
  if (severity === 'warn' || severity === 'warning') return 'warn'
  if (severity === 'info' || severity === 'suggestion') return 'info'
  return 'nit'
}

function mapApiComments(
  apiFile: ReviewDetail['files'][number]
): { n: number; t: string; add?: boolean; comment?: ReviewComment }[] {
  return apiFile.comments.map((c) => ({
    n: c.line,
    t: `// line ${c.line}`,
    add: true,
    comment: {
      line: c.line,
      severity: toLocalSeverity(c.severity),
      title: c.body.split('.')[0] ?? c.body,
      body: c.body,
      confidence: parseInt(c.confidence, 10) || 80,
      category: c.category,
      suggestion: c.suggestion
        ? `${c.suggestion.before}\n---\n${c.suggestion.after}`
        : undefined,
    },
  }))
}

/** Returns true when status is awaiting action */
function isAwaiting(status: string): boolean {
  return status === 'pending' || status === 'in_progress'
}

/** Returns true when status is completed */
function isCompleted(status: string): boolean {
  return (
    status === 'approved' ||
    status === 'changes_requested' ||
    status === 'merged'
  )
}

/** Guard: disable keyboard shortcuts when a text input/select is focused */
function isInputFocused(): boolean {
  const el = document.activeElement
  if (!el) return false
  const tag = el.tagName.toLowerCase()
  return (
    tag === 'input' ||
    tag === 'textarea' ||
    tag === 'select' ||
    (el as HTMLElement).isContentEditable
  )
}

// ── SummaryChip ───────────────────────────────────────────────────────────────

function SummaryChip({
  count,
  label,
  tone,
}: {
  count: number
  label: string
  tone: 'warn' | 'info' | 'default'
}) {
  const c =
    tone === 'warn'
      ? 'var(--status-warn)'
      : tone === 'info'
        ? 'var(--accent-600)'
        : 'var(--text-muted)'
  return (
    <div
      style={{
        textAlign: 'center',
        padding: '6px 14px',
        borderLeft: '1px solid var(--panel-border)',
      }}
    >
      <div
        style={{
          fontSize: 18,
          fontWeight: 600,
          color: c,
          fontVariantNumeric: 'tabular-nums',
        }}
      >
        {count}
      </div>
      <div
        style={{
          fontSize: 10.5,
          color: 'var(--text-subtle)',
          textTransform: 'uppercase',
          letterSpacing: 0.6,
        }}
      >
        {label}
      </div>
    </div>
  )
}

// ── ReviewSummaryBar ──────────────────────────────────────────────────────────

function ReviewSummaryBar({ review }: { review?: ReviewDetail }) {
  const allComments = review ? review.files.flatMap((f) => f.comments) : null
  const totalComments = allComments ? allComments.length : 3
  const fileCount = review ? review.files.length : 7
  const safetyCount = allComments
    ? allComments.filter((c) => c.category === 'safety').length
    : 1
  const correctnessCount = allComments
    ? allComments.filter((c) => c.category === 'correctness').length
    : 1
  const styleCount = allComments
    ? allComments.filter((c) => c.category !== 'safety' && c.category !== 'correctness').length
    : 1

  return (
    <div
      style={{
        padding: '14px 20px',
        borderBottom: '1px solid var(--panel-border)',
        background: 'var(--panel)',
        display: 'flex',
        alignItems: 'center',
        gap: 14,
      }}
    >
      <Avatar bot size={32} />
      <div style={{ flex: 1 }}>
        <div
          style={{
            fontSize: 13,
            fontWeight: 500,
            color: 'var(--text)',
            marginBottom: 2,
          }}
        >
          AI review complete{' '}
          <span style={{ color: 'var(--text-muted)', fontWeight: 400 }}>
            {totalComments} issue{totalComments !== 1 ? 's' : ''} flagged, 0 blocking
          </span>
        </div>
        <div style={{ fontSize: 11.5, color: 'var(--text-subtle)' }}>
          Analyzed {fileCount} file{fileCount !== 1 ? 's' : ''} from{' '}
          <code
            style={{
              fontFamily: 'Geist Mono, monospace',
              fontSize: 11,
              background: 'var(--bg-muted)',
              padding: '1px 4px',
              borderRadius: 3,
              border: '1px solid var(--border)',
            }}
          >
            .gotyme/review.yml
          </code>{' '}
          took 12s
        </div>
      </div>
      <SummaryChip count={safetyCount} label="Safety" tone="warn" />
      <SummaryChip count={correctnessCount} label="Correctness" tone="info" />
      <SummaryChip count={styleCount} label="Style" tone="default" />
    </div>
  )
}

// ── InlineComment ─────────────────────────────────────────────────────────────

function InlineComment({ c }: { c: ReviewComment }) {
  const tone =
    c.severity === 'warn' ? 'warn' : c.severity === 'info' ? 'info' : 'default'
  const color =
    c.severity === 'warn'
      ? 'var(--status-warn)'
      : c.severity === 'info'
        ? 'var(--accent-600)'
        : 'var(--text-muted)'

  return (
    <div
      style={{
        padding: '12px 14px 12px 58px',
        background: 'var(--bg-elev)',
        borderTop: '1px solid var(--panel-border)',
        borderBottom: '1px solid var(--panel-border)',
        boxShadow: 'var(--shadow-sm)',
        fontFamily: 'Geist, sans-serif',
      }}
    >
      <div style={{ display: 'flex', alignItems: 'flex-start', gap: 10 }}>
        <Avatar bot size={24} />
        <div style={{ flex: 1, minWidth: 0 }}>
          <div
            style={{
              display: 'flex',
              alignItems: 'center',
              gap: 8,
              marginBottom: 4,
            }}
          >
            <span
              style={{
                fontSize: 12.5,
                fontWeight: 500,
                color: 'var(--text)',
              }}
            >
              GoTyme-X AI
            </span>
            <Pill tone={tone} size="xs" dot>
              {c.severity.toUpperCase()}
            </Pill>
            <Pill tone="default" size="xs">
              {c.category}
            </Pill>
            <span style={{ fontSize: 10.5, color: 'var(--text-faint)' }}>
              confidence {c.confidence}%
            </span>
          </div>
          <div
            style={{
              fontSize: 13,
              color: 'var(--text)',
              fontWeight: 500,
              marginBottom: 4,
            }}
          >
            {c.title}
          </div>
          <div
            style={{
              fontSize: 12.5,
              color: 'var(--text-muted)',
              lineHeight: 1.5,
            }}
          >
            {c.body}
          </div>
          {c.suggestion && (
            <div
              style={{
                marginTop: 8,
                background: 'var(--code-bg)',
                border: '1px solid var(--panel-border)',
                borderRadius: 6,
                overflow: 'hidden',
              }}
            >
              <div
                style={{
                  padding: '6px 10px',
                  borderBottom: '1px solid var(--panel-border)',
                  fontSize: 11,
                  color: 'var(--text-subtle)',
                  display: 'flex',
                  alignItems: 'center',
                  gap: 6,
                }}
              >
                <Sparkles size={10} color={color} /> Suggested change
              </div>
              <pre
                className="gtx-mono"
                style={{
                  margin: 0,
                  padding: 10,
                  fontSize: 11.5,
                  color: 'var(--code-text)',
                  overflow: 'auto',
                }}
              >
                {c.suggestion}
              </pre>
            </div>
          )}
          <div style={{ display: 'flex', gap: 6, marginTop: 8 }}>
            <Btn size="xs" tone="ghost" icon={<Check size={10} />}>
              Apply
            </Btn>
            <Btn size="xs" tone="bare">
              Reply
            </Btn>
            <Btn size="xs" tone="bare">
              Dismiss
            </Btn>
            <div style={{ flex: 1 }} />
            <Btn size="xs" tone="bare">
              4
            </Btn>
          </div>
        </div>
      </div>
    </div>
  )
}

// ── FileReviewBlock ───────────────────────────────────────────────────────────

function FileReviewBlock({ review }: { review?: ReviewDetail }) {
  const apiFile = review?.files[0]
  if (!apiFile) {
    return (
      <div
        style={{
          background: 'var(--bg-elev)',
          border: '1px dashed var(--panel-border)',
          borderRadius: 12,
          padding: '40px 20px',
          textAlign: 'center',
          color: 'var(--text-faint)',
          fontSize: 13,
        }}
      >
        Select a review from the queue to see the PR diff and AI comments. Review
        file contents and comments are pulled from the live GitHub API — nothing
        is rendered until a real review is open.
      </div>
    )
  }
  const filePath = apiFile.path
  const additions = apiFile.additions
  const deletions = apiFile.deletions
  const diffLines = mapApiComments(apiFile)
  const commentCount = apiFile.comments.length

  return (
    <div
      style={{
        background: 'var(--bg-elev)',
        border: '1px solid var(--panel-border)',
        borderRadius: 12,
        overflow: 'hidden',
        boxShadow: 'var(--shadow-sm)',
      }}
    >
      <div
        style={{
          padding: '10px 14px',
          borderBottom: '1px solid var(--panel-border)',
          display: 'flex',
          alignItems: 'center',
          gap: 8,
          background: 'var(--bg-muted)',
        }}
      >
        <FileCode size={13} color="var(--text-muted)" />
        <span className="gtx-mono" style={{ fontSize: 12, color: 'var(--text)' }}>
          {filePath}
        </span>
        <Pill tone="success" size="xs">
          +{additions}
        </Pill>
        <Pill tone="danger" size="xs">
          -{deletions}
        </Pill>
        <div style={{ flex: 1 }} />
        <span style={{ fontSize: 11, color: 'var(--text-subtle)' }}>
          {commentCount} AI comment{commentCount !== 1 ? 's' : ''}
        </span>
      </div>
      <div
        style={{
          fontFamily: 'Geist Mono, monospace',
          fontSize: 12.5,
          lineHeight: 1.65,
          background: 'var(--code-bg)',
        }}
      >
        {diffLines.map((l, i) => (
          <span key={i} style={{ display: 'contents' }}>
            <div
              style={{
                display: 'flex',
                background: l.add ? 'var(--diff-add-bg)' : 'transparent',
                borderLeft: `2px solid ${l.add ? 'var(--diff-add-line)' : 'transparent'}`,
              }}
            >
              <span
                style={{
                  width: 44,
                  padding: '0 10px',
                  textAlign: 'right',
                  color: 'var(--text-faint)',
                  fontSize: 11,
                  userSelect: 'none',
                  flexShrink: 0,
                }}
              >
                {l.n}
              </span>
              <span
                style={{
                  width: 14,
                  color: l.add ? 'var(--status-success)' : 'transparent',
                  flexShrink: 0,
                  textAlign: 'center',
                }}
              >
                {l.add ? '+' : ' '}
              </span>
              <span style={{ color: 'var(--code-text)', whiteSpace: 'pre' }}>
                {l.t}
              </span>
            </div>
            {l.comment && <InlineComment c={l.comment} />}
          </span>
        ))}
      </div>
    </div>
  )
}

// ── ReviewerRow ───────────────────────────────────────────────────────────────

function ReviewerRow({
  name,
  bot,
  status,
  comment,
}: {
  name: string
  bot?: boolean
  status: 'approved' | 'pending'
  comment?: string
}) {
  return (
    <div style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
      {bot ? <Avatar bot size={22} /> : <Avatar name={name} size={22} />}
      <div style={{ flex: 1, minWidth: 0 }}>
        <div style={{ fontSize: 12.5, color: 'var(--text)' }}>{name}</div>
        {comment && (
          <div style={{ fontSize: 10.5, color: 'var(--text-subtle)' }}>{comment}</div>
        )}
      </div>
      {status === 'approved' && (
        <Pill tone="success" size="xs" icon={<Check size={9} />}>
          Approved
        </Pill>
      )}
      {status === 'pending' && (
        <Pill tone="default" size="xs">
          Pending
        </Pill>
      )}
    </div>
  )
}

// ── CheckRow ──────────────────────────────────────────────────────────────────

function CheckRow({ name, ok, time }: { name: string; ok?: boolean; time?: string }) {
  return (
    <div
      style={{
        display: 'flex',
        alignItems: 'center',
        gap: 8,
        fontSize: 12,
        color: 'var(--text-muted)',
      }}
    >
      <Check
        size={11}
        color={ok ? 'var(--status-success)' : 'var(--text-faint)'}
      />
      <span className="gtx-mono" style={{ flex: 1 }}>
        {name}
      </span>
      {time && (
        <span style={{ fontSize: 10.5, color: 'var(--text-faint)' }}>{time}</span>
      )}
    </div>
  )
}

// ── ReviewSidebar ─────────────────────────────────────────────────────────────

function ReviewSidebar({ review }: { review?: ReviewDetail }) {
  return (
    <div style={{ padding: '16px 20px', fontSize: 12.5 }}>
      <div
        style={{
          fontSize: 11,
          color: 'var(--text-subtle)',
          textTransform: 'uppercase',
          letterSpacing: 0.6,
          marginBottom: 10,
        }}
      >
        Reviewers
      </div>
      <div
        style={{ display: 'flex', flexDirection: 'column', gap: 8, marginBottom: 18 }}
      >
        <ReviewerRow name="GoTyme-X AI" bot status="approved" comment="3 comments" />
        <ReviewerRow name="Mira Delgado" status="pending" />
        <ReviewerRow name="Jamie Ortiz" status="approved" />
      </div>

      <div
        style={{
          fontSize: 11,
          color: 'var(--text-subtle)',
          textTransform: 'uppercase',
          letterSpacing: 0.6,
          marginBottom: 10,
        }}
      >
        Checks
      </div>
      <div
        style={{ display: 'flex', flexDirection: 'column', gap: 6, marginBottom: 18 }}
      >
        <CheckRow name="ci / unit" ok time="42s" />
        <CheckRow name="ci / integration" ok time="3m 18s" />
        <CheckRow name="ci / race-detector" ok time="1m 04s" />
        <CheckRow name="ci / lint" ok time="22s" />
        <CheckRow name="security / snyk" ok time="1m 45s" />
        <CheckRow name="coverage / 87%" ok />
        <CheckRow name="preview / staging" ok time="deployed" />
      </div>

      <div
        style={{
          fontSize: 11,
          color: 'var(--text-subtle)',
          textTransform: 'uppercase',
          letterSpacing: 0.6,
          marginBottom: 10,
        }}
      >
        Linked
      </div>
      <div
        style={{
          fontSize: 12,
          color: 'var(--text-muted)',
          display: 'flex',
          flexDirection: 'column',
          gap: 6,
        }}
      >
        <a
          href="#"
          style={{
            color: 'var(--accent-600)',
            textDecoration: 'none',
            display: 'flex',
            gap: 6,
            alignItems: 'center',
          }}
        >
          <CircleDot size={11} />
          <span className="gtx-mono">{review?.jiraKey ?? 'GTX-4829'}</span>{' '}
          {review?.jiraKey ? review.title : 'Fix race condition'}
        </a>
        <a
          href="#"
          style={{
            color: 'var(--accent-600)',
            textDecoration: 'none',
            display: 'flex',
            gap: 6,
            alignItems: 'center',
          }}
        >
          <Activity size={11} />
          Alert-8821 balance-cache errors
        </a>
      </div>
    </div>
  )
}

// ── QueueTabBar ───────────────────────────────────────────────────────────────

function QueueTabBar({
  activeTab,
  onChange,
  counts,
}: {
  activeTab: QueueTab
  onChange: (tab: QueueTab) => void
  counts: Record<QueueTab, number>
}) {
  const tabs: { id: QueueTab; label: string }[] = [
    { id: 'all', label: 'All' },
    { id: 'awaiting', label: 'Awaiting me' },
    { id: 'completed', label: 'Completed' },
  ]

  return (
    <div
      role="tablist"
      aria-label="Review queue filter"
      style={{
        display: 'flex',
        gap: 2,
        padding: '0 20px',
        borderBottom: '1px solid var(--panel-border)',
        background: 'var(--bg)',
      }}
    >
      {tabs.map((t) => (
        <button
          key={t.id}
          role="tab"
          aria-selected={activeTab === t.id}
          onClick={() => onChange(t.id)}
          style={{
            display: 'inline-flex',
            alignItems: 'center',
            gap: 6,
            padding: '8px 14px',
            border: 'none',
            borderBottom: `2px solid ${activeTab === t.id ? 'var(--accent-500)' : 'transparent'}`,
            background: 'transparent',
            fontSize: 12.5,
            fontWeight: activeTab === t.id ? 500 : 400,
            color: activeTab === t.id ? 'var(--text)' : 'var(--text-muted)',
            cursor: 'pointer',
            fontFamily: 'inherit',
            transition: 'color .1s',
          }}
        >
          {t.label}
          <span
            style={{
              fontSize: 10.5,
              fontWeight: 400,
              color: 'var(--text-faint)',
              background: 'var(--bg-muted)',
              padding: '1px 5px',
              borderRadius: 8,
            }}
          >
            {counts[t.id]}
          </span>
        </button>
      ))}
    </div>
  )
}

// ── Empty state messages per tab ──────────────────────────────────────────────

const EMPTY_STATE_MESSAGES: Record<QueueTab, string> = {
  all: 'No code reviews yet. Reviews will appear here when the review agent analyzes pull requests.',
  awaiting: "You're all caught up! No reviews need your attention.",
  completed: 'No completed reviews in this period.',
}

// ── ReviewQueue ───────────────────────────────────────────────────────────────

function ReviewQueue({
  reviews,
  tab,
  focusedIndex,
  onFocusChange,
  onApprove,
  onRequestChanges,
  onOpen,
}: {
  reviews: ReviewDetail[]
  tab: QueueTab
  focusedIndex: number
  onFocusChange: (index: number) => void
  onApprove: (id: string) => void
  onRequestChanges: (id: string) => void
  onOpen: (id: string) => void
}) {
  const itemRefs = useRef<(HTMLDivElement | null)[]>([])

  // Scroll focused item into view
  useEffect(() => {
    const el = itemRefs.current[focusedIndex]
    el?.scrollIntoView({ block: 'nearest', behavior: 'smooth' })
  }, [focusedIndex])

  if (reviews.length === 0) {
    return (
      <div
        role="status"
        aria-live="polite"
        style={{
          padding: '40px 20px',
          textAlign: 'center',
          color: 'var(--text-subtle)',
          fontSize: 13,
          lineHeight: 1.6,
        }}
      >
        {EMPTY_STATE_MESSAGES[tab]}
      </div>
    )
  }

  return (
    <div
      role="list"
      aria-label="Review queue"
      style={{ display: 'flex', flexDirection: 'column', gap: 8, padding: '16px 20px' }}
    >
      {reviews.map((review, i) => (
        <div
          key={review.id}
          role="listitem"
          ref={(el) => {
            itemRefs.current[i] = el
          }}
          onFocus={() => onFocusChange(i)}
          onMouseEnter={() => onFocusChange(i)}
        >
          <ReviewCard
            review={review}
            focused={focusedIndex === i}
            onApprove={onApprove}
            onRequestChanges={onRequestChanges}
            onClick={onOpen}
          />
        </div>
      ))}
    </div>
  )
}

// ── Page ──────────────────────────────────────────────────────────────────────

export default function ReviewsPage() {
  const router = useRouter()

  // Queue state
  const [queueTab, setQueueTab] = useState<QueueTab>('all')
  const [focusedIndex, setFocusedIndex] = useState(0)
  const [showHelp, setShowHelp] = useState(false)

  // Detail panel state
  const [detailTab, setDetailTab] = useState<ReviewTab>('review')
  const [selectedReviewId, setSelectedReviewId] = useState<string | null>(null)

  // Optimistic status overrides: id -> status
  const [statusOverrides, setStatusOverrides] = useState<Record<string, string>>({})

  const { data: apiReviews, isLoading } = useSWR('reviews', getReviews, {
    refreshInterval: 15000,
  })

  // Merge optimistic overrides into review list
  const reviews: ReviewDetail[] = (apiReviews ?? []).map((r) =>
    statusOverrides[r.id] ? { ...r, status: statusOverrides[r.id] } : r,
  )

  // Filter reviews by queue tab
  const filteredReviews = reviews.filter((r) => {
    if (queueTab === 'awaiting') return isAwaiting(r.status)
    if (queueTab === 'completed') return isCompleted(r.status)
    return true
  })

  const counts: Record<QueueTab, number> = {
    all: reviews.length,
    awaiting: reviews.filter((r) => isAwaiting(r.status)).length,
    completed: reviews.filter((r) => isCompleted(r.status)).length,
  }

  // When tab changes, reset focused index
  const handleQueueTabChange = useCallback((tab: QueueTab) => {
    setQueueTab(tab)
    setFocusedIndex(0)
  }, [])

  // Current focused review
  const focusedReview = filteredReviews[focusedIndex] ?? null

  // Selected review for detail panel (falls back to first review if none selected)
  const selectedReview =
    (selectedReviewId
      ? reviews.find((r) => r.id === selectedReviewId)
      : reviews[0]) ?? reviews[0]

  // ── Actions ─────────────────────────────────────────────────────────────────

  const handleApprove = useCallback(
    async (id: string) => {
      setStatusOverrides((prev) => ({ ...prev, [id]: 'approved' }))
      try {
        await approveReview(id)
        swrMutate('reviews')
      } catch {
        // Rollback optimistic update on error
        setStatusOverrides((prev) => {
          const next = { ...prev }
          delete next[id]
          return next
        })
      }
    },
    [],
  )

  const handleRequestChanges = useCallback(
    async (id: string) => {
      setStatusOverrides((prev) => ({ ...prev, [id]: 'changes_requested' }))
      try {
        await requestChanges(id)
        swrMutate('reviews')
      } catch {
        setStatusOverrides((prev) => {
          const next = { ...prev }
          delete next[id]
          return next
        })
      }
    },
    [],
  )

  const handleOpen = useCallback(
    (id: string) => {
      setSelectedReviewId(id)
      router.push(`/dashboard/reviews/${id}`)
    },
    [router],
  )

  // ── Keyboard shortcuts ───────────────────────────────────────────────────────

  useEffect(() => {
    const handleKeyDown = (e: KeyboardEvent) => {
      // Never fire when a text input is focused
      if (isInputFocused()) return
      // Never fire when modifier keys are held (except Shift for '?')
      if (e.ctrlKey || e.altKey || e.metaKey) return

      switch (e.key) {
        case '1': {
          e.preventDefault()
          handleQueueTabChange('all')
          break
        }
        case '2': {
          e.preventDefault()
          handleQueueTabChange('awaiting')
          break
        }
        case '3': {
          e.preventDefault()
          handleQueueTabChange('completed')
          break
        }
        case 'j': {
          e.preventDefault()
          setFocusedIndex((i) => Math.min(i + 1, filteredReviews.length - 1))
          break
        }
        case 'k': {
          e.preventDefault()
          setFocusedIndex((i) => Math.max(i - 1, 0))
          break
        }
        case 'Escape': {
          e.preventDefault()
          setFocusedIndex(-1)
          break
        }
        case 'a': {
          if (focusedReview && isAwaiting(focusedReview.status)) {
            e.preventDefault()
            handleApprove(focusedReview.id)
          }
          break
        }
        case 'r': {
          if (focusedReview && isAwaiting(focusedReview.status)) {
            e.preventDefault()
            handleRequestChanges(focusedReview.id)
          }
          break
        }
        case 'o':
        case 'Enter': {
          if (focusedReview) {
            e.preventDefault()
            handleOpen(focusedReview.id)
          }
          break
        }
        case '?': {
          e.preventDefault()
          setShowHelp((v) => !v)
          break
        }
      }
    }

    window.addEventListener('keydown', handleKeyDown)
    return () => window.removeEventListener('keydown', handleKeyDown)
  }, [filteredReviews.length, focusedReview, handleApprove, handleRequestChanges, handleOpen, handleQueueTabChange])

  // ── Detail panel ─────────────────────────────────────────────────────────────

  const repoLabel = selectedReview ? selectedReview.repo : '—'
  const prLabel = selectedReview
    ? `PR #${selectedReview.prNumber} — ${selectedReview.title}`
    : 'No review selected'
  const prUrl = selectedReview?.prUrl

  const detailTabs: {
    id: ReviewTab
    label: string
    count: number
    icon?: React.ReactNode
  }[] = [
    { id: 'conversation', label: 'Conversation', count: 5 },
    {
      id: 'review',
      label: 'AI Review',
      count: selectedReview
        ? selectedReview.files.reduce((s, f) => s + f.comments.length, 0)
        : 3,
      icon: <BrandMark size={12} />,
    },
    { id: 'commits', label: 'Commits', count: 2 },
    {
      id: 'checks',
      label: 'Checks',
      count: 8,
      icon: <Check size={11} color="var(--status-success)" />,
    },
  ]

  // ── Render ───────────────────────────────────────────────────────────────────

  return (
    <>
      <main
        style={{ flex: 1, display: 'grid', gridTemplateColumns: '360px 1fr', overflow: 'hidden' }}
      >
        {/* Queue section */}
        <section
          aria-label="Review queue"
          style={{
            borderRight: '1px solid var(--panel-border)',
            display: 'flex',
            flexDirection: 'column',
            overflow: 'hidden',
          }}
        >
          <div
            style={{
              padding: '12px 20px 0',
              borderBottom: 'none',
              background: 'var(--bg)',
            }}
          >
            <h2
              style={{
                margin: 0,
                fontSize: 14,
                fontWeight: 600,
                color: 'var(--text)',
                marginBottom: 10,
                display: 'flex',
                alignItems: 'center',
                gap: 8,
              }}
            >
              Reviews
              <span
                style={{
                  fontSize: 10.5,
                  fontWeight: 400,
                  color: 'var(--text-faint)',
                  background: 'var(--bg-muted)',
                  padding: '1px 5px',
                  borderRadius: 8,
                  border: '1px solid var(--border)',
                }}
              >
                Press ? for shortcuts
              </span>
            </h2>
          </div>

          <QueueTabBar
            activeTab={queueTab}
            onChange={handleQueueTabChange}
            counts={counts}
          />

          <div style={{ flex: 1, overflowY: 'auto' }}>
            {isLoading ? (
              <div
                style={{
                  display: 'flex',
                  justifyContent: 'center',
                  padding: 32,
                }}
                aria-label="Loading reviews"
              >
                <Spinner size={20} />
              </div>
            ) : (
              <ReviewQueue
                reviews={filteredReviews}
                tab={queueTab}
                focusedIndex={focusedIndex}
                onFocusChange={setFocusedIndex}
                onApprove={handleApprove}
                onRequestChanges={handleRequestChanges}
                onOpen={handleOpen}
              />
            )}
          </div>
        </section>

        {/* Detail panel */}
        <div
          style={{
            flex: 1,
            display: 'flex',
            flexDirection: 'column',
            overflow: 'auto',
          }}
        >
          {/* Header */}
          <header
            style={{
              padding: '0 20px',
              borderBottom: '1px solid var(--panel-border)',
              background: 'var(--bg)',
            }}
          >
            <div style={{ display: 'flex', alignItems: 'center', gap: 12, height: 52 }}>
              <div style={{ flex: 1, minWidth: 0 }}>
                <div
                  style={{
                    display: 'flex',
                    alignItems: 'center',
                    gap: 6,
                    fontSize: 11.5,
                    color: 'var(--text-subtle)',
                    marginBottom: 1,
                  }}
                >
                  {repoLabel && <span>{repoLabel}</span>}
                  {repoLabel && <span style={{ fontSize: 10 }}>&rsaquo;</span>}
                  <span style={{ color: 'var(--text)' }}>Pull requests</span>
                </div>
                <div style={{ display: 'flex', alignItems: 'baseline', gap: 10 }}>
                  <h1
                    style={{
                      margin: 0,
                      fontSize: 17,
                      fontWeight: 600,
                      letterSpacing: -0.3,
                      color: 'var(--text)',
                    }}
                  >
                    {prLabel}
                  </h1>
                  <span style={{ fontSize: 12.5, color: 'var(--text-subtle)' }}>
                    {selectedReview ? (selectedReview.jiraKey ?? repoLabel) : 'Select a review from the queue'}
                  </span>
                </div>
              </div>
              <div style={{ display: 'flex', alignItems: 'center', gap: 6 }}>
                <Pill tone="info" dot>
                  <Spinner size={9} />
                  Reviewing
                </Pill>
                <Btn
                  size="sm"
                  icon={<ExternalLink size={11} />}
                  onClick={() => prUrl && window.open(prUrl, '_blank')}
                >
                  Open in GitHub
                </Btn>
                <Btn
                  size="sm"
                  tone="brand"
                  icon={<Check size={11} />}
                  onClick={() => selectedReview && handleApprove(selectedReview.id)}
                >
                  Approve
                </Btn>
              </div>
            </div>

            {/* Detail tabs */}
            <div style={{ display: 'flex', gap: 2, marginTop: -1 }}>
              {detailTabs.map((t) => (
                <button
                  key={t.id}
                  onClick={() => setDetailTab(t.id)}
                  style={{
                    display: 'inline-flex',
                    alignItems: 'center',
                    gap: 6,
                    padding: '8px 14px',
                    border: 'none',
                    borderBottom: `2px solid ${detailTab === t.id ? 'var(--accent-500)' : 'transparent'}`,
                    background: 'transparent',
                    fontSize: 12.5,
                    fontWeight: detailTab === t.id ? 500 : 400,
                    color: detailTab === t.id ? 'var(--text)' : 'var(--text-muted)',
                    cursor: 'pointer',
                    fontFamily: 'inherit',
                    transition: 'color .1s',
                  }}
                >
                  {t.icon}
                  {t.label}
                  <span
                    style={{
                      fontSize: 10.5,
                      fontWeight: 400,
                      color: 'var(--text-faint)',
                      background: 'var(--bg-muted)',
                      padding: '1px 5px',
                      borderRadius: 8,
                    }}
                  >
                    {t.count}
                  </span>
                </button>
              ))}
            </div>
          </header>

          {/* Review summary bar */}
          <ReviewSummaryBar review={selectedReview} />

          {/* Split: diff + sidebar */}
          <div
            style={{
              display: 'grid',
              gridTemplateColumns: '1fr 340px',
              flex: 1,
              minHeight: 0,
            }}
          >
            <div
              style={{
                padding: '16px 20px',
                borderRight: '1px solid var(--panel-border)',
                overflow: 'auto',
              }}
            >
              <FileReviewBlock review={selectedReview} />
            </div>
            <div style={{ overflow: 'auto', background: 'var(--bg-elev)' }}>
              <ReviewSidebar review={selectedReview} />
            </div>
          </div>
        </div>
      </main>

      {/* Keyboard shortcut help overlay */}
      <KeyboardShortcutHelp
        open={showHelp}
        onClose={() => setShowHelp(false)}
      />
    </>
  )
}
