// Linear wavy progress indicator (M3 Expressive), seekable.
// Tokens (LinearProgressIndicatorTokens): thickness 4, amplitude 3, wavelength 40,
// wave height 10, track gap 4, stop 4. The wave travels one wavelength per second
// while playing and flattens (spring) when paused, matching Compose.
import { svg } from './dom';
import { SpringValue, springs } from './motion';

const THICKNESS = 4;
const AMPLITUDE = 3;
const WAVELENGTH = 40;
const HEIGHT = 10;
const GAP = 4;
const STOP = 4;

export class WavyProgress {
  readonly el: HTMLDivElement;
  private readonly active: SVGPathElement;
  private readonly track: SVGLineElement;
  private readonly stop: SVGCircleElement;
  private readonly root: SVGSVGElement;
  private width = 0;
  private fraction = 0;
  private amplitude: number = AMPLITUDE;
  private phase = 0;
  private playing = false;
  private frame = 0;
  private lastTime = 0;
  private readonly amp: SpringValue;

  constructor(opts: { label: string; onSeek?: (fraction: number) => void }) {
    this.active = svg('path', { class: 'wavy-active', fill: 'none', 'stroke-width': THICKNESS, 'stroke-linecap': 'round' });
    this.track = svg('line', { class: 'wavy-track', 'stroke-width': THICKNESS, 'stroke-linecap': 'round' });
    this.stop = svg('circle', { class: 'wavy-stop', r: STOP / 2 });
    this.root = svg('svg', { class: 'wavy-svg', height: HEIGHT + THICKNESS, 'aria-hidden': 'true' }, this.track, this.active, this.stop);

    this.el = document.createElement('div');
    this.el.className = 'wavy';
    // Without onSeek it only shows progress (e.g. for party guests).
    this.el.setAttribute('role', opts.onSeek ? 'slider' : 'progressbar');
    this.el.setAttribute('aria-label', opts.label);
    this.el.setAttribute('aria-valuemin', '0');
    this.el.setAttribute('aria-valuemax', '100');
    if (opts.onSeek) this.el.tabIndex = 0;
    else this.el.classList.add('readonly');
    this.el.append(this.root);

    this.amp = new SpringValue(AMPLITUDE, springs.spatialDefault, (v) => {
      this.amplitude = v;
      this.draw();
    });

    new ResizeObserver(([entry]) => {
      this.width = entry.contentRect.width;
      this.root.setAttribute('width', String(this.width));
      this.draw();
    }).observe(this.el);

    if (opts.onSeek) {
      const seek = (clientX: number) => {
        const rect = this.el.getBoundingClientRect();
        const f = Math.min(1, Math.max(0, (clientX - rect.left) / rect.width));
        this.setProgress(f);
        opts.onSeek?.(f);
      };
      this.el.addEventListener('pointerdown', (e) => seek(e.clientX));
      this.el.addEventListener('keydown', (e) => {
        if (e.key !== 'ArrowLeft' && e.key !== 'ArrowRight') return;
        e.preventDefault();
        const f = Math.min(1, Math.max(0, this.fraction + (e.key === 'ArrowRight' ? 0.02 : -0.02)));
        this.setProgress(f);
        opts.onSeek?.(f);
      });
    }
  }

  setProgress(fraction: number): void {
    this.fraction = Math.min(1, Math.max(0, fraction));
    this.el.setAttribute('aria-valuenow', String(Math.round(this.fraction * 100)));
    this.draw();
  }

  setPlaying(playing: boolean): void {
    if (playing === this.playing) return;
    this.playing = playing;
    this.amp.set(playing ? AMPLITUDE : 0);
    if (playing && !this.frame) {
      this.lastTime = performance.now();
      this.frame = requestAnimationFrame(this.tick);
    }
  }

  private tick = (now: number) => {
    // One wavelength per second, like Compose's default waveSpeed.
    this.phase = (this.phase + ((now - this.lastTime) / 1000) * WAVELENGTH) % WAVELENGTH;
    this.lastTime = now;
    this.draw();
    this.frame = this.playing || this.amplitude > 0.01 ? requestAnimationFrame(this.tick) : 0;
  };

  private draw(): void {
    const w = this.width;
    if (!w) return;
    const mid = (HEIGHT + THICKNESS) / 2;
    const inset = THICKNESS / 2;
    const end = inset + (w - 2 * inset) * this.fraction;

    // Active wave, sampled every 2px; amplitude eases in over the first half-wave.
    const pts: string[] = [];
    for (let x = inset; x <= end; x += 2) {
      const ramp = Math.min(1, (x - inset) / (WAVELENGTH / 2));
      const y = mid + Math.sin(((x + this.phase) / WAVELENGTH) * Math.PI * 2) * this.amplitude * ramp;
      pts.push(`${pts.length ? 'L' : 'M'}${x.toFixed(1)} ${y.toFixed(2)}`);
    }
    this.active.setAttribute('d', pts.length > 1 ? pts.join('') : `M${inset} ${mid}L${inset + 0.01} ${mid}`);

    const trackStart = end + GAP + THICKNESS;
    const trackEnd = w - inset;
    const showTrack = trackStart < trackEnd;
    this.track.setAttribute('x1', String(trackStart));
    this.track.setAttribute('x2', String(trackEnd));
    this.track.setAttribute('y1', String(mid));
    this.track.setAttribute('y2', String(mid));
    this.track.style.visibility = showTrack ? 'visible' : 'hidden';
    this.stop.setAttribute('cx', String(w - inset));
    this.stop.setAttribute('cy', String(mid));
  }
}
