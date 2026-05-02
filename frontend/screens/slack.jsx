// Slack bot conversation
function SlackScreen() {
  const msgs = [
    { user: 'Mira Delgado', at: '14:22', text: (<>@gotyme-x-ai please add rate-limit headers to the <code style={ms}>/v2/transfers</code> endpoint. Jira <code style={ms}>GTX-4821</code>.</>) },
    { bot: true, at: '14:22', text: (<>On it — reading <code style={ms}>GTX-4821</code> now. I'll draft a PR on <code style={ms}>go-tyme/payments-api</code>.</>),
      thread: [
        { icon: <Ic.Jira size={11}/>, label: 'Fetched ticket', detail: 'GTX-4821 · 3 ACs · assigned me' },
        { icon: <Ic.Github size={11}/>, label: 'Cloned repo', detail: 'go-tyme/payments-api @ main' },
        { icon: <Ic.Thinking size={11}/>, label: 'Planned', detail: '4 files · middleware + tests + docs' },
      ]
    },
    { bot: true, at: '14:36', text: (<><strong>PR #2418</strong> opened: <span style={{ color: '#1264a3' }}>fix(payments): add rate-limit headers</span><br/>7 files · +142 −38 · <code style={ms}>fix/GTX-4821-rate-limit</code></>),
      card: { title: '#2418 fix(payments): add rate-limit headers', repo: 'go-tyme/payments-api', status: 'Open · AI-reviewed · CI passing' } },
    { user: 'Jamie Ortiz', at: '14:38', text: 'Nice. Can you also bump the docs?' },
    { bot: true, at: '14:38', text: (<>Already did — see <code style={ms}>docs/rate-limiting.md</code> in the PR. Let me know if the wording works.</>) },
    { user: 'Mira Delgado', at: '14:42', text: 'LGTM. Approving.' },
    { bot: true, at: '14:42', typing: true },
  ];
  return (
    <Shell active="slack" headerProps={{
      title: '#platform-ai',
      crumbs: ['Slack', 'gotyme-eng'],
      sub: '12 members · bot connected',
      right: (<>
        <Btn size="sm" icon={<Ic.Settings size={11}/>}>Bot settings</Btn>
        <Btn size="sm" tone="subtle">Open in Slack</Btn>
      </>),
    }}>
      <div style={{ padding: '20px 24px', maxWidth: 780, margin: '0 auto' }}>
        <div style={{ borderLeft: '2px solid var(--divider)', paddingLeft: 4 }}>
          <div style={{ padding: '8px 16px', fontSize: 11, color: 'var(--text-subtle)', textTransform: 'uppercase', letterSpacing: 0.6, display: 'flex', alignItems: 'center', gap: 8 }}>
            <div style={{ flex: 1, height: 1, background: 'var(--divider)' }}/>
            <span>Today</span>
            <div style={{ flex: 1, height: 1, background: 'var(--divider)' }}/>
          </div>
          {msgs.map((m, i) => <SlackMsg key={i} msg={m}/>)}
        </div>
        <SlackComposer/>
      </div>
    </Shell>
  );
}

const ms = { fontFamily: 'Geist Mono, monospace', fontSize: '90%', background: 'var(--bg-muted)', padding: '1px 4px', borderRadius: 3, border: '1px solid var(--border)' };

function SlackMsg({ msg }) {
  return (
    <div style={{ display: 'flex', gap: 10, padding: '10px 14px', borderRadius: 4 }}>
      {msg.bot ? <Avatar bot size={34}/> : <Avatar name={msg.user} size={34}/>}
      <div style={{ flex: 1, minWidth: 0 }}>
        <div style={{ display: 'flex', alignItems: 'baseline', gap: 8, marginBottom: 3 }}>
          <span style={{ fontSize: 14, fontWeight: 600, color: 'var(--text)' }}>
            {msg.bot ? 'GoTyme-X AI' : msg.user}
          </span>
          {msg.bot && <Pill tone="brand" size="xs">APP</Pill>}
          <span style={{ fontSize: 11, color: 'var(--text-subtle)' }}>{msg.at}</span>
        </div>
        {msg.typing ? (
          <div style={{ display: 'flex', alignItems: 'center', gap: 8, color: 'var(--text-subtle)', fontSize: 13 }}>
            <div style={{ display: 'flex', gap: 3 }}>
              {[0,1,2].map(i => <span key={i} style={{ width: 6, height: 6, borderRadius: 3, background: 'var(--accent-500)', animation: `gtx-pulse 1.2s ${i*.2}s infinite` }}/>)}
            </div>
            <span>thinking…</span>
          </div>
        ) : (
          <>
            <div style={{ fontSize: 13.5, color: 'var(--text)', lineHeight: 1.55 }}>{msg.text}</div>
            {msg.thread && (
              <div style={{ marginTop: 8, border: '1px solid var(--panel-border)', borderRadius: 6, overflow: 'hidden', background: 'var(--panel)' }}>
                {msg.thread.map((t, i) => (
                  <div key={i} style={{ display: 'flex', alignItems: 'center', gap: 8, padding: '7px 12px',
                    borderBottom: i < msg.thread.length - 1 ? '1px solid var(--divider)' : 'none', fontSize: 12 }}>
                    <span style={{ color: 'var(--accent-500)' }}>{t.icon}</span>
                    <span style={{ color: 'var(--text)', fontWeight: 500 }}>{t.label}</span>
                    <span style={{ flex: 1 }}/>
                    <span style={{ color: 'var(--text-subtle)' }}>{t.detail}</span>
                    <Ic.Check size={11} color="var(--status-success)"/>
                  </div>
                ))}
              </div>
            )}
            {msg.card && (
              <div style={{ marginTop: 8, border: '1px solid var(--panel-border)', borderLeft: '3px solid var(--accent-500)', borderRadius: 4, padding: '10px 14px', background: 'var(--panel)', maxWidth: 480 }}>
                <div style={{ display: 'flex', alignItems: 'center', gap: 6, fontSize: 11.5, color: 'var(--text-subtle)', marginBottom: 3 }}>
                  <Ic.Github size={11}/>{msg.card.repo}
                </div>
                <div style={{ fontSize: 13, fontWeight: 500, color: 'var(--text)', marginBottom: 4 }}>{msg.card.title}</div>
                <div style={{ fontSize: 11.5, color: 'var(--status-success)' }}>{msg.card.status}</div>
              </div>
            )}
          </>
        )}
      </div>
    </div>
  );
}

function SlackComposer() {
  return (
    <div style={{ marginTop: 24, border: '1px solid var(--border-strong)', borderRadius: 8, background: 'var(--bg)' }}>
      <div style={{ padding: '10px 14px', minHeight: 60, fontSize: 13.5, color: 'var(--text-faint)' }}>
        Message #platform-ai
      </div>
      <div style={{ padding: '6px 10px', borderTop: '1px solid var(--divider)', display: 'flex', gap: 6, alignItems: 'center' }}>
        <IconBtn size={24}><Ic.Plus size={13}/></IconBtn>
        <IconBtn size={24}><Ic.Wand size={13}/></IconBtn>
        <IconBtn size={24}><Ic.Code size={13}/></IconBtn>
        <div style={{ flex: 1 }}/>
        <Btn size="xs" tone="brand" iconRight={<Ic.ArrowRight size={10}/>}>Send</Btn>
      </div>
    </div>
  );
}

Object.assign(window, { SlackScreen });
