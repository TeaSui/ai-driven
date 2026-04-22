'use client'

import { useState, useEffect, useRef, useMemo } from 'react'
import { use } from 'react'
import useSWR from 'swr'
import {
  Sparkles,
  Code2,
  FileCode,
  Terminal,
  Play,
  Pause,
  X,
  ArrowRight,
} from 'lucide-react'
import { Pill } from '@/components/ui/pill'
import { Btn } from '@/components/ui/btn'
import { BrandMark } from '@/components/ui/brand-mark'
import { StatusDot } from '@/components/ui/status-dot'
import { Spinner } from '@/components/ui/spinner'
import {
  getTask,
  getTaskEvents,
  getTaskEvaluations,
  pauseTask,
  resumeTask,
  stopTask,
} from '@/lib/api'
import type { AgentEvent, TaskEvaluation } from '@/lib/api'

const TERMINAL_STATUSES = new Set(['completed', 'failed', 'cancelled'])
const ACTIVE_STATUSES = new Set(['queued', 'running', 'coding', 'planning', 'testing'])
const DEMO_MODE = process.env.NEXT_PUBLIC_DEMO_MODE === 'true'

// ── Step types ────────────────────────────────────────────────────────────────

type StepKind = 'think' | 'tool' | 'edit' | 'response' | 'cache'

interface AgentStep {
  kind: StepKind
  t: number
  text?: string
  name?: string
  args?: string
  result?: string
  file?: string
  op?: string
  summary?: string
  iteration?: number
  cacheRead?: number
  cacheWrite?: number
}

// ── Static demo data (GTX-4829) ───────────────────────────────────────────────

const DEMO_STEPS: AgentStep[] = [
  { kind: 'think', t: 0, text: 'Reading ticket GTX-4829 — "Fix race condition in ledger balance cache"' },
  { kind: 'tool', t: 400, name: 'jira.getIssue', args: 'GTX-4829', result: 'Found · 3 comments, 2 ACs' },
  { kind: 'think', t: 900, text: 'The description mentions intermittent failures when two concurrent transfers read the same account balance. Need to locate the cache layer.' },
  { kind: 'tool', t: 1400, name: 'github.search', args: 'BalanceCache ledger-core', result: '6 matches across 3 files' },
  { kind: 'tool', t: 1700, name: 'fs.readFile', args: 'internal/cache/balance.go', result: '218 lines' },
  { kind: 'think', t: 2100, text: 'I see it — `Get()` does a check-then-set without locking. Under concurrent load, two callers can both miss the cache and write stale reads. Need a sync.Map + singleflight.' },
  { kind: 'tool', t: 2600, name: 'fs.readFile', args: 'internal/cache/balance_test.go', result: '94 lines · 4 tests' },
  { kind: 'think', t: 3000, text: "Existing tests don't cover concurrency. I'll add a race test that reproduces the bug first, then fix it." },
  { kind: 'edit', t: 3400, file: 'internal/cache/balance_test.go', op: '+41 −0', summary: 'Add TestBalanceCache_ConcurrentReads' },
  { kind: 'tool', t: 3900, name: 'shell.run', args: 'go test -race ./internal/cache/...', result: 'FAIL · DATA RACE detected' },
  { kind: 'think', t: 4300, text: 'Reproduced. Now fixing.' },
  { kind: 'edit', t: 4700, file: 'internal/cache/balance.go', op: '+17 −17', summary: 'Wrap Get() in singleflight.Group' },
  { kind: 'tool', t: 5300, name: 'shell.run', args: 'go test -race ./internal/cache/...', result: 'PASS · 4/4 tests, 0 races' },
  { kind: 'think', t: 5800, text: 'Fix works. Running the full suite + linting before opening a PR.' },
  { kind: 'tool', t: 6300, name: 'shell.run', args: 'go test ./... && golangci-lint run', result: 'PASS · 312/312 tests' },
  { kind: 'tool', t: 6900, name: 'git.commit', args: 'fix(ledger): guard BalanceCache.Get() with singleflight', result: 'a3f21c8' },
  { kind: 'tool', t: 7300, name: 'github.openPR', args: 'fix/GTX-4829-ledger-race → main', result: 'PR #2419 opened' },
]

// ── Diff lines ────────────────────────────────────────────────────────────────

const DIFF_LINES: { n: number; t: string; add?: boolean; del?: boolean }[] = [
  { n: 42, t: 'func (c *BalanceCache) Get(ctx context.Context, accountID string) (*Balance, error) {', del: true },
  { n: 42, t: 'func (c *BalanceCache) Get(ctx context.Context, accountID string) (*Balance, error) {', add: true },
  { n: 43, t: '\tif v, ok := c.m.Load(accountID); ok {', del: true },
  { n: 43, t: '\tv, err, _ := c.sf.Do(accountID, func() (interface{}, error) {', add: true },
  { n: 44, t: '\t\treturn v.(*Balance), nil', del: true },
  { n: 44, t: '\t\tif v, ok := c.m.Load(accountID); ok {', add: true },
  { n: 45, t: '\t}', del: true },
  { n: 45, t: '\t\t\treturn v.(*Balance), nil', add: true },
  { n: 46, t: '\tb, err := c.loader.Load(ctx, accountID)', del: true },
  { n: 46, t: '\t\t}', add: true },
  { n: 47, t: '\tif err != nil {', del: true },
  { n: 47, t: '\t\tb, err := c.loader.Load(ctx, accountID)', add: true },
  { n: 48, t: '\t\treturn nil, err', del: true },
  { n: 48, t: '\t\tif err != nil {', add: true },
  { n: 49, t: '\t}', del: true },
  { n: 49, t: '\t\t\treturn nil, err', add: true },
  { n: 50, t: '\tc.m.Store(accountID, b)', del: true },
  { n: 50, t: '\t\t}', add: true },
  { n: 51, t: '\treturn b, nil', del: true },
  { n: 51, t: '\t\tc.m.Store(accountID, b)', add: true },
  { n: 52, t: '}', del: true },
  { n: 52, t: '\t\treturn b, nil', add: true },
  { n: 53, t: '\t})', add: true },
  { n: 54, t: '\tif err != nil {', add: true },
  { n: 55, t: '\t\treturn nil, err', add: true },
  { n: 56, t: '\t}', add: true },
  { n: 57, t: '\treturn v.(*Balance), nil', add: true },
  { n: 58, t: '}', add: true },
]

// ── FileTab ───────────────────────────────────────────────────────────────────

function FileTab({
  file,
  dir,
  active,
  dirty,
  added,
}: {
  file: string
  dir: string
  active?: boolean
  dirty?: boolean
  added?: boolean
}) {
  return (
    <div
      style={{
        display: 'inline-flex',
        alignItems: 'center',
        gap: 6,
        padding: '10px 14px',
        borderRight: '1px solid var(--panel-border)',
        background: active ? 'var(--bg)' : 'transparent',
        borderBottom: active ? '2px solid var(--accent-500)' : '2px solid transparent',
        fontSize: 12,
        color: active ? 'var(--text)' : 'var(--text-muted)',
        cursor: 'pointer',
        position: 'relative',
        marginBottom: -1,
      }}
    >
      <FileCode size={11} />
      <span className="gtx-mono" style={{ color: 'var(--text-subtle)' }}>
        {dir}/
      </span>
      <span className="gtx-mono" style={{ fontWeight: active ? 500 : 400 }}>
        {file}
      </span>
      {added && (
        <span style={{ fontSize: 10, color: 'var(--status-success)', fontWeight: 600 }}>
          NEW
        </span>
      )}
      {dirty && !added && (
        <span
          style={{
            width: 5,
            height: 5,
            borderRadius: 3,
            background: 'var(--accent-500)',
          }}
        />
      )}
    </div>
  )
}

// ── CodeBlock ─────────────────────────────────────────────────────────────────

function CodeBlock() {
  return (
    <div style={{ minWidth: 'max-content' }}>
      {DIFF_LINES.map((l, i) => (
        <div
          key={i}
          style={{
            display: 'flex',
            background: l.add
              ? 'var(--diff-add-bg)'
              : l.del
                ? 'var(--diff-del-bg)'
                : 'transparent',
            borderLeft: `2px solid ${
              l.add
                ? 'var(--diff-add-line)'
                : l.del
                  ? 'var(--diff-del-line)'
                  : 'transparent'
            }`,
          }}
        >
          <span
            style={{
              width: 48,
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
              textAlign: 'center',
              color: l.add
                ? 'var(--status-success)'
                : l.del
                  ? 'var(--status-danger)'
                  : 'transparent',
              flexShrink: 0,
            }}
          >
            {l.add ? '+' : l.del ? '−' : ' '}
          </span>
          <span
            style={{ flex: 1, color: 'var(--code-text)', whiteSpace: 'pre' }}
          >
            {l.t}
          </span>
        </div>
      ))}
      <div style={{ height: 40 }} />
    </div>
  )
}

// ── MainCodePane ──────────────────────────────────────────────────────────────

function MainCodePane() {
  return (
    <div
      style={{
        borderRight: '1px solid var(--panel-border)',
        display: 'flex',
        flexDirection: 'column',
        minHeight: 0,
        overflow: 'hidden',
      }}
    >
      {/* File tabs */}
      <div
        style={{
          display: 'flex',
          alignItems: 'stretch',
          borderBottom: '1px solid var(--panel-border)',
          background: 'var(--panel)',
          paddingLeft: 8,
        }}
      >
        <FileTab file="balance.go" dir="internal/cache" active dirty />
        <FileTab file="balance_test.go" dir="internal/cache" added />
        <div style={{ flex: 1 }} />
        <div
          style={{
            display: 'flex',
            alignItems: 'center',
            gap: 8,
            padding: '0 12px',
            fontSize: 11.5,
            color: 'var(--text-subtle)',
          }}
        >
          <span className="gtx-mono">main</span>
          <ArrowRight size={10} />
          <span className="gtx-mono" style={{ color: 'var(--accent-600)' }}>
            fix/GTX-4829-ledger-race
          </span>
        </div>
      </div>

      {/* Diff view */}
      <div
        style={{
          flex: 1,
          overflow: 'auto',
          background: 'var(--code-bg)',
          fontFamily: 'Geist Mono, monospace',
          fontSize: 12.5,
          lineHeight: 1.65,
        }}
      >
        <CodeBlock />
      </div>

      {/* Terminal strip */}
      <div
        style={{
          borderTop: '1px solid var(--panel-border)',
          background: 'var(--bg-elev)',
        }}
      >
        <div
          style={{
            display: 'flex',
            alignItems: 'center',
            gap: 8,
            padding: '8px 14px',
            borderBottom: '1px solid var(--panel-border)',
            fontSize: 11.5,
            color: 'var(--text-muted)',
          }}
        >
          <Terminal size={12} />
          Terminal
          <span style={{ color: 'var(--text-faint)' }}>·</span>
          <span className="gtx-mono" style={{ fontSize: 11 }}>
            sandbox-e4c9
          </span>
          <div style={{ flex: 1 }} />
          <Pill tone="success" size="xs" dot>
            PASS
          </Pill>
        </div>
        <div
          className="gtx-mono"
          style={{
            padding: '10px 14px',
            fontSize: 11.5,
            lineHeight: 1.7,
            color: 'var(--text-muted)',
            maxHeight: 140,
            overflow: 'auto',
          }}
        >
          <div>
            <span style={{ color: 'var(--accent-500)' }}>$</span> go test -race
            ./internal/cache/...
          </div>
          <div style={{ color: 'var(--text-subtle)' }}>
            ok&nbsp;&nbsp;internal/cache&nbsp;&nbsp;0.842s{' '}
            <span style={{ color: 'var(--status-success)' }}>(-race)</span>
          </div>
          <div>
            <span style={{ color: 'var(--accent-500)' }}>$</span> go test ./...
          </div>
          <div style={{ color: 'var(--text-subtle)' }}>
            ok&nbsp;&nbsp;./...{' '}
            <span style={{ color: 'var(--status-success)' }}>312/312 tests</span>
          </div>
          <div>
            <span style={{ color: 'var(--accent-500)' }}>$</span> git commit -m
            &quot;fix(ledger): guard BalanceCache.Get() with singleflight&quot;
          </div>
          <div style={{ color: 'var(--text-subtle)' }}>
            [fix/GTX-4829-ledger-race a3f21c8] fix(ledger)...
          </div>
          <div>
            <span style={{ color: 'var(--accent-500)' }}>$</span> gh pr create
            --fill
            <span
              style={{
                animation: 'gtx-blink 1s step-end infinite',
                marginLeft: 2,
              }}
            >
              ▋
            </span>
          </div>
        </div>
      </div>
    </div>
  )
}

// ── StepCard ──────────────────────────────────────────────────────────────────

function StepCard({
  step,
  displayText,
  isLast,
  running,
}: {
  step: AgentStep
  displayText: string
  isLast: boolean
  running: boolean
}) {
  if (step.kind === 'think') {
    return (
      <div
        style={{
          display: 'flex',
          gap: 10,
          marginBottom: 14,
          animation: 'gtx-fadein .25s',
        }}
      >
        <div
          style={{
            width: 4,
            background: 'var(--accent-400)',
            borderRadius: 2,
            opacity: 0.4,
            flexShrink: 0,
          }}
        />
        <div
          style={{
            flex: 1,
            fontSize: 12.5,
            color: 'var(--text-muted)',
            lineHeight: 1.5,
          }}
        >
          {displayText}
          {isLast && running && (
            <span
              style={{
                animation: 'gtx-blink 1s step-end infinite',
                color: 'var(--accent-500)',
              }}
            >
              ▋
            </span>
          )}
        </div>
      </div>
    )
  }

  if (step.kind === 'tool') {
    return (
      <div style={{ marginBottom: 10, animation: 'gtx-fadein .25s' }}>
        <div
          style={{
            display: 'flex',
            alignItems: 'center',
            gap: 6,
            marginBottom: 4,
          }}
        >
          <Sparkles size={11} color="var(--accent-500)" />
          <span
            className="gtx-mono"
            style={{ fontSize: 11, color: 'var(--text)', fontWeight: 500 }}
          >
            {step.name}
          </span>
          <span
            className="gtx-mono"
            style={{ fontSize: 10.5, color: 'var(--text-subtle)' }}
          >
            ({step.args})
          </span>
        </div>
        <div
          style={{
            marginLeft: 17,
            fontSize: 11.5,
            color: 'var(--text-subtle)',
            padding: '4px 8px',
            background: 'var(--bg-muted)',
            borderRadius: 4,
            border: '1px solid var(--border)',
            fontFamily: 'Geist Mono, monospace',
          }}
        >
          ← {step.result}
        </div>
      </div>
    )
  }

  if (step.kind === 'edit') {
    return (
      <div
        style={{
          display: 'flex',
          gap: 10,
          marginBottom: 10,
          padding: 10,
          background: 'var(--accent-soft)',
          border: '1px solid var(--brand-teal-200)',
          borderRadius: 6,
          animation: 'gtx-fadein .25s',
        }}
      >
        <Code2 size={12} color="var(--accent-600)" style={{ marginTop: 2 }} />
        <div style={{ flex: 1, minWidth: 0 }}>
          <div
            className="gtx-mono"
            style={{
              fontSize: 11,
              color: 'var(--accent-600)',
              fontWeight: 500,
              marginBottom: 2,
            }}
          >
            {step.file}{' '}
            <span style={{ color: 'var(--text-subtle)' }}>{step.op}</span>
          </div>
          <div style={{ fontSize: 12, color: 'var(--text)' }}>{step.summary}</div>
        </div>
      </div>
    )
  }

  if (step.kind === 'response') {
    return (
      <div
        style={{
          marginBottom: 12,
          padding: '10px 12px',
          background: 'var(--code-bg)',
          border: '1px solid var(--border)',
          borderRadius: 6,
          animation: 'gtx-fadein .25s',
        }}
      >
        <div
          style={{
            display: 'flex',
            alignItems: 'center',
            gap: 6,
            marginBottom: 6,
            fontSize: 10.5,
            color: 'var(--text-subtle)',
            textTransform: 'uppercase',
            letterSpacing: 0.5,
            fontWeight: 600,
          }}
        >
          <Sparkles size={10} color="var(--accent-500)" />
          Claude Response
          {step.iteration != null && (
            <span style={{ fontWeight: 400 }}> · iteration {step.iteration}</span>
          )}
          {isLast && running && <StatusDot tone="success" pulse size={5} />}
        </div>
        <pre
          className="gtx-mono"
          style={{
            whiteSpace: 'pre-wrap',
            wordBreak: 'break-word',
            color: 'var(--text)',
            fontSize: 11.5,
            lineHeight: 1.55,
            margin: 0,
            maxHeight: 400,
            overflow: 'auto',
          }}
        >
          {displayText}
          {isLast && running && (
            <span
              style={{
                animation: 'gtx-blink 1s step-end infinite',
                color: 'var(--accent-500)',
              }}
            >
              ▋
            </span>
          )}
        </pre>
      </div>
    )
  }

  if (step.kind === 'cache') {
    const isHit = (step.cacheRead ?? 0) > 0
    return (
      <div
        style={{
          display: 'flex',
          alignItems: 'center',
          gap: 6,
          marginBottom: 6,
          padding: '4px 8px',
          fontSize: 10.5,
          color: isHit ? 'var(--status-success)' : 'var(--status-warn)',
          animation: 'gtx-fadein .25s',
        }}
      >
        <span style={{ fontSize: 9 }}>{isHit ? '●' : '○'}</span>
        <span className="gtx-mono">{step.text}</span>
      </div>
    )
  }

  return null
}

// ── ResponsePanel ─────────────────────────────────────────────────────────────

function ResponsePanel({
  text,
  iteration,
  isStreaming,
}: {
  text: string
  iteration?: number
  isStreaming: boolean
}) {
  const panelRef = useRef<HTMLPreElement>(null)

  // Auto-scroll to bottom while content is streaming in
  useEffect(() => {
    if (isStreaming && panelRef.current) {
      panelRef.current.scrollTop = panelRef.current.scrollHeight
    }
  }, [text, isStreaming])

  return (
    <div
      style={{
        marginBottom: 12,
        padding: '10px 12px',
        background: 'var(--code-bg)',
        border: '1px solid var(--border)',
        borderRadius: 6,
        animation: 'gtx-fadein .25s',
      }}
    >
      <div
        style={{
          display: 'flex',
          alignItems: 'center',
          gap: 6,
          marginBottom: 6,
          fontSize: 10.5,
          color: 'var(--text-subtle)',
          textTransform: 'uppercase',
          letterSpacing: 0.5,
          fontWeight: 600,
        }}
      >
        <Sparkles size={10} color="var(--accent-500)" />
        Claude Response
        {iteration != null && (
          <span style={{ fontWeight: 400 }}> · iteration {iteration}</span>
        )}
        {isStreaming && <StatusDot tone="success" pulse size={5} />}
      </div>
      <pre
        ref={panelRef}
        className="gtx-mono"
        style={{
          whiteSpace: 'pre-wrap',
          wordBreak: 'break-word',
          color: 'var(--text)',
          fontSize: 11.5,
          lineHeight: 1.55,
          margin: 0,
          maxHeight: 400,
          overflow: 'auto',
        }}
      >
        {text}
        {isStreaming && (
          <span
            style={{
              animation: 'gtx-blink 1s step-end infinite',
              color: 'var(--accent-500)',
            }}
          >
            ▋
          </span>
        )}
      </pre>
    </div>
  )
}

// ── ThinkingPanel ─────────────────────────────────────────────────────────────

function ThinkingPanel({
  steps,
  liveChar,
  feedRef,
  running,
  events,
  evaluations,
  isStreaming,
}: {
  steps: AgentStep[]
  liveChar: number
  feedRef: React.RefObject<HTMLDivElement | null>
  running: boolean
  events: AgentEvent[]
  evaluations: TaskEvaluation[]
  isStreaming: boolean
}) {
  return (
    <div
      style={{
        background: 'var(--panel)',
        display: 'flex',
        flexDirection: 'column',
        minHeight: 0,
      }}
    >
      <div
        style={{
          padding: '12px 16px',
          borderBottom: '1px solid var(--panel-border)',
          display: 'flex',
          alignItems: 'center',
          gap: 8,
        }}
      >
        <BrandMark size={14} />
        <span style={{ fontSize: 12.5, fontWeight: 500, color: 'var(--text)' }}>
          Thinking stream
        </span>
        {(running || isStreaming) && <StatusDot tone="success" pulse size={6} />}
        <div style={{ flex: 1 }} />
        <span style={{ fontSize: 10.5, color: 'var(--text-faint)' }}>
          {steps.length} steps
        </span>
      </div>
      <div
        ref={feedRef as React.RefObject<HTMLDivElement>}
        aria-live="polite"
        aria-label="Agent thinking stream"
        style={{ flex: 1, overflowY: 'auto', padding: '12px 16px' }}
      >
        {events.length > 0 && <SystemPromptPanel events={events} />}
        {steps.map((s, i) => {
          const isLast = i === steps.length - 1
          const displayText =
            isLast && s.kind === 'think'
              ? (s.text ?? '').slice(0, liveChar)
              : s.text ?? ''

          // Use ResponsePanel for response kind steps
          if (s.kind === 'response') {
            return (
              <ResponsePanel
                key={i}
                text={s.text ?? ''}
                iteration={s.iteration}
                isStreaming={isLast && isStreaming}
              />
            )
          }

          return (
            <StepCard
              key={i}
              step={s}
              displayText={displayText}
              isLast={isLast}
              running={running}
            />
          )
        })}
      </div>
      <EvalPanel evaluations={evaluations} />
    </div>
  )
}

// ── SystemPromptPanel ────────────────────────────────────────────────────────

function SystemPromptPanel({ events }: { events: AgentEvent[] }) {
  const [expanded, setExpanded] = useState(false)
  const promptEvent = events.find(e => e.type === 'prompt_sent')
  if (!promptEvent) return null

  const systemPrompt = promptEvent.data.system_prompt as string | undefined
  const userMessage = promptEvent.data.user_message as string | undefined
  const model = promptEvent.data.model as string | undefined

  return (
    <div style={{ marginBottom: 12, animation: 'gtx-fadein .25s' }}>
      <button
        onClick={() => setExpanded(e => !e)}
        aria-expanded={expanded}
        aria-controls="prompt-sent-detail"
        style={{
          display: 'flex', alignItems: 'center', gap: 6, width: '100%',
          padding: '8px 10px', background: 'var(--bg-muted)', border: '1px solid var(--border)',
          borderRadius: 6, cursor: 'pointer', fontFamily: 'inherit', textAlign: 'left',
        }}
      >
        <span style={{ fontSize: 11, color: 'var(--accent-600)', fontWeight: 500 }}>
          {expanded ? '▾' : '▸'} Prompt sent
        </span>
        {model && (
          <span className="gtx-mono" style={{ fontSize: 10, color: 'var(--text-faint)', marginLeft: 'auto' }}>
            {model}
          </span>
        )}
      </button>
      {expanded && (
        <div id="prompt-sent-detail" style={{ marginTop: 6, padding: '10px 12px', background: 'var(--code-bg)', border: '1px solid var(--border)', borderRadius: 6, fontSize: 11.5, lineHeight: 1.55 }}>
          {systemPrompt && (
            <div style={{ marginBottom: 10 }}>
              <div style={{ fontSize: 10, fontWeight: 600, color: 'var(--text-subtle)', textTransform: 'uppercase', letterSpacing: 0.5, marginBottom: 4 }}>System prompt</div>
              <pre className="gtx-mono" style={{ whiteSpace: 'pre-wrap', wordBreak: 'break-word', color: 'var(--text-muted)', fontSize: 10.5, lineHeight: 1.5, maxHeight: 200, overflow: 'auto', margin: 0 }}>
                {systemPrompt}
              </pre>
            </div>
          )}
          {userMessage && (
            <div>
              <div style={{ fontSize: 10, fontWeight: 600, color: 'var(--text-subtle)', textTransform: 'uppercase', letterSpacing: 0.5, marginBottom: 4 }}>User message</div>
              <pre className="gtx-mono" style={{ whiteSpace: 'pre-wrap', wordBreak: 'break-word', color: 'var(--text)', fontSize: 11, lineHeight: 1.5, margin: 0 }}>
                {userMessage}
              </pre>
            </div>
          )}
        </div>
      )}
    </div>
  )
}

// ── EvalPanel ────────────────────────────────────────────────────────────────

function EvalPanel({ evaluations }: { evaluations: TaskEvaluation[] }) {
  if (evaluations.length === 0) return null

  const avgScore = evaluations.reduce((s, e) => s + e.score, 0) / evaluations.length

  return (
    <div style={{ padding: '12px 16px', borderTop: '1px solid var(--panel-border)' }}>
      <div style={{ display: 'flex', alignItems: 'center', gap: 8, marginBottom: 10 }}>
        <span style={{ fontSize: 12.5, fontWeight: 500, color: 'var(--text)' }}>
          Evaluation
        </span>
        <Pill tone={avgScore >= 7 ? 'success' : avgScore >= 4 ? 'warn' : 'danger'} size="xs">
          {avgScore.toFixed(1)}/10
        </Pill>
      </div>
      <div style={{ display: 'flex', flexDirection: 'column', gap: 8 }}>
        {evaluations.map(ev => (
          <EvalDimension key={ev.id || ev.dimension} eval={ev} />
        ))}
      </div>
    </div>
  )
}

function EvalDimension({ eval: ev }: { eval: TaskEvaluation }) {
  const [expanded, setExpanded] = useState(false)
  const barColor = ev.score >= 7 ? 'var(--status-success)' : ev.score >= 4 ? 'var(--status-warn)' : 'var(--status-danger)'

  return (
    <div>
      <button
        onClick={() => setExpanded(e => !e)}
        aria-expanded={expanded}
        aria-label={`${ev.dimension} evaluation score: ${ev.score}/10`}
        style={{
          display: 'flex', alignItems: 'center', gap: 8, width: '100%',
          background: 'none', border: 'none', padding: '4px 0', cursor: 'pointer',
          fontFamily: 'inherit', textAlign: 'left',
        }}
      >
        <span style={{ fontSize: 11.5, color: 'var(--text-muted)', flex: 1, textTransform: 'capitalize' }}>
          {ev.dimension}
        </span>
        <div style={{ width: 60, height: 4, background: 'var(--bg-muted)', borderRadius: 2, overflow: 'hidden' }}>
          <div style={{ width: `${ev.score * 10}%`, height: '100%', background: barColor, borderRadius: 2 }} />
        </div>
        <span className="gtx-mono" style={{ fontSize: 10.5, color: 'var(--text-subtle)', width: 28, textAlign: 'right' }}>
          {ev.score}/10
        </span>
      </button>
      {expanded && (
        <div style={{ padding: '6px 0 6px 8px', fontSize: 11, color: 'var(--text-subtle)', lineHeight: 1.5 }}>
          {ev.reasoning && <div style={{ marginBottom: 4 }}>{ev.reasoning}</div>}
          {ev.strengths?.length > 0 && (
            <div style={{ color: 'var(--status-success)' }}>
              {ev.strengths.map((s, i) => <div key={i}>+ {s}</div>)}
            </div>
          )}
          {ev.weaknesses?.length > 0 && (
            <div style={{ color: 'var(--status-danger)', marginTop: 2 }}>
              {ev.weaknesses.map((w, i) => <div key={i}>− {w}</div>)}
            </div>
          )}
        </div>
      )}
    </div>
  )
}

// ── Event → AgentStep mapper ──────────────────────────────────────────────────

function mapEventsToSteps(events: AgentEvent[]): AgentStep[] {
  const steps: AgentStep[] = []
  let t = 0
  // Track accumulated streaming response text per iteration
  let activeResponseIdx = -1

  for (const event of events) {
    const { type, data } = event
    // If the backend already translated this event with a stepType, use it
    // to produce the correct AgentStep kind directly.
    const stepType = (event as unknown as Record<string, unknown>).stepType as string | undefined

    if (stepType === 'edit') {
      // File-edit events from translated tool_call_end
      const toolName = (data.tool as string) ?? 'edit'
      const resultData = data.result_data
        ? JSON.stringify(data.result_data).slice(0, 200)
        : undefined
      steps.push({
        kind: 'edit',
        t,
        file: (data.file_path as string) ?? toolName,
        op: resultData ?? '',
        summary: (data.summary as string) ?? `${toolName} completed`,
      })
      t += 300
      activeResponseIdx = -1
      continue
    }

    if (type === 'prompt_sent') {
      steps.push({
        kind: 'think',
        t,
        text: (data.user_message as string) || 'Starting task...',
      })
      t += 400
    } else if (type === 'tool_call_start') {
      // A tool call means the previous response step is done
      activeResponseIdx = -1
      steps.push({
        kind: 'tool',
        t,
        name: data.tool as string | undefined,
        args: JSON.stringify(data.input_data ?? '').slice(0, 100),
        result: undefined,
      })
      t += 300
    } else if (type === 'tool_call_end') {
      for (let i = steps.length - 1; i >= 0; i--) {
        if (steps[i].kind === 'tool' && steps[i].result === undefined) {
          steps[i] = {
            ...steps[i],
            result: data.result_data
              ? JSON.stringify(data.result_data).slice(0, 100)
              : 'Done',
          }
          break
        }
      }
    } else if (type === 'streaming_chunk') {
      // Accumulate streaming text into a single 'response' step
      const content = data.content as string | undefined
      if (content) {
        if (activeResponseIdx >= 0 && steps[activeResponseIdx]?.kind === 'response') {
          steps[activeResponseIdx] = {
            ...steps[activeResponseIdx],
            text: (steps[activeResponseIdx].text ?? '') + content,
            iteration: (data.iteration as number) ?? steps[activeResponseIdx].iteration,
          }
        } else {
          activeResponseIdx = steps.length
          steps.push({
            kind: 'response',
            t,
            text: content,
            iteration: data.iteration as number | undefined,
          })
          t += 100
        }
      }
    } else if (type === 'text_delta') {
      // Final complete response — replace accumulated streaming text
      const content = data.content as string | undefined
      if (content) {
        if (activeResponseIdx >= 0 && steps[activeResponseIdx]?.kind === 'response') {
          steps[activeResponseIdx] = {
            ...steps[activeResponseIdx],
            text: content,
          }
        } else {
          steps.push({ kind: 'response', t, text: content })
          t += 100
        }
        activeResponseIdx = -1
      }
    } else if (type === 'response_chunk') {
      const content = data.content as string | undefined
      if (content) {
        steps.push({ kind: 'think', t, text: content })
        t += 200
      }
    } else if (type === 'iteration') {
      // Show cache metrics inline
      const cacheRead = (data.cache_read_tokens as number) ?? 0
      const cacheWrite = (data.cache_creation_tokens as number) ?? 0
      if (cacheRead > 0 || cacheWrite > 0) {
        steps.push({
          kind: 'cache',
          t,
          iteration: data.iteration as number | undefined,
          cacheRead,
          cacheWrite,
          text: cacheRead > 0
            ? `Cache HIT: ${cacheRead.toLocaleString()} tokens saved`
            : `Cache WRITE: ${cacheWrite.toLocaleString()} tokens cached`,
        })
        t += 50
      }
    } else if (type === 'rag_indexing_start') {
      steps.push({ kind: 'think', t, text: 'Indexing repository for semantic search...' })
      t += 200
    } else if (type === 'rag_indexing_complete') {
      const chunks = data.chunks as number | undefined
      steps.push({ kind: 'think', t, text: `Repository indexed: ${chunks ?? '?'} chunks ready` })
      t += 200
    } else if (type === 'complete') {
      activeResponseIdx = -1
      const summary =
        (data.summary as string) ||
        (data.message as string) ||
        'Task completed.'
      steps.push({ kind: 'think', t, text: summary })
      t += 200
    }
  }

  return steps
}

// ── Page ──────────────────────────────────────────────────────────────────────

export default function CodingAgentPage({
  params,
}: {
  params: Promise<{ id: string }> | { id: string }
}) {
  // Next.js 14 may pass params as a plain object or a Promise depending on mode
  const resolvedParams = params instanceof Promise ? use(params) : params
  const { id } = resolvedParams

  const { data: task } = useSWR(`task-${id}`, () => getTask(id), {
    refreshInterval: (data) => (data?.status && TERMINAL_STATUSES.has(data.status) ? 0 : 5000),
  })
  const isTerminal = task?.status ? TERMINAL_STATUSES.has(task.status) : false
  const { data: events } = useSWR(`task-events-${id}`, () => getTaskEvents(id), {
    refreshInterval: isTerminal ? 0 : 3000,
  })
  const { data: evaluations } = useSWR(
    task?.status === 'completed' ? `task-evals-${id}` : null,
    () => getTaskEvaluations(id),
  )

  // WebSocket for live streaming
  const [wsEvents, setWsEvents] = useState<AgentEvent[]>([])
  const [controlPending, setControlPending] = useState<'pause' | 'resume' | 'stop' | null>(null)
  const wsRef = useRef<WebSocket | null>(null)

  useEffect(() => {
    if (task?.status && !ACTIVE_STATUSES.has(task.status)) return

    // WebSocket URL: prefer NEXT_PUBLIC_WS_URL (deploy-time), fall back to same origin.
    // Next.js rewrites do NOT proxy WebSocket upgrades — production requires a
    // dedicated upgrade-capable proxy (ALB listener rule / Nginx) or an explicit
    // env-configured URL.
    const explicit = process.env.NEXT_PUBLIC_WS_URL
    const protocol = window.location.protocol === 'https:' ? 'wss:' : 'ws:'
    const wsUrl = explicit
      ? `${explicit.replace(/\/$/, '')}/api/chat/stream/${id}`
      : `${protocol}//${window.location.host}/api/chat/stream/${id}`
    try {
      const ws = new WebSocket(wsUrl)
      wsRef.current = ws

      // First-message auth: send API key immediately on open
      ws.onopen = () => {
        const match = document.cookie.match(/(?:^|; )api_key=([^;]*)/)
        const apiKey = match ? decodeURIComponent(match[1]) : null
        if (apiKey) {
          ws.send(JSON.stringify({ type: 'auth', token: apiKey }))
        }
      }

      ws.onmessage = (ev) => {
        try {
          const event = JSON.parse(ev.data) as AgentEvent
          if (event.type === 'connected') return
          setWsEvents(prev => [...prev, event])
        } catch {}
      }

      ws.onerror = () => {}
      ws.onclose = () => { wsRef.current = null }

      return () => { ws.close(); wsRef.current = null }
    } catch {
      // WebSocket connection failed — fall back to polling
    }
  }, [id, task?.status])

  // Merge historical events + live WebSocket events
  const allEvents = useMemo(() => {
    const historical = events ?? []
    if (wsEvents.length === 0) return historical
    // Deduplicate by checking timestamps
    const historicalTs = new Set(historical.map(e => e.timestamp).filter(Boolean))
    const newEvents = wsEvents.filter(e => !e.timestamp || !historicalTs.has(e.timestamp))
    return [...historical, ...newEvents]
  }, [events, wsEvents])

  const realSteps = useMemo(
    () => (allEvents.length > 0 ? mapEventsToSteps(allEvents) : []),
    [allEvents],
  )

  const isRealMode = realSteps.length > 0
  // Demo fallback is only shown in explicit demo mode — otherwise an empty
  // stream shows an empty state rather than fake content.
  const steps = isRealMode ? realSteps : DEMO_MODE ? DEMO_STEPS : []

  // Demo animation state — only active in demo mode
  const [running, setRunning] = useState(true)
  const [idx, setIdx] = useState(isRealMode ? steps.length - 1 : 8)
  const [liveChar, setLiveChar] = useState(0)
  const feedRef = useRef<HTMLDivElement>(null!)

  // When real steps arrive, jump to the end immediately
  useEffect(() => {
    if (isRealMode) {
      setIdx(steps.length - 1)
      setRunning(false)
    }
  }, [isRealMode, steps.length])

  useEffect(() => {
    if (isRealMode) return
    if (!running) return
    if (idx >= DEMO_STEPS.length) return
    const step = DEMO_STEPS[idx]
    const text = step.text ?? step.summary ?? ''
    if (step.kind === 'think' && liveChar < text.length) {
      const t = setTimeout(() => setLiveChar((c) => Math.min(text.length, c + 3)), 18)
      return () => clearTimeout(t)
    }
    const delay = step.kind === 'think' ? 600 : 420
    const t = setTimeout(() => {
      setIdx((i) => i + 1)
      setLiveChar(0)
    }, delay)
    return () => clearTimeout(t)
  }, [isRealMode, running, idx, liveChar])

  useEffect(() => {
    if (feedRef.current) {
      feedRef.current.scrollTop = feedRef.current.scrollHeight
    }
  }, [idx, liveChar])

  const visible = steps.slice(0, idx + 1)
  const isComplete = isRealMode ? true : idx >= DEMO_STEPS.length

  // Streaming status: extract latest iteration metrics from events
  const streamingStatus = useMemo(() => {
    const iterationEvents = allEvents.filter(e => e.type === 'iteration')
    const hasComplete = allEvents.some(e => e.type === 'complete' || e.type === 'error')
    const isStreaming = allEvents.some(e => e.type === 'streaming_chunk') && !hasComplete
    if (iterationEvents.length === 0) return null
    const latest = iterationEvents[iterationEvents.length - 1]
    const totalCacheRead = iterationEvents.reduce(
      (sum, e) => sum + ((e.data.cache_read_tokens as number) ?? 0), 0,
    )
    return {
      iteration: latest.data.iteration as number,
      totalTokens: (latest.data.total_input_tokens as number ?? 0) + (latest.data.total_output_tokens as number ?? 0),
      totalCacheRead,
      isStreaming,
    }
  }, [allEvents])

  // Status pill helpers
  const statusTone = (s?: string): 'info' | 'success' | 'danger' | 'warn' => {
    if (!s) return 'info'
    const lower = s.toLowerCase()
    if (lower === 'completed' || lower === 'success') return 'success'
    if (lower === 'failed' || lower === 'error') return 'danger'
    if (lower === 'paused') return 'warn'
    return 'info'
  }

  const statusLabel = (): string => {
    if (task?.status) return task.status
    if (running && !isComplete) return 'Running · 8m 04s'
    if (isComplete) return 'Complete'
    return 'Paused'
  }

  return (
    <main style={{ flex: 1, display: 'flex', flexDirection: 'column', overflow: 'hidden' }}>
      {/* Header */}
      <div
        style={{
          display: 'flex',
          alignItems: 'center',
          justifyContent: 'space-between',
          padding: '12px 20px',
          borderBottom: '1px solid var(--panel-border)',
          background: 'var(--panel)',
        }}
      >
        <div>
          <div
            style={{
              fontSize: 11.5,
              color: 'var(--text-subtle)',
              marginBottom: 2,
            }}
          >
            Runs /{' '}
            <span className="gtx-mono" style={{ color: 'var(--text)' }}>
              {id}
            </span>
          </div>
          <div style={{ fontSize: 13, fontWeight: 500, color: 'var(--text)' }}>
            {task?.title ?? 'Fix race condition in ledger balance cache'}
          </div>
          {/* Real metadata row */}
          {task && (
            <div
              style={{
                display: 'flex',
                gap: 14,
                marginTop: 4,
                fontSize: 11,
                color: 'var(--text-faint)',
              }}
            >
              {task.iterations != null && (
                <span>
                  <span style={{ color: 'var(--text-subtle)' }}>Iterations:</span>{' '}
                  {task.iterations}
                </span>
              )}
              {task.tokenUsage != null && (
                <span>
                  <span style={{ color: 'var(--text-subtle)' }}>Tokens:</span>{' '}
                  {task.tokenUsage.toLocaleString()}
                </span>
              )}
              {task.costUsd != null && (
                <span>
                  <span style={{ color: 'var(--text-subtle)' }}>Cost:</span>{' '}
                  ${task.costUsd.toFixed(4)}
                </span>
              )}
            </div>
          )}
          {streamingStatus && (
            <div
              style={{
                display: 'flex',
                gap: 10,
                marginTop: 4,
                fontSize: 11,
                alignItems: 'center',
              }}
            >
              {streamingStatus.isStreaming && (
                <span style={{
                  display: 'inline-flex',
                  alignItems: 'center',
                  gap: 4,
                  color: 'var(--accent)',
                  fontWeight: 500,
                }}>
                  <Spinner size={9} color="currentColor" />
                  Generating… (iteration {streamingStatus.iteration}, {streamingStatus.totalTokens.toLocaleString()} tokens)
                </span>
              )}
              {!streamingStatus.isStreaming && streamingStatus.totalTokens > 0 && (
                <span style={{ color: 'var(--text-faint)' }}>
                  {streamingStatus.iteration} iterations · {streamingStatus.totalTokens.toLocaleString()} tokens
                </span>
              )}
              {streamingStatus.totalCacheRead > 0 && (
                <span style={{
                  display: 'inline-flex',
                  alignItems: 'center',
                  gap: 3,
                  padding: '1px 6px',
                  borderRadius: 4,
                  fontSize: 10,
                  fontWeight: 600,
                  background: 'rgba(34,197,94,0.12)',
                  color: '#22c55e',
                }}>
                  Cache: {streamingStatus.totalCacheRead.toLocaleString()} tokens saved
                </span>
              )}
            </div>
          )}
        </div>
        <div style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
          <Pill tone={statusTone(task?.status)} dot>
            {!isRealMode && running && !isComplete && (
              <Spinner size={9} color="currentColor" />
            )}
            {statusLabel()}
          </Pill>
          {isRealMode && !isTerminal ? (
            <>
              {task?.status === 'paused' ? (
                <Btn
                  size="sm"
                  icon={<Play size={11} />}
                  disabled={controlPending != null}
                  onClick={async () => {
                    setControlPending('resume')
                    try { await resumeTask(id) } finally { setControlPending(null) }
                  }}
                >
                  Resume
                </Btn>
              ) : (
                <Btn
                  size="sm"
                  icon={<Pause size={11} />}
                  disabled={controlPending != null}
                  onClick={async () => {
                    setControlPending('pause')
                    try { await pauseTask(id) } finally { setControlPending(null) }
                  }}
                >
                  Pause
                </Btn>
              )}
              <Btn
                size="sm"
                tone="ghost"
                icon={<X size={11} />}
                disabled={controlPending != null}
                onClick={async () => {
                  if (!window.confirm('Stop this run? The task will be marked cancelled.')) return
                  setControlPending('stop')
                  try { await stopTask(id) } finally { setControlPending(null) }
                }}
              >
                Stop
              </Btn>
            </>
          ) : (
            !isComplete && !isRealMode && DEMO_MODE && (
              <>
                <Btn
                  size="sm"
                  icon={running ? <Pause size={11} /> : <Play size={11} />}
                  onClick={() => setRunning((r) => !r)}
                >
                  {running ? 'Pause' : 'Resume'}
                </Btn>
                <Btn size="sm" tone="ghost" icon={<X size={11} />} onClick={() => { setRunning(false); setIdx(DEMO_STEPS.length) }}>
                  Stop
                </Btn>
              </>
            )
          )}
        </div>
      </div>

      {/* Split pane */}
      <div
        style={{
          display: 'grid',
          gridTemplateColumns: '1fr 360px',
          flex: 1,
          minHeight: 0,
        }}
      >
        <MainCodePane />
        <ThinkingPanel
          steps={visible}
          liveChar={isRealMode ? Infinity : liveChar}
          feedRef={feedRef}
          running={!isRealMode && running && !isComplete}
          events={allEvents}
          evaluations={evaluations ?? []}
          isStreaming={streamingStatus?.isStreaming ?? false}
        />
      </div>
    </main>
  )
}
