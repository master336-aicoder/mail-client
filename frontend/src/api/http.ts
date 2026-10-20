import axios from 'axios'
import { ElMessage } from 'element-plus'
import router from '@/router'

const http = axios.create({
  baseURL: '/api',
  timeout: 30000,
  withCredentials: true // 随请求携带 HttpOnly 会话 Cookie
})

function readCookie(name: string): string | null {
  const m = document.cookie.match(new RegExp('(^|;\\s*)' + name + '=([^;]*)'))
  return m ? decodeURIComponent(m[2]) : null
}

const SAFE_METHODS = ['get', 'head', 'options']

http.interceptors.request.use((config) => {
  // 令牌改由 HttpOnly Cookie 承载，前端不再持有/发送 Bearer；
  // 非安全方法回传 CSRF 头（双重提交），与后端 csrf_token Cookie 比对。
  const method = (config.method || 'get').toLowerCase()
  if (!SAFE_METHODS.includes(method)) {
    const csrf = readCookie('csrf_token')
    if (csrf) config.headers['X-CSRF-Token'] = csrf
  }
  return config
})

/** 把传输层（非业务）错误也换成中文可读、可行动的提示，避免英文原文出现在界面上。 */
function friendlyError(error: any): string {
  const status = error.response?.status
  if (!error.response) {
    if (error.code === 'ECONNABORTED' || /timeout/i.test(error.message || '')) {
      return '请求超时：服务器响应过慢，请检查网络后重试'
    }
    return '无法连接服务器：服务可能未启动，或本机网络已中断'
  }
  // 后端统一返回中文 message（含异常翻译），优先展示
  const body = error.response.data?.message
  if (typeof body === 'string' && body.trim()) return body.trim()
  if (status === 403) return '没有权限执行该操作'
  if (status === 404) return '请求的资源不存在，请刷新页面后重试'
  if (status && status >= 500) return `服务器处理失败（错误码 ${status}），请稍后重试`
  return `请求失败（错误码 ${status ?? '未知'}）`
}

// 同一批并行 401 只弹一次「登录失效」提示，避免登录后旧会话残留请求触发连串 toast。
let authExpiredToastAt = 0
function notifyAuthExpired() {
  const now = Date.now()
  if (now - authExpiredToastAt < 3000) return
  authExpiredToastAt = now
  ElMessage.error('登录状态已失效，请重新登录')
}

http.interceptors.response.use(
  (resp) => {
    const body = resp.data
    if (body && body.code !== undefined && body.code !== 0) {
      // silentError 由调用方显式声明「我自己提示」，避免同一错误弹两次
      if (!resp.config?.silentError) ElMessage.error(body.message || '操作失败，请重试')
      const err: any = new Error(body.message || '操作失败')
      err.code = body.code
      err.silent = !!resp.config?.silentError
      return Promise.reject(err)
    }
    return body?.data ?? body
  },
  (error) => {
    const status = error.response?.status
    // 401：未认证/会话失效（Cookie 缺失、过期、无效）→ 清空并跳转登录页
    if (status === 401) {
      localStorage.removeItem('authed')
      localStorage.removeItem('user')
      if (router.currentRoute.value.name !== 'login') {
        router.push('/login')
      }
      notifyAuthExpired()
      return Promise.reject(error)
    }
    // 403：已登录但无权限（如普通用户访问管理员接口）→ 仅提示，不清除会话、不跳转登录
    const msg = friendlyError(error)
    if (!error.config?.silentError) ElMessage.error(msg)
    error.message = msg
    return Promise.reject(error)
  }
)

export default http

// 由于响应拦截器已把 {code,message,data} 信封解包为 data，
// 这里增强 axios 泛型，使 http.get<T>() 直接返回 Promise<T> 而非 AxiosResponse<T>。
declare module 'axios' {
  export interface AxiosInstance {
    get<T = any>(url: string, config?: AxiosRequestConfig): Promise<T>
    delete<T = any>(url: string, config?: AxiosRequestConfig): Promise<T>
    post<T = any>(url: string, data?: any, config?: AxiosRequestConfig): Promise<T>
    put<T = any>(url: string, data?: any, config?: AxiosRequestConfig): Promise<T>
  }
  export interface AxiosRequestConfig {
    // 为 true 时拦截器不弹全局错误提示，由调用方自行呈现（避免重复弹窗）
    silentError?: boolean
  }
}