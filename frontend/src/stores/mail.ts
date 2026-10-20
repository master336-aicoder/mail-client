import { defineStore } from 'pinia'
import { folderApi, mailApi, type FolderNode, type MessageItem, type MessagePage } from '@/api'
import { useNotificationStore } from '@/stores/notification'

let ws: WebSocket | null = null

export const useMailStore = defineStore('mail', {
  state: () => ({
    folders: [] as FolderNode[],
    accounts: [] as any[],
    currentFolderId: 0 as number,
    currentQuery: {} as Record<string, any>,
    messages: [] as MessageItem[],
    total: 0,
    loading: false,
    connected: false,
    pushEnabled: true,
    toast: '' as string
  }),
  actions: {
    async loadFolders() {
      const res: any = await folderApi.tree()
      this.folders = res.data || res
    },
    async loadAccounts() {
      const { accountApi } = await import('@/api')
      this.accounts = await accountApi.list()
    },
    async loadMessages(folderId: number, page = 1, size = 50, query?: Record<string, any>) {
      this.loading = true
      this.currentFolderId = folderId
      if (query) this.currentQuery = query
      try {
        const res: any = await mailApi.list(folderId, page, size, this.currentQuery)
        const pageData: MessagePage = res.list ? res : { list: res, total: res.length }
        this.messages = pageData.list || []
        this.total = pageData.total || 0
      } finally {
        this.loading = false
      }
    },
    async setRead(id: number, read: boolean) {
      await mailApi.updateFlags(id, { read })
      const m = this.messages.find((x) => x.id === id)
      if (m) {
        m.read = read
        m.flags = read ? (m.flags || 0) | 1 : (m.flags || 0) & ~1
      }
    },
    async toggleStar(id: number) {
      const m = this.messages.find((x) => x.id === id)
      if (!m) return
      const starred = !((m.flags || 0) & 2)
      await mailApi.updateFlags(id, { starred })
      m.flags = starred ? (m.flags || 0) | 2 : (m.flags || 0) & ~2
    },
    async remove(id: number) {
      await mailApi.remove(id)
      this.messages = this.messages.filter((m) => m.id !== id)
    },
    connect() {
      if (ws || !this.pushEnabled) return
      // 鉴权改由 HttpOnly 会话 Cookie 承载，握手自动携带，无需在 URL 暴露 token
      const proto = location.protocol === 'https:' ? 'wss' : 'ws'
      ws = new WebSocket(`${proto}://${location.host}/api/ws`)
      ws.onopen = () => {
        this.connected = true
      }
      ws.onmessage = (ev) => {
        try {
          const msg = JSON.parse(ev.data)
          if (msg.type === 'MAIL_NEW') {
            this.toast = `收到新邮件：${msg.data?.subject || ''}`
            // F17: 保留当前搜索/翻页状态，避免新邮件推送覆盖用户当前视图
            if (this.currentFolderId) this.loadMessages(this.currentFolderId, 1, 50, this.currentQuery)
          }
          // 站内信式通知：进铃铛列表，同时保留一条轻提示
          if (msg.type === 'NOTIFICATION') {
            useNotificationStore().receive(msg.data)
            this.toast = msg.data?.title || '收到一条新通知'
          }
        } catch {
          /* ignore */
        }
      }
      ws.onclose = () => {
        this.connected = false
        ws = null
        // F11: 仅在推送开启且非主动断开时重连，避免 WebSocket 泄漏
        if (this.pushEnabled) {
          setTimeout(() => this.connect(), 5000)
        }
      }
      ws.onerror = () => ws?.close()
    },
    disconnect() {
      if (ws) {
        ws.onclose = null
        ws.close()
        ws = null
      }
      this.connected = false
    },
    setPushEnabled(enabled: boolean) {
      this.pushEnabled = enabled
      if (enabled) {
        this.connect()
      } else {
        this.disconnect()
      }
    }
  }
})