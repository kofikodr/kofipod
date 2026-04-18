// primitives.jsx — shared building blocks (status bar, nav, art, etc.)

// ─── Android status bar (themed) ───────────────────────────
function KpStatusBar({ t }) {
  const c = t.text;
  return (
    <div style={{
      height: 28, display: 'flex', alignItems: 'center',
      justifyContent: 'space-between', padding: '0 18px',
      position: 'relative', flexShrink: 0,
      fontFamily: KP_FONT.sans,
    }}>
      <span style={{ fontSize: 13, fontWeight: 600, color: c }}>9:41</span>
      <div style={{
        position: 'absolute', left: '50%', top: 6, transform: 'translateX(-50%)',
        width: 18, height: 18, borderRadius: 100, background: '#0a0a0a',
      }} />
      <div style={{ display: 'flex', gap: 5, alignItems: 'center', color: c }}>
        {/* wifi */}
        <svg width="14" height="14" viewBox="0 0 16 16"><path d="M8 12.3L.9 5.2a10 10 0 0114.2 0L8 12.3z" fill={c}/></svg>
        {/* signal */}
        <svg width="14" height="14" viewBox="0 0 16 16"><path d="M14 14V2L2 14h12z" fill={c}/></svg>
        {/* battery */}
        <svg width="18" height="10" viewBox="0 0 24 12">
          <rect x="0.5" y="0.5" width="21" height="11" rx="2.5" fill="none" stroke={c}/>
          <rect x="2" y="2" width="14" height="8" rx="1" fill={c}/>
          <rect x="22" y="4" width="2" height="4" rx="1" fill={c}/>
        </svg>
      </div>
    </div>
  );
}

// ─── Gesture pill ──────────────────────────────────────────
function KpNavPill({ t }) {
  return (
    <div style={{ height: 22, display: 'flex', alignItems: 'center', justifyContent: 'center', flexShrink: 0 }}>
      <div style={{ width: 108, height: 4, borderRadius: 2, background: t.text, opacity: 0.5 }} />
    </div>
  );
}

// ─── Phone outer frame + inner content ─────────────────────
function KpPhone({ t, children, bg }) {
  return (
    <div style={{
      width: 390, height: 820, borderRadius: 44,
      padding: 8, background: t.name === 'dark' ? '#000' : '#2B1A4A',
      boxShadow: t.shadowMd,
      position: 'relative',
    }}>
      <div className="kp-root" style={{
        width: '100%', height: '100%', borderRadius: 36, overflow: 'hidden',
        background: bg || t.bg,
        display: 'flex', flexDirection: 'column',
      }}>
        <style dangerouslySetInnerHTML={{ __html: kpBaseCss(t) }} />
        <KpStatusBar t={t} />
        {children}
        <KpNavPill t={t} />
      </div>
    </div>
  );
}

// ─── Art placeholder — striped purple tile w/ mono label ───
function KpArt({ t, label, size = 56, radius = 14, seed = 0 }) {
  const hue = (280 + seed * 17) % 360;
  const a = `oklch(${t.name === 'dark' ? '55%' : '52%'} 0.22 ${hue})`;
  const b = `oklch(${t.name === 'dark' ? '68%' : '70%'} 0.18 ${(hue + 28) % 360})`;
  return (
    <div style={{
      width: size, height: size, borderRadius: radius, flexShrink: 0,
      background: `linear-gradient(135deg, ${a} 0%, ${b} 100%)`,
      position: 'relative', overflow: 'hidden',
      boxShadow: `inset 0 0 0 1px rgba(255,255,255,0.08)`,
    }}>
      {/* diagonal stripes */}
      <div style={{
        position: 'absolute', inset: 0, opacity: 0.22,
        backgroundImage: 'repeating-linear-gradient(45deg, transparent 0 10px, rgba(255,255,255,0.6) 10px 11px)',
      }} />
      {/* corner mark */}
      <div className="kp-mono" style={{
        position: 'absolute', bottom: 4, right: 6,
        fontSize: Math.max(8, size * 0.13), color: 'rgba(255,255,255,0.85)',
        letterSpacing: '0.05em', fontWeight: 600,
      }}>{label || '◐'}</div>
    </div>
  );
}

// ─── Bottom tab bar ────────────────────────────────────────
const KP_TABS = [
  { id: 'library', label: 'Library', icon: 'lib' },
  { id: 'search', label: 'Search', icon: 'search' },
  { id: 'downloads', label: 'Downloads', icon: 'down' },
  { id: 'settings', label: 'Settings', icon: 'set' },
];

function KpTabIcon({ kind, active, t }) {
  const c = active ? t.purple : t.textMute;
  const s = 22;
  switch (kind) {
    case 'lib':
      return (
        <svg width={s} height={s} viewBox="0 0 24 24" fill="none" stroke={c} strokeWidth={active ? 2.2 : 1.8} strokeLinecap="round" strokeLinejoin="round">
          <path d="M4 4h4v16H4zM10 4h4v16h-4zM16 6l4 1-3 14-4-1z"/>
        </svg>
      );
    case 'search':
      return (
        <svg width={s} height={s} viewBox="0 0 24 24" fill="none" stroke={c} strokeWidth={active ? 2.2 : 1.8} strokeLinecap="round" strokeLinejoin="round">
          <circle cx="11" cy="11" r="7"/><path d="m20 20-3.5-3.5"/>
        </svg>
      );
    case 'down':
      return (
        <svg width={s} height={s} viewBox="0 0 24 24" fill="none" stroke={c} strokeWidth={active ? 2.2 : 1.8} strokeLinecap="round" strokeLinejoin="round">
          <path d="M12 3v13m0 0-4-4m4 4 4-4M4 20h16"/>
        </svg>
      );
    case 'set':
      return (
        <svg width={s} height={s} viewBox="0 0 24 24" fill="none" stroke={c} strokeWidth={active ? 2.2 : 1.8} strokeLinecap="round" strokeLinejoin="round">
          <circle cx="12" cy="12" r="3"/>
          <path d="M19.4 15a1.7 1.7 0 0 0 .3 1.8l.1.1a2 2 0 1 1-2.8 2.8l-.1-.1a1.7 1.7 0 0 0-1.8-.3 1.7 1.7 0 0 0-1 1.5V21a2 2 0 0 1-4 0v-.1a1.7 1.7 0 0 0-1-1.5 1.7 1.7 0 0 0-1.8.3l-.1.1a2 2 0 1 1-2.8-2.8l.1-.1a1.7 1.7 0 0 0 .3-1.8 1.7 1.7 0 0 0-1.5-1H3a2 2 0 0 1 0-4h.1a1.7 1.7 0 0 0 1.5-1 1.7 1.7 0 0 0-.3-1.8l-.1-.1a2 2 0 1 1 2.8-2.8l.1.1a1.7 1.7 0 0 0 1.8.3h0a1.7 1.7 0 0 0 1-1.5V3a2 2 0 0 1 4 0v.1a1.7 1.7 0 0 0 1 1.5h0a1.7 1.7 0 0 0 1.8-.3l.1-.1a2 2 0 1 1 2.8 2.8l-.1.1a1.7 1.7 0 0 0-.3 1.8v0a1.7 1.7 0 0 0 1.5 1H21a2 2 0 0 1 0 4h-.1a1.7 1.7 0 0 0-1.5 1z"/>
        </svg>
      );
  }
}

function KpTabBar({ t, active = 'library' }) {
  return (
    <div style={{
      borderTop: `1px solid ${t.border}`,
      background: t.surface,
      padding: '8px 8px 4px',
      display: 'grid', gridTemplateColumns: 'repeat(4,1fr)', gap: 4,
      flexShrink: 0,
    }}>
      {KP_TABS.map(tab => {
        const is = tab.id === active;
        return (
          <div key={tab.id} style={{
            display: 'flex', flexDirection: 'column', alignItems: 'center', gap: 3,
            padding: '6px 4px 4px', borderRadius: 12,
            background: is ? t.purpleTint : 'transparent',
          }}>
            <KpTabIcon kind={tab.icon} active={is} t={t} />
            <div style={{
              fontSize: 10.5, fontWeight: is ? 700 : 500,
              color: is ? t.purple : t.textMute, letterSpacing: '-0.01em',
            }}>{tab.label}</div>
          </div>
        );
      })}
    </div>
  );
}

// ─── Generic icon (stroke, inherits color) ─────────────────
function KpIcon({ name, size = 20, color = 'currentColor', stroke = 1.8 }) {
  const p = { width: size, height: size, viewBox: '0 0 24 24', fill: 'none', stroke: color, strokeWidth: stroke, strokeLinecap: 'round', strokeLinejoin: 'round' };
  switch (name) {
    case 'back': return <svg {...p}><path d="M15 6l-6 6 6 6"/></svg>;
    case 'more': return <svg {...p}><circle cx="5" cy="12" r="1.5" fill={color}/><circle cx="12" cy="12" r="1.5" fill={color}/><circle cx="19" cy="12" r="1.5" fill={color}/></svg>;
    case 'plus': return <svg {...p}><path d="M12 5v14M5 12h14"/></svg>;
    case 'close': return <svg {...p}><path d="M6 6l12 12M18 6l-12 12"/></svg>;
    case 'search': return <svg {...p}><circle cx="11" cy="11" r="7"/><path d="m20 20-3.5-3.5"/></svg>;
    case 'folder': return <svg {...p}><path d="M3 7a2 2 0 0 1 2-2h4l2 2h8a2 2 0 0 1 2 2v9a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2z"/></svg>;
    case 'download': return <svg {...p}><path d="M12 3v13m0 0-4-4m4 4 4-4M4 20h16"/></svg>;
    case 'check': return <svg {...p}><path d="M5 12l5 5L20 7"/></svg>;
    case 'bell': return <svg {...p}><path d="M6 8a6 6 0 0 1 12 0v5l2 3H4l2-3zM10 19a2 2 0 0 0 4 0"/></svg>;
    case 'trash': return <svg {...p}><path d="M4 7h16M9 7V4h6v3M6 7l1 13h10l1-13M10 11v6M14 11v6"/></svg>;
    case 'share': return <svg {...p}><circle cx="18" cy="5" r="3"/><circle cx="6" cy="12" r="3"/><circle cx="18" cy="19" r="3"/><path d="m8.6 10.5 6.8-4M8.6 13.5l6.8 4"/></svg>;
    case 'play': return <svg {...p} fill={color} stroke="none"><path d="M7 4v16l13-8z"/></svg>;
    case 'pause': return <svg {...p}><rect x="6" y="5" width="4" height="14" rx="1" fill={color}/><rect x="14" y="5" width="4" height="14" rx="1" fill={color}/></svg>;
    case 'chev': return <svg {...p}><path d="M9 6l6 6-6 6"/></svg>;
    case 'chevD': return <svg {...p}><path d="M6 9l6 6 6-6"/></svg>;
    case 'mic': return <svg {...p}><rect x="9" y="3" width="6" height="12" rx="3"/><path d="M5 11a7 7 0 0 0 14 0M12 18v3"/></svg>;
    case 'person': return <svg {...p}><circle cx="12" cy="8" r="4"/><path d="M4 20c1-4 4-6 8-6s7 2 8 6"/></svg>;
    case 'cloud': return <svg {...p}><path d="M7 18h10a4 4 0 0 0 .6-8 6 6 0 0 0-11.6 1A4 4 0 0 0 7 18z"/></svg>;
    case 'moon': return <svg {...p}><path d="M20 14A8 8 0 0 1 10 4a8 8 0 1 0 10 10z"/></svg>;
    case 'sun': return <svg {...p}><circle cx="12" cy="12" r="4"/><path d="M12 2v2M12 20v2M2 12h2M20 12h2M5 5l1.4 1.4M17.6 17.6 19 19M5 19l1.4-1.4M17.6 6.4 19 5"/></svg>;
    case 'clock': return <svg {...p}><circle cx="12" cy="12" r="9"/><path d="M12 7v5l3 2"/></svg>;
    case 'filter': return <svg {...p}><path d="M3 5h18M6 12h12M10 19h4"/></svg>;
    case 'google': return (
      <svg width={size} height={size} viewBox="0 0 24 24">
        <path d="M21.35 11.1H12v3.2h5.35c-.25 1.5-1.7 4.4-5.35 4.4-3.22 0-5.85-2.67-5.85-5.95S8.78 6.8 12 6.8c1.83 0 3.06.78 3.76 1.45l2.56-2.47C16.72 4.33 14.55 3.4 12 3.4 6.97 3.4 3 7.37 3 12.75S6.97 22.1 12 22.1c6.92 0 9.35-4.85 9.35-8.2 0-.55-.05-.97-.12-1.8z" fill="#4285F4"/>
        <path d="M3 12.75c0-1.6.32-3.1.9-4.45L.85 5.9A11.7 11.7 0 0 0 0 12.75c0 1.9.45 3.7 1.25 5.3l2.9-2.3a6 6 0 0 1-.55-3z" fill="#FBBC05" opacity="0"/>
      </svg>
    );
    case 'radar': return <svg {...p}><circle cx="12" cy="12" r="9"/><circle cx="12" cy="12" r="5"/><circle cx="12" cy="12" r="1.5" fill={color}/></svg>;
    case 'newitem': return <svg {...p}><path d="M12 2v4M12 18v4M2 12h4M18 12h4M5 5l2.5 2.5M16.5 16.5 19 19M5 19l2.5-2.5M16.5 7.5 19 5"/></svg>;
    case 'wifi': return <svg {...p}><path d="M2 9a15 15 0 0 1 20 0M5 13a10 10 0 0 1 14 0M8.5 16.5a5 5 0 0 1 7 0M12 20h0"/></svg>;
    case 'lock': return <svg {...p}><rect x="4" y="11" width="16" height="10" rx="2"/><path d="M8 11V7a4 4 0 0 1 8 0v4"/></svg>;
    case 'disc': return <svg {...p}><circle cx="12" cy="12" r="9"/><circle cx="12" cy="12" r="2"/></svg>;
    default: return null;
  }
}

Object.assign(window, { KpStatusBar, KpNavPill, KpPhone, KpArt, KpTabBar, KpIcon, KP_TABS });
