// Pair screen: where a friend lands. Enter (or arrive with) the host's code.
import { APK_URL, APP_NAME } from '../config';
import { normalizeCode, pair, PairingError, type Pairing } from '../pairing';
import { h, icon, svg } from '../ui/dom';
import { loadingIndicator } from '../ui/feedback';
import type { IconName } from '../ui/icons';
import { cookie, morphPath } from '../ui/shapes';

export interface PairOptions {
  /** Pre-filled from an invite link; submitted automatically. */
  code?: string;
  /** Shown above the form, e.g. after this browser was unlinked. */
  notice?: string;
  onPaired: (pairing: Pairing) => void;
}

/** A slowly turning cookie shape with an icon: the hero on the pair and join screens. */
export function shapeHero(glyph: IconName): HTMLElement {
  const heroPath = svg('path');
  const hero = h('div', { class: 'pair-hero', 'aria-hidden': 'true' },
    svg('svg', { viewBox: '0 0 1 1', class: 'pair-hero-shape' }, heroPath),
    h('span', { class: 'pair-hero-icon' }, icon(glyph)),
  );
  const shape = cookie(9);
  let angle = 0;
  let last = performance.now();
  const reduced = matchMedia('(prefers-reduced-motion: reduce)').matches;
  const spin = (now: number) => {
    if (!reduced) angle = (angle + ((now - last) / 30_000) * 360) % 360;
    last = now;
    heroPath.setAttribute('d', morphPath(shape, shape, 0, angle));
    if (hero.isConnected) requestAnimationFrame(spin);
  };
  heroPath.setAttribute('d', morphPath(shape, shape, 0));
  requestAnimationFrame(spin);
  return hero;
}

export function renderPair(root: HTMLElement, opts: PairOptions): void {
  const hero = shapeHero('music_note');

  // ----------------------------------------------------------- code field
  const input = h('input', {
    class: 'code-input',
    id: 'code',
    inputmode: 'text',
    autocapitalize: 'characters',
    autocomplete: 'one-time-code',
    spellcheck: 'false',
    maxlength: 9,
    'aria-label': 'Party code, 8 characters',
    'aria-describedby': 'code-message',
  });
  const slots = Array.from({ length: 8 }, () => h('span', { class: 'code-slot' }));
  const field = h('label', { class: 'code-field', for: 'code' },
    input,
    h('span', { class: 'code-slots', 'aria-hidden': 'true' }, ...slots.slice(0, 4), h('span', { class: 'code-dash' }), ...slots.slice(4)),
  );

  const renderSlots = () => {
    const value = normalizeCode(input.value);
    const focused = document.activeElement === input;
    slots.forEach((slot, i) => {
      slot.textContent = value[i] ?? '';
      slot.classList.toggle('filled', i < value.length);
      slot.classList.toggle('active', focused && i === Math.min(value.length, 7));
    });
    submit.disabled = value.length !== 8 || busy;
  };

  input.addEventListener('input', () => {
    const value = normalizeCode(input.value);
    input.value = value;
    message.hidden = true;
    renderSlots();
    if (value.length === 8) void join();
  });
  input.addEventListener('focus', renderSlots);
  input.addEventListener('blur', renderSlots);

  // ------------------------------------------------------------- actions
  const submitLabel = h('span', {}, 'Join');
  const submit = h('button', { class: 'btn filled medium state', type: 'submit', disabled: true }, submitLabel);
  const message = h('p', { class: 'form-message body-medium', id: 'code-message', role: 'alert', hidden: true });

  let busy = false;
  const join = async () => {
    const code = normalizeCode(input.value);
    if (code.length !== 8 || busy) return;
    busy = true;
    submit.replaceChildren(loadingIndicator(24, 'Joining'));
    renderSlots();
    try {
      opts.onPaired(await pair(`${code.slice(0, 4)}-${code.slice(4)}`));
    } catch (err) {
      message.textContent = err instanceof PairingError ? err.message : 'Something went wrong. Try again.';
      message.hidden = false;
      field.classList.add('error');
      input.focus();
      input.select();
    } finally {
      busy = false;
      submit.replaceChildren(submitLabel);
      renderSlots();
    }
  };

  const form = h('form', { class: 'pair-form', autocomplete: 'off' }, field, message, submit);
  form.addEventListener('submit', (e) => {
    e.preventDefault();
    void join();
  });
  input.addEventListener('input', () => field.classList.remove('error'));

  // ---------------------------------------------------------------- page
  root.replaceChildren(
    h('header', { class: 'top-bar' }, brand()),
    h('main', { class: 'pair' },
      h('section', { class: 'pair-card' },
        hero,
        opts.notice ? h('p', { class: 'notice body-medium' }, opts.notice) : null,
        h('h1', { class: 'display-small-emphasized' }, 'Join the party'),
        h('p', { class: 'body-large pair-lead' }, "Enter the code from the host's Auxparty app, and pick what plays next from here."),
        form,
      ),
      h('section', { class: 'pair-host' },
        h('span', { class: 'icon-tile' }, icon('smartphone')),
        h('div', {},
          h('div', { class: 'title-medium' }, 'Hosting on Android?'),
          h('div', { class: 'body-medium supporting' }, `Install ${APP_NAME} on the phone that plays the music, then tap Invite.`)),
        h('a', { class: 'btn tonal state', href: APK_URL, rel: 'noopener' }, icon('open_in_new'), 'Get the app'),
      ),
    ),
  );

  if (opts.code) {
    input.value = normalizeCode(opts.code);
    renderSlots();
    void join();
  } else {
    renderSlots();
    input.focus();
  }
}

export function brand(): HTMLElement {
  const mark = svg('svg', { viewBox: '0 0 1 1', class: 'logo', 'aria-hidden': 'true' }, svg('path', { d: morphPath(cookie(9), cookie(9), 0) }));
  return h('div', { class: 'brand' }, mark, h('span', { class: 'title-large-emphasized' }, APP_NAME));
}
