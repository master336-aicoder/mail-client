import { defineStore } from 'pinia'
import { notificationApi, type NotificationItem } from '@/api'

/**
 * 消息通知（站内信）：顶栏铃铛的数据源，同时接收 WebSocket 的 NOTIFICATION 事件。
 */
export const useNotificationStore = defineStore('notification', {
  state: () => ({
    items: [] as NotificationItem[],
    total: 0,
    unread: 0,
    loading: false,
    size: 20
  }),
  actions: {
    async load(page = 1) {
      this.loading = true
      try {
        const res: any = await notificationApi.list({ page, size: this.size })
        this.items = res?.list || []
        this.total = res?.total || 0
      } finally {
        this.loading = false
      }
    },
    async loadUnread() {
      const res: any = await notificationApi.unreadCount()
      this.unread = res?.count || 0
    },
    /** 实时推送入箱：重复 id 忽略，超出当前页长度的尾部丢弃即可，下次打开会重新拉取。 */
    receive(n: NotificationItem) {
      if (!n?.id || this.items.some((x) => x.id === n.id)) return
      this.items.unshift(n)
      if (this.items.length > this.size) this.items.splice(this.items.length - 1)
      this.total++
      if (!n.read) this.unread++
    },
    async markRead(id: number) {
      const item = this.items.find((x) => x.id === id)
      if (!item || item.read) return
      await notificationApi.markRead(id)
      item.read = true
      this.unread = Math.max(0, this.unread - 1)
    },
    async markAllRead() {
      await notificationApi.markAllRead()
      this.items.forEach((x) => (x.read = true))
      this.unread = 0
    },
    async remove(ids: number[]) {
      if (!ids.length) return
      await notificationApi.batchDelete(ids)
      const gone = this.items.filter((x) => ids.includes(x.id))
      this.items = this.items.filter((x) => !ids.includes(x.id))
      this.total = Math.max(0, this.total - gone.length)
      this.unread = Math.max(0, this.unread - gone.filter((x) => !x.read).length)
    },
    async clearRead() {
      const res: any = await notificationApi.clearRead()
      const removed = res?.deleted || 0
      this.items = this.items.filter((x) => !x.read)
      this.total = Math.max(0, this.total - removed)
    }
  }
})

/** 通知类型对应的中文名：后端只存枚举串，展示口径统一放这里。 */
export const NOTIF_TYPES: Record<string, string> = {
  MAIL_NEW: '新邮件',
  RECEIVE_FAILED: '收信异常',
  SEND_FAILED: '发信异常',
  FOLDER_CLEARED: '文件夹清空',
  ACCOUNT_DELETED: '账户移除',
  ACCOUNT_RESET: '账户重置',
  ANNOUNCEMENT: '站内公告'
}

export function notifTypeLabel(t?: string) {
  return (t && NOTIF_TYPES[t]) || '通知'
}

export function notifTagType(t?: string) {
  if (!t) return 'info'
  if (t === 'ANNOUNCEMENT') return 'primary'
  if (t.endsWith('_FAILED')) return 'danger'
  if (t === 'MAIL_NEW') return 'success'
  return 'warning'
}
