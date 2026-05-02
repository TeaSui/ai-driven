// DevOps screen — DataDog alert triage with AI RCA

const DevOpsScreen = () => {
  const [sel, setSel] = React.useState(0);
  const alerts = [
    { id: 'A-8821', severity: 'critical', title: 'balance-cache p99 latency > 2s', service: 'ledger-core', time: '4m ago', status: 'investigating' },
    { id: 'A-8819', severity: 'warning', title: 'webhook-delivery error rate 4.2%', service: 'webhooks', time: '18m ago', status: 'triaged' },
    { id: 'A-8815', severity: 'warning', title: 'postgres connection pool 82%', service: 'auth-service', time: '34m ago', status: 'auto-resolved' },
  ];
  const alert = alerts[sel];
  return (
    <Shell active="devops" headerProps={{
      title: 'DevOps Assistant',
      sub: '3 active alerts · 14 auto-triaged today',
      right: (<>
        <Pill tone="danger" dot size="md">1 critical</Pill>
        <Btn size="sm" icon={<Ic.Datadog size={12}/>}>Open DataDog</Btn>
        <Btn size="sm" tone="brand" icon={<Ic.Bot size={12}/>}>Run agent</Btn>
      </>),
    }}>
      <div style={{ display: 'grid', gridTemplateColumns: '300px 1fr', minHeight: 'calc(100% - 0px)' }}>
        <div style={{ borderRight: '1px solid var(--panel-border)', background: 'var(--panel)' }}>
          <div style={{ padding: '12px 14px', borderBottom: '1px solid var(--panel-border)', fontSize: 11, color: 'var(--text-subtle)', textTransform: 'uppercase', letterSpacing: 0.6 }}>Active alerts</div>
          {alerts.map((a, i) => (
            <button key={a.id} onClick={() => setSel(i)} style={{
              display: 'block', width: '100%', border: 'none', textAlign: 'left', cursor: 'pointer',
              padding: '12px 14px', background: sel === i ? 'var(--bg-hover)' : 'transparent',
              borderLeft: `3px solid ${sel === i ? 'var(--accent-500)' : 'transparent'}`,
              borderBottom: '1px solid var(--panel-border)', fontFamily: 'inherit',
            }}>
              <div style={{ display: 'flex', alignItems: 'center', gap: 6, marginBottom: 4 }}>
                <StatusDot tone={a.severity === 'critical' ? 'danger' : 'warn'} pulse={a.severity === 'critical'} size={7}/>
                <span className="gtx-mono" style={{ fontSize: 11, color: 'var(--text-subtle)' }}>{a.id}</span>
                <span style={{ flex: 1 }}/>
                <span style={{ fontSize: 10.5, color: 'var(--text-faint)' }}>{a.time}</span>
              </div>
              <div style={{ fontSize: 12.5, color: 'var(--text)', fontWeight: 500, marginBottom: 3 }}>{a.title}</div>
              <div style={{ fontSize: 11, color: 'var(--text-subtle)', display: 'flex', gap: 6 }}>
                <span className="gtx-mono">{a.service}</span>
                <span>·</span>
                <span>{a.status}</span>
              </div>
            </button>
          ))}
        </div>
        <IncidentDetail alert={alert}/>
      </div>
    </Shell>
  );
};

function IncidentDetail({ alert }) {
  return (
    <div style={{ overflow: 'auto', padding: 20 }}>
      <div style={{ display: 'flex', alignItems: 'flex-start', gap: 14, marginBottom: 20 }}>
        <div style={{ width: 40, height: 40, borderRadius: 10, background: 'var(--status-danger-bg)',
          color: 'var(--status-danger)', display: 'flex', alignItems: 'center', justifyContent: 'center' }}>
          <Ic.Alert size={20}/>
        </div>
        <div style={{ flex: 1 }}>
          <div style={{ display: 'flex', alignItems: 'center', gap: 8, marginBottom: 4 }}>
            <span className="gtx-mono" style={{ fontSize: 11.5, color: 'var(--text-subtle)' }}>{alert.id}</span>
            <Pill tone="danger" dot>CRITICAL</Pill>
            <Pill tone="info" size="sm">P99 latency</Pill>
          </div>
          <h2 style={{ margin: 0, fontSize: 18, fontWeight: 600, letterSpacing: -0.3, color: 'var(--text)' }}>{alert.title}</h2>
          <div style={{ fontSize: 12.5, color: 'var(--text-subtle)', marginTop: 4 }}>
            <span className="gtx-mono">ledger-core</span> · us-east-2 · triggered 14:32:18 UTC · affecting 3 endpoints
          </div>
        </div>
      </div>

      {/* AI Summary card */}
      <div style={{ border: '1px solid var(--brand-teal-200)', background: 'var(--accent-soft)',
        borderRadius: 10, padding: 16, marginBottom: 16, position: 'relative', overflow: 'hidden' }}>
        <div style={{ position: 'absolute', top: -40, right: -40, width: 140, height: 140,
          background: 'radial-gradient(circle, var(--brand-teal-200), transparent 60%)', opacity: .6 }}/>
        <div style={{ display: 'flex', alignItems: 'center', gap: 10, marginBottom: 10, position: 'relative' }}>
          <Avatar bot size={26}/>
          <div style={{ flex: 1 }}>
            <div style={{ fontSize: 12.5, fontWeight: 500, color: 'var(--text)' }}>AI Root-cause analysis</div>
            <div style={{ fontSize: 11, color: 'var(--text-muted)' }}>Generated 3m 12s after alert fired · confidence 89%</div>
          </div>
          <Pill tone="brand" dot size="sm"><Spinner size={9}/>Live</Pill>
        </div>
        <div style={{ fontSize: 13.5, color: 'var(--text)', lineHeight: 1.55, position: 'relative' }}>
          Latency spike correlates with deploy <Code>a3f21c8</Code> at 14:28 UTC, which introduced a <Code>singleflight.Group</Code>{' '}
          in <Code>BalanceCache.Get()</Code>. Under the current traffic (3.8× normal weekend peak) the singleflight is serializing{' '}
          reads on ~12 hot account IDs. Redis cache-misses are still fast, but the Go mutex contention dominates p99.
        </div>
        <div style={{ marginTop: 14, display: 'flex', gap: 8, position: 'relative' }}>
          <Btn size="sm" tone="brand" icon={<Ic.Jira size={11}/>}>Create incident ticket</Btn>
          <Btn size="sm" icon={<Ic.GitPr size={11}/>}>Draft rollback PR</Btn>
          <Btn size="sm" icon={<Ic.Slack size={11}/>}>Post to #incidents</Btn>
        </div>
      </div>

      {/* Timeline + Signals grid */}
      <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: 12 }}>
        <Card padded={false}>
          <CardHeader title="Investigation timeline" icon={<Ic.Clock size={12}/>}/>
          <div style={{ padding: '4px 0' }}>
            {[
              { t: '0:00', text: 'Alert fired · p99 hit 2.1s', tone: 'danger' },
              { t: '0:12', text: 'Agent picked up · fetching context', tone: 'info' },
              { t: '0:45', text: 'Queried last 4 deploys via GitHub', tone: 'default' },
              { t: '1:20', text: 'Correlated deploy a3f21c8 (+89% latency delta)', tone: 'default' },
              { t: '2:08', text: 'Pulled flamegraph · mutex wait = 71%', tone: 'default' },
              { t: '3:12', text: 'RCA posted · rollback PR drafted', tone: 'success' },
            ].map((e, i) => (
              <div key={i} style={{ display: 'flex', gap: 10, padding: '8px 16px', alignItems: 'flex-start' }}>
                <span className="gtx-mono" style={{ fontSize: 11, color: 'var(--text-subtle)', width: 32, flexShrink: 0 }}>{e.t}</span>
                <StatusDot tone={e.tone} size={6} style={{ marginTop: 5 }}/>
                <span style={{ fontSize: 12.5, color: 'var(--text-muted)' }}>{e.text}</span>
              </div>
            ))}
          </div>
        </Card>
        <Card padded={false}>
          <CardHeader title="Correlated signals" icon={<Ic.Datadog size={12}/>}/>
          <div style={{ padding: 12 }}>
            <Sparkline label="p99 latency" value="2.14s" delta="+312%" tone="danger" data={[0.3,0.4,0.3,0.4,0.35,0.4,0.5,0.6,0.9,1.4,2.1,2.14]}/>
            <Sparkline label="Mutex wait %" value="71%" delta="+65pt" tone="danger" data={[6,8,7,8,9,11,14,22,38,54,68,71]}/>
            <Sparkline label="Error rate" value="0.4%" delta="+0.2pt" tone="warn" data={[.2,.2,.2,.2,.2,.2,.3,.3,.4,.4,.4,.4]}/>
            <Sparkline label="Requests/s" value="4.8k" delta="+18%" tone="default" data={[3.9,4.0,4.1,4.2,4.3,4.4,4.5,4.6,4.7,4.8,4.8,4.8]}/>
          </div>
        </Card>
      </div>
    </div>
  );
}

function CardHeader({ title, icon }) {
  return (
    <div style={{ padding: '10px 14px', borderBottom: '1px solid var(--panel-border)',
      display: 'flex', alignItems: 'center', gap: 8, fontSize: 12, fontWeight: 500, color: 'var(--text)' }}>
      {icon}<span>{title}</span>
    </div>
  );
}

function Sparkline({ label, value, delta, tone, data }) {
  const max = Math.max(...data), min = Math.min(...data);
  const n = data.length;
  const path = data.map((v, i) => {
    const x = (i / (n - 1)) * 100;
    const y = 100 - ((v - min) / (max - min || 1)) * 100;
    return `${i === 0 ? 'M' : 'L'} ${x} ${y}`;
  }).join(' ');
  const c = tone === 'danger' ? 'var(--status-danger)' : tone === 'warn' ? 'var(--status-warn)' : 'var(--accent-500)';
  return (
    <div style={{ display: 'flex', alignItems: 'center', gap: 12, padding: '8px 4px', borderBottom: '1px solid var(--divider)' }}>
      <div style={{ flex: 1, minWidth: 0 }}>
        <div style={{ fontSize: 11.5, color: 'var(--text-subtle)' }}>{label}</div>
        <div style={{ display: 'flex', alignItems: 'baseline', gap: 6 }}>
          <span style={{ fontSize: 14, fontWeight: 600, color: 'var(--text)', fontVariantNumeric: 'tabular-nums' }}>{value}</span>
          <span style={{ fontSize: 10.5, color: c, fontWeight: 500 }}>{delta}</span>
        </div>
      </div>
      <svg width={100} height={30} viewBox="0 0 100 100" preserveAspectRatio="none" style={{ flexShrink: 0 }}>
        <path d={path} stroke={c} strokeWidth="2.5" fill="none" vectorEffect="non-scaling-stroke"/>
      </svg>
    </div>
  );
}

Object.assign(window, { DevOpsScreen });
