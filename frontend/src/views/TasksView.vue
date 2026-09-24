<template>
  <div class="tasks-view">
    <div class="tk-head">
      <h2 class="page-title">后台任务</h2>
      <el-button size="small" @click="load" :loading="loading">刷新</el-button>
    </div>
    <el-card class="card" shadow="never" style="margin-top: 16px">
      <el-table :data="tasks" v-loading="loading" stripe style="width: 100%">
        <el-table-column label="任务名称" min-width="200">
          <template #default="{ row }">{{ row.name }}</template>
        </el-table-column>
        <el-table-column label="类型" width="140">
          <template #default="{ row }">{{ typeLabel(row.type) }}</template>
        </el-table-column>
        <el-table-column label="状态" width="120">
          <template #default="{ row }">
            <el-tag :type="statusType(row.status)" effect="light">{{ statusLabel(row.status) }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="进度" width="180">
          <template #default="{ row }">
            <el-progress :percentage="row.progress" :stroke-width="8"
              :status="row.status === 'FAILED' ? 'exception' : row.status === 'COMPLETED' ? 'success' : ''" />
          </template>
        </el-table-column>
        <el-table-column label="开始时间" width="170">
          <template #default="{ row }">{{ fmt(row.startedAt) }}</template>
        </el-table-column>
        <el-table-column label="结束时间" width="170">
          <template #default="{ row }">{{ fmt(row.finishedAt) }}</template>
        </el-table-column>
        <el-table-column label="耗时" width="120">
          <template #default="{ row }">{{ duration(row.durationMs) }}</template>
        </el-table-column>
        <el-table-column label="操作" width="200" fixed="right">
          <template #default="{ row }">
            <el-button v-if="canRetry(row)" size="small" type="primary" plain @click="retry(row)">重试</el-button>
            <el-button v-if="canCancel(row)" size="small" type="danger" plain @click="cancel(row)">中止</el-button>
          </template>
        </el-table-column>
        <template #empty>暂无后台任务</template>
      </el-table>
    </el-card>
  </div>
</template>

<script setup lang="ts">
import { ref, onMounted, onUnmounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { taskApi } from '@/api'
import dayjs from 'dayjs'

const tasks = ref<any[]>([])
const loading = ref(false)
let timer: number | null = null

async function load() {
  loading.value = true
  try {
    const res: any = await taskApi.list()
    tasks.value = (res && res.data) ? res.data : []
  } finally {
    loading.value = false
  }
}

function statusLabel(s: string) {
  switch (s) {
    case 'PENDING': return '未开始'
    case 'RUNNING': return '进行中'
    case 'COMPLETED': return '已完成'
    case 'FAILED': return '失败'
    case 'CANCELLED': return '已中止'
    default: return s
  }
}
function statusType(s: string) {
  switch (s) {
    case 'COMPLETED': return 'success'
    case 'RUNNING': return 'warning'
    case 'FAILED': return 'danger'
    case 'CANCELLED': return 'info'
    default: return 'info'
  }
}
function canRetry(row: any) {
  return row.status === 'FAILED' || row.status === 'CANCELLED'
}
function canCancel(row: any) {
  return row.status === 'PENDING' || row.status === 'RUNNING'
}
function typeLabel(t: string) {
  switch (t) {
    case 'account_sync': return '账户同步'
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
  const s = sec % 60
  return `${m}m${s}s`
}

async function retry(row: any) {
  await taskApi.retry(row.id)
  ElMessage.success('已重新提交任务')
  load()
}
async function cancel(row: any) {
  try {
    await ElMessageBox.confirm(`确认中止任务「${row.name}」？`, '确认', { type: 'warning' })
  } catch {
    return
  }
  await taskApi.cancel(row.id)
  ElMessage.success('已中止任务')
  load()
}

onMounted(async () => {
  await load()
  // 有进行中任务时轮询刷新进度
  timer = window.setInterval(() => {
    if (tasks.value.some((t) => t.status === 'RUNNING' || t.status === 'PENDING')) load()
  }, 3000)
})
onUnmounted(() => {
  if (timer) window.clearInterval(timer)
})
</script>

<style scoped>
.tasks-view { padding: 20px; }
.page-title { font-size: 20px; }
.tk-head { display: flex; align-items: center; justify-content: space-between; }
.card-head { font-weight: 600; }
</style>