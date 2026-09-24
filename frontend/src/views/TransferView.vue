<template>
  <div class="settings-page">
    <h2 class="page-title">PST 导入导出</h2>
    <p class="desc">将邮件导出为 PST 文件供 Outlook 读取，或从 PST/OST 文件导入邮件。</p>

    <div class="two-col">
      <!-- 导出 -->
      <el-card class="card" shadow="never">
        <template #header><div class="card-head">导出到 PST（供 Outlook 读取）</div></template>
        <el-form label-width="90px">
          <el-form-item label="账户">
            <el-select v-model="exportForm.accountId" placeholder="选择账户" style="width: 100%" @change="onAccountChange">
              <el-option v-for="a in mail.accounts" :key="a.id" :label="a.email" :value="a.id" />
            </el-select>
          </el-form-item>
          <el-form-item label="文件夹">
            <div class="folder-select">
              <div class="folder-select-head">
                <el-checkbox :model-value="selectAllState" :indeterminate="indeterminate" @change="onToggleAll">
                  全选所有文件夹
                </el-checkbox>
                <span class="folder-count">{{ exportForm.folderIds.length }}/{{ accountFolders.length }}</span>
              </div>
              <el-checkbox-group v-model="exportForm.folderIds">
                <el-checkbox v-for="f in accountFolders" :key="f.id" :value="f.id">{{ f.name }}</el-checkbox>
              </el-checkbox-group>
            </div>
          </el-form-item>
          <el-button type="primary" :loading="exporting" @click="doExport">开始导出</el-button>
        </el-form>
        <el-alert v-if="exportTask" :type="exportAlertType" :closable="false" style="margin-top: 12px"
          :title="`导出任务 ${exportTask.status || ''}，进度 ${exportTask.progress || 0}%`" />
        <el-button v-if="exportTask && exportTask.downloadUrl" type="success" plain
          style="margin-top: 8px" @click="downloadExport">
          <el-icon><Download /></el-icon> 下载导出文件
        </el-button>
      </el-card>

      <!-- 导入 -->
      <el-card class="card" shadow="never">
        <template #header><div class="card-head">从 PST / OST 导入</div></template>
        <el-upload drag :auto-upload="false" :on-change="onImportFile" accept=".pst,.ost">
          <el-icon class="el-icon--upload"><UploadFilled /></el-icon>
          <div class="el-upload__text">将 PST/OST 文件拖到此处，或<em>点击上传</em></div>
          <template #tip>
            <div class="el-upload__tip">最大 100MB，仅导入邮件，不改变原始文件。</div>
          </template>
        </el-upload>
        <el-input v-model="importFile.name" disabled placeholder="选择 .pst 或 .ost 文件" style="margin-top: 8px" />
        <el-button type="primary" :disabled="!importFile.name" style="margin-top: 12px">开始导入</el-button>
      </el-card>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, reactive, computed, onMounted, onBeforeUnmount } from 'vue'
import { ElMessage } from 'element-plus'
import { Download } from '@element-plus/icons-vue'
import { useMailStore } from '@/stores/mail'
import { transferApi } from '@/api'

const mail = useMailStore()
const exportForm = reactive({ accountId: undefined as number | undefined, folderIds: [] as number[] })
const exporting = ref(false)
const exportTask = ref<any>(null)
const importFile = ref({ name: '' })
let pollTimer: any = null

// 选定账户后仅展示该账户专属文件夹（过滤掉“全部账户”合并文件夹，避免同名重复）
const accountFolders = computed(() => {
  const aid = exportForm.accountId
  if (!aid) return []
  return (mail.folders || []).filter((f: any) => f.isMerged !== true && Number(f.accountId) === Number(aid))
})
const allFolderIds = computed(() => accountFolders.value.map((f: any) => f.id))
const selectAllState = computed(() =>
  allFolderIds.value.length > 0 && exportForm.folderIds.length === allFolderIds.value.length)
const indeterminate = computed(() =>
  exportForm.folderIds.length > 0 && exportForm.folderIds.length < allFolderIds.value.length)

function onAccountChange() {
  exportForm.folderIds = []
}

function onToggleAll(checked: boolean | string | number) {
  exportForm.folderIds = checked ? [...allFolderIds.value] : []
}

onMounted(async () => {
  await mail.loadAccounts()
  await mail.loadFolders()
})

async function doExport() {
  if (!exportForm.accountId) {
    ElMessage.warning('请选择账户')
    return
  }
  if (exportForm.folderIds.length === 0) {
    ElMessage.warning('请至少选择一个文件夹')
    return
  }
  exporting.value = true
  try {
    const task: any = await transferApi.startExport(exportForm.accountId, exportForm.folderIds)
    // 后端返回 {taskId,status,progress}
    exportTask.value = { ...(exportTask.value || {}), ...task }
    ElMessage.success('导出任务已创建，正在后台执行')
    pollExport()
  } catch (e: any) {
    ElMessage.error('创建导出任务失败：' + (e?.message || e))
  } finally {
    exporting.value = false
  }
}

async function pollExport() {
  if (!exportTask.value?.taskId) return
  try {
    const s: any = await transferApi.status(exportTask.value.taskId)
    exportTask.value = s
    if (s.status === 'RUNNING' || s.status === 'PENDING') {
      schedulePoll()
    } else {
      stopPoll()
      if (s.status === 'COMPLETED') {
        ElMessage.success('导出完成，可下载文件')
      } else if (s.status === 'FAILED') {
        ElMessage.error('导出失败：' + (s.error || s.message || '未知错误'))
      }
    }
  } catch (e) {
    // 失败后停止轮询
    stopPoll()
  }
}

function schedulePoll() {
  stopPoll()
  pollTimer = setTimeout(pollExport, 1500)
}

function stopPoll() {
  if (pollTimer) { clearTimeout(pollTimer); pollTimer = null }
}

function downloadExport() {
  if (!exportTask.value?.taskId) return
  window.open(transferApi.downloadUrl(exportTask.value.taskId), '_blank')
}

const exportAlertType = computed(() => {
  const s = exportTask.value?.status
  if (s === 'COMPLETED') return 'success'
  if (s === 'FAILED') return 'error'
  if (s === 'CANCELLED') return 'warning'
  return 'info'
})

onBeforeUnmount(stopPoll)

function onImportFile(file: any) {
  importFile.value = { name: file.name }
}
</script>

<style scoped>
.settings-page { padding: 20px; }
.page-title { font-size: 20px; }
.desc { color: var(--color-text-3); font-size: 13px; margin-top: 6px; margin-bottom: 16px; }
.card-head { font-weight: 600; }
.two-col { display: grid; grid-template-columns: 1fr 1fr; gap: 16px; }
@media (max-width: 768px) { .two-col { grid-template-columns: 1fr; } }
.folder-select { width: 100%; }
.folder-select-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 4px;
}
.folder-count { color: var(--color-text-3); font-size: 12px; }
.folder-select .el-checkbox-group {
  max-height: 220px;
  overflow-y: auto;
  border: 1px solid var(--color-border);
  border-radius: var(--radius-md);
  padding: 8px 10px;
  display: flex;
  flex-direction: column;
  gap: 6px;
}
</style>