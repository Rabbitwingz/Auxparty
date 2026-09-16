// Album art in an Expressive shape, matching the Android MorphingArtwork: a turning
// 9-sided cookie while playing, settling into a rounded square when paused.
import { h, icon, svg } from './dom';
import { SpringValue, springs } from './motion';
import { cookie, morphPath, roundedSquare } from './shapes';

let nextClipId = 0;
const square = roundedSquare(0.3);
const cookie9 = cookie(9);

export class MorphingArtwork {
  readonly el: HTMLDivElement;
  private readonly frame: HTMLDivElement;
  private readonly clip: SVGPathElement;
  private cover: HTMLElement;
  private playing = false;
  private rotation = 0;
  private last = performance.now();
  private raf = 0;
  private readonly shape: SpringValue;

  constructor(className = '') {
    const id = `art-clip-${nextClipId++}`;
    this.clip = svg('path');
    this.cover = this.placeholder();
    this.frame = h('div', { class: 'art-frame' }, this.cover);
    this.frame.style.clipPath = `url(#${id})`;
    this.el = h('div', { class: `artwork ${className}`.trim() },
      svg('svg', { width: 0, height: 0, 'aria-hidden': 'true', class: 'svg-defs' },
        svg('defs', {}, svg('clipPath', { id, clipPathUnits: 'objectBoundingBox' }, this.clip))),
      this.frame,
    );
    this.shape = new SpringValue(0, springs.spatialSlow, (v) => this.clip.setAttribute('d', morphPath(square, cookie9, v)));
  }

  setImage(src: string | null, alt = ''): void {
    if (src) {
      const current = this.cover instanceof HTMLImageElement ? this.cover.getAttribute('src') : null;
      if (current === src) return;
      const img = h('img', { src, alt, decoding: 'async' });
      this.swap(img);
    } else if (this.cover instanceof HTMLImageElement) {
      this.swap(this.placeholder());
    }
  }

  setPlaying(playing: boolean): void {
    if (playing === this.playing) return;
    this.playing = playing;
    this.shape.set(playing ? 1 : 0);
    if (playing && !this.raf) {
      this.last = performance.now();
      this.raf = requestAnimationFrame(this.spin);
    }
  }

  private swap(next: HTMLElement): void {
    this.cover.replaceWith(next);
    this.cover = next;
    this.applyRotation();
  }

  private placeholder(): HTMLElement {
    return h('div', { class: 'art-placeholder' }, icon('music_note'));
  }

  // The frame turns and the cover counter-turns, so only the outline spins (as on
  // Android). 1.2x keeps the square's corners covered at any angle.
  private spin = (now: number) => {
    this.rotation = (this.rotation + ((now - this.last) / 24_000) * 360) % 360;
    this.last = now;
    this.applyRotation();
    this.raf = this.playing ? requestAnimationFrame(this.spin) : 0;
  };

  private applyRotation(): void {
    this.frame.style.transform = `rotate(${this.rotation}deg)`;
    this.cover.style.transform = `rotate(${-this.rotation}deg) scale(1.2)`;
  }
}
