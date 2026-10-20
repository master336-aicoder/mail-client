<template>
  <div class="settings-page">
    <h2 class="page-title">通用设置</h2>

    <el-card class="card" shadow="never" style="margin-top: 16px">
      <template #header><div class="card-head">外观</div></template>
      <el-form label-width="120px">
        <el-form-item label="主题模式">
          <el-radio-group v-model="theme">
            <el-radio value="auto">跟随系统</el-radio>
            <el-radio value="light">浅色</el-radio>
            <el-radio value="dark">深色</el-radio>
          </el-radio-group>
          <span class="hint">与当前登录用户绑定，换设备或清缓存后仍会恢复</span>
        </el-form-item>
      </el-form>
    </el-card>

    <el-card class="card" shadow="never" style="margin-top: 16px">
      <template #header><div class="card-head">离线与推送</div></template>
      <el-form label-width="120px">
        <el-form-item label="实时推送">
          <el-switch v-model="push" active-text="开启" inactive-text="关闭" />
        </el-form-item>
        <el-form-item label="邮件收取频率">
          <el-input-number v-model="syncValue" :min="1" :max="syncMax" />
          <el-select v-model="syncUnit" style="width: 88px; margin-left: 8px">
            <el-option label="分钟" value="m" />
            <el-option label="小时" value="h" />
            <el-option label="天" value="d" />
          </el-select>
          <span class="hint">账户未单独设置时按此频率自动收取新邮件</span>
          <el-button type="primary" size="small" :loading="syncLoading" @click="saveSync" style="margin-left: 12px">
            保存
          </el-button>
        </el-form-item>
        <el-form-item label="安装为应用">
          <el-button @click="install">安装 PWA</el-button>
          <span class="hint">安装后可离线访问、从桌面启动</span>
        </el-form-item>
      </el-form>
    </el-card>

    <el-card class="card" shadow="never" style="margin-top: 16px">
      <template #header><div class="card-head">账户信息</div></template>
      <el-descriptions :column="1" border v-if="info">
        <el-descriptions-item label="用户名">{{ info.username }}</el-descriptions-item>
        <el-descriptions-item label="邮箱">{{ info.email || '未绑定' }}</el-descriptions-item>
        <el-descriptions-item label="角色">{{ info.role }}</el-descriptions-item>
      </el-descriptions>
    </el-card>

    <el-card class="card" shadow="never" style="margin-top: 16px">
      <template #header><div class="card-head">修改密码</div></template>
      <el-form :model="pwdForm" label-width="120px" style="max-width: 460px">
        <el-form-item label="当前密码">
          <el-input v-model="pwdForm.oldPassword" type="password" show-password placeholder="请输入当前密码" />
        </el-form-item>
        <el-form-item label="新密码">
          <el-input v-model="pwdForm.newPassword" type="password" show-password placeholder="至少 8 位" />
        </el-form-item>
        <el-form-item label="确认新密码">
          <el-input v-model="pwdForm.confirm" type="password" show-password placeholder="再次输入新密码" />
        </el-form-item>
        <el-form-item>
          <el-button type="primary" :loading="changing" @click="changePassword">保存新密码</el-button>
        </el-form-item>
      </el-form>
    </el-card>

    <!-- 以下仅管理员可见：原「系统管理」迁入 -->
    <el-card v-if="auth.isAdmin" class="card" shadow="never" style="margin-top: 16px">
      <template #header><div class="card-head">服务状态</div></template>
      <el-descriptions :column="2" border v-loading="healthLoading">
        <el-descriptions-item label="服务端">
          <el-tag :type="health.server === 'UP' ? 'success' : 'danger'" size="small">{{ health.server }}</el-tag>
        </el-descriptions-item>
        <el-descriptions-item label="数据库">
          <el-tag :type="health.db === 'UP' ? 'success' : 'danger'" size="small">{{ health.db }}</el-tag>
        </el-descriptions-item>
        <el-descriptions-item label="实时推送">
          <el-tag :type="mail.connected ? 'success' : 'info'" size="small">
            {{ mail.connected ? '已连接' : '未连接' }}
          </el-tag>
        </el-descriptions-item>
        <el-descriptions-item label="附件存储">
          <el-tag size="small" type="info">{{ health.storage || '本地' }}</el-tag>
        </el-descriptions-item>
      </el-descriptions>
    </el-card>

    <el-card v-if="auth.isAdmin" class="card" shadow="never" style="margin-top: 16px">
      <template #header><div class="card-head">发布站内信</div></template>
      <el-form :model="form" label-width="90px">
        <el-form-item label="标题">
          <el-input v-model="form.title" maxlength="128" show-word-limit placeholder="必填，一句话说清重点" />
        </el-form-item>
        <el-form-item label="正文">
          <el-input v-model="form.content" type="textarea" :rows="4" maxlength="1000" show-word-limit
            placeholder="如系统维护时间、新功能说明；用户在「消息中心」按原样阅读" />
        </el-form-item>
        <el-form-item label="跳转链接">
          <el-input v-model="form.link" placeholder="可选，如 /settings/accounts" />
        </el-form-item>
        <el-form-item label="收件人">
          <el-radio-group v-model="form.targetAll">
            <el-radio :value="true">全体用户（{{ users.length }} 人）</el-radio>
            <el-radio :value="false">指定用户</el-radio>
          </el-radio-group>
          <el-select v-if="!form.targetAll" v-model="form.userIds" multiple filterable collapse-tags
            placeholder="选择收件用户" style="width: 100%; margin-top: 8px">
            <el-option v-for="u in users" :key="u.id" :label="`${u.username}${u.email ? ' · ' + u.email : ''}`"
              :value="u.id" />
          </el-select>
        </el-form-item>
        <el-form-item>
          <el-button type="primary" :loading="sending" @click="publish">立即发布</el-button>
          <span class="hint">单次最多 500 人；发布即送达，在线用户会立刻收到提醒。</span>
        </el-form-item>
      </el-form>

      <el-table :data="history" style="width: 100%" v-loading="historyLoading" size="small">
        <el-table-column prop="title" label="已发布公告" min-width="200" />
        <el-table-column label="送达" width="80">
          <template #default="{ row }">{{ row.delivered }}</template>
        </el-table-column>
        <el-table-column label="已读" width="80">
          <template #default="{ row }">{{ row.read }}/{{ row.delivered }}</template>
        </el-table-column>
        <el-table-column label="发布时间" width="150">
          <template #default="{ row }">{{ fmt(row.createdAt) }}</template>
        </el-table-column>
      </el-table>
    </el-card>
  </div>
</template>

<script setup lang="ts">
import { ref, reactive, computed, onMounted, watch } from 'vue'
import { ElMessage } from 'element-plus'
import dayjs from 'dayjs'
import { useAuthStore } from '@/stores/auth'
import { useMailStore } from '@/stores/mail'
import { authApi, settingsApi, adminApi, type AnnouncementItem } from '@/api'
import http from '@/api/http'

const auth = useAuthStore()
const mail = useMailStore()
const theme = ref(localStorage.getItem('theme') || 'auto')
const push = computed({
  get: () => mail.pushEnabled,
  set: (v: boolean) => mail.setPushEnabled(v)
})
const info = ref<any>(null)
const changing = ref(false)
const pwdForm = reactive({ oldPassword: '', newPassword: '', confirm: '' })
/** 默认收取频率：界面按单位录入，提交仍换算成秒 */
const SYNC_UNIT_SEC: Record<string, number> = { m: 60, h: 3600, d: 86400 }
const syncValue = ref(1)
const syncUnit = ref('m')
const syncMax = computed(() => (syncUnit.value === 'd' ? 30 : syncUnit.value === 'h' ? 24 : 1440))
const syncLoading = ref(false)

// 管理员迁入：发布站内信 + 服务状态
const users = ref<any[]>([])
const form = reactive({ title: '', content: '', link: '', targetAll: true, userIds: [] as number[] })
const sending = ref(false)
const history = ref<AnnouncementItem[]>([])
const historyLoading = ref(false)
const health = reactive({ server: '检测中', db: '检测中', storage: '' })
const healthLoading = ref(false)

function syncIntervalSec() {
  return Math.max(1, Math.round(syncValue.value)) * (SYNC_UNIT_SEC[syncUnit.value] ?? 60)
}

/** 已存秒数反推成能整除的最大单位，避免 7200 秒显示成 7200/秒 */
function applySyncInterval(sec?: number) {
  const s = sec && sec >= 15 ? sec : 60
  const unit = s % SYNC_UNIT_SEC.d === 0 && s >= SYNC_UNIT_SEC.d ? 'd'
    : s % SYNC_UNIT_SEC.h === 0 && s >= SYNC_UNIT_SEC.h ? 'h' : 'm'
  syncUnit.value = unit
  syncValue.value = Math.max(1, Math.round(s / SYNC_UNIT_SEC[unit]))
}

onMounted(async () => {
  try {
    const res: any = await authApi.me()
    info.value = (res && res.data) ? res.data : res
  } catch {
    info.value = auth.user
  }
  loadSettings()
  if (auth.isAdmin) {
    loadUsers()
    loadHistory()
    loadHealth()
  }
})

async function loadSettings() {
  try {
    const res: any = await settingsApi.get()
    const s = (res && res.data) ? res.data : res
    applySyncInterval(s?.defaultSyncIntervalSec)
    // 主题以后端为准（跨设备一致）；后端为空时保留本地值
    if (s?.theme) theme.value = s.theme
  } catch {
    /* ignore */
  }
}

async function saveSync() {
  syncLoading.value = true
  try {
    await settingsApi.update({ defaultSyncIntervalSec: syncIntervalSec(), theme: theme.value })
    ElMessage.success('收取频率已保存')
  } finally {
    syncLoading.value = false
  }
}

watch(theme, (v) => {
  localStorage.setItem('theme', v)
  document.documentElement.className = v === 'dark' ? 'dark-mode' : ''
  // 与登录用户绑定：写回后端（本地未保存频率时不改动频率值，故一并提交当前频率）
  settingsApi.update({ defaultSyncIntervalSec: syncIntervalSec(), theme: v }).catch(() => { /* 静默 */ })
})

async function loadUsers() {
  try {
    users.value = await adminApi.list() as any
  } catch { users.value = [] }
}

async function loadHistory() {
  historyLoading.value = true
  try {
    history.value = await http.get('/admin/announcements', { params: { limit: 10 }, silentError: true } as any) as any
  } catch { history.value = [] } finally { historyLoading.value = false }
}

async function loadHealth() {
  healthLoading.value = true
  try {
    const res: any = await http.get('/health')
    const data = res.data || res
    health.server = 'UP'
    health.db = data.db || 'DOWN'
    health.storage = data.storage || '本地'
  } catch {
    health.server = 'DOWN'; health.db = 'DOWN'; health.storage = '未知'
  } finally { healthLoading.value = false }
}

async function publish() {
  if (!form.title.trim()) { ElMessage.warning('请填写公告标题'); return }
  if (!form.targetAll && !form.userIds.length) { ElMessage.warning('请至少选择一个收件用户'); return }
  sending.value = true
  try {
    const res: any = await adminApi.publishAnnouncement({
      title: form.title.trim(),
      content: form.content.trim() || undefined,
      link: form.link.trim() || undefined,
      targetAll: form.targetAll,
      userIds: form.targetAll ? undefined : form.userIds
    })
    ElMessage.success(`已发布，送达 ${res?.sent ?? 0} 人`)
    form.title = ''; form.content = ''; form.link = ''
    await loadHistory()
  } finally { sending.value = false }
}

function fmt(d?: string) { return d ? dayjs(d).format('YYYY-MM-DD HH:mm') : '' }

async function changePassword() {
  if (!pwdForm.oldPassword || !pwdForm.newPassword) { ElMessage.warning('请填写完整信息'); return }
  if (pwdForm.newPassword.length < 8) { ElMessage.warning('新密码至少 8 位，与注册要求一致'); return }
  if (pwdForm.newPassword !== pwdForm.confirm) { ElMessage.warning('两次输入的新密码不一致'); return }
  changing.value = true
  try {
    await authApi.changePassword({ oldPassword: pwdForm.oldPassword, newPassword: pwdForm.newPassword })
    ElMessage.success('密码修改成功')
    pwdForm.oldPassword = ''; pwdForm.newPassword = ''; pwdForm.confirm = ''
  } finally { changing.value = false }
}

async function install() {
  // @ts-ignore
  const promptEvent = (window as any).__beforeinstallprompt__
  if (promptEvent) {
    promptEvent.prompt()
  } else {
    ElMessage.info('请使用 Chrome/Edge 打开，并在地址栏点击安装图标')
  }
}
</script>

<style scoped>
.settings-page { padding: 20px; }
.page-title { font-size: 20px; }
.card-head { font-weight: 600; }
.hint { color: var(--color-text-3); font-size: 12px; margin-left: 8px; }
</style>
