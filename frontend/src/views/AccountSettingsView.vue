<template>
  <div class="settings-page">
    <h2 class="page-title">账户管理</h2>

    <!-- 添加账户向导 -->
    <el-card class="card" shadow="never">
      <template #header>
        <div class="card-head">
          <span>添加邮箱账户</span>
        </div>
      </template>
      <el-steps :active="step" simple style="margin-bottom: 20px">
        <el-step title="邮箱地址" />
        <el-step title="服务器配置" />
        <el-step title="验证凭据" />
      </el-steps>

      <div v-if="step === 0">
        <el-form label-width="90px">
          <el-form-item label="邮箱地址">
            <el-input v-model="wizard.email" placeholder="you@example.com" style="width: 320px" />
          </el-form-item>
        </el-form>
        <el-button type="primary" :loading="detecting" @click="detect">自动检测服务器</el-button>
        <el-button plain @click="step = 1">手动配置</el-button>
        <div v-if="detectBox" class="detect-result">
          <div v-for="p in detectBox" :key="p.protocol" class="detect-item">
            <el-tag size="small" type="primary">{{ p.protocol }}</el-tag>
            <span>{{ p.host }}:{{ p.port }}</span>
            <el-button size="small" type="primary" link @click="useProbe(p)">使用此配置</el-button>
          </div>
        </div>
      </div>

      <div v-else-if="step === 1">
        <el-form label-width="120px">
          <el-form-item label="协议">
            <el-radio-group v-model="wizard.protocol">
              <el-radio value="IMAP">IMAP4</el-radio>
              <el-radio value="POP3">POP3</el-radio>
            </el-radio-group>
          </el-form-item>
          <el-form-item label="收件服务器">
            <el-input v-model="wizard.host" placeholder="imap.example.com" style="width: 260px" />
          </el-form-item>
          <el-form-item label="端口">
            <el-input-number v-model="wizard.port" :min="1" :max="65535" />
          </el-form-item>
          <el-form-item label="加密方式">
            <el-select v-model="wizard.encryption" style="width: 180px">
              <el-option label="SSL/TLS" value="SSL" />
              <el-option label="STARTTLS" value="STARTTLS" />
              <el-option label="无加密" value="NONE" />
            </el-select>
          </el-form-item>
          <el-form-item label="发件服务器">
            <el-input v-model="wizard.smtpHost" placeholder="smtp.example.com" style="width: 260px" />
          </el-form-item>
          <el-form-item label="SMTP 端口">
            <el-input-number v-model="wizard.smtpPort" :min="1" :max="65535" />
          </el-form-item>
        </el-form>
        <el-button type="primary" @click="step = 2">下一步</el-button>
        <el-button plain @click="step = 0">上一步</el-button>
      </div>

      <div v-else>
        <el-form label-width="120px">
          <el-form-item label="邮箱地址">
            <el-input v-model="wizard.email" placeholder="you@example.com" style="width: 260px" />
            <div class="tip">用于标识该邮箱账户；为空时后端将拒绝保存。</div>
          </el-form-item>
          <el-form-item label="用户名">
            <el-input v-model="wizard.username" placeholder="登录用户名/邮箱" style="width: 260px" />
          </el-form-item>
          <el-form-item label="密码 / 授权码">
            <el-input v-model="wizard.secret" type="password" show-password
              placeholder="邮箱密码或应用授权码" style="width: 260px" />
          </el-form-item>
          <el-form-item label="显示名称">
            <el-input v-model="wizard.displayName" placeholder="发件显示名" style="width: 260px" />
          </el-form-item>
        </el-form>
        <div class="warn">凭据将使用 AES-256-GCM 加密存储，仅用于收发邮件。</div>
        <el-button type="primary" :loading="saving" @click="save">完成并验证</el-button>
        <el-button plain @click="step = 1">上一步</el-button>
      </div>
    </el-card>

    <!-- 已绑定账户 -->
    <el-card class="card" shadow="never" style="margin-top: 16px">
      <template #header>
        <div class="card-head" style="display: flex; justify-content: space-between; align-items: center">
          <span>已绑定账户</span>
          <el-button size="small" type="primary" plain :loading="refreshAllLoading" @click="refreshAll">
            全部立即收取
          </el-button>
        </div>
      </template>
      <el-table :data="mail.accounts" style="width: 100%">
        <el-table-column prop="email" label="邮箱" min-width="200" />
        <el-table-column prop="protocol" label="协议" width="90" />
        <el-table-column prop="host" label="服务器" min-width="180" />
        <el-table-column label="加密" width="100">
          <template #default="{ row }">{{ row.encryption }}</template>
        </el-table-column>
        <el-table-column label="状态" width="100">
          <template #default="{ row }">
            <el-tag :type="row.active ? 'success' : 'info'" size="small">
              {{ row.active ? '启用' : '停用' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="收取频率" width="170">
          <template #default="{ row }">
            <el-input-number v-model="row.syncIntervalSec" :min="15" :max="86400" :step="15"
              :disabled="savingFreq === row.id" size="small" style="width: 120px" />
            <span style="margin-left: 4px; color: var(--color-text-3); font-size: 12px">秒</span>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="220">
          <template #default="{ row }">
            <el-button size="small" :loading="savingFreq === row.id" @click="saveFreq(row)">保存频率</el-button>
            <el-button size="small" :loading="syncing === row.id" @click="syncNow(row.id)">同步</el-button>
            <el-button size="small" type="danger" plain @click="remove(row.id)">删除</el-button>
          </template>
        </el-table-column>
      </el-table>
    </el-card>
  </div>
</template>

<script setup lang="ts">
import { ref, reactive, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { useMailStore } from '@/stores/mail'
import { accountApi, syncApi } from '@/api'

const mail = useMailStore()
const router = useRouter()
const step = ref(0)
const detectBox = ref<any[]>([])
const detecting = ref(false)
const saving = ref(false)
const syncing = ref<number | null>(null)
const savingFreq = ref<number | null>(null)
const refreshAllLoading = ref(false)

const wizard = reactive<any>({
  email: '',
  protocol: 'IMAP',
  host: '',
  port: 993,
  encryption: 'SSL',
  smtpHost: '',
  smtpPort: 587,
  smtpEncryption: 'STARTTLS',
  username: '',
  secret: '',
  displayName: ''
})

onMounted(mail.loadAccounts)

async function detect() {
  if (!wizard.email.includes('@')) {
    ElMessage.warning('请输入完整邮箱地址')
    return
  }
  detecting.value = true
  try {
    const res: any = await accountApi.autoDetect({ email: wizard.email })
    detectBox.value = res || []
    ElMessage.success('已生成推荐配置')
  } finally {
    detecting.value = false
  }
}

function useProbe(p: any) {
  wizard.protocol = p.protocol
  wizard.host = p.host
  wizard.port = p.port
  wizard.encryption = p.encryption || 'SSL'
  wizard.smtpHost = p.smtpHost
  wizard.smtpPort = p.smtpPort
  wizard.username = wizard.email
  step.value = 2
  ElMessage.success('已应用推荐配置')
}

async function save() {
  if (!wizard.email || !wizard.email.includes('@')) {
    ElMessage.warning('请填写有效的邮箱地址')
    return
  }
  saving.value = true
  try {
    await accountApi.create({ ...wizard })
    ElMessage.success('账户添加成功')
    step.value = 0
    wizard.email = ''
    detectBox.value = []
    await mail.loadAccounts()
  } finally {
    saving.value = false
  }
}

async function syncNow(id: number) {
  syncing.value = id
  try {
    const res: any = await syncApi.trigger(id)
    const taskId = res && res.data ? res.data.taskId : null
    ElMessage.success(taskId ? '同步任务已提交，正在后台执行' : '同步任务已提交')
    // 短暂停留后跳转后台任务页查看进度
    setTimeout(() => { router.push('/tasks') }, 600)
  } finally {
    syncing.value = null
  }
}

async function saveFreq(row: any) {
  savingFreq.value = row.id
  try {
    await accountApi.update(row.id, { syncIntervalSec: row.syncIntervalSec })
    ElMessage.success('收取频率已保存')
  } finally {
    savingFreq.value = null
  }
}

async function refreshAll() {
  refreshAllLoading.value = true
  try {
    const res: any = await syncApi.all()
    const count = res && (res as any).data ? (res as any).data.count : null
    ElMessage.success(count != null ? `已为 ${count} 个账户提交收取任务` : '已提交收取任务')
    setTimeout(() => { router.push('/tasks') }, 600)
  } finally {
    refreshAllLoading.value = false
  }
}

async function remove(id: number) {
  await ElMessageBox.confirm('确认删除该账户？', '提示', { type: 'warning' })
  await accountApi.remove(id)
  ElMessage.success('已删除')
  await mail.loadAccounts()
}
</script>

<style scoped>
.settings-page { padding: 20px; }
.page-title { font-size: 20px; margin-bottom: 16px; }
.card-head { font-weight: 600; }
.detect-result { margin-top: 14px; }
.detect-item {
  display: flex; align-items: center; gap: 12px; padding: 8px;
  border: 1px solid var(--color-border-light); border-radius: var(--radius-md);
  margin-bottom: 8px;
}
.warn { color: var(--color-warning); font-size: 12px; margin-bottom: 12px; }
.tip { color: var(--color-text-3); font-size: 12px; line-height: 1.5; padding-top: 2px; }
</style>