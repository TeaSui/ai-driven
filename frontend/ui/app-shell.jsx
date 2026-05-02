// App shell — sidebar + header, used inside each artboard's screen.
const { useState: uS, useEffect: uE, useRef: uR } = React;

function Sidebar({ active, onNav, collapsed }) {
  const nav = [
    { id: 'timeline', label: 'Timeline', icon: <Ic.Zap size={15}/>, count: 3 },
    { id: 'agent',    label: 'Coding Agent', icon: <Ic.Bot size={15}/>, pulse: true },
    { id: 'review',   label: 'Code Review', icon: <Ic.GitPr size={15}/>, count: 12 },
    { id: 'devops',   label: 'DevOps', icon: <Ic.Alert size={15}/>, count: 2, tone: 'warn' },
    { id: 'metrics',  label: 'Metrics', icon: <Ic.Eye size={15}/> },
    { id: 'slack',    label: 'Slack', icon: <Ic.Slack size={15}/> },
  ];
  const sub = [
    { id: 'repos', label: 'Repositories', icon: <Ic.Folder size={14}/> },
    { id: 'rules', label: 'Review rules', icon: <Ic.Shield size={14}/> },
    { id: 'runs',  label: 'Run history', icon: <Ic.Clock size={14}/> },
  ];
  const w = collapsed ? 56 : 220;
  return (
    <aside style={{
      width: w, flexShrink: 0,
      borderRight: '1px solid var(--panel-border)',
      background: 'var(--panel)',
      display: 'flex', flexDirection: 'column',
      transition: 'width .22s cubic-bezier(.2,.7,.3,1)',
      overflow: 'hidden',
    }}>
      <div style={{ padding: '14px 14px 12px', display: 'flex', alignItems: 'center', justifyContent: 'space-between', height: 52 }}>
        <Wordmark compact={collapsed} />
      </div>

      <div style={{ padding: '0 8px', flex: 1, overflowY: 'auto' }}>
        {!collapsed && (
          <div style={{
            display: 'flex', alignItems: 'center', gap: 8, margin: '4px 4px 10px',
            padding: '6px 8px', borderRadius: 6, background: 'var(--bg-muted)',
            border: '1px solid var(--border)', fontSize: 12, color: 'var(--text-muted)',
          }}>
            <Ic.Search size={13}/>
            <span style={{ flex: 1 }}>Search…</span>
            <Kbd>⌘K</Kbd>
          </div>
        )}

        {nav.map(n => (
          <NavRow key={n.id} item={n} active={active === n.id}
            collapsed={collapsed} onClick={() => onNav && onNav(n.id)} />
        ))}

        {!collapsed && (
          <>
            <div style={{ padding: '16px 10px 6px', fontSize: 10.5, fontWeight: 600,
              letterSpacing: 0.8, color: 'var(--text-faint)', textTransform: 'uppercase' }}>Configure</div>
            {sub.map(s => <NavRow key={s.id} item={s} collapsed={collapsed} muted />)}
          </>
        )}
      </div>

      <div style={{ padding: 10, borderTop: '1px solid var(--panel-border)' }}>
        {collapsed ? (
          <div style={{ display: 'flex', justifyContent: 'center' }}>
            <Avatar name="Alex Chen" size={28}/>
          </div>
        ) : (
          <div style={{ display: 'flex', alignItems: 'center', gap: 9, padding: '4px 6px' }}>
            <Avatar name="Alex Chen" size={28}/>
            <div style={{ flex: 1, minWidth: 0 }}>
              <div style={{ fontSize: 12.5, fontWeight: 500, color: 'var(--text)', overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>Alex Chen</div>
              <div style={{ fontSize: 11, color: 'var(--text-subtle)' }}>Platform · Manila</div>
            </div>
            <IconBtn size={24}><Ic.Settings size={13}/></IconBtn>
          </div>
        )}
      </div>
    </aside>
  );
}

function NavRow({ item, active, collapsed, muted, onClick }) {
  const [h, setH] = uS(false);
  const tone = item.tone === 'warn' ? 'var(--status-warn)' : 'var(--text-muted)';
  return (
    <button
      onClick={onClick}
      onMouseEnter={() => setH(true)} onMouseLeave={() => setH(false)}
      title={collapsed ? item.label : undefined}
      style={{
        display: 'flex', alignItems: 'center', gap: 9,
        width: '100%', border: 'none',
        padding: collapsed ? '8px' : '6px 10px',
        background: active ? 'var(--bg-hover)' : (h ? 'var(--bg-hover)' : 'transparent'),
        color: active ? 'var(--text)' : (muted ? 'var(--text-subtle)' : 'var(--text-muted)'),
        borderRadius: 6, cursor: 'pointer',
        fontSize: 12.8, fontWeight: active ? 500 : 400,
        fontFamily: 'inherit', textAlign: 'left',
        justifyContent: collapsed ? 'center' : 'flex-start',
        position: 'relative',
        transition: 'background .1s, color .1s',
      }}>
      <span style={{ width: 15, height: 15, display: 'inline-flex', alignItems: 'center', justifyContent: 'center',
        color: active ? 'var(--accent-500)' : 'currentColor' }}>{item.icon}</span>
      {!collapsed && <span style={{ flex: 1 }}>{item.label}</span>}
      {!collapsed && item.count != null && (
        <span style={{ fontSize: 10.5, color: tone, fontVariantNumeric: 'tabular-nums',
          fontWeight: 500, padding: '1px 5px', borderRadius: 4,
          background: item.tone === 'warn' ? 'var(--status-warn-bg)' : 'var(--bg-muted)' }}>
          {item.count}
        </span>
      )}
      {!collapsed && item.pulse && <StatusDot tone="success" pulse size={6}/>}
      {collapsed && item.pulse && <span style={{ position: 'absolute', top: 6, right: 6 }}><StatusDot tone="success" pulse size={6}/></span>}
    </button>
  );
}

/* ── Header ───────────────────────────────── */
function Header({ title, sub, crumbs, right, tabs, activeTab, onTab }) {
  return (
    <header style={{
      borderBottom: '1px solid var(--panel-border)',
      background: 'var(--bg)',
      padding: '0 20px',
    }}>
      <div style={{ display: 'flex', alignItems: 'center', gap: 12, height: 52 }}>
        <div style={{ flex: 1, minWidth: 0 }}>
          {crumbs && (
            <div style={{ display: 'flex', alignItems: 'center', gap: 6, fontSize: 11.5, color: 'var(--text-subtle)', marginBottom: 1 }}>
              {crumbs.map((c, i) => (
                <React.Fragment key={i}>
                  {i > 0 && <Ic.Chevron size={10}/>}
                  <span style={{ color: i === crumbs.length - 1 ? 'var(--text)' : 'inherit' }}>{c}</span>
                </React.Fragment>
              ))}
            </div>
          )}
          <div style={{ display: 'flex', alignItems: 'baseline', gap: 10 }}>
            <h1 style={{ margin: 0, fontSize: 17, fontWeight: 600, letterSpacing: -0.3, color: 'var(--text)' }}>{title}</h1>
            {sub && <span style={{ fontSize: 12.5, color: 'var(--text-subtle)' }}>{sub}</span>}
          </div>
        </div>
        <div style={{ display: 'flex', alignItems: 'center', gap: 6 }}>{right}</div>
      </div>
      {tabs && (
        <div style={{ display: 'flex', gap: 2, marginTop: -1 }}>
          {tabs.map(t => (
            <button key={t.id}
              onClick={() => onTab && onTab(t.id)}
              style={{
                background: 'transparent', border: 'none',
                padding: '8px 12px 10px', fontSize: 12.5, fontWeight: 500,
                color: activeTab === t.id ? 'var(--text)' : 'var(--text-subtle)',
                borderBottom: `2px solid ${activeTab === t.id ? 'var(--accent-500)' : 'transparent'}`,
                cursor: 'pointer', fontFamily: 'inherit',
                display: 'inline-flex', alignItems: 'center', gap: 6,
              }}>
              {t.icon}
              {t.label}
              {t.count != null && <span style={{ fontSize: 10.5, color: 'var(--text-faint)' }}>{t.count}</span>}
            </button>
          ))}
        </div>
      )}
    </header>
  );
}

/* ── Shell (sidebar + body) ──────────────── */
function Shell({ active, onNav, children, headerProps }) {
  const { collapsed, theme, density, accent } = useGtx();
  return (
    <div className={`gtx-app gtx-${theme} gtx-density-${density}`}
      style={{
        '--accent-400': accent.a400, '--accent-500': accent.a500, '--accent-600': accent.a600,
        '--accent-soft': accent.aSoft, '--accent-contrast': accent.aContrast,
        height: '100%', width: '100%', display: 'flex',
        overflow: 'hidden',
      }}>
      <Sidebar active={active} onNav={onNav} collapsed={collapsed}/>
      <div style={{ flex: 1, display: 'flex', flexDirection: 'column', minWidth: 0, background: 'var(--bg)' }}>
        {headerProps && <Header {...headerProps}/>}
        <div style={{ flex: 1, overflow: 'auto', background: 'var(--bg)' }}>
          {children}
        </div>
      </div>
    </div>
  );
}

Object.assign(window, { Sidebar, Header, Shell });
