// Loading indicator, snackbar and confirm dialog.
import { h, svg } from './dom';
import { cookie, morphPath, type RadiusFn } from './shapes';

/** Star-like shape: `points` lobes between radius 1 and `inner`. */
const burst = (points: number, inner: number): RadiusFn => (t) => {
  const mid = (1 + inner) / 2;
  const amp = (1 - inner) / 2;
  return mid + amp * Math.cos(points * t);
};

// A sequence in the spirit of LoadingIndicatorDefaults.IndeterminateIndicatorPolygons.
const SEQUENCE: RadiusFn[] = [burst(10, 0.78), cookie(9), burst(5, 0.7), cookie(4), (t) => 0.92 + 0.08 * Math.cos(2 * t), cookie(6)];

/**
 * M3 Expressive loading indicator: a shape that morphs through a sequence while
 * turning. Replaces spinners. Stops animating when detached from the page.
 */
export function loadingIndicator(size = 48, label = 'Loading'): HTMLElement {
  const path = svg('path');
  const el = h('span', { class: 'loading-indicator', role: 'progressbar', 'aria-label': label },
    svg('svg', { viewBox: '0 0 1 1', width: size, height: size, 'aria-hidden': 'true' }, path));
  const reduced = matchMedia('(prefers-reduced-motion: reduce)').matches;
  const start = performance.now();
  const STEP = 650;

  const frame = (now: number) => {
    const t = (now - start) / STEP;
    const i = Math.floor(t) % SEQUENCE.length;
    // Ease in-out within each step with a slight overshoot, like a spring.
    const f = t % 1;
    const eased = f < 0.5 ? 2 * f * f : 1 - Math.pow(-2 * f + 2, 2) / 2;
    const rotation = reduced ? 0 : (t * 360) / 3;
    path.setAttribute('d', morphPath(SEQUENCE[i], SEQUENCE[(i + 1) % SEQUENCE.length], eased, rotation));
    if (el.isConnected || now - start < 1000) requestAnimationFrame(frame);
  };
  requestAnimationFrame(frame);
  path.setAttribute('d', morphPath(SEQUENCE[0], SEQUENCE[0], 0));
  return el;
}

// ----------------------------------------------------------------- snackbar

let snackbarEl: HTMLDivElement | null = null;
let snackbarTimer = 0;

export function snackbar(message: string, action?: { label: string; run: () => void }): void {
  snackbarEl?.remove();
  const text = h('span', { class: 'body-medium' }, message);
  const el = h('div', { class: 'snackbar', role: 'status' }, text);
  if (action) {
    const button = h('button', { class: 'btn text inverse state', type: 'button' }, action.label);
    button.addEventListener('click', () => {
      action.run();
      el.remove();
    });
    el.append(button);
  }
  document.body.append(el);
  snackbarEl = el;
  requestAnimationFrame(() => el.classList.add('open'));
  window.clearTimeout(snackbarTimer);
  snackbarTimer = window.setTimeout(() => {
    el.classList.remove('open');
    window.setTimeout(() => el.remove(), 300);
  }, 4_500);
}

// ------------------------------------------------------------------ dialog

/** M3 basic dialog on the native <dialog>: focus trap, Escape and backdrop for free. */
export function confirmDialog(opts: { title: string; body: string; confirm: string; cancel?: string }): Promise<boolean> {
  return new Promise((resolve) => {
    const cancel = h('button', { class: 'btn text state', type: 'button', value: 'cancel' }, opts.cancel ?? 'Cancel');
    const confirm = h('button', { class: 'btn text state', type: 'button', value: 'confirm' }, opts.confirm);
    const dialog = h('dialog', { class: 'dialog' },
      h('h2', { class: 'headline-small' }, opts.title),
      h('p', { class: 'body-medium' }, opts.body),
      h('div', { class: 'dialog-actions' }, cancel, confirm),
    );
    const close = (result: boolean) => {
      dialog.close();
      dialog.remove();
      resolve(result);
    };
    cancel.addEventListener('click', () => close(false));
    confirm.addEventListener('click', () => close(true));
    dialog.addEventListener('cancel', (e) => {
      e.preventDefault();
      close(false);
    });
    dialog.addEventListener('click', (e) => {
      if (e.target === dialog) close(false); // backdrop
    });
    document.body.append(dialog);
    dialog.showModal();
    confirm.focus();
  });
}
