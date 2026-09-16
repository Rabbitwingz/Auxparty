// M3 Expressive motion: physical springs instead of duration + easing.
// Values are MotionScheme.expressive() from Compose Material 3 1.5.0-alpha28
// (ExpressiveMotionTokens), so the web moves like the app.

export interface Spring {
  damping: number; // damping ratio; < 1 overshoots
  stiffness: number;
}

export const springs = {
  spatialFast: { damping: 0.6, stiffness: 800 },
  spatialDefault: { damping: 0.8, stiffness: 380 },
  spatialSlow: { damping: 0.8, stiffness: 200 },
  effectsFast: { damping: 1, stiffness: 3800 },
  effectsDefault: { damping: 1, stiffness: 1600 },
  effectsSlow: { damping: 1, stiffness: 800 },
} satisfies Record<string, Spring>;

export type SpringName = keyof typeof springs;

const reducedMotion = matchMedia('(prefers-reduced-motion: reduce)');

/** Samples a unit spring (0 to 1, from rest) until it settles. */
function simulate({ damping, stiffness }: Spring): { points: number[]; durationMs: number } {
  const dt = 1 / 240;
  const c = 2 * damping * Math.sqrt(stiffness);
  let x = 0;
  let v = 0;
  let t = 0;
  const trace: [number, number][] = [[0, 0]];
  while (t < 3) {
    const a = -stiffness * (x - 1) - c * v;
    v += a * dt;
    x += v * dt;
    t += dt;
    trace.push([t, x]);
    if (Math.abs(x - 1) < 0.001 && Math.abs(v) < 0.01) break;
  }
  const durationMs = Math.round(t * 1000);
  const samples = 40;
  const points: number[] = [];
  for (let i = 0; i <= samples; i++) {
    const target = (i / samples) * t;
    const hit = trace.find(([time]) => time >= target) ?? trace[trace.length - 1];
    points.push(hit[1]);
  }
  points[points.length - 1] = 1;
  return { points, durationMs };
}

/**
 * Exposes each spring to CSS as an easing plus the duration it needs:
 *   transition: transform var(--motion-spatial-fast-duration) var(--motion-spatial-fast);
 * CSS linear() reproduces the overshoot exactly at sampled points.
 */
export function installMotionTokens(root: HTMLElement = document.documentElement): void {
  for (const [name, spring] of Object.entries(springs)) {
    const kebab = name.replace(/[A-Z]/g, (m) => `-${m.toLowerCase()}`);
    const { points, durationMs } = simulate(spring);
    const easing = `linear(${points.map((p) => +p.toFixed(4)).join(', ')})`;
    root.style.setProperty(`--motion-${kebab}`, easing);
    root.style.setProperty(`--motion-${kebab}-duration`, reducedMotion.matches ? '1ms' : `${durationMs}ms`);
  }
}

/**
 * Drives a numeric value with a spring, for things CSS can't transition
 * (SVG path morphs). Retargeting mid-flight keeps the current velocity.
 */
export class SpringValue {
  private value: number;
  private velocity = 0;
  private target: number;
  private frame = 0;
  private last = 0;

  constructor(
    initial: number,
    private readonly spring: Spring,
    private readonly onUpdate: (value: number) => void,
  ) {
    this.value = initial;
    this.target = initial;
    onUpdate(initial);
  }

  set(target: number): void {
    this.target = target;
    if (reducedMotion.matches) {
      this.value = target;
      this.velocity = 0;
      this.onUpdate(target);
      return;
    }
    if (!this.frame) {
      this.last = performance.now();
      this.frame = requestAnimationFrame(this.step);
    }
  }

  private step = (now: number) => {
    const { damping, stiffness } = this.spring;
    const c = 2 * damping * Math.sqrt(stiffness);
    let elapsed = Math.min((now - this.last) / 1000, 0.064);
    this.last = now;
    while (elapsed > 0) {
      const dt = Math.min(elapsed, 1 / 240);
      const a = -stiffness * (this.value - this.target) - c * this.velocity;
      this.velocity += a * dt;
      this.value += this.velocity * dt;
      elapsed -= dt;
    }
    const settled = Math.abs(this.value - this.target) < 0.0005 && Math.abs(this.velocity) < 0.005;
    if (settled) {
      this.value = this.target;
      this.velocity = 0;
    }
    this.onUpdate(this.value);
    this.frame = settled ? 0 : requestAnimationFrame(this.step);
  };
}
