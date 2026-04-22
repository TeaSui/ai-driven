// Coding Agent screen — watch the AI work in real-time.

const { useState: cgS, useEffect: cgE, useRef: cgR } = React;

const AGENT_STEPS = [
  { kind: 'think', t: 0,    text: 'Reading ticket GTX-4829 — "Fix race condition in ledger balance cache"' },
  { kind: 'tool',  t: 400,  name: 'jira.getIssue', args: 'GTX-4829', result: 'Found · 3 comments, 2 ACs' },
  { kind: 'think', t: 900,  text: 'The description mentions intermittent failures when two concurrent transfers read the same account balance. Need to locate the cache layer.' },
  { kind: 'tool',  t: 1400, name: 'github.search', args: 'BalanceCache ledger-core', result: '6 matches across 3 files' },
  { kind: 'tool',  t: 1700, name: 'fs.readFile', args: 'internal/cache/balance.go', result: '218 lines' },
  { kind: 'think', t: 2100, text: 'I see it — `Get()` does a check-then-set without locking. Under concurrent load, two callers can both miss the cache and write stale reads. Need a sync.Map + singleflight.' },
  { kind: 'tool',  t: 2600, name: 'fs.readFile', args: 'internal/cache/balance_test.go', result: '94 lines · 4 tests' },
  { kind: 'think', t: 3000, text: 'Existing tests don\'t cover concurrency. I\'ll add a race test that reproduces the bug first, then fix it.' },
  { kind: 'edit',  t: 3400, file: 'internal/cache/balance_test.go', op: '+41 −0', summary: 'Add TestBalanceCache_ConcurrentReads' },
  { kind: 'tool',  t: 3900, name: 'shell.run', args: 'go test -race ./internal/cache/...', result: 'FAIL · DATA RACE detected' },
  { kind: 'think', t: 4300, text: 'Reproduced. Now fixing.' },
  { kind: 'edit',  t: 4700, file: 'internal/cache/balance.go', op: '+17 −17', summary: 'Wrap Get() in singleflight.Group' },
  { kind: 'tool',  t: 5300, name: 'shell.run', args: 'go test -race ./internal/cache/...', result: 'PASS · 4/4 tests, 0 races' },
  { kind: 'think', t: 5800, text: 'Fix works. Running the full suite + linting before opening a PR.' },
  { kind: 'tool',  t: 6300, name: 'shell.run', args: 'go test ./... && golangci-lint run', result: 'PASS · 312/312 tests' },
  { kind: 'tool',  t: 6900, name: 'git.commit', args: 'fix(ledger): guard BalanceCache.Get() with singleflight', result: 'a3f21c8' },
  { kind: 'tool',  t: 7300, name: 'github.openPR', args: 'fix/GTX-4829-ledger-race → main', result: 'PR #2419 opened' },
];

function CodingAgentScreen({ showThinking = true }) {
  const [running, setRunning] = cgS(true);
  const [idx, setIdx] = cgS(8); // start mid-run for visual interest
  const [liveChar, setLiveChar] = cgS(0);
  const feedRef = cgR(null);

  cgE(() => {
    if (!running) return;
    const totalSteps = AGENT_STEPS.length;
    if (idx >= totalSteps) return;
    const step = AGENT_STEPS[idx];
    const text = step.text || step.summary || '';
    if (step.kind === 'think' && liveChar < text.length) {
      const t = setTimeout(() => setLiveChar(c => Math.min(text.length, c + 3)), 18);
      return () => clearTimeout(t);
    }
    const t = setTimeout(() => { setIdx(i => i + 1); setLiveChar(0); }, step.kind === 'think' ? 600 : 420);
    return () => clearTimeout(t);
  }, [running, idx, liveChar]);

  cgE(() => {
    if (feedRef.current) feedRef.current.scrollTop = feedRef.current.scrollHeight;
  }, [idx, liveChar]);

  const visible = AGENT_STEPS.slice(0, idx + 1);

  return (
    <Shell active="agent" headerProps={{
      title: 'Coding Agent',
      crumbs: ['Runs', 'GTX-4829'],
      sub: 'Fix race condition in ledger balance cache',
      right: (
        <>
          <Pill tone="info" dot>
            <Spinner size={9} color="currentColor"/>
            {running ? 'Running · 8m 04s' : 'Paused'}
          </Pill>
          <Btn size="sm" icon={running ? <Ic.Pause size={11}/> : <Ic.Play size={11}/>} onClick={() => setRunning(r=>!r)}>
            {running ? 'Pause' : 'Resume'}
          </Btn>
          <Btn size="sm" tone="ghost" icon={<Ic.X size={11}/>}>Stop</Btn>
        </>
      ),
    }}>
      <div style={{ display: 'grid', gridTemplateColumns: showThinking ? '1fr 360px' : '1fr', height: '100%' }}>
        <MainCodePane idx={idx}/>
        {showThinking && <ThinkingPanel steps={visible} liveChar={liveChar} feedRef={feedRef} running={running}/>}
      </div>
    </Shell>
  );
}

function MainCodePane({ idx }) {
  return (
    <div style={{ borderRight: '1px solid var(--panel-border)', display: 'flex', flexDirection: 'column', minHeight: 0 }}>
      {/* File tabs */}
      <div style={{ display: 'flex', alignItems: 'stretch', borderBottom: '1px solid var(--panel-border)', background: 'var(--panel)', paddingLeft: 8 }}>
        <FileTab file="balance.go" dir="internal/cache" active dirty/>
        <FileTab file="balance_test.go" dir="internal/cache" added/>
        <div style={{ flex: 1 }}/>
        <div style={{ display: 'flex', alignItems: 'center', gap: 8, padding: '0 12px', fontSize: 11.5, color: 'var(--text-subtle)' }}>
          <span className="gtx-mono">main</span>
          <Ic.ArrowRight size={10}/>
          <span className="gtx-mono" style={{ color: 'var(--accent-600)' }}>fix/GTX-4829-ledger-race</span>
        </div>
      </div>

      {/* Code */}
      <div style={{ flex: 1, overflow: 'auto', background: 'var(--code-bg)', fontFamily: 'Geist Mono, monospace', fontSize: 12.5, lineHeight: 1.65 }}>
        <CodeBlock/>
      </div>

      {/* Terminal strip */}
      <div style={{ borderTop: '1px solid var(--panel-border)', background: 'var(--bg-elev)' }}>
        <div style={{ display: 'flex', alignItems: 'center', gap: 8, padding: '8px 14px',
          borderBottom: '1px solid var(--panel-border)', fontSize: 11.5, color: 'var(--text-muted)' }}>
          <Ic.Terminal size={12}/> Terminal
          <span style={{ color: 'var(--text-faint)' }}>·</span>
          <span className="gtx-mono" style={{ fontSize: 11 }}>sandbox-e4c9</span>
          <div style={{ flex: 1 }}/>
          <Pill tone="success" size="xs" dot>PASS</Pill>
        </div>
        <div className="gtx-mono" style={{ padding: '10px 14px', fontSize: 11.5, lineHeight: 1.7, color: 'var(--text-muted)', maxHeight: 140, overflow: 'auto' }}>
          <div><span style={{ color: 'var(--accent-500)' }}>$</span> go test -race ./internal/cache/...</div>
          <div style={{ color: 'var(--text-subtle)' }}>ok  internal/cache  0.842s <span style={{ color: 'var(--status-success)' }}>(-race)</span></div>
          <div><span style={{ color: 'var(--accent-500)' }}>$</span> go test ./...</div>
          <div style={{ color: 'var(--text-subtle)' }}>ok  ./... <span style={{ color: 'var(--status-success)' }}>312/312 tests</span></div>
          <div><span style={{ color: 'var(--accent-500)' }}>$</span> git commit -m "fix(ledger): guard BalanceCache.Get() with singleflight"</div>
          <div style={{ color: 'var(--text-subtle)' }}>[fix/GTX-4829-ledger-race a3f21c8] fix(ledger)...</div>
          <div><span style={{ color: 'var(--accent-500)' }}>$</span> gh pr create --fill<span style={{ animation: 'gtx-blink 1s step-end infinite', marginLeft: 2 }}>▋</span></div>
        </div>
      </div>
    </div>
  );
}

function FileTab({ file, dir, active, dirty, added }) {
  return (
    <div style={{
      display: 'inline-flex', alignItems: 'center', gap: 6,
      padding: '10px 14px', borderRight: '1px solid var(--panel-border)',
      background: active ? 'var(--bg)' : 'transparent',
      borderBottom: active ? '2px solid var(--accent-500)' : '2px solid transparent',
      fontSize: 12, color: active ? 'var(--text)' : 'var(--text-muted)', cursor: 'pointer',
      position: 'relative', marginBottom: -1,
    }}>
      <Ic.File size={11}/>
      <span className="gtx-mono" style={{ color: 'var(--text-subtle)' }}>{dir}/</span>
      <span className="gtx-mono" style={{ fontWeight: active ? 500 : 400 }}>{file}</span>
      {added && <span style={{ fontSize: 10, color: 'var(--status-success)', fontWeight: 600 }}>NEW</span>}
      {dirty && !added && <span style={{ width: 5, height: 5, borderRadius: 3, background: 'var(--accent-500)' }}/>}
    </div>
  );
}

function CodeBlock() {
  const lines = [
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
  ];
  return (
    <div style={{ minWidth: 'max-content' }}>
      {lines.map((l, i) => (
        <div key={i} style={{
          display: 'flex',
          background: l.add ? 'var(--diff-add-bg)' : l.del ? 'var(--diff-del-bg)' : 'transparent',
          borderLeft: `2px solid ${l.add ? 'var(--diff-add-line)' : l.del ? 'var(--diff-del-line)' : 'transparent'}`,
        }}>
          <span style={{ width: 48, padding: '0 10px', textAlign: 'right', color: 'var(--text-faint)', fontSize: 11, userSelect: 'none', flexShrink: 0 }}>{l.n}</span>
          <span style={{ width: 14, textAlign: 'center', color: l.add ? 'var(--status-success)' : l.del ? 'var(--status-danger)' : 'transparent', flexShrink: 0 }}>
            {l.add ? '+' : l.del ? '−' : ' '}
          </span>
          <span style={{ flex: 1, color: 'var(--code-text)', whiteSpace: 'pre' }}>{l.t}</span>
        </div>
      ))}
      <div style={{ height: 40 }}/>
    </div>
  );
}

function ThinkingPanel({ steps, liveChar, feedRef, running }) {
  return (
    <div style={{ background: 'var(--panel)', display: 'flex', flexDirection: 'column', minHeight: 0 }}>
      <div style={{ padding: '12px 16px', borderBottom: '1px solid var(--panel-border)',
        display: 'flex', alignItems: 'center', gap: 8 }}>
        <BrandMark size={14}/>
        <span style={{ fontSize: 12.5, fontWeight: 500, color: 'var(--text)' }}>Thinking stream</span>
        {running && <StatusDot tone="success" pulse size={6}/>}
        <div style={{ flex: 1 }}/>
        <span style={{ fontSize: 10.5, color: 'var(--text-faint)' }}>{steps.length} steps</span>
      </div>
      <div ref={feedRef} style={{ flex: 1, overflowY: 'auto', padding: '12px 16px' }}>
        {steps.map((s, i) => {
          const isLast = i === steps.length - 1;
          const displayText = isLast && s.kind === 'think'
            ? (s.text || '').slice(0, liveChar)
            : (s.text || '');
          return (
            <StepCard key={i} step={s} displayText={displayText} isLast={isLast} running={running}/>
          );
        })}
      </div>
    </div>
  );
}

function StepCard({ step, displayText, isLast, running }) {
  if (step.kind === 'think') {
    return (
      <div style={{ display: 'flex', gap: 10, marginBottom: 14, animation: 'gtx-fadein .25s' }}>
        <div style={{ width: 4, background: 'var(--accent-400)', borderRadius: 2, opacity: .4, flexShrink: 0 }}/>
        <div style={{ flex: 1, fontSize: 12.5, color: 'var(--text-muted)', lineHeight: 1.5 }}>
          {displayText}
          {isLast && running && <span style={{ animation: 'gtx-blink 1s step-end infinite', color: 'var(--accent-500)' }}>▋</span>}
        </div>
      </div>
    );
  }
  if (step.kind === 'tool') {
    return (
      <div style={{ marginBottom: 10, animation: 'gtx-fadein .25s' }}>
        <div style={{ display: 'flex', alignItems: 'center', gap: 6, marginBottom: 4 }}>
          <Ic.Wand size={11} color="var(--accent-500)"/>
          <span className="gtx-mono" style={{ fontSize: 11, color: 'var(--text)', fontWeight: 500 }}>{step.name}</span>
          <span className="gtx-mono" style={{ fontSize: 10.5, color: 'var(--text-subtle)' }}>({step.args})</span>
        </div>
        <div style={{ marginLeft: 17, fontSize: 11.5, color: 'var(--text-subtle)',
          padding: '4px 8px', background: 'var(--bg-muted)', borderRadius: 4,
          border: '1px solid var(--border)', fontFamily: 'Geist Mono, monospace' }}>
          ← {step.result}
        </div>
      </div>
    );
  }
  if (step.kind === 'edit') {
    return (
      <div style={{ display: 'flex', gap: 10, marginBottom: 10, padding: 10,
        background: 'var(--accent-soft)', border: '1px solid var(--brand-teal-200)',
        borderRadius: 6, animation: 'gtx-fadein .25s' }}>
        <Ic.Code size={12} color="var(--accent-600)" style={{ marginTop: 2 }}/>
        <div style={{ flex: 1, minWidth: 0 }}>
          <div className="gtx-mono" style={{ fontSize: 11, color: 'var(--accent-600)', fontWeight: 500, marginBottom: 2 }}>
            {step.file} <span style={{ color: 'var(--text-subtle)' }}>{step.op}</span>
          </div>
          <div style={{ fontSize: 12, color: 'var(--text)' }}>{step.summary}</div>
        </div>
      </div>
    );
  }
  return null;
}

Object.assign(window, { CodingAgentScreen });
