// Material 3 Expressive shapes for the web, as radius functions of angle so any
// two can morph smoothly by interpolating radius. They approximate the Compose
// MaterialShapes definitions:
//   Cookie9Sided = RoundedPolygon.star(9, innerRadius = 0.8, rounding = 0.5)
//   Square       = RoundedPolygon.rectangle(1, 1, rounding = 0.3)
// Paths are emitted in a 0..1 box for <clipPath clipPathUnits="objectBoundingBox">.

export type RadiusFn = (theta: number) => number;

const STEPS = 180;

/** Scalloped circle: outer radius 1, inner 0.8, with a smooth (rounded) profile. */
export const cookie = (points = 9): RadiusFn => (theta) =>
  0.9 + 0.1 * Math.cos(points * (theta + Math.PI / 2));

/** Rounded square with corner radius as a fraction of the side. */
export const roundedSquare = (rounding = 0.3): RadiusFn => {
  const half = 0.5;
  const r = rounding * 1; // side is 1
  const inside = (x: number, y: number) => {
    const qx = Math.abs(x) - (half - r);
    const qy = Math.abs(y) - (half - r);
    const outside = Math.hypot(Math.max(qx, 0), Math.max(qy, 0));
    return outside + Math.min(Math.max(qx, qy), 0) - r <= 0;
  };
  const cache = new Map<number, number>();
  return (theta) => {
    const key = Math.round(theta * 1000);
    const hit = cache.get(key);
    if (hit !== undefined) return hit;
    // Binary search along the ray for the boundary; normalise to the 0.5 radius box.
    let lo = 0;
    let hi = 0.75;
    for (let i = 0; i < 24; i++) {
      const mid = (lo + hi) / 2;
      if (inside(Math.cos(theta) * mid, Math.sin(theta) * mid)) lo = mid;
      else hi = mid;
    }
    const value = lo / half;
    cache.set(key, value);
    return value;
  };
};

/** Path between two shapes at progress 0..1, rotated by `rotation` degrees. */
export function morphPath(from: RadiusFn, to: RadiusFn, progress: number, rotation = 0): string {
  const rot = (rotation * Math.PI) / 180;
  const parts: string[] = [];
  for (let i = 0; i < STEPS; i++) {
    const theta = (i / STEPS) * Math.PI * 2;
    const r = (from(theta) * (1 - progress) + to(theta) * progress) * 0.5;
    const x = 0.5 + Math.cos(theta + rot) * r;
    const y = 0.5 + Math.sin(theta + rot) * r;
    parts.push(`${i ? 'L' : 'M'}${x.toFixed(4)} ${y.toFixed(4)}`);
  }
  return `${parts.join('')}Z`;
}
