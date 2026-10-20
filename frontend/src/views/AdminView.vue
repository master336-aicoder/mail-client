<template>
  <div class="settings-page">
    <h2 class="page-title">系统管理</h2>

    <el-alert
      v-if="denied"
      :title="deniedReason"
      type="warning"
      show-icon
      :closable="false"
      style="margin-top: 16px"
    />

    <el-card v-if="!denied" class="card" shadow="never" style="margin-top: 16px">
      <template #header><div class="card-head">用户列表</div></template>
      <el-table :data="users" style="width: 100%" v-loading="loading">
        <el-table-column prop="id" label="ID" width="70" />
        <el-table-column prop="username" label="用户名" min-width="140" />
        <el-table-column prop="email" label="邮箱" min-width="200" />
        <el-table-column label="角色" width="180">
          <template #default="{ row }">
            <el-tag :type="row.role === 'ADMIN' ? 'danger' : 'info'" size="small">{{ row.role }}</el-tag>
            <el-button v-if="row.role !== 'ADMIN'" size="small" text type="primary" @click="setRole(row, 'ADMIN')" style="margin-left: 8px">
              设为管理员
            </el-button>
            <el-button v-else size="small" text type="warning" @click="setRole(row, 'USER')" style="margin-left: 8px">
              设为普通用户
            </el-button>
          </template>
        </el-table-column>
      </el-table>
    </el-card>

    <el-card v-if="!denied" class="card" shadow="never" style="margin-top: 16px">
      <template #header>
        <div class="card-head">
          系统状态监控
          <span class="sampled-at" v-if="status">采样于 {{ fmtTime(status.sampledAt) }}</span>
          <el-button size="small" text type="primary" :loading="statusLoading" @click="loadStatus" style="margin-left: auto">刷新</el-button>
        </div>
      </template>
      <div v-loading="statusLoading">
        <el-alert v-if="statusError" :title="statusError" type="warning" show-icon :closable="false" style="margin-bottom: 12px" />
        <template v-else-if="status">
          <el-descriptions :column="2" border size="small" class="monitor-block">
            <el-descriptions-item label="CPU">
              <div class="metric">
                <span>{{ status.cpu?.cores ?? '-' }} 核</span>
                <el-progress v-if="status.cpu?.systemLoadPct != null" :percentage="clampPct(status.cpu.systemLoadPct)"
                  :stroke-width="12" :color="pctColor(status.cpu.systemLoadPct)" style="width: 160px" />
                <span v-else class="hint">采样中/不可用</span>
              </div>
            </el-descriptions-item>
            <el-descriptions-item label="内存">
              <div class="metric">
                <span>{{ gb(status.memory?.usedBytes) }} / {{ gb(status.memory?.totalBytes) }}</span>
                <el-progress v-if="status.memory?.usedPct != null" :percentage="status.memory.usedPct"
                  :stroke-width="12" :color="pctColor(status.memory.usedPct)" style="width: 160px" />
              </div>
            </el-descriptions-item>
            <el-descriptions-item label="JVM 堆">
              <div class="metric">
                <span>{{ mb(status.jvm?.heapUsedBytes) }} / {{ mb(status.jvm?.heapMaxBytes) }}</span>
                <el-progress v-if="heapPct != null" :percentage="heapPct" :stroke-width="12"
                  :color="pctColor(heapPct)" style="width: 160px" />
              </div>
            </el-descriptions-item>
            <el-descriptions-item label="JVM 运行">
              <span>{{ status.jvm?.name || '-' }} · 线程 {{ status.jvm?.threads ?? '-' }} · 运行 {{ uptimeText }}</span>
            </el-descriptions-item>
          </el-descriptions>

          <div class="sub-head">磁盘目录</div>
          <el-table :data="status.disks || []" size="small" style="width: 100%">
            <el-table-column prop="path" label="盘符/挂载" width="120" />
            <el-table-column label="容量" width="160">
              <template #default="{ row }">{{ gb(row.totalBytes) }}</template>
            </el-table-column>
            <el-table-column label="可用" width="120">
              <template #default="{ row }">{{ gb(row.usableBytes) }}</template>
            </el-table-column>
            <el-table-column label="使用率" min-width="200">
              <template #default="{ row }">
                <el-progress :percentage="row.usedPct ?? 0" :stroke-width="12" :color="pctColor(row.usedPct ?? 0)" />
              </template>
            </el-table-column>
          </el-table>

          <div class="sub-head">网络接口</div>
          <el-table :data="netList" size="small" style="width: 100%">
            <el-table-column prop="name" label="名称" width="140" />
            <el-table-column prop="displayName" label="描述" min-width="180" />
            <el-table-column label="状态" width="110">
              <template #default="{ row }">
                <el-tag :type="row.up ? 'success' : 'info'" size="small">{{ row.up ? '启用' : '停用' }}</el-tag>
                <el-tag v-if="row.loopback" size="small" type="warning" style="margin-left:4px">回环</el-tag>
              </template>
            </el-table-column>
            <el-table-column label="地址" min-width="220">
              <template #default="{ row }">{{ (row.addresses || []).join(', ') || '-' }}</template>
            </el-table-column>
          </el-table>
        </template>
      </div>
    </el-card>

    <el-card v-if="!denied" class="card" shadow="never" style="margin-top: 16px">
      <template #header><div class="card-head">预警设置</div></template>
      <el-form label-width="130px" v-loading="alertLoading">
        <el-form-item label="启用预警">
          <el-switch v-model="alert.enabled" />
          <span class="hint">开启后按巡检间隔采样，超阈值向全体管理员发送站内信</span>
        </el-form-item>
        <el-form-item label="CPU 阈值">
          <el-input-number v-model="alert.cpuThresholdPct" :min="1" :max="100" /> %
        </el-form-item>
        <el-form-item label="内存阈值">
          <el-input-number v-model="alert.memThresholdPct" :min="1" :max="100" /> %
        </el-form-item>
        <el-form-item label="磁盘阈值">
          <el-input-number v-model="alert.diskThresholdPct" :min="1" :max="100" /> %
        </el-form-item>
        <el-form-item label="巡检间隔">
          <el-input-number v-model="alert.checkIntervalMin" :min="1" :max="1440" /> 分钟
        </el-form-item>
        <el-form-item label="告警冷却">
          <el-input-number v-model="alert.cooldownMin" :min="0" :max="1440" /> 分钟
          <span class="hint">同一指标冷却期内不重复告警</span>
        </el-form-item>
        <el-form-item>
          <el-button type="primary" :loading="alertSaving" @click="saveAlert">保存预警设置</el-button>
          <el-button :loading="alertChecking" @click="manualCheck">立即检查一次</el-button>
          <el-button :loading="alertResetting" @click="resetCooldown">重置冷却时间戳</el-button>
        </el-form-item>
      </el-form>
      <el-alert v-if="alertCheckResult" :closable="true" type="info" style="margin-top: 8px"
        @close="alertCheckResult = null"
        :title="`本轮触发 ${alertCheckResult.fired?.length || 0} 项，冷却抑制 ${alertCheckResult.suppressed?.length || 0} 项${alertCheckResult.message ? '：' + alertCheckResult.message : ''}`">
        <ul>
          <li v-for="(f, i) in alertCheckResult.fired" :key="'f'+i">
            已告警：{{ f.metric }} 当前 {{ f.value }}% ≥ 阈值 {{ f.threshold }}% — {{ f.note }}
          </li>
          <li v-for="(s, i) in alertCheckResult.suppressed" :key="'s'+i" style="color: var(--color-text-3)">
            冷却中：{{ s.metric }} 当前 {{ s.value }}% ≥ 阈值 {{ s.threshold }}%
          </li>
        </ul>
      </el-alert>
    </el-card>

    <el-card v-if="!denied" class="card" shadow="never" style="margin-top: 16px">
      <template #header><div class="card-head">后台任务执行记录</div></template>
      <el-form label-width="120px">
        <el-form-item label="保存范围">
          <el-radio-group v-model="retention.scope">
            <el-radio value="ALL">全部记录</el-radio>
            <el-radio value="SUCCESS">成功记录</el-radio>
            <el-radio value="FAILED">失败记录</el-radio>
            <el-radio value="NONE">全不记录</el-radio>
          </el-radio-group>
        </el-form-item>
        <el-form-item label="保存时间">
          <el-radio-group v-model="retention.days">
            <el-radio-button :value="1">1 天</el-radio-button>
            <el-radio-button :value="2">2 天</el-radio-button>
            <el-radio-button :value="7">7 天</el-radio-button>
            <el-radio-button :value="15">15 天</el-radio-button>
            <el-radio-button :value="0">指定天数</el-radio-button>
          </el-radio-group>
        </el-form-item>
        <el-form-item v-if="retention.days === 0" label="自定义天数">
          <el-input-number v-model="retention.customDays" :min="1" :max="3650" />
          <span class="hint">天</span>
        </el-form-item>
        <el-form-item>
          <el-button type="primary" size="small" :loading="retentionLoading" @click="saveRetention">保存</el-button>
          <span class="hint">每 5 分钟按策略清理一次；「全不记录」或超期后，导出产物随记录一并清理</span>
        </el-form-item>
      </el-form>
    </el-card>
  </div>
</template>

<script setup lang="ts">
import { ref, reactive, computed, onMounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import dayjs from 'dayjs'
import { adminApi, settingsApi, type SystemStatus } from '@/api'
import http from '@/api/http'

const users = ref<any[]>([])
const loading = ref(false)
const denied = ref(false)
const deniedReason = ref('')

const status = ref<SystemStatus | null>(null)
const statusLoading = ref(false)
const statusError = ref('')

const alert = reactive({
  enabled: false, cpuThresholdPct: 85, memThresholdPct: 85, diskThresholdPct: 90,
  checkIntervalMin: 5, cooldownMin: 30
})
const alertLoading = ref(false)
const alertSaving = ref(false)
const alertChecking = ref(false)
const alertResetting = ref(false)
const alertCheckResult = ref<any>(null)

const retention = reactive({ scope: 'ALL', days: 7, customDays: 30 })
const retentionLoading = ref(false)

const netList = computed(() => (status.value?.network || []).filter(n => !n.loopback))

const heapPct = computed(() => {
  const j = status.value?.jvm
  if (!j || !j.heapMaxBytes || j.heapMaxBytes <= 0) return null
  return Math.round(100 * j.heapUsedBytes / j.heapMaxBytes)
})

const uptimeText = computed(() => {
  const ms = status.value?.jvm?.uptimeMs
  if (!ms) return '-'
  const s = Math.floor(ms / 1000)
  const d = Math.floor(s / 86400), h = Math.floor((s % 86400) / 3600), m = Math.floor((s % 3600) / 60)
  return (d ? d + '天' : '') + (h ? h + '时' : '') + m + '分'
})

function clampPct(v: number) { return Math.max(0, Math.min(100, Math.round(v))) }
function pctColor(v: number) { return v >= 90 ? '#f56c6c' : v >= 75 ? '#e6a23c' : '#67c23a' }
function gb(b?: number) { return b == null ? '-' : (b / 1024 / 1024 / 1024).toFixed(1) + ' GB' }
function mb(b?: number) { return b == null ? '-' : (b / 1024 / 1024).toFixed(0) + ' MB' }
function fmtTime(ms?: number) { return ms ? dayjs(ms).format('HH:mm:ss') : '' }

onMounted(async () => {
  await load()
  if (!denied.value) {
    loadStatus()
    loadAlert()
    loadRetention()
  }
})

async function load() {
  loading.value = true
  denied.value = false
  deniedReason.value = ''
  try {
    users.value = await http.get('/admin/users', { silentError: true } as any) as any
  } catch (err: any) {
    users.value = []
    const s = err?.response?.status
    denied.value = true
    deniedReason.value = s === 403
      ? '当前账号没有管理员权限，请联系系统管理员。'
      : s === 401
        ? '登录状态已失效，请重新登录后再试。'
        : (err?.message || '用户列表加载失败，请稍后重试。')
  } finally {
    loading.value = false
  }
}

async function loadStatus() {
  statusLoading.value = true
  statusError.value = ''
  try {
    const res: any = await adminApi.systemStatus()
    status.value = (res && res.data) ? res.data : res
  } catch (err: any) {
    statusError.value = err?.message || '系统状态采样失败'
  } finally {
    statusLoading.value = false
  }
}

async function loadAlert() {
  alertLoading.value = true
  try {
    const res: any = await adminApi.systemAlertConfig()
    const c = (res && res.data) ? res.data : res
    if (c) {
      alert.enabled = !!c.enabled
      alert.cpuThresholdPct = c.cpuThresholdPct ?? 85
      alert.memThresholdPct = c.memThresholdPct ?? 85
      alert.diskThresholdPct = c.diskThresholdPct ?? 90
      alert.checkIntervalMin = c.checkIntervalMin ?? 5
      alert.cooldownMin = c.cooldownMin ?? 30
    }
  } catch {
    /* 保持默认 */
  } finally {
    alertLoading.value = false
  }
}

async function saveAlert() {
  alertSaving.value = true
  try {
    await adminApi.updateSystemAlertConfig({ ...alert })
    ElMessage.success('预警设置已保存')
  } finally {
    alertSaving.value = false
  }
}

async function manualCheck() {
  alertChecking.value = true
  try {
    const r: any = await adminApi.systemAlertCheck()
    alertCheckResult.value = r
    if ((r?.fired?.length || 0) > 0) {
      ElMessage.success(`已触发 ${r.fired.length} 项预警并推送给管理员`)
      await loadAlert()
    } else if (r?.message) {
      ElMessage.info(r.message)
    } else {
      ElMessage.info('本轮无指标超过阈值')
    }
  } finally {
    alertChecking.value = false
  }
}

async function resetCooldown() {
  alertResetting.value = true
  try {
    await adminApi.systemAlertResetCooldown()
    ElMessage.success('冷却时间戳已清空，可再次触发')
  } finally {
    alertResetting.value = false
  }
}

async function loadRetention() {
  try {
    const res: any = await settingsApi.get()
    const s = (res && res.data) ? res.data : res
    retention.scope = s?.runRetentionScope || 'ALL'
    const d = s?.runRetentionDays ?? 7
    retention.days = [1, 2, 7, 15].includes(d) ? d : 0
    retention.customDays = d
  } catch { /* ignore */ }
}

async function saveRetention() {
  const days = retention.days === 0 ? retention.customDays : retention.days
  if (!days || days < 1 || days > 3650) {
    ElMessage.warning('保存天数需在 1 ~ 3650 天之间')
    return
  }
  retentionLoading.value = true
  try {
    const cur: any = await settingsApi.get().then((r: any) => (r && r.data) ? r.data : r).catch(() => ({}))
    await settingsApi.update({
      defaultSyncIntervalSec: cur?.defaultSyncIntervalSec ?? 60,
      runRetentionScope: retention.scope,
      runRetentionDays: days
    })
    ElMessage.success('执行记录保存策略已保存')
  } finally {
    retentionLoading.value = false
  }
}

async function setRole(user: any, role: string) {
  const action = role === 'ADMIN' ? '设为管理员' : '设为普通用户'
  try {
    await ElMessageBox.confirm(`确定将用户「${user.username}」${action}吗？`, '修改角色',
      { type: 'warning', confirmButtonText: '确定', cancelButtonText: '取消' })
  } catch { return }
  try {
    await adminApi.setRole(user.id, role)
    user.role = role
    ElMessage.success('角色已更新')
  } catch { /* 拦截器已提示 */ }
}
</script>

<style scoped>
.settings-page { padding: 20px; }
.page-title { font-size: 20px; }
.card-head { font-weight: 600; display: flex; align-items: center; gap: 8px; }
.sampled-at { color: var(--color-text-3); font-size: 12px; font-weight: 400; }
.metric { display: flex; align-items: center; gap: 12px; }
.sub-head { font-weight: 600; margin: 16px 0 8px; }
.hint { color: var(--color-text-3); font-size: 12px; margin-left: 8px; }
.monitor-block :deep(.el-descriptions__label) { width: 90px; }
</style>
