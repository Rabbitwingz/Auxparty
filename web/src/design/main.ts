// Design-direction page: the Remote screen with fake state, for sign-off on the
// Material 3 Expressive look before every screen is built.
import '../ui/base.css';
import './design.css';
import { BRAND_SEED, ROLE_NAMES, applyScheme, registerColorProperties, seedFromImage } from '../ui/color';
import { h, icon, svg } from '../ui/dom';
import { SpringValue, installMotionTokens, springs } from '../ui/motion';
import { cookie, morphPath, roundedSquare } from '../ui/shapes';
import { Slider } from '../ui/slider';
import { WavyProgress } from '../ui/wavy';
import { ocean, results, sunset, thumb } from './samples';

type Art = 'sunset' | 'ocean' | 'none';

const state = {
  art: 'sunset' as Art,
  dark: matchMedia('(prefers-color-scheme: dark)').matches,
  playing: true,
  positionMs: 83_000,
  durationMs: 231_000,
  volume: 9,
};

const TRACKS: Record<Art, { title: string; artist: string }> = {
  sunset: { title: 'Golden Hour Drive', artist: 'The Midnight Arcade' },
  ocean: { title: 'Tidal', artist: 'Harbour Lights' },
  none: { title: 'Nothing playing', artist: 'Start a song on your phone, or pick one here.' },
};

registerColorProperties();
installMotionTokens();
// Colour roles cross-fade on the slow effects spring when the artwork changes.
document.documentElement.style.transition = ROLE_NAMES
  .map((n) => `--md-sys-color-${n} var(--motion-effects-slow-duration) var(--motion-effects-slow)`)
  .join(', ');

// ----------------------------------------------------------------- artwork

const clipPath = svg('path');
const artCanvasHost = h('div', { class: 'art' });
const artwork = h('div', { class: 'art-wrap' },
  svg('svg', { width: 0, height: 0, 'aria-hidden': 'true', class: 'defs' },
    svg('defs', {}, svg('clipPath', { id: 'art-clip', clipPathUnits: 'objectBoundingBox' }, clipPath))),
  artCanvasHost,
);

const square = roundedSquare(0.3);
const cookie9 = cookie(9);
let shapeProgress = 1;
let rotation = 0;
const redrawClip = () => clipPath.setAttribute('d', morphPath(square, cookie9, shapeProgress, rotation));
const shape = new SpringValue(1, springs.spatialSlow, (v) => {
  shapeProgress = v;
  redrawClip();
});

// The outline turns like a record while playing; the artwork stays upright.
let lastFrame = performance.now();
const spin = (now: number) => {
  if (state.playing) rotation = (rotation + ((now - lastFrame) / 24_000) * 360) % 360;
  lastFrame = now;
  redrawClip();
  requestAnimationFrame(spin);
};
requestAnimationFrame(spin);

// ------------------------------------------------------------------- player

const title = h('h1', { class: 'headline-medium-emphasized title' });
const artist = h('div', { class: 'title-medium artist' });
const source = h('div', { class: 'label-large-emphasized source' }, 'YOUTUBE MUSIC');
const elapsed = h('span', { class: 'label-medium' });
const total = h('span', { class: 'label-medium' });

const progress = new WavyProgress({
  label: 'Seek',
  onSeek: (f) => {
    state.positionMs = f * state.durationMs;
    renderTime();
  },
});

const playButton = h('button', { class: 'icon-btn filled size-xl wide state', type: 'button' });
const transport = h('div', { class: 'transport' },
  h('button', { class: 'icon-btn tonal size-l state', type: 'button', 'aria-label': 'Previous' }, icon('skip_previous')),
  playButton,
  h('button', { class: 'icon-btn tonal size-l state', type: 'button', 'aria-label': 'Next' }, icon('skip_next')),
);
playButton.addEventListener('click', () => {
  state.playing = !state.playing;
  render();
});

const volume = new Slider({ label: 'Volume', min: 0, max: 15, value: state.volume });

const player = h('section', { class: 'player' },
  artwork,
  h('div', { class: 'meta' }, source, title, artist),
  h('div', { class: 'progress' }, progress.el, h('div', { class: 'times' }, elapsed, total)),
  transport,
  h('div', { class: 'volume' }, icon('volume_down'), volume.el, icon('volume_up')),
);

// ------------------------------------------------------------------- browse

const results$ = h('div', { class: 'list' },
  ...results.map((r, i) => h('button', { class: 'list-item state', type: 'button' },
    thumb(r.hue, i),
    h('div', { class: 'text' },
      h('div', { class: 'headline body-large' }, r.title),
      h('div', { class: 'supporting body-medium' }, `${r.artist} · ${r.album}`)),
    h('span', { class: 'trailing label-medium' }, r.duration),
  )),
);

const browse = h('section', { class: 'browse' },
  h('h2', { class: 'headline-small-emphasized' }, 'Pick the next song'),
  h('label', { class: 'search-bar' },
    icon('search'),
    h('input', { type: 'search', placeholder: 'Search YouTube Music', value: 'summer night drive', 'aria-label': 'Search YouTube Music' })),
  h('div', { class: 'label-large results-label' }, 'Songs'),
  results$,
);

// ------------------------------------------------------------ app bar + review

const segmented = <T extends string>(label: string, options: [T, string][], get: () => T, set: (v: T) => void) => {
  const group = h('div', { class: 'segmented', role: 'group', 'aria-label': label });
  const buttons = options.map(([value, text]) => {
    const b = h('button', { type: 'button', class: 'state' }, text);
    b.addEventListener('click', () => {
      set(value);
      render();
    });
    return [value, b] as const;
  });
  group.append(...buttons.map(([, b]) => b));
  return { el: group, sync: () => buttons.forEach(([value, b]) => b.setAttribute('aria-pressed', String(get() === value))) };
};

const artChoice = segmented<Art>('Artwork', [['sunset', 'Sunset'], ['ocean', 'Ocean'], ['none', 'No art']], () => state.art, (v) => { state.art = v; });
const themeChoice = segmented<'light' | 'dark'>('Theme', [['light', 'Light'], ['dark', 'Dark']], () => (state.dark ? 'dark' : 'light'), (v) => { state.dark = v === 'dark'; });
const playChoice = segmented<'play' | 'pause'>('Playback', [['play', 'Playing'], ['pause', 'Paused']], () => (state.playing ? 'play' : 'pause'), (v) => { state.playing = v === 'play'; });

const logo = svg('svg', { viewBox: '0 0 1 1', class: 'logo', 'aria-hidden': 'true' }, svg('path', { d: morphPath(square, cookie9, 1, 0) }));

document.getElementById('app')!.append(
  h('header', { class: 'top-bar' },
    h('div', { class: 'brand' }, logo, h('span', { class: 'title-large-emphasized' }, 'Auxparty')),
    h('span', { class: 'chip label-large' }, icon('cloud_done'), 'Phone connected'),
    h('button', { class: 'icon-btn state', type: 'button', 'aria-label': 'More' }, icon('more_vert')),
  ),
  h('div', { class: 'review' }, h('span', { class: 'label-large' }, 'Design review'), artChoice.el, themeChoice.el, playChoice.el),
  h('main', { class: 'remote' }, player, browse),
);

// ------------------------------------------------------------------- render

function renderTime() {
  const fmt = (ms: number) => `${Math.floor(ms / 60000)}:${String(Math.floor(ms / 1000) % 60).padStart(2, '0')}`;
  const hasTrack = state.art !== 'none';
  elapsed.textContent = hasTrack ? fmt(state.positionMs) : '0:00';
  total.textContent = hasTrack ? fmt(state.durationMs) : '--:--';
  progress.setProgress(hasTrack ? state.positionMs / state.durationMs : 0);
}

function render() {
  const cover = state.art === 'sunset' ? sunset() : state.art === 'ocean' ? ocean() : null;
  artCanvasHost.replaceChildren(cover ?? h('div', { class: 'art-empty' }, icon('music_note')));
  applyScheme((cover && seedFromImage(cover)) ?? BRAND_SEED, state.dark);

  const track = TRACKS[state.art];
  title.textContent = track.title;
  artist.textContent = track.artist;
  source.hidden = state.art === 'none';

  const playing = state.playing && state.art !== 'none';
  shape.set(playing ? 1 : 0);
  progress.setPlaying(playing);
  playButton.classList.toggle('square', playing);
  playButton.replaceChildren(icon(playing ? 'pause' : 'play_arrow'));
  playButton.setAttribute('aria-label', playing ? 'Pause' : 'Play');

  renderTime();
  [artChoice, themeChoice, playChoice].forEach((c) => c.sync());
}

setInterval(() => {
  if (!state.playing || state.art === 'none') return;
  state.positionMs = (state.positionMs + 1000) % state.durationMs;
  renderTime();
}, 1000);

render();
