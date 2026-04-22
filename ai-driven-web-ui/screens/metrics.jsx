// Metrics dashboard

function MetricsScreen() {
  return (
    <Shell active="metrics" headerProps={{
      title: 'Metrics',
      sub: 'Last 30 days · all teams',
      right: (<>
        <Btn size="sm" icon={<Ic.Clock size={12}/>}>30d</Btn>
        <Btn size="sm" icon={<Ic.Team size={12}/>}>All teams</Btn>
        <Btn size="sm" icon={<Ic.ArrowUpRight size={12}/>}>Export</Btn>
      </>),
    }}>
      <div style={{ padding: 20, display: 'flex', flexDirection: 'column', gap: 16 }}>
        <HeroMetrics/>
        <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: 12 }}>
          <ChartCard title="PRs merged · by source" subtitle="Agent vs human authorship">
            <StackedBars/>
          </ChartCard>
          <ChartCard title="Cycle time" subtitle="Ticket → merged">
            <LineChart/>
          </ChartCard>
        </div>
        <div style={{ display: 'grid', gridTemplateColumns: '1.3fr 1fr', gap: 12 }}>
          <ChartCard title="Agent activity by hour" subtitle="UTC">
            <Heatmap/>
          </ChartCard>
          <ChartCard title="Top contributors" subtitle="This month">
            <Leaderboard/>
          </ChartCard>
        </div>
      </div>
    </Shell>
  );
}

function HeroMetrics() {
  const cards = [
    { label: 'Engineering hours saved', value: '1,284', unit: 'hrs', delta: '+38%', sub: '≈ 32 FTE-weeks', spark: [6,8,9,12,14,16,18,22,26,30,34,38] },
    { label: 'PRs merged by agent', value: '412', delta: '+62%', sub: 'of 647 total · 64%', spark: [3,5,7,10,14,18,22,28,34,40,46,52] },
    { label: 'Incidents auto-triaged', value: '89', delta: '+41%', sub: 'avg 3m 48s to RCA', spark: [2,3,4,5,6,7,8,10,12,14,17,19] },
    { label: 'Review coverage', value: '98.7%', unit: '', delta: '+2.1pt', sub: 'of all PRs', spark: [88,90,91,92,93,94,95,96,97,97,98,99] },
  ];
  return (
    <div style={{ display: 'grid', gridTemplateColumns: 'repeat(4,1fr)', gap: 12 }}>
      {cards.map(c => (
        <div key={c.label} style={{ background: 'var(--panel)', border: '1px solid var(--panel-border)', borderRadius: 10, padding: 16, position: 'relative', overflow: 'hidden' }}>
          <div style={{ fontSize: 11.5, color: 'var(--text-subtle)', marginBottom: 6 }}>{c.label}</div>
          <div style={{ display: 'flex', alignItems: 'baseline', gap: 6 }}>
            <span style={{ fontSize: 32, fontWeight: 600, letterSpacing: -1, color: 'var(--text)', fontVariantNumeric: 'tabular-nums' }}>{c.value}</span>
            {c.unit && <span style={{ fontSize: 13, color: 'var(--text-muted)' }}>{c.unit}</span>}
          </div>
          <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', marginTop: 6 }}>
            <span style={{ fontSize: 11, color: 'var(--text-subtle)' }}>{c.sub}</span>
            <span style={{ fontSize: 11, color: 'var(--status-success)', fontWeight: 500 }}>↑ {c.delta}</span>
          </div>
          <MiniSpark data={c.spark}/>
        </div>
      ))}
    </div>
  );
}

function MiniSpark({ data }) {
  const max = Math.max(...data), min = Math.min(...data), n = data.length;
  const pts = data.map((v, i) => [(i / (n - 1)) * 100, 100 - ((v - min) / (max - min || 1)) * 80]);
  const path = pts.map((p, i) => `${i === 0 ? 'M' : 'L'} ${p[0]} ${p[1]}`).join(' ');
  const area = path + ` L 100 100 L 0 100 Z`;
  return (
    <svg viewBox="0 0 100 100" preserveAspectRatio="none" style={{ position: 'absolute', right: 0, bottom: 0, width: '55%', height: 40, opacity: .7 }}>
      <defs><linearGradient id="sgrad" x1="0" y1="0" x2="0" y2="1"><stop offset="0" stopColor="var(--accent-400)" stopOpacity=".35"/><stop offset="1" stopColor="var(--accent-400)" stopOpacity="0"/></linearGradient></defs>
      <path d={area} fill="url(#sgrad)"/>
      <path d={path} stroke="var(--accent-500)" strokeWidth="1.8" fill="none" vectorEffect="non-scaling-stroke"/>
    </svg>
  );
}

function ChartCard({ title, subtitle, children }) {
  return (
    <div style={{ background: 'var(--panel)', border: '1px solid var(--panel-border)', borderRadius: 10 }}>
      <div style={{ padding: '12px 16px', borderBottom: '1px solid var(--panel-border)' }}>
        <div style={{ fontSize: 13, fontWeight: 500, color: 'var(--text)' }}>{title}</div>
        <div style={{ fontSize: 11.5, color: 'var(--text-subtle)' }}>{subtitle}</div>
      </div>
      <div style={{ padding: 16 }}>{children}</div>
    </div>
  );
}

function StackedBars() {
  const weeks = ['W1','W2','W3','W4','W5','W6','W7','W8','W9','W10','W11','W12'];
  const agent = [18, 24, 32, 41, 52, 48, 58, 67, 72, 81, 88, 94];
  const human = [52, 48, 45, 42, 40, 38, 36, 34, 32, 30, 28, 26];
  const max = Math.max(...agent.map((a,i) => a + human[i]));
  return (
    <div>
      <div style={{ display: 'flex', alignItems: 'flex-end', gap: 6, height: 180 }}>
        {weeks.map((w,i) => {
          const total = agent[i] + human[i];
          const ah = (agent[i] / max) * 180;
          const hh = (human[i] / max) * 180;
          return (
            <div key={w} style={{ flex: 1, display: 'flex', flexDirection: 'column-reverse', alignItems: 'center' }}>
              <div style={{ width: '80%', height: ah, background: 'var(--accent-500)', borderRadius: '2px 2px 0 0' }}/>
              <div style={{ width: '80%', height: hh, background: 'var(--brand-teal-200)' }}/>
            </div>
          );
        })}
      </div>
      <div style={{ display: 'flex', gap: 6, marginTop: 4 }}>
        {weeks.map(w => <div key={w} style={{ flex: 1, textAlign: 'center', fontSize: 10, color: 'var(--text-faint)' }}>{w}</div>)}
      </div>
      <div style={{ display: 'flex', gap: 14, marginTop: 10, fontSize: 11.5, color: 'var(--text-muted)' }}>
        <span style={{ display: 'inline-flex', alignItems: 'center', gap: 6 }}><span style={{ width: 10, height: 10, background: 'var(--accent-500)', borderRadius: 2 }}/>Agent-authored (64%)</span>
        <span style={{ display: 'inline-flex', alignItems: 'center', gap: 6 }}><span style={{ width: 10, height: 10, background: 'var(--brand-teal-200)', borderRadius: 2 }}/>Human-authored</span>
      </div>
    </div>
  );
}

function LineChart() {
  const series = [
    { label: 'Agent', color: 'var(--accent-500)', data: [42,38,36,30,28,26,24,22,20,18,16,14] },
    { label: 'Human', color: 'var(--brand-teal-300)', data: [58,60,58,59,57,58,56,57,55,56,54,55] },
  ];
  const max = 70, n = 12;
  return (
    <div>
      <svg viewBox="0 0 100 60" preserveAspectRatio="none" style={{ width: '100%', height: 180 }}>
        {[0.25,0.5,0.75].map(f => <line key={f} x1="0" x2="100" y1={60*f} y2={60*f} stroke="var(--divider)" strokeWidth=".3"/>)}
        {series.map(s => {
          const path = s.data.map((v,i) => `${i===0?'M':'L'} ${(i/(n-1))*100} ${60 - (v/max)*60}`).join(' ');
          return <path key={s.label} d={path} stroke={s.color} strokeWidth="1" fill="none" vectorEffect="non-scaling-stroke"/>;
        })}
        {series.map(s => s.data.map((v,i) => (
          <circle key={s.label+i} cx={(i/(n-1))*100} cy={60 - (v/max)*60} r=".8" fill={s.color}/>
        )))}
      </svg>
      <div style={{ display: 'flex', justifyContent: 'space-between', marginTop: 4, fontSize: 10, color: 'var(--text-faint)' }}>
        <span>Apr 1</span><span>Apr 15</span><span>Apr 30</span>
      </div>
      <div style={{ display: 'flex', gap: 14, marginTop: 10, fontSize: 11.5, color: 'var(--text-muted)' }}>
        <span style={{ display: 'inline-flex', alignItems: 'center', gap: 6 }}><span style={{ width: 10, height: 2, background: 'var(--accent-500)' }}/>Agent · 14m avg</span>
        <span style={{ display: 'inline-flex', alignItems: 'center', gap: 6 }}><span style={{ width: 10, height: 2, background: 'var(--brand-teal-300)' }}/>Human · 55m avg</span>
      </div>
    </div>
  );
}

function Heatmap() {
  const days = ['Mon','Tue','Wed','Thu','Fri','Sat','Sun'];
  const hours = 24;
  const rnd = (d, h) => {
    const v = (Math.sin(d * 1.7 + h * 0.9) + 1) / 2;
    const biz = h >= 8 && h <= 20 ? 0.7 : 0.15;
    const wk = d < 5 ? 1 : 0.3;
    return Math.min(1, v * biz * wk + (Math.cos(d + h) + 1) * 0.08);
  };
  return (
    <div>
      <div style={{ display: 'flex', gap: 2, alignItems: 'center' }}>
        <div style={{ width: 32 }}/>
        {Array.from({ length: hours }).map((_, h) => (
          <div key={h} style={{ flex: 1, fontSize: 9, textAlign: 'center', color: h % 4 === 0 ? 'var(--text-faint)' : 'transparent' }}>{h}</div>
        ))}
      </div>
      {days.map((d, di) => (
        <div key={d} style={{ display: 'flex', gap: 2, alignItems: 'center', marginTop: 2 }}>
          <div style={{ width: 32, fontSize: 10.5, color: 'var(--text-subtle)' }}>{d}</div>
          {Array.from({ length: hours }).map((_, h) => {
            const v = rnd(di, h);
            return <div key={h} style={{ flex: 1, height: 18, borderRadius: 2, background: `rgba(134,184,190,${0.08 + v * 0.9})` }}/>;
          })}
        </div>
      ))}
      <div style={{ marginTop: 12, display: 'flex', alignItems: 'center', gap: 6, fontSize: 11, color: 'var(--text-subtle)' }}>
        <span>Less</span>
        {[.1,.3,.5,.7,.9].map(v => <div key={v} style={{ width: 14, height: 10, borderRadius: 2, background: `rgba(134,184,190,${0.08 + v * 0.9})` }}/>)}
        <span>More</span>
      </div>
    </div>
  );
}

function Leaderboard() {
  const rows = [
    { name: 'GoTyme-X AI', bot: true, prs: 412, time: '14m', saved: '1,284h' },
    { name: 'Mira Delgado', prs: 38, time: '52m', saved: null },
    { name: 'Jamie Ortiz', prs: 31, time: '1h 08m', saved: null },
    { name: 'Priya Narayan', prs: 28, time: '48m', saved: null },
    { name: 'Marcus Vaughn', prs: 24, time: '1h 12m', saved: null },
  ];
  const max = 412;
  return (
    <div style={{ display: 'flex', flexDirection: 'column', gap: 2 }}>
      {rows.map(r => (
        <div key={r.name} style={{ display: 'grid', gridTemplateColumns: '28px 1fr 48px', gap: 10, alignItems: 'center', padding: '6px 4px' }}>
          {r.bot ? <Avatar bot size={24}/> : <Avatar name={r.name} size={24}/>}
          <div style={{ minWidth: 0 }}>
            <div style={{ display: 'flex', justifyContent: 'space-between', marginBottom: 3, fontSize: 12 }}>
              <span style={{ color: 'var(--text)', fontWeight: r.bot ? 500 : 400 }}>{r.name}</span>
              <span style={{ color: 'var(--text-subtle)' }}>avg {r.time}</span>
            </div>
            <div style={{ height: 6, borderRadius: 3, background: 'var(--bg-muted)', overflow: 'hidden' }}>
              <div style={{ height: '100%', width: `${(r.prs / max) * 100}%`, background: r.bot ? 'var(--accent-500)' : 'var(--brand-teal-300)' }}/>
            </div>
          </div>
          <span style={{ textAlign: 'right', fontSize: 13, fontWeight: 500, color: 'var(--text)', fontVariantNumeric: 'tabular-nums' }}>{r.prs}</span>
        </div>
      ))}
    </div>
  );
}

Object.assign(window, { MetricsScreen });
