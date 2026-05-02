'use client'

import { Btn } from '@/components/ui/btn'
import { Avatar } from '@/components/ui/avatar'
import { Pill } from '@/components/ui/pill'

// ── Inline code style (matches prototype) ────────────────────────────────────

const codeStyle: React.CSSProperties = {
  fontFamily: 'Geist Mono, monospace',
  fontSize: '90%',
  background: 'var(--bg-muted)',
  padding: '1px 4px',
  borderRadius: 3,
  border: '1px solid var(--border)',
}

function Code({ children }: { children: React.ReactNode }) {
  return <code style={codeStyle}>{children}</code>
}

// ── Check icon ───────────────────────────────────────────────────────────────

function CheckIcon({ size = 11 }: { size?: number }) {
  return (
    <svg
      width={size}
      height={size}
      viewBox="0 0 16 16"
      fill="none"
      stroke="currentColor"
      strokeWidth={2}
      strokeLinecap="round"
      strokeLinejoin="round"
      style={{ display: 'inline-block', flexShrink: 0 }}
    >
      <path d="M3 8l3.5 3.5L13 5" />
    </svg>
  )
}

// ── Slack message row ────────────────────────────────────────────────────────

interface MessageProps {
  sender: string
  isBot?: boolean
  time: string
  children: React.ReactNode
}

function Message({ sender, isBot, time, children }: MessageProps) {
  return (
    <div
      style={{
        display: 'flex',
        gap: 10,
        padding: '10px 14px',
        borderRadius: 4,
      }}
    >
      {isBot ? <Avatar bot size={34} /> : <Avatar name={sender} size={34} />}
      <div style={{ flex: 1, minWidth: 0 }}>
        <div style={{ display: 'flex', alignItems: 'baseline', gap: 8, marginBottom: 3 }}>
          <span
            style={{
              fontSize: 14,
              fontWeight: 600,
              color: 'var(--text)',
            }}
          >
            {sender}
          </span>
          {isBot && <Pill tone="brand" size="xs">APP</Pill>}
          <span
            style={{
              fontSize: 11,
              color: 'var(--text-subtle)',
            }}
          >
            {time}
          </span>
        </div>
        <div
          style={{
            fontSize: 13.5,
            color: 'var(--text)',
            lineHeight: 1.55,
          }}
        >
          {children}
        </div>
      </div>
    </div>
  )
}

// ── Action thread card ───────────────────────────────────────────────────────

function ThreadCard({
  rows,
}: {
  rows: { icon: string; label: string; detail: string }[]
}) {
  return (
    <div
      style={{
        marginTop: 8,
        border: '1px solid var(--panel-border)',
        borderRadius: 6,
        overflow: 'hidden',
        background: 'var(--panel)',
      }}
    >
      {rows.map((row, i) => (
        <div
          key={i}
          style={{
            display: 'flex',
            alignItems: 'center',
            gap: 8,
            padding: '7px 12px',
            borderBottom: i < rows.length - 1 ? '1px solid var(--divider)' : 'none',
            fontSize: 12,
          }}
        >
          <span style={{ color: 'var(--accent-500)' }}>{row.icon}</span>
          <span style={{ color: 'var(--text)', fontWeight: 500 }}>{row.label}</span>
          <span style={{ flex: 1 }} />
          <span style={{ color: 'var(--text-subtle)' }}>{row.detail}</span>
          <CheckIcon size={11} />
        </div>
      ))}
    </div>
  )
}

// ── PR embed card ────────────────────────────────────────────────────────────

function PrCard() {
  return (
    <div
      style={{
        marginTop: 8,
        border: '1px solid var(--panel-border)',
        borderLeft: '3px solid var(--accent-500)',
        borderRadius: 4,
        padding: '10px 14px',
        background: 'var(--panel)',
        maxWidth: 480,
      }}
    >
      <div
        style={{
          display: 'flex',
          alignItems: 'center',
          gap: 6,
          fontSize: 11.5,
          color: 'var(--text-subtle)',
          marginBottom: 3,
        }}
      >
        <span style={{ fontSize: 13 }}>🔄</span>
        go-tyme/payments-api
      </div>
      <div
        style={{
          fontSize: 13,
          fontWeight: 500,
          color: 'var(--text)',
          marginBottom: 4,
        }}
      >
        #2418 fix(payments): add rate-limit headers
      </div>
      <div
        style={{
          fontSize: 11.5,
          color: 'var(--status-success)',
        }}
      >
        Open · AI-reviewed · CI passing
      </div>
    </div>
  )
}

// ── Today divider ────────────────────────────────────────────────────────────

function TodayDivider() {
  return (
    <div
      role="separator"
      aria-label="Today"
      style={{
        padding: '8px 16px',
        fontSize: 11,
        color: 'var(--text-subtle)',
        textTransform: 'uppercase',
        letterSpacing: 0.6,
        display: 'flex',
        alignItems: 'center',
        gap: 8,
      }}
    >
      <div style={{ flex: 1, height: 1, background: 'var(--divider)' }} />
      <span>Today</span>
      <div style={{ flex: 1, height: 1, background: 'var(--divider)' }} />
    </div>
  )
}

// ── Typing indicator ─────────────────────────────────────────────────────────

function TypingDots() {
  return (
    <div style={{ display: 'flex', alignItems: 'center', gap: 8, color: 'var(--text-subtle)', fontSize: 13 }}>
      <div style={{ display: 'flex', gap: 3 }}>
        {[0, 1, 2].map((i) => (
          <span
            key={i}
            style={{
              width: 6,
              height: 6,
              borderRadius: 3,
              background: 'var(--accent-500)',
              animation: `gtx-pulse 1.2s ${i * 0.2}s infinite`,
            }}
          />
        ))}
      </div>
      <span>thinking&hellip;</span>
    </div>
  )
}

// ── Composer ──────────────────────────────────────────────────────────────────

function IconBtn({ children }: { children: React.ReactNode }) {
  return (
    <button
      style={{
        display: 'inline-flex',
        alignItems: 'center',
        justifyContent: 'center',
        width: 24,
        height: 24,
        border: 'none',
        background: 'transparent',
        color: 'var(--text-muted)',
        borderRadius: 4,
        cursor: 'pointer',
        padding: 0,
      }}
    >
      {children}
    </button>
  )
}

function IconPlus({ size = 13 }: { size?: number }) {
  return (
    <svg width={size} height={size} viewBox="0 0 16 16" fill="none"
      stroke="currentColor" strokeWidth={1.8} strokeLinecap="round"
      style={{ display: 'inline-block' }}>
      <path d="M8 3v10M3 8h10" />
    </svg>
  )
}

function IconWand({ size = 13 }: { size?: number }) {
  return (
    <svg width={size} height={size} viewBox="0 0 16 16" fill="none"
      stroke="currentColor" strokeWidth={1.5} strokeLinecap="round" strokeLinejoin="round"
      style={{ display: 'inline-block' }}>
      <path d="M2 14l10-10M9.5 2.5l1.5 2.5 2.5-1.5L12 6l2.5 1.5-2.5 1.5L12 12l-2.5-3" />
    </svg>
  )
}

function IconCode({ size = 13 }: { size?: number }) {
  return (
    <svg width={size} height={size} viewBox="0 0 16 16" fill="none"
      stroke="currentColor" strokeWidth={1.5} strokeLinecap="round" strokeLinejoin="round"
      style={{ display: 'inline-block' }}>
      <path d="M5 4L1 8l4 4M11 4l4 4-4 4" />
    </svg>
  )
}

function IconArrowRight({ size = 10 }: { size?: number }) {
  return (
    <svg width={size} height={size} viewBox="0 0 16 16" fill="none"
      stroke="currentColor" strokeWidth={2} strokeLinecap="round" strokeLinejoin="round"
      style={{ display: 'inline-block' }}>
      <path d="M3 8h10M9 4l4 4-4 4" />
    </svg>
  )
}

function SlackComposer() {
  return (
    <div
      style={{
        marginTop: 24,
        border: '1px solid var(--border-strong, var(--border))',
        borderRadius: 8,
        background: 'var(--bg)',
      }}
    >
      <div
        style={{
          padding: '10px 14px',
          minHeight: 60,
          fontSize: 13.5,
          color: 'var(--text-faint)',
        }}
      >
        Message #platform-ai
      </div>
      <div
        style={{
          padding: '6px 10px',
          borderTop: '1px solid var(--divider)',
          display: 'flex',
          gap: 6,
          alignItems: 'center',
        }}
      >
        <IconBtn><IconPlus size={13} /></IconBtn>
        <IconBtn><IconWand size={13} /></IconBtn>
        <IconBtn><IconCode size={13} /></IconBtn>
        <div style={{ flex: 1 }} />
        <Btn size="xs" tone="brand" iconRight={<IconArrowRight size={10} />}>
          Send
        </Btn>
      </div>
    </div>
  )
}

// ── Settings icon ────────────────────────────────────────────────────────────

function IconSettings({ size = 11 }: { size?: number }) {
  return (
    <svg width={size} height={size} viewBox="0 0 16 16" fill="none"
      stroke="currentColor" strokeWidth={1.5} strokeLinecap="round" strokeLinejoin="round"
      style={{ display: 'inline-block', flexShrink: 0 }}>
      <circle cx="8" cy="8" r="2" />
      <path d="M13.5 8a5.5 5.5 0 00-.1-1l1.3-1-1.3-2.2-1.6.5A5.5 5.5 0 0010.5 3L10 1.4H6L5.5 3a5.5 5.5 0 00-1.3.8l-1.6-.5L1.3 5.5l1.3 1A5.5 5.5 0 002.5 8v1l-1.3 1 1.3 2.2 1.6-.5c.4.3.8.6 1.3.8L6 14.6h4l.5-1.6c.5-.2.9-.5 1.3-.8l1.6.5 1.3-2.2-1.3-1v-1z" />
    </svg>
  )
}

// ── Page ──────────────────────────────────────────────────────────────────────

export default function SlackPage() {
  return (
    <main
      style={{
        flex: 1,
        overflow: 'hidden',
        display: 'flex',
        flexDirection: 'column',
      }}
    >
      {/* Header — matches Shell Header pattern */}
      <header
        style={{
          borderBottom: '1px solid var(--panel-border)',
          background: 'var(--bg)',
          padding: '0 20px',
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
              <span>Slack</span>
              <span style={{ fontSize: 10 }}>&rsaquo;</span>
              <span style={{ color: 'var(--text)' }}>gotyme-eng</span>
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
                #platform-ai
              </h1>
              <span style={{ fontSize: 12.5, color: 'var(--text-subtle)' }}>
                12 members · bot connected
              </span>
            </div>
          </div>
          <div style={{ display: 'flex', alignItems: 'center', gap: 6 }}>
            <Btn size="sm" icon={<IconSettings size={11} />}>
              Bot settings
            </Btn>
            <Btn size="sm" tone="ghost">
              Open in Slack
            </Btn>
          </div>
        </div>
      </header>

      {/* Message feed */}
      <div
        style={{
          flex: 1,
          overflowY: 'auto',
        }}
      >
        <div style={{ padding: '20px 24px', maxWidth: 780, margin: '0 auto' }}>
          <div style={{ borderLeft: '2px solid var(--divider)', paddingLeft: 4 }}>
            <TodayDivider />

            {/* Message 1 — Mira Delgado */}
            <Message sender="Mira Delgado" time="14:22">
              @gotyme-x-ai please add rate-limit headers to the{' '}
              <Code>/v2/transfers</Code> endpoint. Jira <Code>GTX-4821</Code>.
            </Message>

            {/* Message 2 — Bot, with thread card */}
            <Message sender="GoTyme-X AI" isBot time="14:22">
              <div>
                On it — reading <Code>GTX-4821</Code> now. I&apos;ll draft a PR on{' '}
                <Code>go-tyme/payments-api</Code>.
              </div>
              <ThreadCard
                rows={[
                  {
                    icon: '📋',
                    label: 'Fetched ticket',
                    detail: 'GTX-4821 · 3 ACs · assigned me',
                  },
                  {
                    icon: '🔗',
                    label: 'Cloned repo',
                    detail: 'go-tyme/payments-api @ main',
                  },
                  {
                    icon: '⋯',
                    label: 'Planned',
                    detail: '4 files · middleware + tests + docs',
                  },
                ]}
              />
            </Message>

            {/* Message 3 — Bot, PR opened */}
            <Message sender="GoTyme-X AI" isBot time="14:36">
              <div>
                <strong>PR #2418</strong> opened:{' '}
                <span style={{ color: '#1264a3' }}>fix(payments): add rate-limit headers</span>
                <br />
                7 files · +142 &minus;38 · <Code>fix/GTX-4821-rate-limit</Code>
              </div>
              <PrCard />
            </Message>

            {/* Message 4 — Jamie Ortiz */}
            <Message sender="Jamie Ortiz" time="14:38">
              Nice. Can you also bump the docs?
            </Message>

            {/* Message 5 — Bot */}
            <Message sender="GoTyme-X AI" isBot time="14:38">
              Already did — see <Code>docs/rate-limiting.md</Code> in the PR. Let me
              know if the wording works.
            </Message>

            {/* Message 6 — Mira Delgado */}
            <Message sender="Mira Delgado" time="14:42">
              LGTM. Approving.
            </Message>

            {/* Message 7 — Bot typing */}
            <Message sender="GoTyme-X AI" isBot time="14:42">
              <TypingDots />
            </Message>
          </div>

          <SlackComposer />
        </div>
      </div>
    </main>
  )
}
