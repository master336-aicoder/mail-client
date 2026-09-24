import { defineStore } from 'pinia'
import { authApi, type UserInfo } from '@/api'

export const useAuthStore = defineStore('auth', {
  state: () => ({
    // 令牌不再由前端持有（HttpOnly Cookie 承载）；authed 仅为路由用的非敏感标记
    authed: localStorage.getItem('authed') === '1',
    user: (localStorage.getItem('user') ? JSON.parse(localStorage.getItem('user')!) : null) as UserInfo | null
  }),
  getters: {
    isAuthenticated: (s) => s.authed,
    isAdmin: (s) => s.user?.role === 'ADMIN'
  },
  actions: {
    async login(username: string, password: string) {
      const data: any = await authApi.login({ username, password })
      this.user = data.user
      this.authed = true
      localStorage.setItem('authed', '1')
      localStorage.setItem('user', JSON.stringify(data.user))
    },
    async register(username: string, password: string, email?: string, confirmPassword?: string) {
      await authApi.register({ username, password, confirmPassword: confirmPassword ?? password, email })
    },
    async fetchMe() {
      try {
        this.user = await authApi.me()
        localStorage.setItem('user', JSON.stringify(this.user))
      } catch {
        /* 会话失效由 http 拦截器处理 */
      }
    },
    async logout() {
      // 先在前端即时清理标记，避免路由守卫在读到 authed=true 时把 /login 重定向回 /mail
      this.authed = false
      this.user = null
      localStorage.removeItem('authed')
      localStorage.removeItem('user')
      try {
        await authApi.logout()
      } catch {
        /* 忽略登出接口异常 */
      }
    }
  }
})
