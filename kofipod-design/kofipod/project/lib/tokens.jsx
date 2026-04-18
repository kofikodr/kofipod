// tokens.jsx — design tokens for Kofipod
// Light: purple-dominant, pink for CTAs only. Dark: near-black + muted plum + soft pink glow.

const KP_LIGHT = {
  name: 'light',
  bg: '#FBF8FF',
  bgSubtle: '#F3ECFF',
  surface: '#FFFFFF',
  surfaceAlt: '#F6F0FF',
  border: '#E7DDFB',
  borderStrong: '#D5C4F4',
  text: '#1A0B33',
  textSoft: '#50407A',
  textMute: '#8A7BB0',
  // purples
  purple: '#4B1E9E',
  purpleDeep: '#2E0D6E',
  purpleSoft: '#6D3BD2',
  purpleTint: '#EADFFC',
  purpleOn: '#FFFFFF',
  // accent pink (reserved)
  pink: '#FF2E9A',
  pinkSoft: '#FFD6EA',
  pinkOn: '#FFFFFF',
  pinkGlow: 'rgba(255,46,154,0.35)',
  // art placeholder stripes
  artA: '#6D3BD2',
  artB: '#8B5CF6',
  // status
  success: '#10B981',
  warn: '#F59E0B',
  danger: '#E11D48',
  // shadows
  shadowSm: '0 1px 2px rgba(75,30,158,0.08)',
  shadowMd: '0 6px 24px rgba(75,30,158,0.10)',
  shadowPink: '0 6px 20px rgba(255,46,154,0.28)',
};

const KP_DARK = {
  name: 'dark',
  bg: '#0D0814',
  bgSubtle: '#140C22',
  surface: '#1A1128',
  surfaceAlt: '#231636',
  border: '#2D1F46',
  borderStrong: '#3E2B60',
  text: '#F2E9FF',
  textSoft: '#BCA7E0',
  textMute: '#7E6BA6',
  purple: '#A881F5',
  purpleDeep: '#7C4DEB',
  purpleSoft: '#C4A6FF',
  purpleTint: '#2A1A4A',
  purpleOn: '#0D0814',
  pink: '#FF6BB5',
  pinkSoft: '#3A1930',
  pinkOn: '#15070F',
  pinkGlow: 'rgba(255,107,181,0.28)',
  artA: '#7C4DEB',
  artB: '#C4A6FF',
  success: '#34D399',
  warn: '#FBBF24',
  danger: '#FB7185',
  shadowSm: '0 1px 2px rgba(0,0,0,0.5)',
  shadowMd: '0 10px 30px rgba(0,0,0,0.55)',
  shadowPink: '0 0 28px rgba(255,107,181,0.35)',
};

const KP_RADII = { xs: 8, sm: 12, md: 16, lg: 20, xl: 28, pill: 999 };
const KP_FONT = {
  sans: '"Plus Jakarta Sans", system-ui, -apple-system, sans-serif',
  mono: '"JetBrains Mono", ui-monospace, Menlo, monospace',
};

// Shared app-level CSS we inject once per artboard root
const kpBaseCss = (t) => `
  * { box-sizing: border-box; }
  .kp-root {
    font-family: ${KP_FONT.sans};
    color: ${t.text};
    background: ${t.bg};
    height: 100%;
    letter-spacing: -0.01em;
  }
  .kp-root ::selection { background: ${t.pink}; color: #fff; }
  .kp-mono { font-family: ${KP_FONT.mono}; font-feature-settings: "ss01"; }
  .kp-scroll::-webkit-scrollbar { display: none; }
`;

Object.assign(window, { KP_LIGHT, KP_DARK, KP_RADII, KP_FONT, kpBaseCss });
