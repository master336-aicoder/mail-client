import { fileURLToPath, URL } from 'node:url'
import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'
import { VitePWA } from 'vite-plugin-pwa'

export default defineConfig({
  plugins: [
    vue(),
    VitePWA({
      registerType: 'autoUpdate',
      includeAssets: ['favicon.svg'],
      workbox: {
        // 后端接口/推送/监控是真实请求，不能被 SPA 的 index.html 兜底页拦截，
        // 否则浏览器直接打开下载链接只会拿到前端壳子并跳回邮件页。
        navigateFallbackDenylist: [/^\/api/, /^\/ws/, /^\/actuator/]
      },
      manifest: {
        name: '云邮 Mail',
        short_name: '云邮',
        description: 'Web 邮箱客户端（多用户 · IMAP/POP3/SMTP · 离线可用）',
        theme_color: '#2563EB',
        background_color: '#F9FAFB',
        display: 'standalone',
        icons: [
          { src: 'favicon.svg', sizes: 'any', type: 'image/svg+xml' }
        ]
      }
    })
  ],
  resolve: {
    alias: {
      '@': fileURLToPath(new URL('./src', import.meta.url))
    }
  },
  server: {
    port: 5173,
    proxy: {
      '/api': {
        target: 'http://localhost:8080',
        changeOrigin: true,
        ws: true
      }
    }
  }
})