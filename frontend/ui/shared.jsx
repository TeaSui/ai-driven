// Shared UI primitives for GoTyme-X AI
// Buttons, pills, cards, avatars, tooltips, etc.

const { useState, useEffect, useRef, useMemo, useCallback } = React;

/* ── Pill / Badge ─────────────────────────── */
function Pill({ tone = 'default', children, style, icon, dot, size = 'sm' }) {
  const tones = {
    default: { bg: 'var(--bg-muted)', fg: 'var(--text-muted)', bd: 'var(--border)' },
    brand:   { bg: 'var(--accent-soft)', fg: 'var(--brand-teal-700)', bd: 'var(--brand-teal-200)' },
    success: { bg: 'var(--status-success-bg)', fg: 'var(--status-success)', bd: 'transparent' },
    warn:    { bg: 'var(--status-warn-bg)', fg: 'var(--status-warn)', bd: 'transparent' },
    danger:  { bg: 'var(--status-danger-bg)', fg: 'var(--status-danger)', bd: 'transparent' },
    info:    { bg: 'var(--status-info-bg)', fg: 'var(--status-info)', bd: 'transparent' },
    ghost:   { bg: 'transparent', fg: 'var(--text-muted)', bd: 'var(--border)' },
    solid:   { bg: 'var(--text)', fg: 'var(--bg)', bd: 'transparent' },
  };
  const t = tones[tone] || tones.default;
  const sz = size === 'xs'
    ? { fs: 10.5, pd: '2px 6px', h: 18 }
    : size === 'md'
    ? { fs: 12.5, pd: '4px 10px', h: 24 }
    : { fs: 11, pd: '2px 8px', h: 20 };
  return (
    <span style={{
      display: 'inline-flex', alignItems: 'center', gap: 5,
      fontSize: sz.fs, fontWeight: 500, height: sz.h, padding: sz.pd,
      background: t.bg, color: t.fg, border: `1px solid ${t.bd}`,
      borderRadius: 999, letterSpacing: 0.1, whiteSpace: 'nowrap',
      lineHeight: 1, ...style,
    }}>
      {dot && <span style={{ width: 6, height: 6, borderRadius: 3, background: t.fg, display: 'inline-block' }} />}
      {icon}
      {children}
    </span>
  );
}

/* ── Button ───────────────────────────────── */
function Btn({ tone = 'ghost', size = 'md', children, icon, iconRight, onClick, style, active, disabled, title }) {
  const [hover, setHover] = useState(false);
  const tones = {
    primary: { bg: 'var(--text)', fg: 'var(--bg)', bd: 'var(--text)', hover: 'var(--brand-teal-800)' },
    brand:   { bg: 'var(--accent-500)', fg: '#fff', bd: 'var(--accent-500)', hover: 'var(--accent-600)' },
    ghost:   { bg: 'transparent', fg: 'var(--text)', bd: 'var(--border)', hover: 'var(--bg-hover)' },
    subtle:  { bg: 'var(--bg-muted)', fg: 'var(--text)', bd: 'transparent', hover: 'var(--bg-hover)' },
    bare:    { bg: 'transparent', fg: 'var(--text-muted)', bd: 'transparent', hover: 'var(--bg-hover)' },
    danger:  { bg: 'var(--status-danger-bg)', fg: 'var(--status-danger)', bd: 'transparent', hover: 'var(--status-danger-bg)' },
  };
  const t = tones[tone];
  const sizes = {
    xs: { fs: 11.5, h: 22, px: 8, icon: 11 },
    sm: { fs: 12.5, h: 28, px: 10, icon: 13 },
    md: { fs: 13, h: 32, px: 12, icon: 14 },
    lg: { fs: 14, h: 38, px: 16, icon: 16 },
  };
  const s = sizes[size];
  return (
    <button
      title={title}
      disabled={disabled}
      onMouseEnter={() => setHover(true)}
      onMouseLeave={() => setHover(false)}
      onClick={onClick}
      style={{
        display: 'inline-flex', alignItems: 'center', justifyContent: 'center', gap: 6,
        height: s.h, padding: `0 ${s.px}px`, fontSize: s.fs, fontWeight: 500,
        background: active ? t.hover : (hover ? t.hover : t.bg),
        color: t.fg, border: `1px solid ${t.bd}`, borderRadius: 6,
        cursor: disabled ? 'not-allowed' : 'pointer',
        opacity: disabled ? 0.5 : 1,
        fontFamily: 'inherit', whiteSpace: 'nowrap',
        transition: 'background .12s, border-color .12s',
        ...style,
      }}>
      {icon && <span style={{ display: 'inline-flex', width: s.icon, height: s.icon }}>{icon}</span>}
      {children}
      {iconRight && <span style={{ display: 'inline-flex', width: s.icon, height: s.icon }}>{iconRight}</span>}
    </button>
  );
}

/* ── Icon button ──────────────────────────── */
function IconBtn({ children, onClick, title, size = 28, active, style }) {
  const [hover, setHover] = useState(false);
  return (
    <button title={title} onClick={onClick}
      onMouseEnter={() => setHover(true)}
      onMouseLeave={() => setHover(false)}
      style={{
        display: 'inline-flex', alignItems: 'center', justifyContent: 'center',
        width: size, height: size, border: 'none',
        background: (hover || active) ? 'var(--bg-hover)' : 'transparent',
        color: active ? 'var(--text)' : 'var(--text-muted)',
        borderRadius: 6, cursor: 'pointer',
        transition: 'background .12s, color .12s',
        ...style,
      }}>
      {children}
    </button>
  );
}

/* ── Card ─────────────────────────────────── */
function Card({ children, style, padded = true, elevated }) {
  return (
    <div style={{
      background: 'var(--panel)',
      border: '1px solid var(--panel-border)',
      borderRadius: 10,
      boxShadow: elevated ? 'var(--shadow-md)' : 'var(--shadow-sm)',
      padding: padded ? 16 : 0,
      ...style,
    }}>{children}</div>
  );
}

/* ── Avatar ───────────────────────────────── */
function Avatar({ name, size = 24, color, bot, style }) {
  const initials = name ? name.split(/\s+/).map(w => w[0]).slice(0, 2).join('').toUpperCase() : '?';
  // stable color from name
  const palette = ['#86B8BE', '#C79A6B', '#9E8AB0', '#7BA582', '#C98484', '#6B8FA8', '#B5A06B'];
  const idx = name ? [...name].reduce((a, c) => a + c.charCodeAt(0), 0) % palette.length : 0;
  const bg = color || palette[idx];
  if (bot) {
    return (
      <div style={{
        width: size, height: size, borderRadius: size / 4,
        background: 'var(--brand-ink)', color: 'var(--brand-teal-400)',
        display: 'inline-flex', alignItems: 'center', justifyContent: 'center',
        fontSize: size * 0.42, fontWeight: 700,
        boxShadow: 'inset 0 0 0 1px var(--brand-teal-700)',
        fontFamily: 'Geist Mono, monospace',
        ...style,
      }}>✦</div>
    );
  }
  return (
    <div style={{
      width: size, height: size, borderRadius: '50%',
      background: bg, color: '#fff',
      display: 'inline-flex', alignItems: 'center', justifyContent: 'center',
      fontSize: size * 0.38, fontWeight: 600, letterSpacing: 0.2,
      ...style,
    }}>{initials}</div>
  );
}

/* ── Brand mark (from logo) ───────────────── */
function BrandMark({ size = 22, stroke }) {
  // Reconstructed X from the GoTyme-X logo
  const c = stroke || 'var(--accent-400)';
  return (
    <svg width={size} height={size} viewBox="0 0 24 24" style={{ display: 'block' }}>
      <path d="M4 4 L12 12 L20 4" stroke={c} strokeWidth="3" fill="none" strokeLinecap="square"/>
      <path d="M4 20 L12 12 L20 20" stroke={c} strokeWidth="3" fill="none" strokeLinecap="square"/>
      <path d="M12 12 L12 17" stroke={c} strokeWidth="3" fill="none" strokeLinecap="square"/>
    </svg>
  );
}

/* ── Wordmark ─────────────────────────────── */
function Wordmark({ compact }) {
  return (
    <div style={{ display: 'inline-flex', alignItems: 'center', gap: 8 }}>
      <BrandMark size={18} />
      {!compact && (
        <span style={{ fontSize: 14, fontWeight: 600, letterSpacing: -0.3, color: 'var(--text)' }}>
          GoTyme<span style={{ color: 'var(--accent-500)' }}>·X</span> AI
        </span>
      )}
    </div>
  );
}

/* ── Divider ──────────────────────────────── */
function Divider({ vertical, style }) {
  return vertical
    ? <div style={{ width: 1, alignSelf: 'stretch', background: 'var(--divider)', ...style }} />
    : <div style={{ height: 1, background: 'var(--divider)', ...style }} />;
}

/* ── Progress (linear) ────────────────────── */
function Progress({ value = 0, tone = 'brand', height = 4, style }) {
  const color = {
    brand: 'var(--accent-500)',
    success: 'var(--status-success)',
    warn: 'var(--status-warn)',
    danger: 'var(--status-danger)',
  }[tone];
  return (
    <div style={{ height, background: 'var(--bg-muted)', borderRadius: height, overflow: 'hidden', ...style }}>
      <div style={{ height: '100%', width: `${Math.min(100, Math.max(0, value))}%`, background: color, transition: 'width .3s cubic-bezier(.2,.7,.3,1)' }} />
    </div>
  );
}

/* ── Spinner ──────────────────────────────── */
function Spinner({ size = 14, color }) {
  return (
    <svg width={size} height={size} viewBox="0 0 24 24" style={{ animation: 'gtx-spin .9s linear infinite' }}>
      <circle cx="12" cy="12" r="9" stroke="currentColor" strokeWidth="2.2" fill="none" strokeOpacity=".25" style={{ color: color || 'currentColor' }} />
      <path d="M21 12 A9 9 0 0 0 12 3" stroke={color || 'currentColor'} strokeWidth="2.2" fill="none" strokeLinecap="round" />
    </svg>
  );
}

/* ── Status dot (animated) ────────────────── */
function StatusDot({ tone = 'success', size = 8, pulse }) {
  const c = {
    success: 'var(--status-success)',
    warn: 'var(--status-warn)',
    danger: 'var(--status-danger)',
    info: 'var(--status-info)',
    idle: 'var(--text-faint)',
  }[tone];
  return (
    <span style={{ position: 'relative', display: 'inline-block', width: size, height: size }}>
      <span style={{ position: 'absolute', inset: 0, background: c, borderRadius: '50%' }} />
      {pulse && <span style={{ position: 'absolute', inset: 0, background: c, borderRadius: '50%', animation: 'gtx-ring 1.6s ease-out infinite' }} />}
    </span>
  );
}

/* ── Code block (light inline) ────────────── */
function Code({ children, lang, style }) {
  return (
    <code className="gtx-mono" style={{
      fontSize: 'var(--gtx-fs-sm)',
      background: 'var(--code-bg)',
      color: 'var(--code-text)',
      padding: '1px 5px',
      borderRadius: 4,
      border: '1px solid var(--panel-border)',
      ...style,
    }}>{children}</code>
  );
}

/* ── Kbd ──────────────────────────────────── */
function Kbd({ children }) {
  return (
    <kbd style={{
      fontFamily: 'Geist Mono, monospace', fontSize: 10.5,
      padding: '1px 5px', borderRadius: 3,
      background: 'var(--bg-muted)', color: 'var(--text-muted)',
      border: '1px solid var(--border)', borderBottomWidth: 2,
      minWidth: 16, display: 'inline-block', textAlign: 'center',
    }}>{children}</kbd>
  );
}

/* ── useInterval ──────────────────────────── */
function useInterval(cb, delay) {
  const saved = useRef(cb);
  useEffect(() => { saved.current = cb; }, [cb]);
  useEffect(() => {
    if (delay == null) return;
    const id = setInterval(() => saved.current(), delay);
    return () => clearInterval(id);
  }, [delay]);
}

/* ── Theme / Tweaks context ───────────────── */
const GtxCtx = React.createContext(null);

function useGtx() { return React.useContext(GtxCtx); }

Object.assign(window, {
  Pill, Btn, IconBtn, Card, Avatar, BrandMark, Wordmark, Divider,
  Progress, Spinner, StatusDot, Code, Kbd, useInterval,
  GtxCtx, useGtx,
});
