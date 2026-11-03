import react from '@vitejs/plugin-react'
import { createLogger, defineConfig } from 'vite'

/**
 * Коли вкладку закривають чи перезавантажують, браузер рве WebSocket, а сервер саме шле state
 * (20 разів/с) — проксі не встигає дописати й логує EPIPE/ECONNRESET. Це нешкідливо (сервер коректно
 * прибирає гравця), тож ховаємо лише ці два коди; решта помилок проксі (напр. ECONNREFUSED — сервер
 * гри не запущений) лишаються видимими.
 */
const logger = createLogger()
const logError = logger.error.bind(logger)
logger.error = (msg, options) => {
  const text = `${msg}\n${options?.error?.stack ?? ''}`
  if (text.includes('ws proxy') && /EPIPE|ECONNRESET/.test(text)) return
  logError(msg, options)
}

// https://vite.dev/config/
export default defineConfig({
  plugins: [react()],
  customLogger: logger,
  server: {
    host: true,
    proxy: {
      // GAME_SERVER=ws://localhost:8081 npm run dev — якщо сервер на іншому порту.
      '/ws': { target: process.env.GAME_SERVER ?? 'ws://localhost:8080', ws: true },
    },
  },
})
