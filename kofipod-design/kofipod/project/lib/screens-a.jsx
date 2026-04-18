// screens-a.jsx — Onboarding, Search, PodcastDetail, Library

// Mock data
const KP_CATEGORIES = ['Tech', 'Design', 'Science', 'Business', 'Comedy', 'History', 'Politics'];

const KP_PODS = [
  { id: 'p1', title: 'Signal & Noise', host: 'Mira Halverson', cat: 'Tech', eps: 214,
    desc: 'Weekly conversations on what matters in the software industry — with builders, operators, and the occasional skeptic.',
    seed: 1 },
  { id: 'p2', title: 'Slow Burn Science', host: 'Dr. Ade Okonkwo', cat: 'Science', eps: 87,
    desc: 'Unhurried deep dives into the science stories that shaped the century.',
    seed: 3 },
  { id: 'p3', title: 'The Second Draft', host: 'Imogen Reyes', cat: 'Design', eps: 142,
    desc: 'Designers talking candidly about the work that didn\'t ship.',
    seed: 5 },
  { id: 'p4', title: 'Ledgers & Legends', host: 'Taro Benavidez', cat: 'Business', eps: 63,
    desc: 'A business history podcast for people who read the footnotes.',
    seed: 7 },
  { id: 'p5', title: 'Field Recordings', host: 'Kora Lindqvist', cat: 'History', eps: 198,
    desc: 'Oral histories, artifacts, and the places in between.',
    seed: 9 },
];

// ── Onboarding / Google sign-in ───────────────────────────
function ScreenOnboarding({ t }) {
  return (
    <KpPhone t={t}>
      <div style={{ flex: 1, position: 'relative', overflow: 'hidden', display: 'flex', flexDirection: 'column' }}>
        {/* radial glow */}
        <div style={{
          position: 'absolute', top: -120, left: -60, width: 500, height: 500,
          background: `radial-gradient(circle, ${t.pink}33 0%, transparent 60%)`,
        }} />
        <div style={{
          position: 'absolute', bottom: -140, right: -100, width: 440, height: 440,
          background: `radial-gradient(circle, ${t.purple}44 0%, transparent 60%)`,
        }} />
        <div style={{ position: 'relative', padding: '48px 28px 24px', flex: 1, display: 'flex', flexDirection: 'column' }}>
          {/* logo */}
          <div style={{ display: 'flex', alignItems: 'center', gap: 10, marginBottom: 48 }}>
            <div style={{
              width: 38, height: 38, borderRadius: 12,
              background: `linear-gradient(135deg, ${t.purple}, ${t.pink})`,
              boxShadow: t.shadowPink,
              display: 'flex', alignItems: 'center', justifyContent: 'center', color: '#fff',
            }}>
              <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="#fff" strokeWidth="2.4" strokeLinecap="round"><path d="M12 3v10M8 9a4 4 0 1 0 8 0M6 14a6 6 0 0 0 12 0M4 19a8 8 0 0 0 16 0"/></svg>
            </div>
            <div style={{ fontWeight: 800, fontSize: 22, letterSpacing: '-0.03em' }}>Kofipod</div>
          </div>

          <div style={{ flex: 1 }}>
            <div style={{
              fontSize: 40, fontWeight: 800, lineHeight: 1.05,
              letterSpacing: '-0.035em', color: t.text, marginBottom: 16,
            }}>
              Your podcasts,<br/>
              <span style={{ color: t.purple }}>your way.</span>
              <span style={{ color: t.pink }}>.</span>
            </div>
            <div style={{ fontSize: 15, color: t.textSoft, lineHeight: 1.5, maxWidth: 300 }}>
              Search the open podcast index. Build your own lists. Sync privately to your Google Drive.
            </div>

            {/* benefit pills */}
            <div style={{ display: 'flex', flexDirection: 'column', gap: 10, marginTop: 32 }}>
              {[
                { icon: 'search', txt: 'Search by title or person' },
                { icon: 'cloud', txt: 'Backed up to your private Drive folder' },
                { icon: 'download', txt: 'On-demand & daily auto-downloads' },
              ].map((x, i) => (
                <div key={i} style={{
                  display: 'flex', alignItems: 'center', gap: 12,
                  padding: '10px 14px', borderRadius: 14,
                  background: t.surfaceAlt, border: `1px solid ${t.border}`,
                }}>
                  <div style={{
                    width: 28, height: 28, borderRadius: 8, flexShrink: 0,
                    background: t.purpleTint, color: t.purple,
                    display: 'flex', alignItems: 'center', justifyContent: 'center',
                  }}>
                    <KpIcon name={x.icon} size={15} color={t.purple} stroke={2} />
                  </div>
                  <div style={{ fontSize: 13.5, fontWeight: 500, color: t.text }}>{x.txt}</div>
                </div>
              ))}
            </div>
          </div>

          {/* CTAs */}
          <div style={{ display: 'flex', flexDirection: 'column', gap: 10, marginTop: 24 }}>
            <button style={{
              height: 54, borderRadius: 999, border: 'none', cursor: 'pointer',
              background: t.pink, color: t.pinkOn, boxShadow: t.shadowPink,
              fontSize: 15, fontWeight: 700, fontFamily: KP_FONT.sans,
              display: 'flex', alignItems: 'center', justifyContent: 'center', gap: 10,
            }}>
              <svg width="18" height="18" viewBox="0 0 24 24">
                <path d="M21.8 12.2c0-.7-.1-1.4-.2-2H12v3.9h5.5c-.2 1.2-1 2.3-2 3v2.5h3.3c1.9-1.8 3-4.4 3-7.4z" fill="#fff"/>
                <path d="M12 22c2.7 0 5-.9 6.7-2.4l-3.3-2.5c-.9.6-2.1 1-3.4 1-2.6 0-4.8-1.8-5.6-4.1H3v2.6A10 10 0 0 0 12 22z" fill="#fff"/>
                <path d="M6.4 14c-.2-.6-.3-1.3-.3-2s.1-1.4.3-2V7.4H3a10 10 0 0 0 0 9.2L6.4 14z" fill="#fff"/>
                <path d="M12 5.9c1.5 0 2.8.5 3.8 1.5l2.9-2.9A10 10 0 0 0 3 7.4L6.4 10c.8-2.3 3-4.1 5.6-4.1z" fill="#fff"/>
              </svg>
              Continue with Google
            </button>
            <button style={{
              height: 48, borderRadius: 999, border: `1.5px solid ${t.border}`,
              background: 'transparent', color: t.textSoft, cursor: 'pointer',
              fontSize: 14, fontWeight: 600, fontFamily: KP_FONT.sans,
            }}>
              Skip — use locally
            </button>
            <div style={{ textAlign: 'center', fontSize: 11, color: t.textMute, marginTop: 2, lineHeight: 1.5 }}>
              We only request <span style={{ color: t.textSoft, fontWeight: 600 }}>drive.appdata</span> scope — a hidden, app-only folder. Your library stays private, even from us.
            </div>
          </div>
        </div>
      </div>
    </KpPhone>
  );
}

// ── Search results ────────────────────────────────────────
function ScreenSearch({ t }) {
  return (
    <KpPhone t={t}>
      {/* header */}
      <div style={{ padding: '8px 20px 12px' }}>
        <div style={{ fontSize: 28, fontWeight: 800, letterSpacing: '-0.03em', color: t.text }}>Search</div>
        <div style={{ fontSize: 13, color: t.textMute, marginTop: 2 }}>Powered by the Podcast Index</div>
      </div>
      {/* search bar */}
      <div style={{ padding: '0 20px 12px' }}>
        <div style={{
          display: 'flex', alignItems: 'center', gap: 10,
          background: t.surfaceAlt, border: `1.5px solid ${t.borderStrong}`,
          borderRadius: 16, padding: '12px 14px',
        }}>
          <KpIcon name="search" size={18} color={t.purple} stroke={2.2} />
          <div style={{ flex: 1, fontSize: 15, color: t.text, fontWeight: 500 }}>
            ezra klein<span style={{ display: 'inline-block', width: 2, height: 16, background: t.pink, marginLeft: 2, verticalAlign: 'middle' }} />
          </div>
          <div style={{
            width: 24, height: 24, borderRadius: 999, background: t.border,
            display: 'flex', alignItems: 'center', justifyContent: 'center',
          }}>
            <KpIcon name="close" size={13} color={t.textSoft} stroke={2.4} />
          </div>
        </div>
        {/* filter chips */}
        <div style={{ display: 'flex', gap: 8, marginTop: 10 }}>
          {[
            { label: 'All', active: true },
            { label: 'By title', active: false },
            { label: 'By person', active: false },
          ].map((c, i) => (
            <div key={i} style={{
              padding: '6px 14px', borderRadius: 999, fontSize: 12.5, fontWeight: 600,
              background: c.active ? t.purple : 'transparent',
              color: c.active ? t.purpleOn : t.textSoft,
              border: `1px solid ${c.active ? t.purple : t.border}`,
            }}>{c.label}</div>
          ))}
        </div>
      </div>
      {/* results */}
      <div className="kp-scroll" style={{ flex: 1, overflow: 'auto', padding: '4px 20px 12px' }}>
        <div style={{ fontSize: 11, fontWeight: 700, color: t.textMute, letterSpacing: '0.08em', textTransform: 'uppercase', margin: '8px 0 10px' }}>
          3 podcasts · 7 matching episodes
        </div>

        {/* featured result */}
        <SearchResultCard t={t} pod={{
          title: 'The Ezra Klein Show', host: 'Ezra Klein · NYT Opinion', eps: 312, cat: 'Politics',
          desc: 'Big ideas and long conversations about politics, society, media, and the future of humanity.',
          seed: 2, tag: 'Top match',
        }} />
        <SearchResultCard t={t} pod={{
          title: 'Plain English', host: 'Derek Thompson', eps: 188, cat: 'Business',
          desc: 'Economics, culture, technology — explained without the jargon. Occasional guest: Ezra Klein.',
          seed: 6,
        }} />
        <SearchResultCard t={t} pod={{
          title: 'Conversations with Tyler', host: 'Tyler Cowen', eps: 241, cat: 'Economics',
          desc: 'The economist in conversation with writers, scientists, and intellectuals. Featured guest: Ezra Klein (Ep. 142).',
          seed: 4,
        }} />

        <div style={{ fontSize: 11, fontWeight: 700, color: t.textMute, letterSpacing: '0.08em', textTransform: 'uppercase', margin: '18px 0 10px' }}>
          Episodes mentioning "Ezra Klein"
        </div>
        <div style={{ display: 'flex', flexDirection: 'column', gap: 0 }}>
          {[
            { pod: 'Plain English', ep: 'Why political media is broken', date: 'Mar 18' },
            { pod: 'Odd Lots', ep: 'The abundance agenda, explained', date: 'Feb 02' },
          ].map((e, i) => (
            <div key={i} style={{
              display: 'flex', alignItems: 'center', gap: 12,
              padding: '10px 0', borderTop: i > 0 ? `1px solid ${t.border}` : 'none',
            }}>
              <KpArt t={t} size={40} radius={10} label="EP" seed={10 + i} />
              <div style={{ flex: 1, minWidth: 0 }}>
                <div style={{ fontSize: 13.5, fontWeight: 600, color: t.text, whiteSpace: 'nowrap', overflow: 'hidden', textOverflow: 'ellipsis' }}>{e.ep}</div>
                <div style={{ fontSize: 11.5, color: t.textMute, marginTop: 1 }}>{e.pod} · {e.date}</div>
              </div>
              <KpIcon name="chev" size={16} color={t.textMute} />
            </div>
          ))}
        </div>
      </div>
      <KpTabBar t={t} active="search" />
    </KpPhone>
  );
}

function SearchResultCard({ t, pod }) {
  return (
    <div style={{
      background: t.surface, border: `1px solid ${t.border}`,
      borderRadius: 18, padding: 14, marginBottom: 10,
      display: 'flex', gap: 12, position: 'relative',
      boxShadow: t.shadowSm,
    }}>
      <KpArt t={t} size={68} radius={14} label={pod.title.slice(0, 2).toUpperCase()} seed={pod.seed} />
      <div style={{ flex: 1, minWidth: 0 }}>
        <div style={{ display: 'flex', alignItems: 'center', gap: 6, marginBottom: 2 }}>
          <div style={{ fontSize: 15, fontWeight: 700, color: t.text, letterSpacing: '-0.01em', whiteSpace: 'nowrap', overflow: 'hidden', textOverflow: 'ellipsis', flex: 1 }}>{pod.title}</div>
          {pod.tag && (
            <div style={{
              fontSize: 9.5, fontWeight: 700, padding: '3px 7px', borderRadius: 999,
              background: t.pink, color: t.pinkOn, letterSpacing: '0.04em', textTransform: 'uppercase',
              boxShadow: t.shadowPink,
            }}>{pod.tag}</div>
          )}
        </div>
        <div style={{ fontSize: 12, color: t.textSoft, fontWeight: 500, marginBottom: 4 }}>
          {pod.host}
        </div>
        <div style={{
          fontSize: 12, color: t.textMute, lineHeight: 1.4,
          display: '-webkit-box', WebkitLineClamp: 2, WebkitBoxOrient: 'vertical',
          overflow: 'hidden',
        }}>{pod.desc}</div>
        <div style={{ display: 'flex', alignItems: 'center', gap: 8, marginTop: 8 }}>
          <div className="kp-mono" style={{
            fontSize: 10, color: t.textMute,
            padding: '2px 7px', border: `1px solid ${t.border}`, borderRadius: 6,
          }}>{pod.cat}</div>
          <div className="kp-mono" style={{ fontSize: 10.5, color: t.textMute }}>{pod.eps} eps</div>
        </div>
      </div>
    </div>
  );
}

// ── Podcast detail + episode list ─────────────────────────
function ScreenDetail({ t }) {
  const pod = {
    title: 'Signal & Noise', host: 'Mira Halverson', cat: 'Technology', eps: 214, sub: 12400,
    desc: 'Weekly conversations on what matters in the software industry — with builders, operators, and the occasional skeptic. New episode every Tuesday.',
    seed: 1,
  };
  const episodes = [
    { title: 'The compiler is the interface', dur: '1h 04m', date: 'Apr 15', size: '58 MB', state: 'downloaded' },
    { title: 'A short history of developer tooling', dur: '47m', date: 'Apr 08', size: '43 MB', state: 'downloading', pct: 62 },
    { title: 'Why platforms eat themselves', dur: '52m', date: 'Apr 01', size: '48 MB', state: 'queued' },
    { title: 'Design partners and anti-patterns', dur: '1h 12m', date: 'Mar 25', size: '66 MB', state: 'none' },
    { title: 'The long tail of small software', dur: '38m', date: 'Mar 18', size: '34 MB', state: 'none' },
    { title: 'Everything you know about staffing is wrong', dur: '1h 22m', date: 'Mar 11', size: '74 MB', state: 'none' },
  ];

  return (
    <KpPhone t={t}>
      <div className="kp-scroll" style={{ flex: 1, overflow: 'auto' }}>
        {/* top bar */}
        <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', padding: '6px 12px' }}>
          <div style={{ width: 36, height: 36, borderRadius: 999, display: 'flex', alignItems: 'center', justifyContent: 'center' }}>
            <KpIcon name="back" color={t.text} />
          </div>
          <div style={{ display: 'flex', gap: 4 }}>
            <div style={{ width: 36, height: 36, borderRadius: 999, display: 'flex', alignItems: 'center', justifyContent: 'center' }}><KpIcon name="share" color={t.text} size={18} /></div>
            <div style={{ width: 36, height: 36, borderRadius: 999, display: 'flex', alignItems: 'center', justifyContent: 'center' }}><KpIcon name="more" color={t.text} /></div>
          </div>
        </div>

        {/* hero */}
        <div style={{ padding: '4px 20px 18px', display: 'flex', gap: 16 }}>
          <KpArt t={t} size={108} radius={20} label={pod.title.slice(0,2).toUpperCase()} seed={pod.seed} />
          <div style={{ flex: 1, minWidth: 0 }}>
            <div style={{ fontSize: 10.5, fontWeight: 700, color: t.pink, letterSpacing: '0.1em', textTransform: 'uppercase', marginBottom: 4 }}>
              {pod.cat}
            </div>
            <div style={{ fontSize: 22, fontWeight: 800, color: t.text, letterSpacing: '-0.025em', lineHeight: 1.1 }}>
              {pod.title}
            </div>
            <div style={{ fontSize: 13, color: t.textSoft, fontWeight: 500, marginTop: 4 }}>
              {pod.host}
            </div>
            <div className="kp-mono" style={{ fontSize: 10.5, color: t.textMute, marginTop: 8 }}>
              {pod.eps} EPS · {pod.sub.toLocaleString()} SUBS
            </div>
          </div>
        </div>

        {/* desc */}
        <div style={{ padding: '0 20px 16px' }}>
          <div style={{ fontSize: 13.5, color: t.textSoft, lineHeight: 1.55 }}>{pod.desc}</div>
        </div>

        {/* action row */}
        <div style={{ padding: '0 20px 16px', display: 'flex', gap: 10 }}>
          <button style={{
            flex: 1, height: 48, borderRadius: 999, border: 'none', cursor: 'pointer',
            background: t.pink, color: t.pinkOn, boxShadow: t.shadowPink,
            fontSize: 14, fontWeight: 700, fontFamily: KP_FONT.sans,
            display: 'flex', alignItems: 'center', justifyContent: 'center', gap: 8,
          }}>
            <KpIcon name="check" size={18} color={t.pinkOn} stroke={2.6} />
            Saved to Tech
          </button>
          <button style={{
            width: 48, height: 48, borderRadius: 999,
            background: t.purpleTint, color: t.purple, border: 'none',
            display: 'flex', alignItems: 'center', justifyContent: 'center',
          }}>
            <KpIcon name="bell" size={19} color={t.purple} />
          </button>
          <button style={{
            width: 48, height: 48, borderRadius: 999,
            background: t.purpleTint, color: t.purple, border: 'none',
            display: 'flex', alignItems: 'center', justifyContent: 'center',
          }}>
            <KpIcon name="download" size={19} color={t.purple} stroke={2} />
          </button>
        </div>

        {/* episodes tab row */}
        <div style={{ padding: '0 20px 8px', display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
          <div style={{ display: 'flex', gap: 16, fontSize: 14 }}>
            <div style={{ fontWeight: 700, color: t.text, paddingBottom: 8, borderBottom: `2px solid ${t.pink}` }}>Episodes</div>
            <div style={{ fontWeight: 500, color: t.textMute, paddingBottom: 8 }}>About</div>
          </div>
          <div style={{ display: 'flex', alignItems: 'center', gap: 4, color: t.textSoft, fontSize: 12, fontWeight: 600 }}>
            Newest first <KpIcon name="chevD" size={14} color={t.textSoft} />
          </div>
        </div>

        {/* episode list */}
        <div style={{ padding: '0 20px 20px' }}>
          {episodes.map((e, i) => <EpisodeRow key={i} t={t} ep={e} isFirst={i === 0} />)}
        </div>
      </div>
      <KpTabBar t={t} active="library" />
    </KpPhone>
  );
}

function EpisodeRow({ t, ep, isFirst }) {
  const stateIcon = () => {
    if (ep.state === 'downloaded') return (
      <div style={{ display: 'flex', alignItems: 'center', gap: 6 }}>
        <div style={{
          width: 24, height: 24, borderRadius: 999, background: t.purpleTint,
          display: 'flex', alignItems: 'center', justifyContent: 'center',
        }}>
          <KpIcon name="check" size={13} color={t.purple} stroke={3} />
        </div>
      </div>
    );
    if (ep.state === 'downloading') return (
      <div style={{
        width: 28, height: 28, borderRadius: 999,
        background: `conic-gradient(${t.pink} ${ep.pct}%, ${t.border} 0)`,
        display: 'flex', alignItems: 'center', justifyContent: 'center',
      }}>
        <div style={{ width: 20, height: 20, borderRadius: 999, background: t.surface,
          display: 'flex', alignItems: 'center', justifyContent: 'center' }}>
          <div className="kp-mono" style={{ fontSize: 8, fontWeight: 700, color: t.pink }}>{ep.pct}</div>
        </div>
      </div>
    );
    if (ep.state === 'queued') return (
      <div style={{
        width: 28, height: 28, borderRadius: 999, border: `1.5px dashed ${t.borderStrong}`,
        display: 'flex', alignItems: 'center', justifyContent: 'center',
      }}>
        <KpIcon name="clock" size={13} color={t.textMute} stroke={2} />
      </div>
    );
    return (
      <div style={{
        width: 28, height: 28, borderRadius: 999, border: `1.5px solid ${t.border}`,
        display: 'flex', alignItems: 'center', justifyContent: 'center',
      }}>
        <KpIcon name="download" size={14} color={t.textSoft} stroke={2} />
      </div>
    );
  };

  return (
    <div style={{
      padding: '14px 0',
      borderTop: !isFirst ? `1px solid ${t.border}` : 'none',
      display: 'flex', gap: 12, alignItems: 'flex-start',
    }}>
      <div style={{
        width: 40, height: 40, borderRadius: 999, flexShrink: 0,
        background: t.purple, color: t.purpleOn,
        display: 'flex', alignItems: 'center', justifyContent: 'center',
      }}>
        <KpIcon name="play" size={15} color={t.purpleOn} />
      </div>
      <div style={{ flex: 1, minWidth: 0 }}>
        <div style={{ fontSize: 14, fontWeight: 600, color: t.text, lineHeight: 1.3 }}>{ep.title}</div>
        <div className="kp-mono" style={{ fontSize: 10.5, color: t.textMute, marginTop: 6, letterSpacing: '0.03em' }}>
          {ep.date.toUpperCase()} · {ep.dur} · {ep.size}
        </div>
      </div>
      <div style={{ paddingTop: 4 }}>{stateIcon()}</div>
    </div>
  );
}

// ── Library (lists/folders) ───────────────────────────────
function ScreenLibrary({ t }) {
  const lists = [
    { name: 'Tech', count: 8, color: t.purple, seed: 1 },
    { name: 'Long walks', count: 5, color: t.pink, seed: 3 },
    { name: 'Work background', count: 12, color: '#6D3BD2', seed: 7 },
    { name: 'To try', count: 3, color: '#A881F5', seed: 9 },
  ];
  const recent = KP_PODS.slice(0, 4);

  return (
    <KpPhone t={t}>
      {/* header */}
      <div style={{ padding: '8px 20px 16px', display: 'flex', alignItems: 'center', justifyContent: 'space-between' }}>
        <div>
          <div style={{ fontSize: 28, fontWeight: 800, letterSpacing: '-0.03em', color: t.text }}>Library</div>
          <div style={{ fontSize: 12.5, color: t.textMute, marginTop: 2, display: 'flex', alignItems: 'center', gap: 6 }}>
            <div style={{ width: 6, height: 6, borderRadius: 999, background: t.success }} />
            Synced 2m ago · Drive
          </div>
        </div>
        <div style={{
          width: 40, height: 40, borderRadius: 999, background: t.pink, color: t.pinkOn,
          display: 'flex', alignItems: 'center', justifyContent: 'center', boxShadow: t.shadowPink,
        }}>
          <KpIcon name="plus" size={20} color={t.pinkOn} stroke={2.4} />
        </div>
      </div>

      {/* lists grid */}
      <div className="kp-scroll" style={{ flex: 1, overflow: 'auto', padding: '0 20px' }}>
        <div style={{ fontSize: 11, fontWeight: 700, color: t.textMute, letterSpacing: '0.08em', textTransform: 'uppercase', marginBottom: 10 }}>
          Your lists
        </div>
        <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: 10 }}>
          {lists.map((l, i) => (
            <div key={i} style={{
              background: i === 0 ? t.purple : t.surface,
              border: i === 0 ? `1px solid ${t.purple}` : `1px solid ${t.border}`,
              borderRadius: 18, padding: 14,
              height: 110, display: 'flex', flexDirection: 'column', justifyContent: 'space-between',
              position: 'relative', overflow: 'hidden',
              boxShadow: t.shadowSm,
            }}>
              {/* stacked art peek */}
              <div style={{ position: 'absolute', top: -6, right: -6, display: 'flex' }}>
                <div style={{ transform: 'rotate(8deg)', marginRight: -14, opacity: 0.7 }}>
                  <KpArt t={t} size={42} radius={10} label="" seed={l.seed + 1} />
                </div>
                <div style={{ transform: 'rotate(-6deg)' }}>
                  <KpArt t={t} size={42} radius={10} label="" seed={l.seed} />
                </div>
              </div>
              <KpIcon name="folder" size={20} color={i === 0 ? t.pink : t.purple} stroke={2} />
              <div>
                <div style={{ fontSize: 15, fontWeight: 700, color: i === 0 ? '#fff' : t.text, letterSpacing: '-0.015em' }}>{l.name}</div>
                <div className="kp-mono" style={{ fontSize: 10.5, color: i === 0 ? 'rgba(255,255,255,0.75)' : t.textMute, marginTop: 2 }}>{l.count} PODCASTS</div>
              </div>
            </div>
          ))}
          {/* new list tile */}
          <div style={{
            height: 110, borderRadius: 18, border: `1.5px dashed ${t.borderStrong}`,
            display: 'flex', flexDirection: 'column', alignItems: 'center', justifyContent: 'center', gap: 6,
            color: t.textSoft,
          }}>
            <KpIcon name="plus" size={18} color={t.textSoft} stroke={2} />
            <div style={{ fontSize: 12, fontWeight: 600 }}>New list</div>
          </div>
        </div>

        {/* recent podcasts */}
        <div style={{ fontSize: 11, fontWeight: 700, color: t.textMute, letterSpacing: '0.08em', textTransform: 'uppercase', margin: '24px 0 10px' }}>
          Recently opened
        </div>
        <div style={{ display: 'flex', flexDirection: 'column', gap: 0, marginBottom: 12 }}>
          {recent.map((p, i) => (
            <div key={p.id} style={{
              display: 'flex', alignItems: 'center', gap: 12,
              padding: '10px 0', borderTop: i > 0 ? `1px solid ${t.border}` : 'none',
            }}>
              <KpArt t={t} size={46} radius={12} label={p.title.slice(0,2).toUpperCase()} seed={p.seed} />
              <div style={{ flex: 1, minWidth: 0 }}>
                <div style={{ fontSize: 13.5, fontWeight: 600, color: t.text }}>{p.title}</div>
                <div style={{ fontSize: 11.5, color: t.textMute, marginTop: 1 }}>{p.host}</div>
              </div>
              <div className="kp-mono" style={{ fontSize: 10, color: t.textMute }}>
                {p.eps} EPS
              </div>
            </div>
          ))}
        </div>
      </div>
      <KpTabBar t={t} active="library" />
    </KpPhone>
  );
}

Object.assign(window, { ScreenOnboarding, ScreenSearch, ScreenDetail, ScreenLibrary });
