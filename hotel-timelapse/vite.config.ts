import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'

// Relative base so `vite build` output runs from any static host, an intranet
// folder, or an <iframe> in an existing portal (BUILD_SPEC §0).
export default defineConfig({
  base: './',
  plugins: [react()],
})
