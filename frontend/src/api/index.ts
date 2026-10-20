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
  logout: () => http.post('/auth/logout'),
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
  /** 接受此证书 / 添加安全例外：忽略 TLS 证书校验错误 */
  ignoreCertError?: boolean
}

export interface MailAccount {
  id: number
  email: string
  displayName?: string
  protocol: string
  host: string
  port: number
  encryption: string
  smtpHost?: string
  smtpPort?: number
  smtpEncryption?: string
  authType?: string
  username: string
  active: boolean
  syncIntervalSec?: number
  /** 收信排程：INTERVAL 按间隔 / DAILY 每天定点 */
  syncMode?: 'INTERVAL' | 'DAILY' | string
  /** DAILY 模式的每日时刻，后端返回 HH:mm:ss */
  syncDailyTime?: string
  lastSyncedAt?: string
  ignoreCertError?: boolean
}

// 全量可更新配置；null/缺省字段后端保持原值不变
export interface AccountUpdateReq {
  protocol?: string
  email?: string
  displayName?: string
  host?: string
  port?: number
  encryption?: 'SSL' | 'STARTTLS' | 'NONE' | string
  smtpHost?: string
  smtpPort?: number
  smtpEncryption?: 'SSL' | 'STARTTLS' | 'NONE' | string
  username?: string
  secret?: string
  syncIntervalSec?: number
  syncMode?: 'INTERVAL' | 'DAILY' | string
  /** HH:mm，仅 syncMode=DAILY 时必填 */
  syncDailyTime?: string
  active?: boolean
  ignoreCertError?: boolean
}

export interface AccountTestResult {
  receiveOk: boolean
  sendOk: boolean
  receiveError: string | null
  sendError: string | null
  /** 实际被测试的端点，如 IMAP host:993（SSL 隐式 TLS），便于确认测的是哪一侧配置 */
  receiveTarget?: string
  sendTarget?: string
  allOk?: boolean
}

/** 连接测试要跑完两次真实网络握手（各有 15s 连接超时），单独放宽超时避免半途报「网络错误」 */
const TEST_TIMEOUT = { timeout: 100_000 }
export const accountApi = {
  autoDetect: (data: AutoDetectReq) => http.post('/accounts/auto-detect', data),
  list: () => http.get('/accounts'),
  create: (data: AccountReq) => http.post('/accounts', data),
  update: (id: number, data: AccountUpdateReq) => http.put(`/accounts/${id}`, data),
  test: (id: number) => http.post(`/accounts/${id}/test`, undefined, TEST_TIMEOUT) as Promise<AccountTestResult>,
  /** 未保存也能测：直接提交表单里的配置；密码留空时靠 accountId 复用已存密码。
   *  失败原因由调用方在结果面板里逐行展示，故关掉全局 toast 免重复。 */
  testConfig: (data: AccountUpdateReq & { accountId?: number | null }) =>
    http.post('/accounts/test-config', data, { ...TEST_TIMEOUT, silentError: true }) as Promise<AccountTestResult>,
  remove: (id: number) => http.delete(`/accounts/${id}`),
  /** 清空该账户本地所有文件夹的邮件与附件，并立即触发重新拉取（不会删除服务器邮件） */
  reset: (id: number) => http.post(`/accounts/${id}/reset`) as Promise<{
    accountId: number; messages: number; attachments: number; files: number
  }>
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
  update: (id: number, data: any) => http.put(`/folders/${id}`, data),
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
  updateDraft: (id: number, data: any) => http.put(`/messages/draft/${id}`, data),
  updateFlags: (id: number, flags: { read?: boolean; starred?: boolean; important?: boolean }) =>
    http.put(`/messages/${id}/flags`, flags),
  remove: (id: number) => http.delete(`/messages/${id}`),
  /** 批量标记：只改传入的项，返回 {updated} */
  batchFlags: (ids: number[], flags: { read?: boolean; starred?: boolean; important?: boolean }) =>
    http.post('/messages/batch-flags', { ids, ...flags }) as Promise<{ updated: number }>,
  batchDelete: (ids: number[]) => http.post('/messages/batch-delete', { ids }) as Promise<{ deleted: number }>,
  /** 批量移动到账户级文件夹，返回 {moved} */
  batchMove: (ids: number[], folderId: number) =>
    http.post('/messages/batch-move', { ids, folderId }) as Promise<{ moved: number }>,
  /** 整个文件夹标记已读（合并视图含各账户同名文件夹） */
  markFolderRead: (folderId: number) =>
    http.post(`/folders/${folderId}/mark-read`) as Promise<{ updated: number }>,
  clearFolder: (folderId: number) =>
    http.post(`/folders/${folderId}/clear`) as Promise<{ deleted: number }>
}

export interface NotificationItem {
  id: number
  type: string
  title: string
  content?: string
  link?: string
  read: boolean
  createdAt: string
}

export const notificationApi = {
  list: (params?: { page?: number; size?: number; unreadOnly?: boolean; type?: string; keyword?: string }) =>
    http.get('/notifications', { params }) as Promise<TaskPage<NotificationItem>>,
  unreadCount: () => http.get('/notifications/unread-count') as Promise<{ count: number }>,
  markRead: (id: number) => http.put(`/notifications/${id}/read`),
  markAllRead: () => http.put('/notifications/read-all') as Promise<{ updated: number }>,
  batchDelete: (ids: number[]) =>
    http.post('/notifications/batch-delete', { ids }) as Promise<{ deleted: number }>,
  clearRead: () => http.delete('/notifications/read') as Promise<{ deleted: number }>
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
  startExport: (accountId: number, folderIds: number[], format: 'zip' | 'mbox' = 'zip', config?: { silentError?: boolean }) =>
    http.post('/transfer/export', { accountId, folderIds, format }, config),
  /** 上传 PST 文件，返回 { uploadId, fileName, size } */
  uploadImport: (file: File, config?: { silentError?: boolean }) => {
    const form = new FormData()
    form.append('file', file)
    return http.post('/transfer/import/upload', form, config) as Promise<{
      uploadId: string; fileName: string; size: number
    }>
  },
  /** 提交导入任务：把已上传的 PST 导入到指定账户的文件夹 */
  startImport: (data: { uploadId: string; accountId: number; folderName?: string }) =>
    http.post('/transfer/import', data) as Promise<{ taskId: number }>,
  status: (taskId: number) => http.get(`/transfer/${taskId}`) as Promise<TransferStatus>
}

export interface TransferStatus {
  taskId?: number
  status?: string
  progress?: number
  message?: string
  error?: string
  result?: string
  finished?: boolean
  attachments?: TaskAttachment[]
  downloadUrl?: string
}

export interface AttachmentMeta {
  id: number
  name: string
  mimeType?: string
  size: number
}

export const attachmentApi = {
  upload: (file: File, accountId?: number, config?: { silentError?: boolean }) => {
    const form = new FormData()
    form.append('file', file)
    if (accountId != null) form.append('accountId', String(accountId))
    return http.post('/attachments', form, config) as Promise<AttachmentMeta>
  },
  copy: (id: number, config?: { silentError?: boolean }) =>
    http.post(`/attachments/${id}/copy`, null, config) as Promise<AttachmentMeta>,
  downloadUrl: (id: number) => `/api/attachments/${id}/content`
}

export const syncApi = {
  trigger: (accountId: number) => http.post('/sync', null, { params: { accountId } }),
  all: () => http.post('/sync/all')
}

export const settingsApi = {
  get: () => http.get('/settings'),
  update: (data: {
    defaultSyncIntervalSec: number
    runRetentionScope?: string
    runRetentionDays?: number
    theme?: string
  }) => http.put('/settings', data)
}

export interface TaskItem {
  id: number
  name: string
  type: string
  accountId?: number
  accountEmail?: string
  periodic: boolean
  enabled: boolean
  scheduleSec?: number
  effectiveScheduleSec?: number
  /** 排程方式与每日定点时刻（账户同步任务） */
  scheduleMode?: 'INTERVAL' | 'DAILY' | string
  dailyTime?: string
  createdAt?: string
  lastRunAt?: string
  nextRunAt?: string
  /** 以下来自最近一次执行记录 */
  lastRunId?: number
  status?: string
  progress?: number
  message?: string
  error?: string
  result?: string
  triggerType?: string
  startedAt?: string
  finishedAt?: string
  durationMs?: number
  attachmentCount?: number
}

export interface TaskRunItem {
  id: number
  taskId: number
  status: string
  progress?: number
  message?: string
  error?: string
  result?: string
  triggerType?: string
  startedAt?: string
  finishedAt?: string
  durationMs?: number
  createdAt?: string
  attachments: TaskAttachment[]
}

export interface TaskAttachment {
  id: number
  name: string
  size?: number
  contentType?: string
  createdAt?: string
  downloadUrl: string
  available: boolean
}

export interface TaskPage<T = TaskItem> {
  total: number
  page: number
  size: number
  list: T[]
}

export const taskApi = {
  list: (params?: { page?: number; size?: number; type?: string }) =>
    http.get<TaskPage>('/tasks', { params }),
  runs: (id: number, params?: { page?: number; size?: number }) =>
    http.get<TaskPage<TaskRunItem>>(`/tasks/${id}/runs`, { params }),
  status: (id: number) => http.get(`/tasks/${id}/status`),
  batchDeleteRuns: (data: { ids?: number[]; taskId?: number }) =>
    http.post('/tasks/runs/batch-delete', data) as Promise<{ deleted: number }>,
  batchExportRuns: (data: { ids?: number[]; taskId?: number }) =>
    http.post('/tasks/runs/batch-export', data) as Promise<{ taskId: number }>,
  run: (id: number) => http.post(`/tasks/${id}/run`),
  cancel: (id: number) => http.post(`/tasks/${id}/cancel`),
  enable: (id: number) => http.post(`/tasks/${id}/enable`),
  disable: (id: number) => http.post(`/tasks/${id}/disable`),
  remove: (id: number) => http.post('/tasks/batch-delete', { ids: [id] }) as Promise<{ deleted: number }>,
  batchRun: (ids: number[]) => http.post('/tasks/batch-run', { ids }) as Promise<{ started: number }>,
  batchCancel: (ids: number[]) => http.post('/tasks/batch-cancel', { ids }) as Promise<{ cancelled: number }>,
  batchEnable: (ids: number[]) => http.post('/tasks/batch-enable', { ids }) as Promise<{ updated: number }>,
  batchDisable: (ids: number[]) => http.post('/tasks/batch-disable', { ids }) as Promise<{ updated: number }>,
  batchDelete: (ids: number[]) => http.post('/tasks/batch-delete', { ids }) as Promise<{ deleted: number }>,
  /** 任务级记录策略；传 null 表示该项跟随全局策略。 */
  setRunPolicy: (id: number, scope: string | null, days: number | null) =>
    http.post(`/tasks/${id}/run-policy`, { runRetentionScope: scope, runRetentionDays: days }),
  attachmentUrl: (id: number) => `/api/task-attachments/${id}/download`,
  /** 运行时信息：定时调度开关（启动参数）+ 守护进程状态。 */
  runtime: () =>
    http.get('/tasks/runtime', { silentError: true } as any) as Promise<TaskRuntime>,
  /** 立即触发一次守护巡检，返回本轮回收条数。 */
  watchdogSweep: () =>
    http.post('/tasks/watchdog-sweep') as Promise<{ recovered: number }>
}

/** 后台任务运行时信息，与后端 /api/tasks/runtime 对齐 */
export interface TaskRuntime {
  schedulingEnabled: boolean
  watchdog: {
    active: boolean
    intervalSec: number
    lastSweepAt?: string
    totalRecovered: number
  }
}

export interface AnnouncementItem {
  batchNo: string
  title: string
  content?: string
  link?: string
  senderId?: number
  createdAt?: string
  /** 送达人数 */
  delivered: number
  /** 已读人数 */
  read: number
}

export interface AnnouncementReq {
  title: string
  content?: string
  link?: string
  targetAll: boolean
  userIds?: number[]
}

export const adminApi = {
  list: () => http.get('/admin/users') as Promise<Array<{ id: number; username: string; email?: string; role?: string }>>,
  setRole: (id: number, role: string) => http.put(`/admin/users/${id}/role`, { role }),
  accounts: () => http.get('/admin/accounts'),
  publishAnnouncement: (data: AnnouncementReq) =>
    http.post('/admin/announcements', data) as Promise<{ sent: number; targetAll: boolean }>,
  announcements: (limit = 20) =>
    http.get('/admin/announcements', { params: { limit } }) as Promise<AnnouncementItem[]>,
  /** 系统状态实时采样（CPU/内存/磁盘/网络/JVM） */
  systemStatus: () => http.get('/admin/system-status', { silentError: true } as any) as Promise<SystemStatus>,
  systemAlertConfig: () => http.get('/admin/system-alert-config', { silentError: true } as any) as Promise<SystemAlertConfig>,
  updateSystemAlertConfig: (data: Partial<SystemAlertConfig>) =>
    http.put('/admin/system-alert-config', data) as Promise<SystemAlertConfig>,
  systemAlertCheck: () =>
    http.post('/admin/system-alert-check') as unknown as Promise<{
      enabled: boolean
      fired: Array<{ metric: string; value: number; threshold: number; note: string }>
      suppressed: Array<{ metric: string; value: number; threshold: number; note: string }>
      message?: string
    }>,
  systemAlertResetCooldown: () =>
    http.post('/admin/system-alert-reset-cooldown') as unknown as Promise<SystemAlertConfig>
}

/** 与后端 com.mail.monitor.SystemStatus 对齐的前端类型 */
export interface SystemStatus {
  sampledAt: number
  cpu?: { cores: number; systemLoadPct?: number; processLoadPct?: number; load1?: number; load5?: number; load15?: number }
  memory?: { totalBytes: number; freeBytes: number; usedBytes: number; usedPct?: number; osAvailableBytes?: number }
  disks?: Array<{ path: string; label: string; totalBytes: number; usableBytes: number; usedBytes: number; usedPct?: number }>
  network?: Array<{ name: string; displayName?: string; up: boolean; loopback: boolean; addresses?: string[] }>
  jvm?: { name?: string; vendor?: string; version?: string; uptimeMs: number; startTime: number; threads: number;
    heapUsedBytes: number; heapCommittedBytes: number; heapMaxBytes: number; pools?: Record<string, number> }
}

export interface SystemAlertConfig {
  id?: number
  enabled: boolean
  cpuThresholdPct: number
  memThresholdPct: number
  diskThresholdPct: number
  checkIntervalMin: number
  cooldownMin: number
  lastCpuAlertAt?: string
  lastMemAlertAt?: string
  lastDiskAlertAt?: string
}