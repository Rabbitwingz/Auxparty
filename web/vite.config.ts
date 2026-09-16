import { readFileSync } from 'node:fs';
import { defineConfig } from 'vite';

// `vite preview` serves the production build with the same security headers
// Vercel sends, so CSP problems show up locally before deploying.
const vercel = JSON.parse(readFileSync(new URL('./vercel.json', import.meta.url), 'utf8'));
const productionHeaders: Record<string, string> = Object.fromEntries(
  vercel.headers.flatMap((rule: { headers: { key: string; value: string }[] }) => rule.headers.map((h) => [h.key, h.value])),
);

export default defineConfig({
  build: {
    outDir: 'dist',
    rollupOptions: {
      input: {
        main: 'index.html',
        // Design-direction page for reviewing the Material 3 Expressive look.
        design: 'design.html',
      },
    },
  },
  preview: {
    headers: productionHeaders,
  },
});
