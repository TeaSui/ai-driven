'use client'

import { useState } from 'react'
import {
  AlertTriangle,
  Lightbulb,
  Sparkles,
  GitPullRequest,
  FileCode,
  ChevronDown,
  ChevronRight,
  ThumbsUp,
  ThumbsDown,
  ExternalLink,
} from 'lucide-react'
import { Card } from '@/components/ui/card'
import { Badge, AgentBadge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { cn } from '@/lib/cn'

// MOCK — wire to real API
type Severity = 'suggestion' | 'warning' | 'critical'
type Confidence = 'high' | 'medium' | 'low'

interface InlineComment {
  id: string
  line: number
  severity: Severity
  confidence: Confidence
  category: string
  body: string
  suggestion?: { before: string; after: string }
  resolved?: boolean
}

interface DiffFile {
  path: string
  additions: number
  deletions: number
  hunk: Array<
    | { type: 'context'; line: number; text: string }
    | { type: 'add'; line: number; text: string }
    | { type: 'remove'; line: number; text: string }
  >
  comments: InlineComment[]
}

const MOCK_PR = {
  number: 847,
  repo: 'platform/api',
  title: 'Add rate limiting to /api/chat/send',
  branch: 'agent/plat-4218-rate-limit → main',
  author: 'coding-agent',
  jiraKey: 'PLAT-4218',
  url: 'https://github.com/platform/api/pull/847',
  summary:
    'Adds token-bucket rate limiting (10 req/min per user) to the chat send endpoint. Uses Redis as the backing store with a fallback to in-memory when Redis is unavailable. Includes metrics emission for rate-limit hits.',
  files: [
    {
      path: 'src/routes/chat/send.ts',
      additions: 24,
      deletions: 3,
      hunk: [
        { type: 'context', line: 12, text: "import { Router } from 'express'" },
        {
          type: 'context',
          line: 13,
          text: "import { sendMessage } from '../handlers/chat'",
        },
        {
          type: 'add',
          line: 14,
          text: "import { rateLimit } from '../middleware/rate-limit'",
        },
        {
          type: 'add',
          line: 15,
          text: "import { metrics } from '../lib/metrics'",
        },
        { type: 'context', line: 16, text: '' },
        {
          type: 'context',
          line: 17,
          text: 'export const router = Router()',
        },
        { type: 'context', line: 18, text: '' },
        { type: 'remove', line: 19, text: "router.post('/send', async (req, res) => {" },
        {
          type: 'add',
          line: 19,
          text: "router.post('/send', rateLimit({ window: 60, max: 10 }), async (req, res) => {",
        },
        {
          type: 'add',
          line: 20,
          text: '  const userId = req.user?.id',
        },
        {
          type: 'add',
          line: 21,
          text: '  if (!userId) return res.status(401).json({ error: "unauthorized" })',
        },
        { type: 'context', line: 22, text: '' },
        {
          type: 'context',
          line: 23,
          text: '  try {',
        },
        {
          type: 'add',
          line: 24,
          text: '    metrics.increment("chat.send.requested", { userId })',
        },
        {
          type: 'context',
          line: 25,
          text: '    const result = await sendMessage(req.body)',
        },
        {
          type: 'context',
          line: 26,
          text: '    res.json(result)',
        },
      ] as DiffFile['hunk'],
      comments: [
        {
          id: 'c1',
          line: 19,
          severity: 'warning',
          confidence: 'high',
          category: 'Security',
          body: 'Rate limit key should include user ID for per-user throttling. As written, this limits globally across all users to 10 req/min — this will DoS legitimate traffic at scale.',
          suggestion: {
            before: "rateLimit({ window: 60, max: 10 })",
            after: 'rateLimit({ window: 60, max: 10, keyBy: (req) => req.user?.id ?? req.ip })',
          },
        },
        {
          id: 'c2',
          line: 21,
          severity: 'suggestion',
          confidence: 'medium',
          category: 'Error handling',
          body: 'Consider using the shared `UnauthorizedError` class so error responses are consistent with the rest of the API.',
        },
        {
          id: 'c3',
          line: 24,
          severity: 'critical',
          confidence: 'high',
          category: 'PII / Privacy',
          body: 'Do not include raw userId as a metric label — it causes label cardinality explosion in Prometheus and is a PII concern. Hash it or drop the label.',
          suggestion: {
            before: 'metrics.increment("chat.send.requested", { userId })',
            after: 'metrics.increment("chat.send.requested", { userBucket: hashBucket(userId, 100) })',
          },
        },
      ],
    },
    {
      path: 'src/middleware/rate-limit.ts',
      additions: 48,
      deletions: 0,
      hunk: [
        { type: 'add', line: 1, text: "import type { Request, Response, NextFunction } from 'express'" },
        { type: 'add', line: 2, text: "import { redis } from '../lib/redis'" },
        { type: 'add', line: 3, text: '' },
        {
          type: 'add',
          line: 4,
          text: 'interface RateLimitOptions {',
        },
        { type: 'add', line: 5, text: '  window: number // seconds' },
        { type: 'add', line: 6, text: '  max: number' },
        { type: 'add', line: 7, text: '}' },
      ] as DiffFile['hunk'],
      comments: [
        {
          id: 'c4',
          line: 7,
          severity: 'suggestion',
          confidence: 'high',
          category: 'API design',
          body: 'Missing `keyBy` option in the interface — callers will need it for per-user / per-IP limiting. Suggest making it required.',
        },
      ],
    },
  ] as DiffFile[],
}

const SEVERITY_CONFIG: Record<
  Severity,
  { label: string; color: string; bg: string; icon: React.ElementType }
> = {
  critical: {
    label: 'Critical',
    color: 'text-red-400',
    bg: 'bg-red-500/10 border-red-500/30',
    icon: AlertTriangle,
  },
  warning: {
    label: 'Warning',
    color: 'text-amber-400',
    bg: 'bg-amber-500/10 border-amber-500/30',
    icon: AlertTriangle,
  },
  suggestion: {
    label: 'Suggestion',
    color: 'text-brand-light',
    bg: 'bg-brand/10 border-brand/30',
    icon: Lightbulb,
  },
}

function CommentCard({ comment }: { comment: InlineComment }) {
  const [resolved, setResolved] = useState(comment.resolved ?? false)
  const sev = SEVERITY_CONFIG[comment.severity]
  const Icon = sev.icon

  return (
    <div
      className={cn(
        'rounded-lg border p-3 my-2 ml-14',
        sev.bg,
        resolved && 'opacity-50',
      )}
    >
      <div className="flex items-start gap-3">
        <Sparkles className={cn('h-4 w-4 flex-shrink-0 mt-0.5', sev.color)} />
        <div className="flex-1 min-w-0">
          <div className="flex items-center gap-2 mb-1.5 flex-wrap">
            <span className={cn('text-xs font-semibold', sev.color)}>
              {sev.label}
            </span>
            <span className="text-[10px] text-slate-500">·</span>
            <span className="text-[11px] text-slate-400">
              {comment.category}
            </span>
            <span className="text-[10px] text-slate-500">·</span>
            <span className="text-[10px] text-slate-500 uppercase tracking-wider">
              {comment.confidence} confidence
            </span>
            <span className="text-[10px] text-slate-500 ml-auto font-mono">
              Line {comment.line}
            </span>
          </div>
          <p className="text-sm text-slate-200 leading-relaxed">
            {comment.body}
          </p>
          {comment.suggestion && (
            <div className="mt-3 rounded-md bg-surface-base overflow-hidden font-mono text-xs">
              <div className="bg-red-500/10 border-l-2 border-red-500/50 px-3 py-1.5 text-slate-300">
                <span className="text-red-400 mr-2">-</span>
                {comment.suggestion.before}
              </div>
              <div className="bg-emerald-500/10 border-l-2 border-emerald-500/50 px-3 py-1.5 text-slate-300">
                <span className="text-emerald-400 mr-2">+</span>
                {comment.suggestion.after}
              </div>
            </div>
          )}
          <div className="flex items-center gap-1 mt-3">
            <button
              onClick={() => setResolved(!resolved)}
              className={cn(
                'text-[11px] font-medium px-2 py-1 rounded transition-colors',
                resolved
                  ? 'text-slate-500 hover:text-slate-300'
                  : 'text-brand-light hover:bg-brand/10',
              )}
            >
              {resolved ? 'Reopen' : 'Resolve'}
            </button>
            <button className="text-[11px] text-slate-500 hover:text-slate-300 px-2 py-1 rounded transition-colors">
              Reply
            </button>
            <div className="flex items-center gap-0.5 ml-auto">
              <button
                aria-label="Helpful"
                className="p-1 text-slate-500 hover:text-emerald-400 rounded transition-colors"
              >
                <ThumbsUp className="h-3 w-3" />
              </button>
              <button
                aria-label="Not helpful"
                className="p-1 text-slate-500 hover:text-red-400 rounded transition-colors"
              >
                <ThumbsDown className="h-3 w-3" />
              </button>
            </div>
          </div>
        </div>
      </div>
    </div>
  )
}

function FileBlock({ file }: { file: DiffFile }) {
  const [open, setOpen] = useState(true)
  const commentsByLine = file.comments.reduce(
    (acc, c) => {
      acc[c.line] = acc[c.line] ?? []
      acc[c.line].push(c)
      return acc
    },
    {} as Record<number, InlineComment[]>,
  )

  return (
    <Card className="p-0 overflow-hidden">
      {/* File header */}
      <button
        onClick={() => setOpen(!open)}
        className="flex items-center gap-3 w-full px-4 py-3 border-b border-slate-700/50 hover:bg-surface-base transition-colors text-left"
      >
        {open ? (
          <ChevronDown className="h-3.5 w-3.5 text-slate-500" />
        ) : (
          <ChevronRight className="h-3.5 w-3.5 text-slate-500" />
        )}
        <FileCode className="h-3.5 w-3.5 text-slate-400" />
        <span className="text-xs font-mono text-slate-200 flex-1 truncate">
          {file.path}
        </span>
        <span className="text-[11px] tabular-nums">
          <span className="text-emerald-400">+{file.additions}</span>
          <span className="mx-1 text-slate-600">/</span>
          <span className="text-red-400">−{file.deletions}</span>
        </span>
        {file.comments.length > 0 && (
          <Badge className="bg-brand/15 text-brand-light">
            <Sparkles className="h-2.5 w-2.5 mr-1" />
            {file.comments.length}
          </Badge>
        )}
      </button>

      {/* Diff body */}
      {open && (
        <div className="font-mono text-xs">
          {file.hunk.map((line, i) => {
            const bg =
              line.type === 'add'
                ? 'bg-emerald-500/5'
                : line.type === 'remove'
                  ? 'bg-red-500/5'
                  : 'bg-transparent'
            const prefix =
              line.type === 'add'
                ? '+'
                : line.type === 'remove'
                  ? '−'
                  : ' '
            const prefixColor =
              line.type === 'add'
                ? 'text-emerald-400'
                : line.type === 'remove'
                  ? 'text-red-400'
                  : 'text-slate-600'
            const textColor =
              line.type === 'remove'
                ? 'text-slate-400'
                : 'text-slate-200'
            const comments = commentsByLine[line.line] ?? []
            return (
              <div key={i}>
                <div className={cn('flex items-center hover:bg-surface-base/50', bg)}>
                  <span className="w-10 px-2 text-right text-slate-600 select-none tabular-nums flex-shrink-0 border-r border-slate-700/30">
                    {line.line}
                  </span>
                  <span
                    className={cn(
                      'w-6 text-center select-none flex-shrink-0',
                      prefixColor,
                    )}
                  >
                    {prefix}
                  </span>
                  <pre
                    className={cn(
                      'px-2 py-0.5 whitespace-pre-wrap break-all flex-1',
                      textColor,
                    )}
                  >
                    {line.text || '\u00a0'}
                  </pre>
                </div>
                {line.type !== 'remove' &&
                  comments.map((c) => <CommentCard key={c.id} comment={c} />)}
              </div>
            )
          })}
        </div>
      )}
    </Card>
  )
}

export default function ReviewsPage() {
  const totalComments = MOCK_PR.files.reduce(
    (acc, f) => acc + f.comments.length,
    0,
  )
  const critical = MOCK_PR.files.reduce(
    (acc, f) =>
      acc + f.comments.filter((c) => c.severity === 'critical').length,
    0,
  )
  const warnings = MOCK_PR.files.reduce(
    (acc, f) =>
      acc + f.comments.filter((c) => c.severity === 'warning').length,
    0,
  )
  const suggestions = MOCK_PR.files.reduce(
    (acc, f) =>
      acc + f.comments.filter((c) => c.severity === 'suggestion').length,
    0,
  )

  return (
    <div className="space-y-6">
      {/* PR header */}
      <Card>
        <div className="flex items-start justify-between gap-4">
          <div className="min-w-0 flex-1">
            <div className="flex items-center gap-2 flex-wrap mb-2">
              <GitPullRequest className="h-4 w-4 text-brand-light flex-shrink-0" />
              <span className="text-xs font-mono text-slate-500">
                {MOCK_PR.repo}
              </span>
              <span className="text-xs text-slate-500">·</span>
              <span className="text-xs text-brand-light font-mono">
                #{MOCK_PR.number}
              </span>
              <span className="text-xs text-slate-500">·</span>
              <span className="text-[10px] text-slate-500 font-mono uppercase">
                {MOCK_PR.jiraKey}
              </span>
            </div>
            <h1 className="text-lg font-bold text-slate-100">{MOCK_PR.title}</h1>
            <p className="text-xs text-slate-500 mt-1 font-mono">
              {MOCK_PR.branch}
            </p>
            <p className="text-sm text-slate-300 mt-3 leading-relaxed">
              {MOCK_PR.summary}
            </p>
            <div className="flex items-center gap-2 mt-3">
              <AgentBadge type="coding" />
              <a
                href={MOCK_PR.url}
                target="_blank"
                rel="noopener noreferrer"
                className="inline-flex items-center gap-1 text-xs text-brand-light hover:underline"
              >
                View on GitHub <ExternalLink className="h-3 w-3" />
              </a>
            </div>
          </div>
          <div className="flex flex-col items-end gap-2 flex-shrink-0">
            <Button size="sm" variant="primary">
              Approve &amp; merge
            </Button>
            <Button size="sm" variant="secondary">
              Request changes
            </Button>
          </div>
        </div>
      </Card>

      {/* Review summary */}
      <Card>
        <div className="flex items-center justify-between mb-3">
          <div className="flex items-center gap-2">
            <Sparkles className="h-4 w-4 text-brand-light" />
            <h2 className="text-sm font-semibold text-slate-100">
              AI review summary
            </h2>
          </div>
          <span className="text-[10px] text-slate-500 uppercase tracking-wider">
            {totalComments} comments · {MOCK_PR.files.length} files
          </span>
        </div>
        <div className="grid grid-cols-3 gap-3">
          <div className="rounded-lg border border-red-500/30 bg-red-500/5 p-3">
            <div className="flex items-center gap-2 mb-1">
              <AlertTriangle className="h-3.5 w-3.5 text-red-400" />
              <span className="text-xs font-semibold text-red-400">
                Critical
              </span>
            </div>
            <p className="text-2xl font-semibold text-slate-100 tabular-nums">
              {critical}
            </p>
          </div>
          <div className="rounded-lg border border-amber-500/30 bg-amber-500/5 p-3">
            <div className="flex items-center gap-2 mb-1">
              <AlertTriangle className="h-3.5 w-3.5 text-amber-400" />
              <span className="text-xs font-semibold text-amber-400">
                Warnings
              </span>
            </div>
            <p className="text-2xl font-semibold text-slate-100 tabular-nums">
              {warnings}
            </p>
          </div>
          <div className="rounded-lg border border-brand/30 bg-brand/5 p-3">
            <div className="flex items-center gap-2 mb-1">
              <Lightbulb className="h-3.5 w-3.5 text-brand-light" />
              <span className="text-xs font-semibold text-brand-light">
                Suggestions
              </span>
            </div>
            <p className="text-2xl font-semibold text-slate-100 tabular-nums">
              {suggestions}
            </p>
          </div>
        </div>
      </Card>

      {/* File diffs */}
      <div className="space-y-4">
        {MOCK_PR.files.map((f) => (
          <FileBlock key={f.path} file={f} />
        ))}
      </div>
    </div>
  )
}
