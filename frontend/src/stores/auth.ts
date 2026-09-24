import { defineStore } from 'pinia'
import { authApi, type UserInfo } from '@/api'

export const useAuthStore = defineStore('auth', {
  state: () => ({
    token: localStorage.getItem('access_token') || '',
    user: (localStorage.getItem('user') ? JSON.parse(localStorage.getItem('user')!) : null) as UserInfo | null
  }),
  getters: {
    isAuthenticated: (s) => !!s.token,
    isAdmin: (s) => s.user?.role === 'ADMIN'
  },
  actions: {
    async login(username: string, password: string) {
      const data: any = await authApi.login({ username, password })
      this.token = data.accessToken
      this.user = data.user
      localStorage.setItem('access_token', data.accessToken)
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
        /* token 失效由 http 拦截器处理 */
      }
    },
    logout() {
      this.token = ''
      this.user = null
      localStorage.removeItem('access_token')
      localStorage.removeItem('user')
    }
  }
})