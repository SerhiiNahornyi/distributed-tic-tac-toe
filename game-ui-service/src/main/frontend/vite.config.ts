import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';

export default defineConfig({
  plugins: [react()],
  build: {
    outDir: 'dist',
    // The Maven build copies this straight onto the classpath, so no hashed subdirectories that
    // would need extra resource handler configuration.
    emptyOutDir: true,
  },
  server: {
    port: 5173,
    // `npm run dev` gives hot reload while still talking to the real backend. In the packaged jar
    // the SPA is served by the UI service itself, so requests are same-origin and no proxy exists.
    proxy: {
      '/api': {
        target: 'http://localhost:8080',
        changeOrigin: true,
      },
    },
  },
});
