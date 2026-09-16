// Ties colour to the music: the artwork seeds the scheme, light/dark follows the
// user's choice or the system. Mirrors HostViewModel.updateSeed on Android.
import { BRAND_SEED, ROLE_NAMES, applyScheme, registerColorProperties, seedFromImage } from './ui/color';
import { installMotionTokens } from './ui/motion';

export type ThemeMode = 'system' | 'light' | 'dark';

const MODE_KEY = 'auxparty.theme';
const systemDark = matchMedia('(prefers-color-scheme: dark)');

let seed = BRAND_SEED;
let artKey: string | null = null;
let seedTimer = 0;
const seedCache = new Map<string, number>();

export function themeMode(): ThemeMode {
  try {
    const v = localStorage.getItem(MODE_KEY);
    return v === 'light' || v === 'dark' ? v : 'system';
  } catch {
    return 'system';
  }
}

export function setThemeMode(mode: ThemeMode): void {
  try {
    if (mode === 'system') localStorage.removeItem(MODE_KEY);
    else localStorage.setItem(MODE_KEY, mode);
  } catch {
    /* ignore */
  }
  apply();
}

const isDark = () => (themeMode() === 'system' ? systemDark.matches : themeMode() === 'dark');

function apply(): void {
  applyScheme(seed, isDark());
}

export function initTheme(): void {
  registerColorProperties();
  installMotionTokens();
  apply();
  // Colour roles cross-fade on the slow effects spring. Enabled after the first
  // paint so the page doesn't animate in from the fallback colours.
  requestAnimationFrame(() => {
    document.documentElement.style.transition = ROLE_NAMES
      .map((n) => `--md-sys-color-${n} var(--motion-effects-slow-duration) var(--motion-effects-slow)`)
      .join(', ');
  });
  systemDark.addEventListener('change', () => {
    if (themeMode() === 'system') apply();
  });
}

/**
 * Re-themes from artwork. Debounced so skipping through tracks doesn't strobe,
 * cached per track so returning to one is instant.
 */
export function themeFromArtwork(key: string | null, src: string | null): void {
  if (key === artKey) return;
  artKey = key;
  window.clearTimeout(seedTimer);

  if (!key || !src) {
    seed = BRAND_SEED;
    apply();
    return;
  }
  const cached = seedCache.get(key);
  if (cached !== undefined) {
    seed = cached;
    apply();
    return;
  }
  seedTimer = window.setTimeout(() => {
    const img = new Image();
    img.onload = () => {
      if (artKey !== key) return;
      const next = seedFromImage(img) ?? BRAND_SEED;
      seedCache.set(key, next);
      seed = next;
      apply();
    };
    img.src = src;
  }, 300);
}
