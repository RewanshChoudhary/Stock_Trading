import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';

export default defineConfig({
  plugins: [react()],
  base: '/',
  build: { outDir: '../../threadpool-web/src/main/resources/static', emptyOutDir: true },
});
