// Album-art colour engine. Mirrors the Android app: Material Color Utilities'
// Celebi quantizer + Score picks a seed, and the Content scheme keeps the UI
// recognisably close to the artwork. Roles become CSS custom properties that
// are registered with @property, so a track change animates instead of snapping.
import {
  Hct,
  MaterialDynamicColors,
  SchemeContent,
  hexFromArgb,
  sourceColorFromImageBytes,
  type DynamicColor,
  type DynamicScheme,
} from '@material/material-color-utilities';

/** Used when nothing is playing, or the track has no artwork. Same as Android. */
export const BRAND_SEED = 0xff7b5cff;

const SAMPLE_PX = 112; // same downscale as Android; result is stable, far cheaper

const colors = new MaterialDynamicColors();

/** Role name (CSS custom property suffix) to its dynamic colour. */
const ROLES: Record<string, DynamicColor> = {
  primary: colors.primary(),
  'on-primary': colors.onPrimary(),
  'primary-container': colors.primaryContainer(),
  'on-primary-container': colors.onPrimaryContainer(),
  'inverse-primary': colors.inversePrimary(),
  secondary: colors.secondary(),
  'on-secondary': colors.onSecondary(),
  'secondary-container': colors.secondaryContainer(),
  'on-secondary-container': colors.onSecondaryContainer(),
  tertiary: colors.tertiary(),
  'on-tertiary': colors.onTertiary(),
  'tertiary-container': colors.tertiaryContainer(),
  'on-tertiary-container': colors.onTertiaryContainer(),
  error: colors.error(),
  'on-error': colors.onError(),
  'error-container': colors.errorContainer(),
  'on-error-container': colors.onErrorContainer(),
  background: colors.background(),
  surface: colors.surface(),
  'surface-dim': colors.surfaceDim(),
  'surface-bright': colors.surfaceBright(),
  'surface-container-lowest': colors.surfaceContainerLowest(),
  'surface-container-low': colors.surfaceContainerLow(),
  'surface-container': colors.surfaceContainer(),
  'surface-container-high': colors.surfaceContainerHigh(),
  'surface-container-highest': colors.surfaceContainerHighest(),
  'on-surface': colors.onSurface(),
  'on-surface-variant': colors.onSurfaceVariant(),
  outline: colors.outline(),
  'outline-variant': colors.outlineVariant(),
  'inverse-surface': colors.inverseSurface(),
  'inverse-on-surface': colors.inverseOnSurface(),
  scrim: colors.scrim(),
  shadow: colors.shadow(),
};

export const ROLE_NAMES = Object.keys(ROLES);

/** Seed colour for an image, or null if it can't be read (e.g. tainted canvas). */
export function seedFromImage(image: CanvasImageSource): number | null {
  const canvas = document.createElement('canvas');
  canvas.width = SAMPLE_PX;
  canvas.height = SAMPLE_PX;
  const ctx = canvas.getContext('2d', { willReadFrequently: true });
  if (!ctx) return null;
  try {
    ctx.drawImage(image, 0, 0, SAMPLE_PX, SAMPLE_PX);
    return sourceColorFromImageBytes(ctx.getImageData(0, 0, SAMPLE_PX, SAMPLE_PX).data);
  } catch {
    return null;
  }
}

export function schemeFor(seed: number, dark: boolean): DynamicScheme {
  return new SchemeContent(Hct.fromInt(seed), dark, 0, '2025');
}

/** Writes every role as --md-sys-color-<role> on the target element. */
export function applyScheme(seed: number, dark: boolean, target: HTMLElement = document.documentElement): void {
  const scheme = schemeFor(seed, dark);
  for (const [name, role] of Object.entries(ROLES)) {
    target.style.setProperty(`--md-sys-color-${name}`, hexFromArgb(role.getArgb(scheme)));
  }
  target.style.colorScheme = dark ? 'dark' : 'light';
  document.querySelector('meta[name="theme-color"]')?.setAttribute('content', hexFromArgb(ROLES.surface.getArgb(scheme)));
}

/**
 * Registers colour roles as animatable properties. Unregistered custom
 * properties swap instantly; registered <color> ones interpolate.
 */
export function registerColorProperties(): void {
  if (!('registerProperty' in CSS)) return;
  for (const name of ROLE_NAMES) {
    try {
      CSS.registerProperty({ name: `--md-sys-color-${name}`, syntax: '<color>', inherits: true, initialValue: 'transparent' });
    } catch {
      // Already registered (hot reload).
    }
  }
}
