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
          <el-input-number v-model="syncInterval" :min="15" :max="86400" :step="15" />
          <span class="hint">秒/次，账户未单独设置时以此频率自动收取新邮件</span>
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
          <el-input v-model="pwdForm.newPassword" type="password" show-password placeholder="至少 6 位" />
        </el-form-item>
        <el-form-item label="确认新密码">
          <el-input v-model="pwdForm.confirm" type="password" show-password placeholder="再次输入新密码" />
        </el-form-item>
        <el-form-item>
          <el-button type="primary" :loading="changing" @click="changePassword">保存新密码</el-button>
        </el-form-item>
      </el-form>
    </el-card>
  </div>
</template>

<script setup lang="ts">
import { ref, reactive, onMounted, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { useAuthStore } from '@/stores/auth'
import { authApi, settingsApi } from '@/api'

const auth = useAuthStore()
const theme = ref(localStorage.getItem('theme') || 'auto')
const push = ref(true)
const info = ref<any>(null)
const changing = ref(false)
const pwdForm = reactive({ oldPassword: '', newPassword: '', confirm: '' })
const syncInterval = ref(60)
const syncLoading = ref(false)

onMounted(async () => {
  try {
    const res: any = await authApi.me()
    info.value = (res && res.data) ? res.data : res
  } catch {
    info.value = auth.user
  }
  loadSync()
})

async function loadSync() {
  try {
    const res: any = await settingsApi.get()
    const s = (res && res.data) ? res.data : res
    syncInterval.value = s?.defaultSyncIntervalSec ?? 60
  } catch {
    /* ignore */
  }
}

async function saveSync() {
  syncLoading.value = true
  try {
    await settingsApi.update({ defaultSyncIntervalSec: syncInterval.value })
    ElMessage.success('收取频率已保存')
  } finally {
    syncLoading.value = false
  }
}

watch(theme, (v) => {
  localStorage.setItem('theme', v)
  document.documentElement.className =
    v === 'dark' ? 'dark-mode' : v === 'light' ? '' : ''
})

async function changePassword() {
  if (!pwdForm.oldPassword || !pwdForm.newPassword) {
    ElMessage.warning('请填写完整信息')
    return
  }
  if (pwdForm.newPassword.length < 6) {
    ElMessage.warning('新密码至少 6 位')
    return
  }
  if (pwdForm.newPassword !== pwdForm.confirm) {
    ElMessage.warning('两次输入的新密码不一致')
    return
  }
  changing.value = true
  try {
    await authApi.changePassword({ oldPassword: pwdForm.oldPassword, newPassword: pwdForm.newPassword })
    ElMessage.success('密码修改成功')
    pwdForm.oldPassword = ''
    pwdForm.newPassword = ''
    pwdForm.confirm = ''
  } finally {
    changing.value = false
  }
}

async function install() {
  // @ts-ignore
  const promptEvent = (window as any).__beforeinstallprompt__
  if (promptEvent) {
    promptEvent.prompt()
  } else {
    alert('请使用 Chrome/Edge 打开，并在地址栏点击安装图标')
  }
}
</script>

<style scoped>
.settings-page { padding: 20px; }
.page-title { font-size: 20px; }
.card-head { font-weight: 600; }
.hint { color: var(--color-text-3); font-size: 12px; margin-left: 8px; }
</style>