<template>
  <div class="tasks-view">
    <div class="tk-head">
      <h2 class="page-title">后台任务</h2>
      <div>
        <el-button size="small" plain :icon="Setting" @click="openPolicy">全局策略</el-button>
        <el-button size="small" plain :icon="Refresh" :loading="sweeping" @click="doSweep">立即巡检</el-button>
        <el-button size="small" @click="load" :loading="loading">刷新</el-button>
      </div>
    </div>
    <div v-if="runtime" class="tk-runtime">
      <el-tag :type="runtime.schedulingEnabled ? 'success' : 'warning'" effect="dark" size="small">
        定时调度：{{ runtime.schedulingEnabled ? '已开启' : '已关闭' }}
      </el-tag>
      <span class="tk-runtime-note">
        <template v-if="runtime.schedulingEnabled">
          周期收信、记录清理、系统预警等定时任务正常运行。
        </template>
        <template v-else>
          启动参数已关闭定时调度（<code>--mail.app.scheduling-enabled=false</code>）：周期收信与执行记录自动清理不会运行，仅可手动执行。
        </template>
      </span>
      <el-divider direction="vertical" />
      <el-tag :type="runtime.watchdog?.active ? 'success' : 'info'" effect="plain" size="small">
        守护进程：{{ runtime.watchdog?.active ? '运行中' : '未运行' }}
      </el-tag>
      <span class="tk-runtime-note">
        每 {{ runtime.watchdog?.intervalSec || 60 }} 秒巡检一次，自动回收「执行线程丢失但仍显示进行中」的任务；
        上次巡检 {{ fmt(runtime.watchdog?.lastSweepAt) }}，累计回收 {{ runtime.watchdog?.totalRecovered ?? 0 }} 条。
      </span>
    </div>
    <el-card class="card" shadow="never" style="margin-top: 16px">
      <div class="tk-toolbar">
        <el-button size="small" type="primary" plain :icon="VideoPlay"
          :disabled="!selectedIds.length" :loading="batchLoading === 'run'" @click="batchRun">批量执行</el-button>
        <el-button size="small" type="warning" plain :icon="CircleClose"
          :disabled="!selectedIds.length" :loading="batchLoading === 'cancel'" @click="batchCancel">批量中止</el-button>
        <el-button size="small" plain :icon="Open"
          :disabled="!selectedIds.length" :loading="batchLoading === 'enable'" @click="batchSetEnabled(true)">批量启用</el-button>
        <el-button size="small" plain :icon="TurnOff"
          :disabled="!selectedIds.length" :loading="batchLoading === 'disable'" @click="batchSetEnabled(false)">批量停用</el-button>
        <el-button size="small" type="danger" plain :icon="Delete"
          :disabled="!selectedIds.length" :loading="batchLoading === 'delete'" @click="batchDelete">批量删除</el-button>
        <span v-if="selectedIds.length" class="tk-selected-hint">已选 {{ selectedIds.length }} 项（可跨页选择）</span>
        <el-button v-if="selectedIds.length" size="small" text :icon="Delete" @click="clearSelection">清空选择</el-button>
      </div>
      <el-table ref="tableRef" :data="tasks" v-loading="loading" stripe style="width: 100%" row-key="id"
        @selection-change="onSelectionChange">
        <el-table-column type="selection" width="44" :reserve-selection="true" />
        <el-table-column label="任务名称" width="200" show-overflow-tooltip>
          <template #default="{ row }">{{ row.name }}</template>
        </el-table-column>
        <el-table-column label="类型" width="90">
          <template #default="{ row }">{{ typeLabel(row.type) }}</template>
        </el-table-column>
        <el-table-column label="调度" width="130">
          <template #default="{ row }">
            <template v-if="row.periodic">
              <el-tag :type="row.enabled ? 'success' : 'info'" effect="plain" size="small">
                {{ row.enabled ? '启用' : '停用' }}
              </el-tag>
              <span class="tk-cycle">{{ cycleText(row) }}</span>
            </template>
            <el-tag v-else effect="plain" size="small" type="info">一次性</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="状态" width="90">
          <template #default="{ row }">
            <el-tag :type="statusType(row.status)" effect="light">{{ statusLabel(row.status) }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="进度" width="140">
          <template #default="{ row }">
            <el-progress :percentage="row.progress || 0" :stroke-width="8"
              :status="row.status === 'FAILED' ? 'exception' : row.status === 'COMPLETED' ? 'success' : ''" />
          </template>
        </el-table-column>
        <el-table-column label="最近执行说明" min-width="200" show-overflow-tooltip>
          <template #default="{ row }">
            <span v-if="row.error" class="tk-error">{{ row.error }}</span>
            <span v-else>{{ row.message || row.result || '-' }}</span>
          </template>
        </el-table-column>
        <el-table-column label="下次执行" width="150">
          <template #default="{ row }">{{ row.nextRunAt ? fmt(row.nextRunAt) : '-' }}</template>
        </el-table-column>
        <el-table-column label="操作" width="370" fixed="right" class-name="tk-ops-cell">
          <template #default="{ row }">
            <el-button size="small" type="primary" plain :disabled="canCancel(row)" @click="run(row)">立即执行</el-button>
            <el-button v-if="canCancel(row)" size="small" type="danger" plain @click="cancel(row)">中止</el-button>
            <template v-if="row.periodic">
              <el-button size="small" plain @click="toggleEnabled(row)">{{ row.enabled ? '停用' : '启用' }}</el-button>
            </template>
            <el-tooltip :content="policySummary(row)" placement="top">
              <el-button size="small" text :type="hasOwnPolicy(row) ? 'warning' : 'primary'"
                @click="openTaskPolicy(row)">策略</el-button>
            </el-tooltip>
            <el-button size="small" text type="primary" @click="openRuns(row)">记录</el-button>
            <el-button size="small" text type="danger" @click="removeOne(row)">删除</el-button>
          </template>
        </el-table-column>
        <template #empty>暂无后台任务</template>
      </el-table>
      <div class="tk-pager">
        <el-pagination background layout="total, sizes, prev, pager, next, jumper"
          :total="total" :page-sizes="[20, 50, 100, 200]"
          v-model:current-page="page" v-model:page-size="size" />
      </div>
    </el-card>

    <!-- 执行记录 -->
    <el-drawer v-model="runsVisible" :title="`执行记录 · ${currentTask?.name || ''}`" size="62%">
      <div class="tk-runs-head">
        <span v-if="currentTask?.periodic">
          周期任务，{{ currentTask.enabled ? '已启用' : '已停用' }}，
          {{ cycleText(currentTask) }}
          {{ currentTask.nextRunAt ? `，下次执行 ${fmt(currentTask.nextRunAt)}` : '' }}
        </span>
        <span v-else>一次性任务，每次手动操作产生一条新任务</span>
        <el-button size="small" text :icon="Refresh" @click="loadRuns">刷新</el-button>
      </div>
      <el-alert v-if="runExportMsg" type="info" :closable="false" style="margin-bottom: 10px"
        :title="runExportMsg" />
      <div class="tk-toolbar">
        <el-button size="small" type="primary" plain :icon="Download"
          :disabled="!selectedRunIds.length" @click="exportRuns(false)">导出选中</el-button>
        <el-button size="small" type="primary" plain :icon="Download" @click="exportRuns(true)">全部导出</el-button>
        <el-button size="small" type="danger" plain :icon="Delete"
          :disabled="!selectedRunIds.length" @click="deleteRuns(false)">删除选中</el-button>
        <el-button size="small" type="danger" plain :icon="Delete" @click="deleteRuns(true)">全部删除</el-button>
        <span v-if="selectedRunIds.length" class="tk-selected-hint">已选 {{ selectedRunIds.length }} 条记录</span>
      </div>
      <el-table :data="runs" v-loading="runsLoading" stripe style="width: 100%" row-key="id"
        @selection-change="(rows: any[]) => selectedRuns = rows">
        <el-table-column type="selection" width="44" :selectable="(row: any) => !['PENDING', 'RUNNING'].includes(row.status)" />
        <el-table-column label="开始" width="150">
          <template #default="{ row }">{{ fmt(row.startedAt || row.createdAt) }}</template>
        </el-table-column>
        <el-table-column label="触发" width="70">
          <template #default="{ row }">{{ row.triggerType === 'SCHEDULED' ? '定时' : '手动' }}</template>
        </el-table-column>
        <el-table-column label="状态" width="90">
          <template #default="{ row }">
            <el-tag :type="statusType(row.status)" effect="light">{{ statusLabel(row.status) }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="结果" min-width="220" show-overflow-tooltip>
          <template #default="{ row }">
            <div v-if="row.error" class="tk-error">{{ row.error }}</div>
            <div v-else>{{ row.message || '-' }}</div>
            <div v-if="row.result" class="tk-result">{{ row.result }}</div>
          </template>
        </el-table-column>
        <el-table-column label="耗时" width="80">
          <template #default="{ row }">{{ duration(row.durationMs) }}</template>
        </el-table-column>
        <el-table-column label="产物附件" min-width="180">
          <template #default="{ row }">
            <div v-if="row.attachments?.length" class="tk-atts">
              <el-link v-for="a in row.attachments" :key="a.id" type="primary"
                underline="never" @click="download(a)">
                <el-icon><Download /></el-icon>
                <span class="tk-att-name">{{ a.name }}</span>
                <span class="tk-att-size">{{ fmtSize(a.size) }}</span>
              </el-link>
              <div v-if="row.attachments.some((a: any) => !a.available)" class="tk-error">
                部分产物文件已清理，无法下载
              </div>
            </div>
            <span v-else>-</span>
          </template>
        </el-table-column>
        <template #empty>该任务还没有执行记录</template>
      </el-table>
      <div class="tk-pager">
        <el-pagination background layout="total, prev, pager, next" :total="runsTotal"
            v-model:current-page="runsPage" :page-size="runsSize" @current-change="loadRuns" />
      </div>
    </el-drawer>

    <!-- 执行记录保存策略（全局） -->
    <el-dialog v-model="policyVisible" title="全局执行记录保存策略" width="460px">
      <el-form label-width="90px">
        <el-form-item label="保存范围">
          <el-radio-group v-model="policy.scope">
            <el-radio value="ALL">全部记录</el-radio>
            <el-radio value="SUCCESS">成功记录</el-radio>
            <el-radio value="FAILED">失败记录</el-radio>
            <el-radio value="NONE">全不记录</el-radio>
          </el-radio-group>
        </el-form-item>
        <el-form-item label="保存时间">
          <el-radio-group v-model="policy.days">
            <el-radio-button :value="1">1 天</el-radio-button>
            <el-radio-button :value="2">2 天</el-radio-button>
            <el-radio-button :value="7">7 天</el-radio-button>
            <el-radio-button :value="15">15 天</el-radio-button>
            <el-radio-button :value="0">指定天数</el-radio-button>
          </el-radio-group>
        </el-form-item>
        <el-form-item v-if="policy.days === 0" label="自定义">
          <el-input-number v-model="policy.customDays" :min="1" :max="3650" />
          <span class="tk-cycle">天</span>
        </el-form-item>
      </el-form>
      <div class="tk-policy-tip">
        系统每 5 分钟按策略清理一次已结束的执行记录；进行中的记录不受影响。
        「全不记录」或超出保存天数后，导出产物也会随记录一并清理、无法再下载。
        这里是全局默认值，列表里单个任务的「策略」配置优先级更高。
      </div>
      <template #footer>
        <el-button @click="policyVisible = false">取消</el-button>
        <el-button type="primary" :loading="policySaving" @click="savePolicy">保存</el-button>
      </template>
    </el-dialog>

    <!-- 执行记录保存策略（单个任务，优先于全局） -->
    <el-dialog v-model="taskPolicyVisible" :title="`记录策略 · ${taskPolicyTarget?.name || ''}`" width="460px">
      <el-form label-width="90px">
        <el-form-item label="配置方式">
          <el-radio-group v-model="taskPolicy.follow">
            <el-radio :value="true">跟随全局（{{ scopeLabel(globalPolicy.scope) }} / {{ globalPolicy.days }} 天）</el-radio>
            <el-radio :value="false">单独配置</el-radio>
          </el-radio-group>
        </el-form-item>
        <template v-if="!taskPolicy.follow">
          <el-form-item label="保存范围">
            <el-radio-group v-model="taskPolicy.scope">
              <el-radio value="ALL">全部记录</el-radio>
              <el-radio value="SUCCESS">成功记录</el-radio>
              <el-radio value="FAILED">失败记录</el-radio>
              <el-radio value="NONE">全不记录</el-radio>
            </el-radio-group>
          </el-form-item>
          <el-form-item label="保存时间">
            <el-radio-group v-model="taskPolicy.days">
              <el-radio-button :value="1">1 天</el-radio-button>
              <el-radio-button :value="2">2 天</el-radio-button>
              <el-radio-button :value="7">7 天</el-radio-button>
              <el-radio-button :value="15">15 天</el-radio-button>
              <el-radio-button :value="0">指定天数</el-radio-button>
            </el-radio-group>
          </el-form-item>
          <el-form-item v-if="taskPolicy.days === 0" label="自定义">
            <el-input-number v-model="taskPolicy.customDays" :min="1" :max="3650" />
            <span class="tk-cycle">天</span>
          </el-form-item>
        </template>
      </el-form>
      <div class="tk-policy-tip">
        任务级策略优先于全局策略，系统每 5 分钟按生效策略清理该任务已结束的执行记录；
        「全不记录」或超出保存天数后，导出产物也会随记录一并清理、无法再下载。
      </div>
      <template #footer>
        <el-button @click="taskPolicyVisible = false">取消</el-button>
        <el-button type="primary" :loading="taskPolicySaving" @click="saveTaskPolicy">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { ref, reactive, computed, onMounted, onUnmounted, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Refresh, CircleClose, Delete, VideoPlay, TurnOff, Open, Download, Setting } from '@element-plus/icons-vue'
import { taskApi, settingsApi } from '@/api'
import dayjs from 'dayjs'

const tasks = ref<any[]>([])
const loading = ref(false)
const tableRef = ref<any>(null)
const selected = ref<any[]>([])
const selectedIds = computed(() => selected.value.map((r) => r.id))
const batchLoading = ref<'' | 'run' | 'cancel' | 'enable' | 'disable' | 'delete'>('')
const page = ref(1)
const size = ref(20)
const total = ref(0)
let timer: number | null = null

// 运行时信息：定时调度开关 + 守护进程状态
const runtime = ref<any>(null)
const sweeping = ref(false)
async function loadRuntime() {
  try {
    const res: any = await taskApi.runtime()
    runtime.value = (res && res.data) ? res.data : res
  } catch { /* 保持无 */ }
}
async function doSweep() {
  sweeping.value = true
  try {
    const res: any = await taskApi.watchdogSweep()
    const recovered = (res?.recovered ?? (res?.data?.recovered)) ?? 0
    ElMessage.success(recovered > 0 ? `守护巡检完成，回收 ${recovered} 条中断执行` : '守护巡检完成，未发现中断执行')
    await Promise.all([loadRuntime(), load()])
  } finally {
    sweeping.value = false
  }
}

// 执行记录抽屉
const runsVisible = ref(false)
const runsLoading = ref(false)
const currentTask = ref<any>(null)
const runs = ref<any[]>([])
const runsPage = ref(1)
const runsSize = ref(20)
const runsTotal = ref(0)
let runsTimer: number | null = null

async function load() {
  loading.value = true
  try {
    // 响应拦截器已解包信封，这里拿到的就是 { total, page, size, list }
    const res = await taskApi.list({ page: page.value, size: size.value })
    total.value = res.total ?? 0
    const maxPage = Math.max(1, Math.ceil(total.value / size.value))
    // 删除后当前页可能已经空了，退回最后一页；page 变化会由 watch 再拉一次
    if (page.value > maxPage) {
      page.value = maxPage
      return
    }
    tasks.value = res.list ?? []
    if (runsVisible.value && currentTask.value) await loadRuns()
  } finally {
    loading.value = false
  }
}

// 改每页条数时页码也会回到第 1 页，两个 ref 同帧变化只发一次请求
watch([page, size], load)

function onSelectionChange(rows: any[]) {
  selected.value = rows
}

function clearSelection() {
  tableRef.value?.clearSelection()
  selected.value = []
}

function statusLabel(s?: string) {
  switch (s) {
    case 'PENDING': return '排队中'
    case 'RUNNING': return '进行中'
    case 'COMPLETED': return '已完成'
    case 'FAILED': return '失败'
    case 'CANCELLED': return '已中止'
    case 'IDLE': return '未执行'
    default: return s || '未执行'
  }
}
function statusType(s?: string) {
  switch (s) {
    case 'COMPLETED': return 'success'
    case 'RUNNING': return 'warning'
    case 'FAILED': return 'danger'
    default: return 'info'
  }
}
function canCancel(row: any) {
  return row.status === 'PENDING' || row.status === 'RUNNING'
}
function typeLabel(t: string) {
  switch (t) {
    case 'account_sync': return '账户同步'
    case 'pst_export': return '邮件导出'
    case 'task_run_export': return '记录导出'
    default: return t
  }
}
function fmt(s?: string) {
  return s ? dayjs(s).format('YYYY-MM-DD HH:mm:ss') : '-'
}
function duration(ms?: number) {
  if (ms == null) return '-'
  const sec = Math.round(ms / 1000)
  if (sec < 60) return `${sec}s`
  const m = Math.floor(sec / 60)
  return `${m}m${sec % 60}s`
}
/** 周期展示：整分钟说分钟，否则说秒 */
function cycleLabel(sec?: number) {
  if (!sec) return '跟随默认'
  return sec % 60 === 0 ? `每 ${sec / 60} 分钟` : `每 ${sec} 秒`
}
/** 排程展示：定点任务说每天几点，间隔任务按周期秒数描述 */
function cycleText(row: any) {
  if (row?.scheduleMode === 'DAILY') return `每天 ${(row.dailyTime || '').slice(0, 5) || '未设置'}`
  return cycleLabel(row?.effectiveScheduleSec)
}
function fmtSize(bytes?: number) {
  if (bytes == null) return ''
  if (bytes < 1024) return `${bytes} B`
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KB`
  return `${(bytes / 1024 / 1024).toFixed(1)} MB`
}

async function run(row: any) {
  await taskApi.run(row.id)
  ElMessage.success('已开始执行，可在「记录」中查看进度')
  load()
}
async function cancel(row: any) {
  try {
    await ElMessageBox.confirm(`确认中止任务「${row.name}」当前这轮执行？`, '确认', { type: 'warning' })
  } catch {
    return
  }
  await taskApi.cancel(row.id)
  ElMessage.success('已中止本轮执行')
  load()
}
async function toggleEnabled(row: any) {
  if (row.enabled) {
    await taskApi.disable(row.id)
    ElMessage.success('已停用，不再自动执行')
  } else {
    await taskApi.enable(row.id)
    ElMessage.success('已启用，将按计划自动执行')
  }
  load()
}
async function removeOne(row: any) {
  try {
    await ElMessageBox.confirm(
      `确认删除任务「${row.name}」？其全部执行记录与产物记录也会一并删除，且不可恢复。`,
      '确认', { type: 'warning', confirmButtonText: '删除', cancelButtonText: '取消' }
    )
  } catch {
    return
  }
  const res = await taskApi.remove(row.id)
  ElMessage.success(`已删除 ${res.deleted} 条任务`)
  selected.value = selected.value.filter((r) => r.id !== row.id)
  await load()
}

function openRuns(row: any) {
  currentTask.value = row
  runsPage.value = 1
  selectedRuns.value = []
  runsVisible.value = true
  loadRuns()
  startRunsPolling()
}

async function loadRuns() {
  if (!currentTask.value) return
  runsLoading.value = true
  try {
    const res = await taskApi.runs(currentTask.value.id, { page: runsPage.value, size: runsSize.value })
    runsTotal.value = res.total ?? 0
    runs.value = res.list ?? []
  } finally {
    runsLoading.value = false
  }
  scheduleRunsPolling()
}

/** 抽屉打开且仍有进行中的执行时，3 秒刷一次 */
function scheduleRunsPolling() {
  stopRunsPolling()
  if (!runsVisible.value) return
  if (!runs.value.some((r) => canCancel(r))) return
  runsTimer = window.setTimeout(loadRuns, 3000)
}
function startRunsPolling() {
  stopRunsPolling()
  scheduleRunsPolling()
}
function stopRunsPolling() {
  if (runsTimer) { clearTimeout(runsTimer); runsTimer = null }
}

watch(runsVisible, (v) => { if (!v) { stopRunsPolling(); stopExportPolling(); runExportMsg.value = '' } })

// ---------------------------------------------------------------- 执行记录批量操作
const selectedRuns = ref<any[]>([])
const selectedRunIds = computed(() => selectedRuns.value.map((r) => r.id))
const runExportMsg = ref('')
let exportPollTimer: number | null = null

async function deleteRuns(all: boolean) {
  if (!all && !selectedRunIds.value.length) return
  const scope = all ? `任务「${currentTask.value?.name}」的全部执行记录` : `选中的 ${selectedRunIds.value.length} 条执行记录`
  try {
    await ElMessageBox.confirm(`确认删除${scope}？产物附件记录会一并删除，且不可恢复。`, '确认',
      { type: 'warning', confirmButtonText: '删除', cancelButtonText: '取消' })
  } catch {
    return
  }
  const res = await taskApi.batchDeleteRuns(all ? { taskId: currentTask.value.id } : { ids: selectedRunIds.value })
  ElMessage.success(`已删除 ${res.deleted} 条执行记录`)
  selectedRuns.value = []
  await loadRuns()
}

async function exportRuns(all: boolean) {
  if (!all && !selectedRunIds.value.length) return
  stopExportPolling()
  const res = await taskApi.batchExportRuns(all ? { taskId: currentTask.value.id } : { ids: selectedRunIds.value })
  runExportMsg.value = '记录导出任务已创建，正在后台执行…'
  pollRunExport(res.taskId)
}

function pollRunExport(taskId: number) {
  exportPollTimer = window.setTimeout(async () => {
    try {
      const s: any = await taskApi.status(taskId)
      if (s.status === 'PENDING' || s.status === 'RUNNING') {
        runExportMsg.value = `记录导出任务进行中（${s.progress || 0}%）`
        pollRunExport(taskId)
        return
      }
      runExportMsg.value = ''
      if (s.status === 'COMPLETED') {
        ElMessage.success((s.result || '导出完成') + '，请到该导出任务的「记录」中下载')
      } else {
        ElMessage.error('记录导出失败：' + (s.error || s.message || '未知错误'))
      }
      await load()
    } catch {
      runExportMsg.value = ''
      ElMessage.error('记录导出状态查询失败')
    }
  }, 2000)
}

function stopExportPolling() {
  if (exportPollTimer) { clearTimeout(exportPollTimer); exportPollTimer = null }
}

// ---------------------------------------------------------------- 保存策略
const policyVisible = ref(false)
const policySaving = ref(false)
const policy = reactive({ scope: 'ALL', days: 7, customDays: 30 })
/** 全局策略的当前值，任务级弹窗需要展示「跟随全局」的具体生效值 */
const globalPolicy = reactive({ scope: 'ALL', days: 7 })
let currentIntervalSec = 60

const SCOPE_LABEL: Record<string, string> = {
  ALL: '全部记录', SUCCESS: '成功记录', FAILED: '失败记录', NONE: '全不记录'
}
function scopeLabel(s?: string) {
  return SCOPE_LABEL[s || 'ALL']
}

/** 全局设置里没有排程字段时也要保留原间隔，保存策略不能顺手把默认频率重置。 */
async function loadGlobalPolicy() {
  try {
    const s: any = await settingsApi.get()
    currentIntervalSec = s?.defaultSyncIntervalSec ?? 60
    globalPolicy.scope = s?.runRetentionScope || 'ALL'
    globalPolicy.days = s?.runRetentionDays ?? 7
  } catch { /* 用默认值 */ }
}

async function openPolicy() {
  await loadGlobalPolicy()
  policy.scope = globalPolicy.scope
  policy.days = [1, 2, 7, 15].includes(globalPolicy.days) ? globalPolicy.days : 0
  policy.customDays = globalPolicy.days
  policyVisible.value = true
}

async function savePolicy() {
  const days = policy.days === 0 ? policy.customDays : policy.days
  if (!days || days < 1 || days > 3650) {
    ElMessage.warning('保存天数需在 1 ~ 3650 天之间')
    return
  }
  policySaving.value = true
  try {
    await settingsApi.update({
      defaultSyncIntervalSec: currentIntervalSec,
      runRetentionScope: policy.scope,
      runRetentionDays: days
    })
    ElMessage.success('执行记录保存策略已保存')
    policyVisible.value = false
  } finally {
    policySaving.value = false
  }
}

// ---------------------------------------------------------------- 任务级保存策略
const taskPolicyVisible = ref(false)
const taskPolicySaving = ref(false)
const taskPolicyTarget = ref<any>(null)
const taskPolicy = reactive({ follow: true, scope: 'ALL', days: 7, customDays: 30 })

function hasOwnPolicy(row: any) {
  return row.runRetentionScope != null || row.runRetentionDays != null
}
/** 列表按钮的悬浮说明：直接给出该任务当前生效的策略 */
function policySummary(row: any) {
  if (!hasOwnPolicy(row)) return `跟随全局：${scopeLabel(globalPolicy.scope)} / ${globalPolicy.days} 天`
  const scope = row.runRetentionScope ? scopeLabel(row.runRetentionScope) : scopeLabel(globalPolicy.scope)
  const days = row.runRetentionDays ?? globalPolicy.days
  return `本任务单独配置：${scope} / ${days} 天`
}

async function openTaskPolicy(row: any) {
  await loadGlobalPolicy()
  taskPolicyTarget.value = row
  const d = row.runRetentionDays ?? globalPolicy.days
  taskPolicy.follow = !hasOwnPolicy(row)
  taskPolicy.scope = row.runRetentionScope || globalPolicy.scope
  taskPolicy.days = [1, 2, 7, 15].includes(d) ? d : 0
  taskPolicy.customDays = d
  taskPolicyVisible.value = true
}

async function saveTaskPolicy() {
  const t = taskPolicyTarget.value
  if (!t) return
  let scope: string | null = null
  let days: number | null = null
  if (!taskPolicy.follow) {
    scope = taskPolicy.scope
    days = taskPolicy.days === 0 ? taskPolicy.customDays : taskPolicy.days
    if (!days || days < 1 || days > 3650) {
      ElMessage.warning('保存天数需在 1 ~ 3650 天之间')
      return
    }
  }
  taskPolicySaving.value = true
  try {
    await taskApi.setRunPolicy(t.id, scope, days)
    ElMessage.success(taskPolicy.follow ? '该任务已改为跟随全局策略' : '该任务的记录策略已保存')
    taskPolicyVisible.value = false
    await load()
  } finally {
    taskPolicySaving.value = false
  }
}

function download(a: any) {
  if (!a.available) {
    ElMessage.warning('产物文件已不在磁盘上，无法下载')
    return
  }
  // 必须用同页锚点触发：window.open 打开的是顶层导航，会被 PWA 的兜底页接管并跳回邮件列表
  const link = document.createElement('a')
  link.href = taskApi.attachmentUrl(a.id)
  link.download = a.name || 'attachment'
  link.click()
}

async function batchRun() {
  if (!selectedIds.value.length) return
  batchLoading.value = 'run'
  try {
    const res = await taskApi.batchRun(selectedIds.value)
    ElMessage.success(`已启动 ${res.started} 个任务${res.started < selectedIds.value.length ? '（正在执行或类型已下线的已跳过）' : ''}`)
    clearSelection()
    await load()
  } finally {
    batchLoading.value = ''
  }
}

async function batchCancel() {
  if (!selectedIds.value.length) return
  batchLoading.value = 'cancel'
  try {
    const res = await taskApi.batchCancel(selectedIds.value)
    ElMessage.success(`已中止 ${res.cancelled} 个任务的本轮执行`)
    clearSelection()
    await load()
  } finally {
    batchLoading.value = ''
  }
}

async function batchSetEnabled(enabled: boolean) {
  if (!selectedIds.value.length) return
  batchLoading.value = enabled ? 'enable' : 'disable'
  try {
    const ids = selected.value.filter((r) => r.periodic).map((r) => r.id)
    if (!ids.length) {
      ElMessage.warning('启用/停用只作用于周期任务，所选任务里没有周期任务')
      return
    }
    const res = enabled ? await taskApi.batchEnable(ids) : await taskApi.batchDisable(ids)
    ElMessage.success(`已${enabled ? '启用' : '停用'} ${res.updated} 个周期任务`)
    clearSelection()
    await load()
  } finally {
    batchLoading.value = ''
  }
}

async function batchDelete() {
  if (!selectedIds.value.length) return
  try {
    await ElMessageBox.confirm(
      `确认删除选中的 ${selectedIds.value.length} 条任务？其执行记录与产物记录也会一并删除，且不可恢复。`,
      '确认', { type: 'warning', confirmButtonText: '删除', cancelButtonText: '取消' }
    )
  } catch {
    return
  }
  batchLoading.value = 'delete'
  try {
    const res = await taskApi.batchDelete(selectedIds.value)
    ElMessage.success(`已删除 ${res.deleted} 条任务`)
    clearSelection()
    await load()
  } finally {
    batchLoading.value = ''
  }
}

onMounted(async () => {
  loadRuntime()
  await load()
  // 有进行中任务时轮询刷新进度
  timer = window.setInterval(() => {
    if (tasks.value.some((t) => canCancel(t))) load()
  }, 3000)
})
onUnmounted(() => {
  if (timer) window.clearInterval(timer)
  stopRunsPolling()
  stopExportPolling()
})
</script>

<style scoped>
.tasks-view { padding: 20px; }
.page-title { font-size: 20px; }
.tk-head { display: flex; align-items: center; justify-content: space-between; }
.tk-runtime { display: flex; align-items: center; gap: 8px; flex-wrap: wrap; margin-top: 12px;
  padding: 8px 12px; border: 1px solid var(--el-border-color-lighter); border-radius: 6px; background: var(--el-fill-color-lighter); }
.tk-runtime-note { color: var(--color-text-2); font-size: 12px; }
.tk-runtime-note code { background: var(--el-fill-color); padding: 1px 4px; border-radius: 3px; }
.card-head { font-weight: 600; }
.tk-toolbar { display: flex; align-items: center; gap: 4px; margin-bottom: 12px; flex-wrap: wrap; }
.tk-selected-hint { margin-left: 8px; color: var(--color-text-3); font-size: 12px; }
.tk-pager { display: flex; justify-content: flex-end; margin-top: 12px; }
.tk-error { color: var(--color-danger); }
.tk-cycle { margin-left: 6px; color: var(--color-text-3); font-size: 12px; }
.tk-result { color: var(--color-text-3); font-size: 12px; }
.tk-runs-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  color: var(--color-text-3);
  font-size: 12px;
  margin-bottom: 10px;
}
.tk-atts { display: flex; flex-direction: column; gap: 2px; }
.tk-policy-tip { color: var(--color-text-3); font-size: 12px; line-height: 1.6; }
.tk-att-name { margin-left: 4px; }
.tk-att-size { margin-left: 6px; color: var(--color-text-3); font-size: 12px; }
/* 操作列按钮一行排开，任务多时不换行 */
:deep(.tk-ops-cell) .cell {
  display: flex;
  align-items: center;
  flex-wrap: nowrap;
  gap: 4px;
}
:deep(.tk-ops-cell) .cell .el-button + .el-button,
:deep(.tk-ops-cell) .cell .el-button {
  margin-left: 0;
}
:deep(.tk-ops-cell) .cell .el-tooltip__trigger { display: inline-flex; }
</style>
