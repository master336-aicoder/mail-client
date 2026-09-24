import axios from 'axios'
import { ElMessage } from 'element-plus'
import router from '@/router'

const http = axios.create({
  baseURL: '/api',
  timeout: 30000
})

http.interceptors.request.use((config) => {
  const token = localStorage.getItem('access_token')
  if (token) {
    config.headers.Authorization = `Bearer ${token}`
  }
  return config
})

http.interceptors.response.use(
  (resp) => {
    const body = resp.data
    if (body && body.code !== undefined && body.code !== 0) {
      ElMessage.error(body.message || '请求失败')
      return Promise.reject(new Error(body.message))
    }
    return body?.data ?? body
  },
  (error) => {
    const status = error.response?.status
    // 401：未认证/会话失效（token 缺失、过期、无效）→ 清空并跳转登录页
    if (status === 401) {
      localStorage.removeItem('access_token')
      localStorage.removeItem('user')
      if (router.currentRoute.value.name !== 'login') {
        router.push('/login')
      }
      ElMessage.error('登录状态已失效，请重新登录')
      return Promise.reject(error)
    }
    // 403：已登录但无权限（如普通用户访问管理员接口）→ 仅提示，不清除会话、不跳转登录
    const msg = error.response?.data?.message || error.message || '网络错误'
    if (status === 403) {
      ElMessage.error(msg || '没有权限访问')
      return Promise.reject(error)
    }
    ElMessage.error(msg)
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
    // 保留默认签名，避免改动其余用法
  }
}