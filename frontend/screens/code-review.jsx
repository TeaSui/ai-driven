// Code Review screen — PR diff with inline AI comments.
const { useState: crS } = React;

const REVIEW_COMMENTS = [
  { line: 47, severity: 'warn', title: 'Potential nil deref',
    body: 'If `c.loader` is nil (possible during tests with a zero-value cache), this will panic. Consider a guard or documented precondition.',
    confidence: 82, category: 'safety' },
  { line: 53, severity: 'info', title: 'Consider context cancellation',
    body: '`singleflight.Do` doesn\'t respect `ctx`. If the caller cancels, the in-flight call still completes. Use `DoChan` + `select` if cancellation matters here.',
    confidence: 91, category: 'correctness',
    suggestion: `ch := c.sf.DoChan(accountID, fn)\nselect {\ncase <-ctx.Done(): return nil, ctx.Err()\ncase r := <-ch: return r.Val.(*Balance), r.Err\n}` },
  { line: 57, severity: 'nit', title: 'Consider typed return',
    body: 'The `interface{}` → `*Balance` assertion is safe but noisy. Since Go 1.21, `singleflight` has generic variants; you could use `singleflight.Group[string, *Balance]{}`.',
    confidence: 68, category: 'style' },
];

function CodeReviewScreen() {
  const [tab, setTab] = crS('review');
  return (
    <Shell active="review" headerProps={{
      title: 'PR #2419',
      crumbs: ['go-tyme/ledger-core', 'Pull requests', '#2419'],
      sub: 'fix(ledger): guard BalanceCache.Get() with singleflight',
      right: (
        <>
          <Pill tone="info" dot><Spinner size={9}/>Reviewing</Pill>
          <Btn size="sm" icon={<Ic.ArrowUpRight size={11}/>}>Open in GitHub</Btn>
          <Btn size="sm" tone="brand" icon={<Ic.Check size={11}/>}>Approve</Btn>
        </>
      ),
      tabs: [
        { id: 'conversation', label: 'Conversation', count: 5 },
        { id: 'review', label: 'AI Review', count: 3, icon: <BrandMark size={12}/> },
        { id: 'commits', label: 'Commits', count: 2 },
        { id: 'checks', label: 'Checks', count: 8, icon: <Ic.Check size={11} color="var(--status-success)"/> },
      ],
      activeTab: tab, onTab: setTab,
    }}>
      <ReviewSummaryBar/>
      <div style={{ display: 'grid', gridTemplateColumns: '1fr 340px', gap: 0 }}>
        <div style={{ padding: '16px 20px', borderRight: '1px solid var(--panel-border)', minHeight: 0 }}>
          <FileReviewBlock/>
        </div>
        <ReviewSidebar/>
      </div>
    </Shell>
  );
}

function ReviewSummaryBar() {
  return (
    <div style={{ padding: '14px 20px', borderBottom: '1px solid var(--panel-border)',
      background: 'var(--panel)', display: 'flex', alignItems: 'center', gap: 14 }}>
      <Avatar bot size={32}/>
      <div style={{ flex: 1 }}>
        <div style={{ fontSize: 13, fontWeight: 500, color: 'var(--text)', marginBottom: 2 }}>
          AI review complete · <span style={{ color: 'var(--text-muted)', fontWeight: 400 }}>3 issues flagged, 0 blocking</span>
        </div>
        <div style={{ fontSize: 11.5, color: 'var(--text-subtle)' }}>
          Analyzed 7 files · 200 lines · 14 rules from <Code>.gotyme/review.yml</Code> · took 12s
        </div>
      </div>
      <div style={{ display: 'flex', gap: 10 }}>
        <SummaryChip count={1} label="Safety" tone="warn"/>
        <SummaryChip count={1} label="Correctness" tone="info"/>
        <SummaryChip count={1} label="Style" tone="default"/>
      </div>
    </div>
  );
}

function SummaryChip({ count, label, tone }) {
  const c = tone === 'warn' ? 'var(--status-warn)' : tone === 'info' ? 'var(--accent-600)' : 'var(--text-muted)';
  return (
    <div style={{ textAlign: 'center', padding: '6px 14px', borderLeft: '1px solid var(--panel-border)' }}>
      <div style={{ fontSize: 18, fontWeight: 600, color: c, fontVariantNumeric: 'tabular-nums' }}>{count}</div>
      <div style={{ fontSize: 10.5, color: 'var(--text-subtle)', textTransform: 'uppercase', letterSpacing: 0.6 }}>{label}</div>
    </div>
  );
}

function FileReviewBlock() {
  const lines = [
    { n: 42, t: 'func (c *BalanceCache) Get(ctx context.Context, accountID string) (*Balance, error) {' },
    { n: 43, t: '\tv, err, _ := c.sf.Do(accountID, func() (interface{}, error) {', add: true },
    { n: 44, t: '\t\tif v, ok := c.m.Load(accountID); ok {', add: true },
    { n: 45, t: '\t\t\treturn v.(*Balance), nil', add: true },
    { n: 46, t: '\t\t}', add: true },
    { n: 47, t: '\t\tb, err := c.loader.Load(ctx, accountID)', add: true, comment: REVIEW_COMMENTS[0] },
    { n: 48, t: '\t\tif err != nil {', add: true },
    { n: 49, t: '\t\t\treturn nil, err', add: true },
    { n: 50, t: '\t\t}', add: true },
    { n: 51, t: '\t\tc.m.Store(accountID, b)', add: true },
    { n: 52, t: '\t\treturn b, nil', add: true },
    { n: 53, t: '\t})', add: true, comment: REVIEW_COMMENTS[1] },
    { n: 54, t: '\tif err != nil {', add: true },
    { n: 55, t: '\t\treturn nil, err', add: true },
    { n: 56, t: '\t}', add: true },
    { n: 57, t: '\treturn v.(*Balance), nil', add: true, comment: REVIEW_COMMENTS[2] },
    { n: 58, t: '}', add: true },
  ];
  return (
    <div style={{ background: 'var(--bg-elev)', border: '1px solid var(--panel-border)', borderRadius: 8, overflow: 'hidden' }}>
      <div style={{ padding: '10px 14px', borderBottom: '1px solid var(--panel-border)', display: 'flex', alignItems: 'center', gap: 8, background: 'var(--panel)' }}>
        <Ic.File size={13} color="var(--text-muted)"/>
        <span className="gtx-mono" style={{ fontSize: 12, color: 'var(--text)' }}>internal/cache/balance.go</span>
        <Pill tone="success" size="xs">+17</Pill>
        <Pill tone="danger" size="xs">−17</Pill>
        <div style={{ flex: 1 }}/>
        <span style={{ fontSize: 11, color: 'var(--text-subtle)' }}>3 AI comments</span>
      </div>
      <div style={{ fontFamily: 'Geist Mono, monospace', fontSize: 12.5, lineHeight: 1.65, background: 'var(--code-bg)' }}>
        {lines.map((l, i) => (
          <React.Fragment key={i}>
            <div style={{
              display: 'flex',
              background: l.add ? 'var(--diff-add-bg)' : 'transparent',
              borderLeft: `2px solid ${l.add ? 'var(--diff-add-line)' : 'transparent'}`,
            }}>
              <span style={{ width: 44, padding: '0 10px', textAlign: 'right', color: 'var(--text-faint)', fontSize: 11, userSelect: 'none', flexShrink: 0 }}>{l.n}</span>
              <span style={{ width: 14, color: l.add ? 'var(--status-success)' : 'transparent', flexShrink: 0, textAlign: 'center' }}>{l.add ? '+' : ' '}</span>
              <span style={{ color: 'var(--code-text)', whiteSpace: 'pre' }}>{l.t}</span>
            </div>
            {l.comment && <InlineComment c={l.comment}/>}
          </React.Fragment>
        ))}
      </div>
    </div>
  );
}

function InlineComment({ c }) {
  const tone = c.severity === 'warn' ? 'warn' : c.severity === 'info' ? 'info' : 'default';
  const color = c.severity === 'warn' ? 'var(--status-warn)' : c.severity === 'info' ? 'var(--accent-600)' : 'var(--text-muted)';
  return (
    <div style={{ padding: '12px 14px 12px 58px', background: 'var(--panel)',
      borderTop: '1px solid var(--panel-border)', borderBottom: '1px solid var(--panel-border)', fontFamily: 'Geist, sans-serif' }}>
      <div style={{ display: 'flex', alignItems: 'flex-start', gap: 10 }}>
        <Avatar bot size={24}/>
        <div style={{ flex: 1, minWidth: 0 }}>
          <div style={{ display: 'flex', alignItems: 'center', gap: 8, marginBottom: 4 }}>
            <span style={{ fontSize: 12.5, fontWeight: 500, color: 'var(--text)' }}>GoTyme-X AI</span>
            <Pill tone={tone} size="xs" dot>{c.severity.toUpperCase()}</Pill>
            <Pill tone="default" size="xs">{c.category}</Pill>
            <span style={{ fontSize: 10.5, color: 'var(--text-faint)' }}>· confidence {c.confidence}%</span>
          </div>
          <div style={{ fontSize: 13, color: 'var(--text)', fontWeight: 500, marginBottom: 4 }}>{c.title}</div>
          <div style={{ fontSize: 12.5, color: 'var(--text-muted)', lineHeight: 1.5 }}>{c.body}</div>
          {c.suggestion && (
            <div style={{ marginTop: 8, background: 'var(--code-bg)', border: '1px solid var(--panel-border)', borderRadius: 6, overflow: 'hidden' }}>
              <div style={{ padding: '6px 10px', borderBottom: '1px solid var(--panel-border)', fontSize: 11, color: 'var(--text-subtle)', display: 'flex', alignItems: 'center', gap: 6 }}>
                <Ic.Wand size={10} color={color}/> Suggested change
              </div>
              <pre className="gtx-mono" style={{ margin: 0, padding: 10, fontSize: 11.5, color: 'var(--code-text)', overflow: 'auto' }}>{c.suggestion}</pre>
            </div>
          )}
          <div style={{ display: 'flex', gap: 6, marginTop: 8 }}>
            <Btn size="xs" tone="ghost" icon={<Ic.Check size={10}/>}>Apply</Btn>
            <Btn size="xs" tone="bare">Reply</Btn>
            <Btn size="xs" tone="bare">Dismiss</Btn>
            <div style={{ flex: 1 }}/>
            <Btn size="xs" tone="bare">👍 4</Btn>
          </div>
        </div>
      </div>
    </div>
  );
}

function ReviewSidebar() {
  return (
    <div style={{ padding: '16px 20px', fontSize: 12.5 }}>
      <div style={{ fontSize: 11, color: 'var(--text-subtle)', textTransform: 'uppercase', letterSpacing: 0.6, marginBottom: 10 }}>Reviewers</div>
      <div style={{ display: 'flex', flexDirection: 'column', gap: 8, marginBottom: 18 }}>
        <ReviewerRow name="GoTyme-X AI" bot status="approved" comment="3 comments"/>
        <ReviewerRow name="Mira Delgado" status="pending"/>
        <ReviewerRow name="Jamie Ortiz" status="approved"/>
      </div>

      <div style={{ fontSize: 11, color: 'var(--text-subtle)', textTransform: 'uppercase', letterSpacing: 0.6, marginBottom: 10 }}>Checks</div>
      <div style={{ display: 'flex', flexDirection: 'column', gap: 6, marginBottom: 18 }}>
        <CheckRow name="ci / unit" ok time="42s"/>
        <CheckRow name="ci / integration" ok time="3m 18s"/>
        <CheckRow name="ci / race-detector" ok time="1m 04s"/>
        <CheckRow name="ci / lint" ok time="22s"/>
        <CheckRow name="security / snyk" ok time="1m 45s"/>
        <CheckRow name="coverage / 87%" ok/>
        <CheckRow name="preview / staging" ok time="deployed"/>
      </div>

      <div style={{ fontSize: 11, color: 'var(--text-subtle)', textTransform: 'uppercase', letterSpacing: 0.6, marginBottom: 10 }}>Linked</div>
      <div style={{ fontSize: 12, color: 'var(--text-muted)', display: 'flex', flexDirection: 'column', gap: 6 }}>
        <a style={{ color: 'var(--accent-600)', textDecoration: 'none', display: 'flex', gap: 6, alignItems: 'center' }}>
          <Ic.Jira size={11}/><span className="gtx-mono">GTX-4829</span> Fix race condition…
        </a>
        <a style={{ color: 'var(--accent-600)', textDecoration: 'none', display: 'flex', gap: 6, alignItems: 'center' }}>
          <Ic.Datadog size={11}/>Alert-8821 balance-cache errors
        </a>
      </div>
    </div>
  );
}

function ReviewerRow({ name, bot, status, comment }) {
  return (
    <div style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
      {bot ? <Avatar bot size={22}/> : <Avatar name={name} size={22}/>}
      <div style={{ flex: 1, minWidth: 0 }}>
        <div style={{ fontSize: 12.5, color: 'var(--text)' }}>{name}</div>
        {comment && <div style={{ fontSize: 10.5, color: 'var(--text-subtle)' }}>{comment}</div>}
      </div>
      {status === 'approved' && <Pill tone="success" size="xs" icon={<Ic.Check size={9}/>}>Approved</Pill>}
      {status === 'pending' && <Pill tone="default" size="xs">Pending</Pill>}
    </div>
  );
}

function CheckRow({ name, ok, time }) {
  return (
    <div style={{ display: 'flex', alignItems: 'center', gap: 8, fontSize: 12, color: 'var(--text-muted)' }}>
      <Ic.Check size={11} color={ok ? 'var(--status-success)' : 'var(--text-faint)'}/>
      <span className="gtx-mono" style={{ flex: 1 }}>{name}</span>
      {time && <span style={{ fontSize: 10.5, color: 'var(--text-faint)' }}>{time}</span>}
    </div>
  );
}

Object.assign(window, { CodeReviewScreen });
