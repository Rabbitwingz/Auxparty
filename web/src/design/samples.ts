// Synthetic artwork, drawn in code (no copyrighted covers). The two covers match
// the Android screenshot tests so both platforms can be compared side by side.

const SIZE = 600;

function canvas(draw: (c: CanvasRenderingContext2D) => void, size = SIZE): HTMLCanvasElement {
  const el = document.createElement('canvas');
  el.width = size;
  el.height = size;
  const ctx = el.getContext('2d')!;
  ctx.scale(size / SIZE, size / SIZE);
  draw(ctx);
  return el;
}

export function sunset(size?: number): HTMLCanvasElement {
  return canvas((c) => {
    const sky = c.createLinearGradient(0, 0, 0, SIZE);
    sky.addColorStop(0, '#2B1055');
    sky.addColorStop(0.5, '#D6246E');
    sky.addColorStop(1, '#FF8A3D');
    c.fillStyle = sky;
    c.fillRect(0, 0, SIZE, SIZE);
    const sun = c.createRadialGradient(300, 380, 0, 300, 380, 150);
    sun.addColorStop(0, '#FFE08A');
    sun.addColorStop(1, '#FFB347');
    c.fillStyle = sun;
    c.beginPath();
    c.arc(300, 380, 130, 0, Math.PI * 2);
    c.fill();
    c.fillStyle = '#1A0B2E';
    c.fillRect(0, 440, SIZE, SIZE - 440);
  }, size);
}

export function ocean(size?: number): HTMLCanvasElement {
  return canvas((c) => {
    const sea = c.createLinearGradient(0, 0, SIZE, SIZE);
    sea.addColorStop(0, '#03363D');
    sea.addColorStop(0.5, '#0B7A75');
    sea.addColorStop(1, '#7FD1B9');
    c.fillStyle = sea;
    c.fillRect(0, 0, SIZE, SIZE);
    c.strokeStyle = 'rgba(232, 255, 247, 0.33)';
    c.lineWidth = 18;
    for (let i = 0; i < 7; i++) {
      c.beginPath();
      c.arc(420, 180, 60 + i * 55, 0, Math.PI * 2);
      c.stroke();
    }
  }, size);
}

/** Small abstract covers for search results. */
export function thumb(hue: number, variant: number): HTMLCanvasElement {
  return canvas((c) => {
    const g = c.createLinearGradient(0, 0, SIZE, SIZE);
    g.addColorStop(0, `hsl(${hue} 70% 30%)`);
    g.addColorStop(1, `hsl(${(hue + 40) % 360} 85% 62%)`);
    c.fillStyle = g;
    c.fillRect(0, 0, SIZE, SIZE);
    c.fillStyle = `hsl(${(hue + 180) % 360} 90% 75% / 0.8)`;
    c.beginPath();
    if (variant % 2) c.arc(300, 300, 170, 0, Math.PI * 2);
    else c.rect(150, 150, 300, 300);
    c.fill();
  }, 112);
}

export const results = [
  { title: 'Golden Hour Drive', artist: 'The Midnight Arcade', album: 'Neon Coast', duration: '3:51', hue: 330 },
  { title: 'Tidal', artist: 'Harbour Lights', album: 'Undertow', duration: '4:18', hue: 175 },
  { title: 'Paper Planets', artist: 'Sora Vale', album: 'Low Orbit', duration: '3:24', hue: 250 },
  { title: 'Warm Static', artist: 'Kilo & the Keys', album: 'Warm Static', duration: '2:57', hue: 25 },
  { title: 'Night Market', artist: 'Lumen Club', album: 'After Hours', duration: '5:02', hue: 290 },
  { title: 'Wildflower Radio', artist: 'June Harbor', album: 'Fields', duration: '3:40', hue: 95 },
];
