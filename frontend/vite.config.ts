import react from '@vitejs/plugin-react'
import { defineConfig } from 'vite'

// Dev server: API and identity calls go to the local gateway (deploy/docker-compose.yml), as in production
// where the gateway serves this app and the API on one origin.
const gateway = 'http://localhost:8080'

export default defineConfig({
  plugins: [react()],
  server: {
    port: 5173,
    proxy: {
      '/api': gateway,
      '/mock-sap': gateway,
    },
  },
})
