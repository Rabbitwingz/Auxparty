import { defineConfig } from 'vite';

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
});
