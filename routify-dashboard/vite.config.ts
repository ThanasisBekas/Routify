import { defineConfig, loadEnv } from 'vite'
import react from '@vitejs/plugin-react'
import tailwindcss from '@tailwindcss/vite'

// https://vite.dev/config/
export default defineConfig(({ mode }) => {
  // loadEnv merges .env, .env.{mode}, and .env.{mode}.local
  const env = loadEnv(mode, '.', '')
  const isMock = env.VITE_MOCK === 'true'

  return {
    plugins: [
      react(),
      tailwindcss(),
    ],
    server: {
      // In mock mode MSW intercepts all /api calls in the browser — no proxy needed.
      proxy: isMock ? undefined : {
        '/api': {
          target: 'http://localhost:8082',
          changeOrigin: true,
        },
        '/ws': {
          target: 'ws://localhost:8082',
          ws: true,
          changeOrigin: true,
        },
        '/sse': {
          target: 'http://localhost:8082',
          changeOrigin: true,
        },
      },
    },
  }
})
