// Overview / hero screen — ties the platform together.

function OverviewScreen() {
  return (
    <Shell active="timeline" headerProps={{
      title: 'Overview',
      sub: 'What the agents did for you today',
      right: (<>
        <Btn size="sm" icon={<Ic.Clock size={12}/>}>Today</Btn>
        <Btn size="sm" tone="brand" icon={<Ic.Bot size={12}/>}>New run</Btn>
      </>),
    }}>
      <div style={{ padding: 0, display: 'flex', flexDirection: 'column' }}>
        <HeroBanner/>
        <AgentsRow/>
        <LiveFeed/>
      </div>
    </Shell>
  );
}

function HeroBanner() {
  return (
    <div style={{
      position: 'relative', overflow: 'hidden',
      borderBottom: '1px solid var(--panel-border)',
      background: 'linear-gradient(135deg, var(--brand-teal-50), var(--bg))',
      padding: '36px 32px 32px',
    }}>
      {/* large decorative X from the mark */}
      <svg viewBox="0 0 200 200" style={{ position: 'absolute', right: -30, top: -30, width: 360, height: 360, opacity: .08, pointerEvents: 'none' }}>
        <path d="M30 30 L100 100 L170 30 M30 170 L100 100 L170 170 M100 100 L100 145" stroke="var(--accent-500)" strokeWidth="12" fill="none" strokeLinecap="square"/>
      </svg>
      <div style={{ position: 'relative', maxWidth: 780 }}>
        <div style={{ display: 'inline-flex', alignItems: 'center', gap: 8, padding: '4px 10px',
          background: 'var(--panel)', border: '1px solid var(--brand-teal-200)', borderRadius: 999,
          fontSize: 11, color: 'var(--accent-600)', marginBottom: 14, fontWeight: 500 }}>
          <StatusDot tone="success" pulse size={6}/>
          4 agents online · processing 7 runs
        </div>
        <h1 className="gtx-serif" style={{ margin: 0, fontSize: 44, fontWeight: 400, letterSpacing: -1.2, color: 'var(--text)', lineHeight: 1.05 }}>
          Your engineering org, <em style={{ color: 'var(--accent-600)' }}>augmented</em>.
        </h1>
        <div style={{ fontSize: 14, color: 'var(--text-muted)', marginTop: 10, maxWidth: 620, lineHeight: 1.5 }}>
          Jira tickets become PRs. PRs are reviewed inline. Alerts get triaged before you wake up. Every step is observable, attributable, and overridable.
        </div>
        <div style={{ display: 'flex', gap: 18, marginTop: 22 }}>
          <HeroStat n="412" l="PRs merged" s="this month"/>
          <HeroStat n="1,284h" l="Engineering hours saved"/>
          <HeroStat n="89" l="Incidents auto-triaged"/>
          <HeroStat n="14m" l="Avg cycle time" tone="success"/>
        </div>
      </div>
    </div>
  );
}

function HeroStat({ n, l, s, tone }) {
  return (
    <div>
      <div style={{ fontSize: 26, fontWeight: 600, color: tone === 'success' ? 'var(--status-success)' : 'var(--text)',
        letterSpacing: -0.6, fontVariantNumeric: 'tabular-nums', lineHeight: 1.1 }}>{n}</div>
      <div style={{ fontSize: 11.5, color: 'var(--text-subtle)' }}>{l}{s && <span style={{ marginLeft: 4, color: 'var(--text-faint)' }}>· {s}</span>}</div>
    </div>
  );
}

function AgentsRow() {
  const agents = [
    { name: 'Coding', icon: <Ic.Code size={14}/>, status: 'Working', detail: '3 active runs · GTX-4829 +2', tone: 'info', pulse: true },
    { name: 'Review', icon: <Ic.GitPr size={14}/>, status: 'Idle', detail: '12 PRs reviewed today · 98% approval' },
    { name: 'DevOps', icon: <Ic.Alert size={14}/>, status: 'Investigating', detail: 'Alert-8821 · ledger-core', tone: 'warn', pulse: true },
    { name: 'Workflow', icon: <Ic.Git size={14}/>, status: 'Orchestrating', detail: '7 pipelines · 2 awaiting deploy', tone: 'info' },
  ];
  return (
    <div style={{ padding: '20px 32px 12px', display: 'grid', gridTemplateColumns: 'repeat(4,1fr)', gap: 12 }}>
      {agents.map(a => (
        <div key={a.name} style={{
          background: 'var(--panel)', border: '1px solid var(--panel-border)', borderRadius: 10,
          padding: 14, display: 'flex', alignItems: 'flex-start', gap: 12,
        }}>
          <div style={{ width: 34, height: 34, borderRadius: 8,
            background: a.tone === 'warn' ? 'var(--status-warn-bg)' : 'var(--accent-soft)',
            color: a.tone === 'warn' ? 'var(--status-warn)' : 'var(--accent-600)',
            display: 'flex', alignItems: 'center', justifyContent: 'center', flexShrink: 0 }}>{a.icon}</div>
          <div style={{ flex: 1, minWidth: 0 }}>
            <div style={{ fontSize: 13.5, fontWeight: 500, color: 'var(--text)' }}>{a.name} Agent</div>
            <div style={{ fontSize: 11.5, color: 'var(--text-subtle)', marginTop: 2 }}>{a.detail}</div>
          </div>
          <Pill tone={a.tone || 'default'} size="xs" dot>{a.pulse && <Spinner size={8}/>}{a.status}</Pill>
        </div>
      ))}
    </div>
  );
}

function LiveFeed() {
  const events = [
    { t: '2m ago', icon: <Ic.GitPr size={12}/>, tone: 'info', title: 'Opened PR #2419', sub: 'fix(ledger): guard BalanceCache.Get() with singleflight · go-tyme/ledger-core' },
    { t: '4m ago', icon: <Ic.Alert size={12}/>, tone: 'warn', title: 'Alert triaged · A-8821', sub: 'RCA posted · rollback PR drafted · confidence 89%' },
    { t: '8m ago', icon: <Ic.Check size={12}/>, tone: 'success', title: 'Merged PR #2418', sub: 'fix(payments): add rate-limit headers · 2 approvals · CI green' },
    { t: '14m ago', icon: <Ic.Comment size={12}/>, tone: 'default', title: 'Review · 3 comments on #2419', sub: '1 safety · 1 correctness · 1 style · 0 blocking' },
    { t: '22m ago', icon: <Ic.Jira size={12}/>, tone: 'default', title: 'Picked up GTX-4830', sub: 'Add idempotency key validation to /webhooks' },
    { t: '36m ago', icon: <Ic.Aws size={12}/>, tone: 'success', title: 'Deployed to prod', sub: 'auth-service v2.41.3 · 0 errors · 0 rollbacks' },
  ];
  return (
    <div style={{ padding: '12px 32px 32px' }}>
      <div style={{ display: 'flex', alignItems: 'baseline', gap: 12, padding: '8px 0 12px' }}>
        <h3 style={{ margin: 0, fontSize: 13, fontWeight: 500, color: 'var(--text)' }}>Live feed</h3>
        <span style={{ fontSize: 11.5, color: 'var(--text-subtle)' }}>Last hour · auto-updating</span>
        <div style={{ flex: 1 }}/>
        <StatusDot tone="success" pulse size={6}/>
        <span style={{ fontSize: 11, color: 'var(--text-muted)' }}>Live</span>
      </div>
      <div style={{ background: 'var(--panel)', border: '1px solid var(--panel-border)', borderRadius: 10, overflow: 'hidden' }}>
        {events.map((e, i) => (
          <div key={i} style={{ display: 'grid', gridTemplateColumns: '72px 24px 1fr auto',
            gap: 12, alignItems: 'center', padding: '12px 14px',
            borderBottom: i < events.length - 1 ? '1px solid var(--divider)' : 'none' }}>
            <span style={{ fontSize: 11, color: 'var(--text-faint)', fontVariantNumeric: 'tabular-nums' }}>{e.t}</span>
            <div style={{ width: 22, height: 22, borderRadius: 11,
              background: e.tone === 'success' ? 'var(--status-success-bg)'
                : e.tone === 'warn' ? 'var(--status-warn-bg)'
                : e.tone === 'info' ? 'var(--status-info-bg)' : 'var(--bg-muted)',
              color: e.tone === 'success' ? 'var(--status-success)'
                : e.tone === 'warn' ? 'var(--status-warn)'
                : e.tone === 'info' ? 'var(--accent-600)' : 'var(--text-muted)',
              display: 'flex', alignItems: 'center', justifyContent: 'center' }}>{e.icon}</div>
            <div style={{ minWidth: 0 }}>
              <div style={{ fontSize: 13, color: 'var(--text)', fontWeight: 500 }}>{e.title}</div>
              <div style={{ fontSize: 11.5, color: 'var(--text-subtle)', overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>{e.sub}</div>
            </div>
            <Avatar bot size={22}/>
          </div>
        ))}
      </div>
    </div>
  );
}

Object.assign(window, { OverviewScreen });
