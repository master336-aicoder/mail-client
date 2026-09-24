import http from './http'

export interface LoginReq {
  username: string
  password: string
}
export interface RegisterReq {
  username: string
  password: string
  confirmPassword?: string
  email?: string
}
export interface UserInfo {
  id: number
  username: string
  email?: string
  role: string
}

export const authApi = {
  login: (data: LoginReq) => http.post('/auth/login', data),
  register: (data: RegisterReq) => http.post('/auth/register', data),
  me: () => http.get('/auth/me'),
  changePassword: (data: { oldPassword: string; newPassword: string }) => http.post('/auth/password', data)
}

export interface AutoDetectReq {
  email: string
}
export interface ProbeResult {
  protocol: string
  host: string
  port: number
  encryption: string
  smtpHost: string
  smtpPort: number
  smtpEncryption: string
}

export interface AccountReq {
  email: string
  displayName?: string
  protocol: string
  host: string
  port: number
  encryption: string
  authType: string
  username: string
  secret: string
  smtpHost?: string
  smtpPort?: number
  smtpEncryption?: string
}

export interface MailAccount {
  id: number
  email: string
  displayName?: string
  protocol: string
  host: string
  port: number
  encryption: string
  username: string
  active: boolean
  syncIntervalSec?: number
  lastSyncedAt?: string
}

export const accountApi = {
  autoDetect: (data: AutoDetectReq) => http.post('/accounts/auto-detect', data),
  list: () => http.get('/accounts'),
  create: (data: AccountReq) => http.post('/accounts', data),
  update: (id: number, data: { syncIntervalSec?: number; active?: boolean }) =>
    http.put(`/accounts/${id}`, data),
  remove: (id: number) => http.delete(`/accounts/${id}`)
}

export interface FolderNode {
  id: number
  name: string
  nameZh?: string
  accountId?: number | null
  accountEmail?: string
  isMerged?: boolean
  total: number
  unread: number
  children?: FolderNode[]
}

export const folderApi = {
  tree: () => http.get('/folders') as Promise<{ code: number; data: FolderNode[] }>,
  create: (data: any) => http.post('/folders', data),
  remove: (id: number) => http.delete(`/folders/${id}`)
}

export interface MessageItem {
  id: number
  folderId: number
  subject: string
  sender: string
  senderEmail: string
  date: string
  flags: number
  read: boolean
  hasAttach: boolean
  preview?: string
  size?: number
}
export interface MessagePage {
  total: number
  list: MessageItem[]
}

export const mailApi = {
  list: (folderId: number, page = 1, size = 50, query?: Record<string, any>) =>
    http.get(`/folders/${folderId}/messages`, { params: { page, size, ...query } }),
  detail: (id: number) => http.get(`/messages/${id}`),
  send: (data: any) => http.post('/messages', data),
  saveDraft: (data: any) => http.post('/messages/draft', data),
  updateFlags: (id: number, flags: { read?: boolean; starred?: boolean; important?: boolean }) =>
    http.put(`/messages/${id}/flags`, flags),
  remove: (id: number) => http.delete(`/messages/${id}`)
}

export interface Rule {
  id: number
  name: string
  accountId?: number
  priority: number
  conditions: string
  actions: string
  enabled: boolean
}

export const ruleApi = {
  list: () => http.get('/rules'),
  create: (data: any) => http.post('/rules', data),
  update: (id: number, data: any) => http.put(`/rules/${id}`, data),
  toggle: (id: number, enabled: boolean) => http.put(`/rules/${id}/toggle`, null, { params: { enabled } }),
  remove: (id: number) => http.delete(`/rules/${id}`),
  reorder: (ids: number[]) => http.post('/rules/reorder', { ids })
}

export const transferApi = {
  startExport: (accountId: number, folderIds: number[]) =>
    http.post('/transfer/export', { accountId, folderIds }),
  status: (taskId: string) => http.get(`/transfer/${taskId}`) as Promise<TransferStatus>,
  downloadUrl: (taskId: string) => `/api/transfer/${taskId}/download`
}

export interface TransferStatus {
  taskId?: string
  status?: string
  progress?: number
  message?: string
  downloadUrl?: string
}

export const syncApi = {
  trigger: (accountId: number) => http.post('/sync', null, { params: { accountId } }),
  all: () => http.post('/sync/all')
}

export const settingsApi = {
  get: () => http.get('/settings'),
  update: (data: { defaultSyncIntervalSec: number }) => http.put('/settings', data)
}

export interface TaskItem {
  id: number
  name: string
  type: string
  status: string
  progress: number
  startedAt?: string
  finishedAt?: string
  durationMs?: number
  message?: string
  error?: string
  accountId?: number
}

export const taskApi = {
  list: () => http.get('/tasks') as Promise<{ code: number; data: TaskItem[] }>,
  retry: (id: number) => http.post(`/tasks/${id}/retry`),
  cancel: (id: number) => http.post(`/tasks/${id}/cancel`)
}

export const adminApi = {
  list: () => http.get('/admin/users'),
  setRole: (id: number, role: string) => http.put(`/admin/users/${id}/role`, { role }),
  accounts: () => http.get('/admin/accounts')
}