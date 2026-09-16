// Tiny DOM helpers. Everything from the network is set as text, never HTML.
import { ICON_VIEWBOX, icons, type IconName } from './icons';

const SVG_NS = 'http://www.w3.org/2000/svg';

type Attrs = Record<string, string | number | boolean | undefined>;
type Child = Node | string | null | undefined | false;

export function h<K extends keyof HTMLElementTagNameMap>(
  tag: K,
  attrs: Attrs = {},
  ...children: Child[]
): HTMLElementTagNameMap[K] {
  const el = document.createElement(tag);
  for (const [key, value] of Object.entries(attrs)) {
    if (value === undefined || value === false) continue;
    if (key === 'class') el.className = String(value);
    else el.setAttribute(key, value === true ? '' : String(value));
  }
  for (const child of children) {
    if (child === null || child === undefined || child === false) continue;
    el.append(typeof child === 'string' ? document.createTextNode(child) : child);
  }
  return el;
}

export function svg<K extends keyof SVGElementTagNameMap>(tag: K, attrs: Attrs = {}, ...children: Node[]): SVGElementTagNameMap[K] {
  const el = document.createElementNS(SVG_NS, tag);
  for (const [key, value] of Object.entries(attrs)) {
    if (value !== undefined && value !== false) el.setAttribute(key, String(value));
  }
  el.append(...children);
  return el;
}

/** A Material Symbols Rounded glyph, sized by CSS (1em by default). */
export function icon(name: IconName, label?: string): SVGSVGElement {
  const el = svg('svg', { viewBox: ICON_VIEWBOX, class: 'icon', 'aria-hidden': label ? undefined : 'true', role: label ? 'img' : undefined },
    svg('path', { d: icons[name] }));
  if (label) el.setAttribute('aria-label', label);
  return el;
}
