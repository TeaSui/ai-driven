// Timeline screen — the unified Jira → Code → Review → Deploy → Monitor view

const { useState: tlS, useEffect: tlE, useRef: tlR, useMemo: tlM } = React;

const TIMELINE_RUNS = [
  {
    id: 'GTX-4821', title: 'Implement rate-limit headers on /v2/transfers',
    repo: 'go-tyme/payments-api', branch: 'feat/GTX-4821-rate-limit',
    status: 'deployed', stage: 5, assignee: 'AI Agent',
    updated: '3m ago', duration: '14m 32s',
    pr: '#2418', author: 'Mira Delgado',
    files: 7, plus: 142, minus: 38,
    reviews: { approved: 2, comments: 3 },
  },
  {
    id: 'GTX-4829', title: 'Fix race condition in ledger balance cache',
    repo: 'go-tyme/ledger-core', branch: 'fix/GTX-4829-ledger-race',
    status: 'reviewing', stage: 3, assignee: 'AI Agent',
    updated: 'now', duration: '8m 04s',
    pr: '#2419', author: 'AI Agent',
    files: 3, plus: 58, minus: 17,
    reviews: { approved: 0, comments: 2 },
    active: true,
  },
  {
    id: 'GTX-4830', title: 'Add idempotency key validation to /webhooks',
    repo: 'go-tyme/webhooks', branch: 'feat/GTX-4830-idempotency',
    status: 'coding', stage: 2, assignee: 'AI Agent',
    updated: 'now', duration: '2m 18s',
    active: true,
  },
  {
    id: 'GTX-4815', title: 'Migrate session store to Redis Cluster',
    repo: 'go-tyme/auth-service', branch: 'chore/GTX-4815-redis',
    status: 'merged', stage: 5, assignee: 'Jamie Ortiz',
    updated: '1h ago', duration: '38m 12s',
    pr: '#2415',
    files: 12, plus: 318, minus: 204,
    reviews: { approved: 3, comments: 8 },
  },
  {
    id: 'GTX-4792', title: 'Refactor KYC webhook retry logic',
    repo: 'go-tyme/kyc-adapter',
    status: 'blocked', stage: 3, assignee: 'AI Agent',
    updated: '2h ago', duration: '22m 41s',
    pr: '#2401',
    files: 5, plus: 89, minus: 62,
    reviews: { approved: 0, comments: 11 },
  },
];

const STAGES = [
  { id: 'jira',    label: 'Jira',    icon: <Ic.Jira size={11}/> },
  { id: 'code',    label: 'Code',    icon: <Ic.Code size={11}/> },
  { id: 'review',  label: 'Review',  icon: <Ic.GitPr size={11}/> },
  { id: 'deploy',  label: 'Deploy',  icon: <Ic.Aws size={11}/> },
  { id: 'monitor', label: 'Monitor', icon: <Ic.Datadog size={11}/> },
];

function TimelineScreen({ onOpenRun }) {
  const [filter, setFilter] = tlS('all');
  const filtered = filter === 'all' ? TIMELINE_RUNS
    : filter === 'active' ? TIMELINE_RUNS.filter(r => r.active)
    : TIMELINE_RUNS.filter(r => r.status === filter);

  return (
    <Shell active="timeline" headerProps={{
      title: 'Timeline',
      sub: 'Live view · 5 repos · 12 active runs',
      right: (
        <>
          <Btn size="sm" icon={<Ic.Filter size={12}/>}>Filter</Btn>
          <Btn size="sm" icon={<Ic.Refresh size={12}/>}>Refresh</Btn>
          <Btn size="sm" tone="brand" icon={<Ic.Plus size={12}/>}>New run</Btn>
        </>
      ),
      tabs: [
        { id: 'all', label: 'All', count: TIMELINE_RUNS.length },
        { id: 'active', label: 'Active', count: TIMELINE_RUNS.filter(r=>r.active).length, icon: <StatusDot tone="success" pulse size={6}/> },
        { id: 'merged', label: 'Merged', count: 148 },
        { id: 'blocked', label: 'Blocked', count: 2 },
      ],
      activeTab: filter,
      onTab: setFilter,
    }}>
      <TimelineStats/>
      <div style={{ padding: '0 20px 20px' }}>
        <TimelineLegend/>
        <div style={{ display: 'flex', flexDirection: 'column', gap: 8 }}>
          {filtered.map(r => <TimelineRow key={r.id} run={r} onOpen={() => onOpenRun && onOpenRun(r)}/>)}
        </div>
      </div>
    </Shell>
  );
}

function TimelineStats() {
  const stats = [
    { label: 'Tickets in flight', value: '12', delta: '+3', tone: 'brand' },
    { label: 'PRs opened today', value: '18', delta: '+42%', tone: 'success' },
    { label: 'Merge rate', value: '87%', delta: '+4pt', tone: 'success' },
    { label: 'Avg cycle time', value: '24m', delta: '−6m', tone: 'success' },
    { label: 'Engineering hours saved', value: '124h', delta: 'this week', tone: 'default' },
  ];
  return (
    <div style={{
      display: 'grid', gridTemplateColumns: 'repeat(5, 1fr)', gap: 1,
      background: 'var(--panel-border)',
      borderBottom: '1px solid var(--panel-border)',
    }}>
      {stats.map(s => (
        <div key={s.label} style={{ background: 'var(--bg)', padding: '16px 20px' }}>
          <div style={{ fontSize: 11.5, color: 'var(--text-subtle)', marginBottom: 4 }}>{s.label}</div>
          <div style={{ display: 'flex', alignItems: 'baseline', gap: 8 }}>
            <div style={{ fontSize: 24, fontWeight: 600, color: 'var(--text)', letterSpacing: -0.6, fontVariantNumeric: 'tabular-nums' }}>{s.value}</div>
            <div style={{ fontSize: 11, fontWeight: 500,
              color: s.tone === 'success' ? 'var(--status-success)' : s.tone === 'brand' ? 'var(--accent-600)' : 'var(--text-subtle)'
            }}>{s.delta}</div>
          </div>
        </div>
      ))}
    </div>
  );
}

function TimelineLegend() {
  return (
    <div style={{ display: 'flex', alignItems: 'center', gap: 14, padding: '14px 0 10px', fontSize: 11, color: 'var(--text-subtle)' }}>
      <span>Stages:</span>
      {STAGES.map((s, i) => (
        <span key={s.id} style={{ display: 'inline-flex', alignItems: 'center', gap: 5 }}>
          <span style={{ width: 16, height: 16, borderRadius: 4, border: '1px solid var(--border)',
            display: 'inline-flex', alignItems: 'center', justifyContent: 'center', color: 'var(--text-muted)' }}>{s.icon}</span>
          {s.label}
        </span>
      ))}
    </div>
  );
}

function TimelineRow({ run, onOpen }) {
  const [hover, setHover] = tlS(false);
  const statusMap = {
    deployed: { tone: 'success', label: 'Deployed' },
    merged:   { tone: 'success', label: 'Merged' },
    reviewing:{ tone: 'info', label: 'In review' },
    coding:   { tone: 'info', label: 'Coding' },
    blocked:  { tone: 'danger', label: 'Blocked' },
  };
  const st = statusMap[run.status];
  return (
    <div onClick={onOpen}
      onMouseEnter={() => setHover(true)} onMouseLeave={() => setHover(false)}
      style={{
        display: 'grid',
        gridTemplateColumns: '110px 1fr 260px 180px 120px',
        gap: 16, alignItems: 'center',
        padding: '14px 16px', background: 'var(--panel)',
        border: `1px solid ${hover ? 'var(--border-strong)' : 'var(--panel-border)'}`,
        borderRadius: 8, cursor: 'pointer',
        transition: 'border-color .1s, background .1s',
      }}>
      {/* Ticket ID */}
      <div style={{ display: 'flex', alignItems: 'center', gap: 6 }}>
        <Ic.Jira size={13} color="var(--accent-500)"/>
        <span className="gtx-mono" style={{ fontSize: 12, color: 'var(--text)', fontWeight: 500 }}>{run.id}</span>
      </div>

      {/* Title + repo */}
      <div style={{ minWidth: 0 }}>
        <div style={{ fontSize: 13.5, color: 'var(--text)', fontWeight: 500, overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap', marginBottom: 3 }}>
          {run.title}
        </div>
        <div style={{ display: 'flex', alignItems: 'center', gap: 10, fontSize: 11.5, color: 'var(--text-subtle)' }}>
          <span style={{ display: 'inline-flex', alignItems: 'center', gap: 4 }}>
            <Ic.Github size={11}/>{run.repo}
          </span>
          {run.branch && (
            <span className="gtx-mono" style={{ display: 'inline-flex', alignItems: 'center', gap: 3 }}>
              <Ic.GitBranch size={10}/>{run.branch}
            </span>
          )}
          {run.pr && <span className="gtx-mono" style={{ color: 'var(--accent-600)' }}>{run.pr}</span>}
        </div>
      </div>

      {/* Stage pipeline */}
      <StagePipeline stage={run.stage} active={run.active} blocked={run.status === 'blocked'}/>

      {/* Author + diff stats */}
      <div style={{ display: 'flex', alignItems: 'center', gap: 10, fontSize: 11.5, color: 'var(--text-subtle)' }}>
        {run.assignee === 'AI Agent'
          ? <span style={{ display: 'inline-flex', alignItems: 'center', gap: 5 }}>
              <Avatar bot size={18}/><span style={{ color: 'var(--text-muted)' }}>Agent</span>
            </span>
          : <span style={{ display: 'inline-flex', alignItems: 'center', gap: 5 }}>
              <Avatar name={run.assignee} size={18}/><span>{run.assignee.split(' ')[0]}</span>
            </span>
        }
        {run.files && (
          <>
            <span style={{ color: 'var(--text-faint)' }}>·</span>
            <span className="gtx-mono" style={{ color: 'var(--status-success)' }}>+{run.plus}</span>
            <span className="gtx-mono" style={{ color: 'var(--status-danger)' }}>−{run.minus}</span>
          </>
        )}
      </div>

      {/* Status pill */}
      <div style={{ display: 'flex', alignItems: 'center', gap: 8, justifySelf: 'end' }}>
        <Pill tone={st.tone} dot={run.active}>
          {run.active && <Spinner size={9} color="currentColor"/>}
          {st.label}
        </Pill>
      </div>
    </div>
  );
}

function StagePipeline({ stage, active, blocked }) {
  // 5 segments connected by lines
  return (
    <div style={{ display: 'flex', alignItems: 'center' }}>
      {STAGES.map((s, i) => {
        const done = i < stage;
        const current = i === stage - 1;
        const pending = i >= stage;
        const color = blocked && current ? 'var(--status-danger)'
          : current && active ? 'var(--accent-500)'
          : done ? 'var(--status-success)'
          : 'var(--text-faint)';
        const bg = blocked && current ? 'var(--status-danger-bg)'
          : current && active ? 'var(--accent-soft)'
          : done ? 'var(--status-success-bg)'
          : 'var(--bg-muted)';
        return (
          <React.Fragment key={s.id}>
            <div title={s.label} style={{
              width: 22, height: 22, borderRadius: 11,
              background: bg, color,
              border: `1px solid ${pending ? 'var(--border)' : 'transparent'}`,
              display: 'flex', alignItems: 'center', justifyContent: 'center',
              flexShrink: 0, position: 'relative',
            }}>
              {done ? <Ic.Check size={10}/> : s.icon}
              {current && active && <span style={{ position: 'absolute', inset: -3, borderRadius: 14,
                border: `1.5px solid ${color}`, animation: 'gtx-ring 1.8s ease-out infinite', opacity: .5 }}/>}
            </div>
            {i < STAGES.length - 1 && (
              <div style={{ flex: 1, height: 2,
                background: i < stage - 1 ? 'var(--status-success)' : 'var(--border)',
                margin: '0 2px',
              }}/>
            )}
          </React.Fragment>
        );
      })}
    </div>
  );
}

Object.assign(window, { TimelineScreen, TIMELINE_RUNS });
