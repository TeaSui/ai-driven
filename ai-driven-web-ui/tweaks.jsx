// Tweaks panel — bottom-right floating
const { useState: twS, useEffect: twE } = React;

const ACCENTS = {
  teal:    { a400: '#86b8be', a500: '#5fa0a8', a600: '#47858d', aSoft: '#f0f7f8', aContrast: '#0b1416' },
  indigo:  { a400: '#8b95e0', a500: '#6b77d0', a600: '#4e5ab8', aSoft: '#f2f3fb', aContrast: '#fff' },
  coral:   { a400: '#e68a6b', a500: '#d46e4d', a600: '#b5543a', aSoft: '#fbf1ec', aContrast: '#fff' },
  amber:   { a400: '#e0b26c', a500: '#c8944a', a600: '#a77732', aSoft: '#faf3e7', aContrast: '#0b1416' },
  ink:     { a400: '#4a5658', a500: '#2e3638', a600: '#1a2022', aSoft: '#f2f0ec', aContrast: '#fff' },
};

function TweaksPanel() {
  const gtx = useGtx();
  const [open, setOpen] = twS(true);
  const [editActive, setEditActive] = twS(false);

  twE(() => {
    const handler = (e) => {
      if (!e.data) return;
      if (e.data.type === '__activate_edit_mode') { setEditActive(true); setOpen(true); }
      if (e.data.type === '__deactivate_edit_mode') setEditActive(false);
    };
    window.addEventListener('message', handler);
    try { window.parent.postMessage({ type: '__edit_mode_available' }, '*'); } catch (e) {}
    return () => window.removeEventListener('message', handler);
  }, []);

  const persist = (edits) => {
    try { window.parent.postMessage({ type: '__edit_mode_set_keys', edits }, '*'); } catch (e) {}
  };

  if (!editActive) return null;

  return (
    <div style={{
      position: 'fixed', bottom: 18, right: 18, zIndex: 1000,
      width: 280, background: '#0f1a1d', color: '#eaf2f3',
      border: '1px solid #2a3e44', borderRadius: 12,
      boxShadow: '0 20px 60px rgba(0,0,0,.4)',
      fontFamily: 'Geist, -apple-system, sans-serif',
      fontSize: 12.5, overflow: 'hidden',
    }}>
      <div onClick={() => setOpen(o=>!o)} style={{ padding: '10px 14px', display: 'flex', alignItems: 'center', gap: 8,
        borderBottom: open ? '1px solid #1d2c31' : 'none', cursor: 'pointer' }}>
        <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="#86b8be" strokeWidth="2" strokeLinecap="square">
          <path d="M4 4 L12 12 L20 4 M4 20 L12 12 L20 20 M12 12 L12 17"/>
        </svg>
        <span style={{ fontWeight: 600, flex: 1 }}>Tweaks</span>
        <span style={{ transform: `rotate(${open?180:0}deg)`, transition: 'transform .2s' }}>
          <svg width="10" height="10" viewBox="0 0 10 10" fill="none" stroke="currentColor" strokeWidth="1.5"><path d="M2 6l3-3 3 3"/></svg>
        </span>
      </div>
      {open && (
        <div style={{ padding: 14, display: 'flex', flexDirection: 'column', gap: 14 }}>
          <Group label="Theme">
            <Seg options={[{v:'light',l:'Light',i:'☀'},{v:'dark',l:'Dark',i:'☾'}]}
              value={gtx.theme} onChange={v => { gtx.setTheme(v); persist({ theme: v }); }}/>
          </Group>
          <Group label="Accent">
            <div style={{ display: 'flex', gap: 6 }}>
              {Object.entries(ACCENTS).map(([k,v]) => (
                <button key={k} title={k} onClick={() => { gtx.setAccentKey(k); persist({ accentKey: k }); }}
                  style={{ width: 30, height: 30, borderRadius: 8,
                    background: v.a500, border: gtx.accentKey === k ? '2px solid #fff' : '2px solid transparent',
                    cursor: 'pointer', padding: 0 }}/>
              ))}
            </div>
          </Group>
          <Group label="Density">
            <Seg options={[{v:'compact',l:'Compact'},{v:'balanced',l:'Balanced'},{v:'spacious',l:'Spacious'}]}
              value={gtx.density} onChange={v => { gtx.setDensity(v); persist({ density: v }); }}/>
          </Group>
          <Group label="Sidebar">
            <Seg options={[{v:'expanded',l:'Expanded'},{v:'collapsed',l:'Collapsed'}]}
              value={gtx.collapsed ? 'collapsed' : 'expanded'}
              onChange={v => { gtx.setCollapsed(v==='collapsed'); persist({ sidebarCollapsed: v==='collapsed' }); }}/>
          </Group>
          <Group label="Agent thinking">
            <Seg options={[{v:'on',l:'Show'},{v:'off',l:'Hide'}]}
              value={gtx.showThinking ? 'on' : 'off'}
              onChange={v => { gtx.setShowThinking(v==='on'); persist({ showThinking: v==='on' }); }}/>
          </Group>
        </div>
      )}
    </div>
  );
}

function Group({ label, children }) {
  return (
    <div>
      <div style={{ fontSize: 10.5, color: '#7a8a8e', textTransform: 'uppercase', letterSpacing: 0.6, marginBottom: 6 }}>{label}</div>
      {children}
    </div>
  );
}

function Seg({ options, value, onChange }) {
  return (
    <div style={{ display: 'flex', background: '#0a1214', borderRadius: 6, padding: 2 }}>
      {options.map(o => (
        <button key={o.v} onClick={() => onChange(o.v)}
          style={{ flex: 1, padding: '5px 8px', background: value===o.v ? '#1d2c31' : 'transparent',
            color: value===o.v ? '#eaf2f3' : '#7a8a8e', border: 'none', borderRadius: 4,
            cursor: 'pointer', fontSize: 11.5, fontWeight: value===o.v ? 500 : 400, fontFamily: 'inherit' }}>
          {o.i && <span style={{ marginRight: 4 }}>{o.i}</span>}{o.l}
        </button>
      ))}
    </div>
  );
}

Object.assign(window, { TweaksPanel, ACCENTS });
