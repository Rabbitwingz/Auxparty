// M3 Expressive slider (SliderTokens): 16px tracks with full rounding, a 4×44
// handle that narrows to 2 while pressed, and a 6px gap either side of it.

export class Slider {
  readonly el: HTMLDivElement;
  private value: number;

  constructor(
    private readonly opts: {
      label: string;
      min: number;
      max: number;
      value: number;
      step?: number;
      onInput?: (value: number) => void;
      onChange?: (value: number) => void;
    },
  ) {
    this.value = opts.value;
    this.el = document.createElement('div');
    this.el.className = 'slider';
    this.el.tabIndex = 0;
    this.el.setAttribute('role', 'slider');
    this.el.setAttribute('aria-label', opts.label);
    this.el.setAttribute('aria-valuemin', String(opts.min));
    this.el.setAttribute('aria-valuemax', String(opts.max));

    for (const part of ['active', 'inactive', 'handle']) {
      const span = document.createElement('span');
      span.className = `slider-${part}`;
      this.el.append(span);
    }

    const fromPointer = (clientX: number) => {
      const rect = this.el.getBoundingClientRect();
      const f = Math.min(1, Math.max(0, (clientX - rect.left) / rect.width));
      return opts.min + f * (opts.max - opts.min);
    };

    this.el.addEventListener('pointerdown', (e) => {
      this.el.setPointerCapture(e.pointerId);
      this.el.classList.add('pressed');
      this.update(fromPointer(e.clientX), true);
    });
    this.el.addEventListener('pointermove', (e) => {
      if (this.el.hasPointerCapture(e.pointerId)) this.update(fromPointer(e.clientX), true);
    });
    const release = (e: PointerEvent) => {
      if (!this.el.hasPointerCapture(e.pointerId)) return;
      this.el.releasePointerCapture(e.pointerId);
      this.el.classList.remove('pressed');
      opts.onChange?.(this.value);
    };
    this.el.addEventListener('pointerup', release);
    this.el.addEventListener('pointercancel', release);
    this.el.addEventListener('keydown', (e) => {
      const step = opts.step ?? 1;
      const delta = e.key === 'ArrowRight' || e.key === 'ArrowUp' ? step : e.key === 'ArrowLeft' || e.key === 'ArrowDown' ? -step : 0;
      if (!delta) return;
      e.preventDefault();
      this.update(this.value + delta, true);
      opts.onChange?.(this.value);
    });

    this.set(opts.value);
  }

  set(value: number): void {
    this.update(value, false);
  }

  private update(raw: number, fromUser: boolean): void {
    const { min, max } = this.opts;
    const step = this.opts.step ?? 1;
    const value = Math.min(max, Math.max(min, Math.round(raw / step) * step));
    this.value = value;
    this.el.style.setProperty('--slider-fraction', String((value - min) / (max - min || 1)));
    this.el.setAttribute('aria-valuenow', String(value));
    if (fromUser) this.opts.onInput?.(value);
  }
}
