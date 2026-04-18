// screens-b.jsx — Downloads, Notification, Settings, Scheduler

// ── Downloads manager ─────────────────────────────────────
function ScreenDownloads({ t }) {
  const active = [
    { pod: 'Signal & Noise', ep: 'A short history of developer tooling', pct: 62, eta: '1m 12s', size: '43 MB', seed: 1 },
    { pod: 'Slow Burn Science', ep: 'The half-life of a good idea', pct: 18, eta: '4m 30s', size: '51 MB', seed: 3 },
  ];
  const queue = [
    { pod: 'Signal & Noise', ep: 'Why platforms eat themselves', size: '48 MB', seed: 1 },
    { pod: 'The Second Draft', ep: 'Refactor the brief', size: '39 MB', seed: 5 },
  ];
  const done = [
    { pod: 'Signal & Noise', ep: 'The compiler is the interface', size: '58 MB', date: 'Today', seed: 1 },
    { pod: 'Field Recordings', ep: 'Letters from the archive', size: '62 MB', date: 'Yesterday', seed: 9 },
    { pod: 'Ledgers & Legends', ep: 'The Medici balance sheet', size: '71 MB', date: '2 days ago', seed: 7 },
  ];
  const used = 1.42, cap = 3.0, pct = (used / cap) * 100;

  return (
    <KpPhone t={t}>
      {/* header */}
      <div style={{ padding: '8px 20px 12px' }}>
        <div style={{ fontSize: 28, fontWeight: 800, letterSpacing: '-0.03em', color: t.text }}>Downloads</div>
      </div>

      {/* storage ring */}
      <div style={{ padding: '0 20px 16px' }}>
        <div style={{
          background: t.surface, border: `1px solid ${t.border}`, borderRadius: 20, padding: 16,
          display: 'flex', alignItems: 'center', gap: 16, boxShadow: t.shadowSm,
        }}>
          <div style={{
            width: 72, height: 72, borderRadius: 999,
            background: `conic-gradient(${t.pink} 0%, ${t.purple} ${pct}%, ${t.border} ${pct}%)`,
            display: 'flex', alignItems: 'center', justifyContent: 'center', flexShrink: 0,
          }}>
            <div style={{
              width: 58, height: 58, borderRadius: 999, background: t.surface,
              display: 'flex', flexDirection: 'column', alignItems: 'center', justifyContent: 'center',
            }}>
              <div className="kp-mono" style={{ fontSize: 14, fontWeight: 800, color: t.text, lineHeight: 1 }}>{used}</div>
              <div className="kp-mono" style={{ fontSize: 8.5, color: t.textMute, letterSpacing: '0.05em' }}>GB USED</div>
            </div>
          </div>
          <div style={{ flex: 1 }}>
            <div style={{ fontSize: 13, color: t.textMute, fontWeight: 500 }}>Auto-download cap</div>
            <div style={{ fontSize: 20, fontWeight: 800, color: t.text, letterSpacing: '-0.02em', marginTop: 2 }}>
              {used} / {cap} GB
            </div>
            <div style={{ fontSize: 11.5, color: t.textMute, marginTop: 4 }}>
              47% full · 18 episodes
            </div>
          </div>
        </div>
      </div>

      {/* lists */}
      <div className="kp-scroll" style={{ flex: 1, overflow: 'auto', padding: '0 20px' }}>
        <SectionLabel t={t}>
          Downloading · 2
          <span style={{ marginLeft: 'auto', fontSize: 11, color: t.pink, textTransform: 'none', fontWeight: 600, letterSpacing: 0 }}>Pause all</span>
        </SectionLabel>
        {active.map((a, i) => (
          <div key={i} style={{
            display: 'flex', gap: 12, padding: '10px 0',
            borderTop: i > 0 ? `1px solid ${t.border}` : 'none',
          }}>
            <KpArt t={t} size={44} radius={11} label={a.pod.slice(0,2).toUpperCase()} seed={a.seed} />
            <div style={{ flex: 1, minWidth: 0 }}>
              <div style={{ fontSize: 13, fontWeight: 600, color: t.text, whiteSpace: 'nowrap', overflow: 'hidden', textOverflow: 'ellipsis' }}>{a.ep}</div>
              <div style={{ fontSize: 11, color: t.textMute, marginTop: 1 }}>{a.pod}</div>
              {/* progress bar */}
              <div style={{ marginTop: 8, height: 4, borderRadius: 999, background: t.border, overflow: 'hidden' }}>
                <div style={{ width: `${a.pct}%`, height: '100%', background: t.pink, borderRadius: 999 }} />
              </div>
              <div className="kp-mono" style={{ fontSize: 9.5, color: t.textMute, marginTop: 5, display: 'flex', justifyContent: 'space-between' }}>
                <span>{a.pct}% · {a.size}</span>
                <span>ETA {a.eta}</span>
              </div>
            </div>
            <div style={{ paddingTop: 4 }}>
              <div style={{
                width: 30, height: 30, borderRadius: 999, border: `1px solid ${t.border}`,
                display: 'flex', alignItems: 'center', justifyContent: 'center',
              }}>
                <KpIcon name="close" size={12} color={t.textSoft} stroke={2.4} />
              </div>
            </div>
          </div>
        ))}

        <SectionLabel t={t}>Up next · 2</SectionLabel>
        {queue.map((q, i) => (
          <div key={i} style={{
            display: 'flex', alignItems: 'center', gap: 12, padding: '10px 0',
            borderTop: i > 0 ? `1px solid ${t.border}` : 'none',
          }}>
            <KpArt t={t} size={40} radius={10} label={q.pod.slice(0,2).toUpperCase()} seed={q.seed} />
            <div style={{ flex: 1, minWidth: 0 }}>
              <div style={{ fontSize: 13, fontWeight: 600, color: t.text }}>{q.ep}</div>
              <div className="kp-mono" style={{ fontSize: 10, color: t.textMute, marginTop: 2 }}>{q.pod.toUpperCase()} · {q.size}</div>
            </div>
            <KpIcon name="clock" size={15} color={t.textMute} />
          </div>
        ))}

        <SectionLabel t={t}>
          Downloaded · 16
          <span style={{ marginLeft: 'auto', fontSize: 11, color: t.textSoft, textTransform: 'none', fontWeight: 600, letterSpacing: 0, display: 'flex', alignItems: 'center', gap: 3 }}>
            Oldest first <KpIcon name="chevD" size={12} color={t.textSoft} />
          </span>
        </SectionLabel>
        {done.map((d, i) => (
          <div key={i} style={{
            display: 'flex', alignItems: 'center', gap: 12, padding: '10px 0',
            borderTop: i > 0 ? `1px solid ${t.border}` : 'none',
          }}>
            <KpArt t={t} size={40} radius={10} label={d.pod.slice(0,2).toUpperCase()} seed={d.seed} />
            <div style={{ flex: 1, minWidth: 0 }}>
              <div style={{ fontSize: 13, fontWeight: 600, color: t.text }}>{d.ep}</div>
              <div className="kp-mono" style={{ fontSize: 10, color: t.textMute, marginTop: 2 }}>{d.date.toUpperCase()} · {d.size}</div>
            </div>
            <div style={{
              width: 30, height: 30, borderRadius: 999, border: `1px solid ${t.border}`,
              display: 'flex', alignItems: 'center', justifyContent: 'center',
            }}>
              <KpIcon name="trash" size={13} color={t.textSoft} stroke={1.8} />
            </div>
          </div>
        ))}
        <div style={{ height: 12 }} />
      </div>
      <KpTabBar t={t} active="downloads" />
    </KpPhone>
  );
}

function SectionLabel({ t, children }) {
  return (
    <div style={{
      display: 'flex', alignItems: 'center',
      fontSize: 11, fontWeight: 700, color: t.textMute,
      letterSpacing: '0.08em', textTransform: 'uppercase',
      margin: '18px 0 6px',
    }}>{children}</div>
  );
}

// ── Notification (pulled-down shade) ──────────────────────
function ScreenNotification({ t }) {
  // A dimmed wallpaper/home with the notification shade pulled half-down.
  const shadeBg = t.name === 'dark' ? '#1A1128' : '#1A0B33';
  const shadeText = '#F2E9FF';
  const shadeMute = 'rgba(242,233,255,0.55)';

  return (
    <KpPhone t={t} bg={t.name === 'dark' ? '#05030A' : '#1A0B33'}>
      <div style={{ flex: 1, position: 'relative', overflow: 'hidden' }}>
        {/* shade */}
        <div style={{
          position: 'absolute', top: 0, left: 0, right: 0,
          background: shadeBg, paddingBottom: 20,
          borderBottomLeftRadius: 24, borderBottomRightRadius: 24,
          boxShadow: '0 20px 40px rgba(0,0,0,0.5)',
        }}>
          {/* quick settings strip */}
          <div style={{ padding: '8px 20px 14px', display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
            <div className="kp-mono" style={{ fontSize: 10.5, color: shadeMute, letterSpacing: '0.08em' }}>
              TUE, APR 21 · 07:12
            </div>
            <div style={{ display: 'flex', gap: 6 }}>
              {['wifi', 'moon', 'bell'].map((n, i) => (
                <div key={i} style={{
                  width: 28, height: 28, borderRadius: 999,
                  background: 'rgba(255,255,255,0.1)',
                  display: 'flex', alignItems: 'center', justifyContent: 'center',
                }}>
                  <KpIcon name={n} size={13} color={shadeText} stroke={2} />
                </div>
              ))}
            </div>
          </div>

          {/* section heading */}
          <div style={{ padding: '0 16px', display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: 6 }}>
            <div style={{ fontSize: 12, fontWeight: 600, color: shadeMute }}>Notifications</div>
            <div style={{ fontSize: 11.5, color: shadeMute }}>Clear all</div>
          </div>

          {/* Kofipod notification */}
          <div style={{ padding: '0 10px' }}>
            <div style={{
              background: t.surfaceAlt, color: t.text, borderRadius: 22, padding: 14,
              border: `1px solid ${t.borderStrong}`,
            }}>
              <div style={{ display: 'flex', alignItems: 'center', gap: 8, marginBottom: 10 }}>
                <div style={{
                  width: 20, height: 20, borderRadius: 6,
                  background: `linear-gradient(135deg, ${t.purple}, ${t.pink})`,
                  display: 'flex', alignItems: 'center', justifyContent: 'center',
                }}>
                  <svg width="11" height="11" viewBox="0 0 24 24" fill="none" stroke="#fff" strokeWidth="3" strokeLinecap="round"><path d="M12 3v10M6 14a6 6 0 0 0 12 0"/></svg>
                </div>
                <div style={{ fontSize: 11.5, fontWeight: 700, letterSpacing: '0.02em', color: t.text }}>Kofipod</div>
                <div style={{ fontSize: 11, color: t.textMute }}>· now</div>
                <div style={{ marginLeft: 'auto', fontSize: 10.5, fontWeight: 700, padding: '2px 7px', borderRadius: 999, background: t.pink, color: t.pinkOn }}>
                  2 NEW
                </div>
              </div>

              {/* stacked rows */}
              <div style={{ display: 'flex', gap: 10, alignItems: 'flex-start' }}>
                <KpArt t={t} size={48} radius={12} label="SN" seed={1} />
                <div style={{ flex: 1, minWidth: 0 }}>
                  <div style={{ fontSize: 13.5, fontWeight: 700, color: t.text, letterSpacing: '-0.01em' }}>Signal & Noise</div>
                  <div style={{ fontSize: 12.5, color: t.textSoft, marginTop: 2, lineHeight: 1.35 }}>
                    New episode: <span style={{ color: t.text, fontWeight: 600 }}>The compiler is the interface</span>
                  </div>
                  <div className="kp-mono" style={{ fontSize: 10, color: t.textMute, marginTop: 3 }}>58 MB · 1H 04M</div>
                </div>
              </div>
              <div style={{ height: 1, background: t.border, margin: '12px 0 12px 58px' }} />
              <div style={{ display: 'flex', gap: 10, alignItems: 'flex-start' }}>
                <KpArt t={t} size={48} radius={12} label="SB" seed={3} />
                <div style={{ flex: 1, minWidth: 0 }}>
                  <div style={{ fontSize: 13.5, fontWeight: 700, color: t.text }}>Slow Burn Science</div>
                  <div style={{ fontSize: 12.5, color: t.textSoft, marginTop: 2 }}>
                    New episode: <span style={{ color: t.text, fontWeight: 600 }}>The half-life of a good idea</span>
                  </div>
                  <div className="kp-mono" style={{ fontSize: 10, color: t.textMute, marginTop: 3 }}>51 MB · 42M</div>
                </div>
              </div>

              {/* actions */}
              <div style={{ display: 'flex', gap: 8, marginTop: 14 }}>
                <button style={{
                  flex: 1, height: 36, borderRadius: 999, border: 'none',
                  background: t.pink, color: t.pinkOn, fontWeight: 700, fontSize: 12.5,
                  fontFamily: KP_FONT.sans, boxShadow: t.shadowPink,
                }}>Download both</button>
                <button style={{
                  flex: 1, height: 36, borderRadius: 999,
                  border: `1px solid ${t.border}`, background: 'transparent',
                  color: t.textSoft, fontWeight: 600, fontSize: 12.5, fontFamily: KP_FONT.sans,
                }}>Later</button>
              </div>
            </div>
          </div>

          {/* another muted notification */}
          <div style={{ padding: '10px 16px 0' }}>
            <div style={{ display: 'flex', alignItems: 'center', gap: 10, opacity: 0.6 }}>
              <div style={{ width: 20, height: 20, borderRadius: 6, background: '#34D399' }} />
              <div style={{ fontSize: 12.5, color: shadeText }}>Calendar · Standup in 18 min</div>
            </div>
          </div>

          {/* grab handle */}
          <div style={{ display: 'flex', justifyContent: 'center', marginTop: 14 }}>
            <div style={{ width: 40, height: 4, borderRadius: 999, background: 'rgba(255,255,255,0.3)' }} />
          </div>
        </div>

        {/* dim scrim */}
        <div style={{ position: 'absolute', inset: 0, background: 'rgba(0,0,0,0.35)', pointerEvents: 'none' }} />
      </div>
    </KpPhone>
  );
}

// ── Settings ──────────────────────────────────────────────
function ScreenSettings({ t }) {
  return (
    <KpPhone t={t}>
      <div style={{ padding: '8px 20px 8px' }}>
        <div style={{ fontSize: 28, fontWeight: 800, letterSpacing: '-0.03em', color: t.text }}>Settings</div>
      </div>
      <div className="kp-scroll" style={{ flex: 1, overflow: 'auto', padding: '0 20px 12px' }}>
        {/* account card */}
        <div style={{
          background: `linear-gradient(135deg, ${t.purple}, ${t.purpleDeep})`,
          borderRadius: 20, padding: 16, color: '#fff',
          display: 'flex', alignItems: 'center', gap: 14, position: 'relative', overflow: 'hidden',
          boxShadow: t.shadowMd, marginTop: 4,
        }}>
          <div style={{ position: 'absolute', top: -30, right: -30, width: 120, height: 120, borderRadius: 999, background: `${t.pink}33` }} />
          <div style={{
            width: 48, height: 48, borderRadius: 999, flexShrink: 0,
            background: 'rgba(255,255,255,0.2)',
            display: 'flex', alignItems: 'center', justifyContent: 'center',
            fontWeight: 800, fontSize: 16, letterSpacing: '-0.02em', zIndex: 1,
          }}>JM</div>
          <div style={{ flex: 1, zIndex: 1 }}>
            <div style={{ fontSize: 15, fontWeight: 700, letterSpacing: '-0.01em' }}>James Mireles</div>
            <div style={{ fontSize: 12, opacity: 0.85, marginTop: 2 }}>james.m@gmail.com</div>
          </div>
          <div style={{ zIndex: 1, display: 'flex', alignItems: 'center', gap: 4, fontSize: 11, fontWeight: 600, padding: '5px 9px', borderRadius: 999, background: 'rgba(255,255,255,0.18)' }}>
            <div style={{ width: 6, height: 6, borderRadius: 999, background: t.success }} />
            Drive synced
          </div>
        </div>

        <SectionLabel t={t}>Backup</SectionLabel>
        <SettingRow t={t} icon="cloud" title="Google Drive sync" value="On · appDataFolder" meta />
        <SettingRow t={t} icon="lock" title="What gets backed up" value="Library, lists, playback positions. No audio files." meta multiLine />
        <SettingRow t={t} icon="clock" title="Last backup" value={<span className="kp-mono" style={{ fontSize: 11, color: t.textMute, letterSpacing: '0.03em' }}>07:12 · TODAY</span>} />

        <SectionLabel t={t}>Auto-downloader</SectionLabel>
        <SettingRow t={t} icon="radar" title="Daily check for new episodes" toggle value="Checks ~once a day when on Wi-Fi. Battery-aware — may shift by a few hours." meta toggleOn multiLine />
        <SettingRow t={t} icon="wifi" title="Wi-Fi only" toggle toggleOn />
        {/* Storage slider */}
        <div style={{
          padding: '14px 16px', background: t.surface, border: `1px solid ${t.border}`,
          borderRadius: 16, marginTop: 8,
        }}>
          <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: 10 }}>
            <div>
              <div style={{ fontSize: 13.5, fontWeight: 600, color: t.text }}>Max auto-download size</div>
              <div style={{ fontSize: 11.5, color: t.textMute, marginTop: 2 }}>Oldest unplayed episodes are removed first</div>
            </div>
            <div className="kp-mono" style={{ fontSize: 14, fontWeight: 800, color: t.pink }}>3.0 GB</div>
          </div>
          {/* track */}
          <div style={{ position: 'relative', height: 8, borderRadius: 999, background: t.border, marginTop: 4 }}>
            <div style={{
              position: 'absolute', left: 0, top: 0, bottom: 0, width: '37.5%',
              background: `linear-gradient(90deg, ${t.purple}, ${t.pink})`, borderRadius: 999,
            }} />
            <div style={{
              position: 'absolute', left: '37.5%', top: '50%', transform: 'translate(-50%,-50%)',
              width: 22, height: 22, borderRadius: 999, background: '#fff',
              boxShadow: '0 2px 8px rgba(0,0,0,0.25)',
              border: `2px solid ${t.pink}`,
            }} />
          </div>
          <div className="kp-mono" style={{ display: 'flex', justifyContent: 'space-between', marginTop: 8, fontSize: 10, color: t.textMute }}>
            <span>500 MB</span><span>8 GB</span>
          </div>
        </div>

        <SectionLabel t={t}>Notifications</SectionLabel>
        <SettingRow t={t} icon="bell" title="New episode alerts" toggle toggleOn />

        <SectionLabel t={t}>Appearance</SectionLabel>
        <SettingRow t={t} icon="moon" title="Dark mode" value="Follow system" chev />

        <SectionLabel t={t}>Advanced</SectionLabel>
        <SettingRow t={t} icon="disc" title="API status" value={<span style={{ color: t.success, fontWeight: 700, fontSize: 12 }}>● Podcast Index reachable</span>} />
        <SettingRow t={t} icon="person" title="Signed in as" value="james.m@gmail.com" chev />

        <div style={{ textAlign: 'center', marginTop: 20, marginBottom: 8 }}>
          <div className="kp-mono" style={{ fontSize: 10, color: t.textMute, letterSpacing: '0.08em' }}>
            KOFIPOD v1.4.2 · BUILD 214
          </div>
        </div>
      </div>
      <KpTabBar t={t} active="settings" />
    </KpPhone>
  );
}

function SettingRow({ t, icon, title, value, toggle, toggleOn, chev, meta, multiLine }) {
  return (
    <div style={{
      padding: '12px 14px', background: t.surface, border: `1px solid ${t.border}`,
      borderRadius: 14, marginBottom: 6,
      display: 'flex', alignItems: multiLine ? 'flex-start' : 'center', gap: 12,
    }}>
      <div style={{
        width: 32, height: 32, borderRadius: 10, flexShrink: 0,
        background: t.purpleTint, color: t.purple,
        display: 'flex', alignItems: 'center', justifyContent: 'center',
      }}>
        <KpIcon name={icon} size={15} color={t.purple} stroke={2} />
      </div>
      <div style={{ flex: 1, minWidth: 0 }}>
        <div style={{ fontSize: 13.5, fontWeight: 600, color: t.text, letterSpacing: '-0.005em' }}>{title}</div>
        {value && (
          <div style={{ fontSize: 11.5, color: t.textMute, marginTop: 2, lineHeight: 1.4 }}>{value}</div>
        )}
      </div>
      {toggle && (
        <div style={{
          width: 44, height: 26, borderRadius: 999, flexShrink: 0,
          background: toggleOn ? t.pink : t.border,
          position: 'relative', boxShadow: toggleOn ? t.shadowPink : 'none',
        }}>
          <div style={{
            position: 'absolute', top: 3, left: toggleOn ? 21 : 3,
            width: 20, height: 20, borderRadius: 999, background: '#fff',
            boxShadow: '0 1px 3px rgba(0,0,0,0.25)',
            transition: 'left 0.2s',
          }} />
        </div>
      )}
      {chev && <KpIcon name="chev" size={16} color={t.textMute} />}
    </div>
  );
}

// ── Scheduler explainer (standalone info screen) ─────────
function ScreenScheduler({ t }) {
  return (
    <KpPhone t={t}>
      <div style={{ display: 'flex', alignItems: 'center', padding: '6px 8px' }}>
        <div style={{ width: 36, height: 36, borderRadius: 999, display: 'flex', alignItems: 'center', justifyContent: 'center' }}>
          <KpIcon name="back" color={t.text} />
        </div>
        <div style={{ fontSize: 15, fontWeight: 700, color: t.text, marginLeft: 4 }}>Daily check</div>
      </div>

      <div className="kp-scroll" style={{ flex: 1, overflow: 'auto', padding: '4px 20px 20px' }}>
        {/* hero */}
        <div style={{
          borderRadius: 24, padding: 22, marginTop: 4,
          background: `linear-gradient(135deg, ${t.purple}, ${t.purpleDeep})`,
          color: '#fff', position: 'relative', overflow: 'hidden',
          boxShadow: t.shadowMd,
        }}>
          <div style={{ position: 'absolute', top: -40, right: -40, width: 180, height: 180, borderRadius: 999, background: `${t.pink}40` }} />
          <div style={{ position: 'absolute', bottom: -60, left: -20, width: 140, height: 140, borderRadius: 999, background: 'rgba(255,255,255,0.08)' }} />
          <div style={{ position: 'relative' }}>
            <div style={{
              width: 44, height: 44, borderRadius: 14,
              background: 'rgba(255,255,255,0.18)',
              display: 'flex', alignItems: 'center', justifyContent: 'center', marginBottom: 14,
            }}>
              <KpIcon name="radar" size={22} color="#fff" stroke={2} />
            </div>
            <div style={{ fontSize: 22, fontWeight: 800, letterSpacing: '-0.025em', lineHeight: 1.15 }}>
              Checks once a day,<br/>gently.
            </div>
            <div style={{ fontSize: 13, opacity: 0.85, marginTop: 10, lineHeight: 1.5, maxWidth: 280 }}>
              Kofipod uses Android's <span className="kp-mono" style={{ fontSize: 12 }}>WorkManager</span> to poll your podcasts roughly once per day — when the OS decides it's cheap.
            </div>
          </div>
        </div>

        {/* state */}
        <div style={{
          background: t.surface, border: `1px solid ${t.border}`,
          borderRadius: 18, padding: 14, marginTop: 14,
          display: 'flex', alignItems: 'center', gap: 12,
        }}>
          <div style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
            <div style={{ position: 'relative', width: 10, height: 10 }}>
              <div style={{ position: 'absolute', inset: 0, borderRadius: 999, background: t.success }} />
              <div style={{ position: 'absolute', inset: -4, borderRadius: 999, background: t.success, opacity: 0.3 }} />
            </div>
          </div>
          <div style={{ flex: 1 }}>
            <div style={{ fontSize: 13, fontWeight: 700, color: t.text }}>Scheduler is on</div>
            <div className="kp-mono" style={{ fontSize: 10.5, color: t.textMute, marginTop: 2 }}>LAST RUN 07:12 · NEXT ~06:00 TOMORROW</div>
          </div>
          <div style={{ width: 44, height: 26, borderRadius: 999, background: t.pink, position: 'relative', boxShadow: t.shadowPink }}>
            <div style={{ position: 'absolute', top: 3, left: 21, width: 20, height: 20, borderRadius: 999, background: '#fff' }} />
          </div>
        </div>

        <SectionLabel t={t}>What "roughly once a day" means</SectionLabel>
        <div style={{ display: 'flex', flexDirection: 'column', gap: 8 }}>
          {[
            { h: 'Battery-aware', d: 'The OS may delay the check if you\'re on battery saver.' },
            { h: 'Wi-Fi preferred', d: 'When Wi-Fi Only is on, checks wait for a network you trust.' },
            { h: 'Coalesced', d: 'If you open the app, Kofipod checks right away and resets the clock.' },
          ].map((x, i) => (
            <div key={i} style={{
              display: 'flex', gap: 12, padding: '12px 14px',
              background: t.surface, border: `1px solid ${t.border}`, borderRadius: 14,
            }}>
              <div style={{
                width: 26, height: 26, borderRadius: 8, flexShrink: 0,
                background: t.purpleTint, color: t.purple,
                display: 'flex', alignItems: 'center', justifyContent: 'center',
                fontSize: 11, fontWeight: 800,
              }}>{i + 1}</div>
              <div style={{ flex: 1 }}>
                <div style={{ fontSize: 13, fontWeight: 700, color: t.text, letterSpacing: '-0.005em' }}>{x.h}</div>
                <div style={{ fontSize: 12, color: t.textMute, marginTop: 2, lineHeight: 1.4 }}>{x.d}</div>
              </div>
            </div>
          ))}
        </div>

        {/* last runs */}
        <SectionLabel t={t}>Last 7 runs</SectionLabel>
        <div style={{
          background: t.surface, border: `1px solid ${t.border}`,
          borderRadius: 16, padding: 14,
        }}>
          <div style={{ display: 'flex', gap: 6, alignItems: 'flex-end', height: 54 }}>
            {[14, 22, 18, 0, 28, 12, 20].map((v, i) => (
              <div key={i} style={{ flex: 1, display: 'flex', flexDirection: 'column', alignItems: 'center', gap: 6 }}>
                <div style={{
                  width: '100%', height: v === 0 ? 4 : (v / 28) * 44,
                  background: v === 0 ? t.border : (i === 6 ? t.pink : t.purple),
                  borderRadius: 4,
                }} />
              </div>
            ))}
          </div>
          <div className="kp-mono" style={{ display: 'flex', gap: 6, marginTop: 8, fontSize: 9.5, color: t.textMute }}>
            {['M','T','W','T','F','S','S'].map((d, i) => (
              <div key={i} style={{ flex: 1, textAlign: 'center' }}>{d}</div>
            ))}
          </div>
          <div style={{ marginTop: 10, fontSize: 11.5, color: t.textMute }}>
            1 run skipped Thursday · low battery
          </div>
        </div>
      </div>
    </KpPhone>
  );
}

Object.assign(window, { ScreenDownloads, ScreenNotification, ScreenSettings, ScreenScheduler });
