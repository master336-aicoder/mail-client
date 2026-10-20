<template>
  <div class="settings-page">
    <h2 class="page-title">邮件导入导出</h2>
    <p class="desc">将邮件导出为 EML/MBOX 归档供 Outlook、Thunderbird 等客户端导入，或从 PST 文件导入邮件。</p>

    <div class="two-col">
      <!-- 导出 -->
      <el-card class="card" shadow="never">
        <template #header><div class="card-head">导出邮件（EML / MBOX 归档）</div></template>
        <el-alert type="info" :closable="false" style="margin-bottom: 12px"
          title="导出为标准 EML(zip) 或 MBOX 归档文件，均为业界通用格式，可被邮件客户端导入。二进制 PST/OST 无法由本系统生成。" />
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
          <el-form-item label="导出格式">
            <el-radio-group v-model="exportForm.format">
              <el-radio value="zip">EML 打包 (.zip)</el-radio>
              <el-radio value="mbox">MBOX 归档 (.mbox)</el-radio>
            </el-radio-group>
            <div class="tip">EML：每封邮件一个 .eml，压缩包形式提供；MBOX：单个邮箱归档文件。两者均可被 Outlook/Thunderbird 等导入。</div>
          </el-form-item>
          <el-button type="primary" :loading="exporting" @click="doExport">开始导出</el-button>
        </el-form>
        <el-alert v-if="exportTask" :type="exportAlertType" :closable="false" style="margin-top: 12px"
          :title="`导出任务 ${statusLabel(exportTask.status)}，进度 ${exportTask.progress || 0}%`"
          :description="exportTask.error || exportTask.message || ''" />
        <div v-if="exportAttachments.length" class="export-atts">
          <div class="atts-head">导出产物</div>
          <el-link v-for="a in exportAttachments" :key="a.id" type="primary" underline="never"
            @click="download(a)">
            <el-icon><Download /></el-icon>
            <span class="att-name">{{ a.name }}</span>
            <span class="att-size">{{ fmtSize(a.size) }}</span>
          </el-link>
          <div class="atts-tip">每次导出在「后台任务」中新增一条一次性任务，历史产物可在其执行记录里重新下载。</div>
        </div>
      </el-card>

      <!-- 导入 -->
      <el-card class="card" shadow="never">
        <template #header><div class="card-head">从 PST 导入</div></template>
        <el-alert type="info" :closable="false" style="margin-bottom: 12px"
          title="支持 Outlook 的 .pst 文件；OST 无法直接解析，请先在 Outlook 中另存为 .pst。导入的邮件按原文件夹层级归入所选账户。" />
        <el-form label-width="90px">
          <el-form-item label="账户">
            <el-select v-model="importForm.accountId" placeholder="选择导入到哪个账户" style="width: 100%">
              <el-option v-for="a in mail.accounts" :key="a.id" :label="a.email" :value="a.id" />
            </el-select>
          </el-form-item>
          <el-form-item label="根文件夹">
            <el-input v-model="importForm.folderName" maxlength="60" placeholder="PST 导入" />
            <span class="tip">PST 顶层内容归入该文件夹名，其下子文件夹自动创建</span>
          </el-form-item>
          <el-form-item label="选择文件">
            <el-upload
              :auto-upload="false"
              :limit="1"
              accept=".pst"
              :file-list="importFiles"
              :on-change="onFileChange"
              :on-remove="onFileRemove"
              :on-exceed="onExceed">
              <el-button>选择 .pst 文件</el-button>
              <template #tip><div class="tip">单个文件不超过 100 MB</div></template>
            </el-upload>
          </el-form-item>
          <el-button type="primary" :loading="importing" :disabled="!importForm.accountId || !pendingFile"
            @click="doImport">上传并导入</el-button>
        </el-form>

        <el-alert v-if="importTask" :type="importAlertType" :closable="false" style="margin-top: 12px"
          :title="`导入任务 ${statusLabel(importTask.status)}，进度 ${importTask.progress || 0}%`"
          :description="importTask.error || importTask.message || ''" />
        <div v-if="importTask?.status === 'COMPLETED'" class="tip" style="margin-top:8px">
          邮件已进入所选账户的文件夹，可在「邮件管理」左侧目录中查看。
        </div>
      </el-card>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, reactive, computed, onMounted, onBeforeUnmount } from 'vue'
import { ElMessage } from 'element-plus'
import type { UploadFile, UploadUserFile } from 'element-plus'
import { Download } from '@element-plus/icons-vue'
import { useMailStore } from '@/stores/mail'
import { transferApi, taskApi } from '@/api'

const mail = useMailStore()
const exportForm = reactive({ accountId: undefined as number | undefined, folderIds: [] as number[], format: 'zip' as 'zip' | 'mbox' })
const exporting = ref(false)
const exportTask = ref<any>(null)
let pollTimer: any = null

// 导入：两步（上传落盘 → 提交异步任务），产物与进度走通用任务状态接口
const importForm = reactive({ accountId: undefined as number | undefined, folderName: 'PST 导入' })
const importFiles = ref<UploadUserFile[]>([])
const pendingFile = ref<File | null>(null)
const importing = ref(false)
const importTask = ref<any>(null)
let importPollTimer: any = null

function onFileChange(file: UploadFile) {
  const raw = file.raw as File | undefined
  if (!raw) return
  const lower = (raw.name || '').toLowerCase()
  if (lower.endsWith('.ost')) {
    ElMessage.warning('OST 无法直接解析，请在 Outlook 中另存为 .pst 后再导入')
    importFiles.value = []
    pendingFile.value = null
    return
  }
  if (!lower.endsWith('.pst')) {
    ElMessage.warning('仅支持 .pst 文件')
    importFiles.value = []
    pendingFile.value = null
    return
  }
  importFiles.value = [file as UploadUserFile]
  pendingFile.value = raw
}

function onFileRemove() {
  importFiles.value = []
  pendingFile.value = null
}

function onExceed(files: File[]) {
  // 只保留最新选择的一个
  const f = files[0]
  if (f) onFileChange({ name: f.name, raw: f, status: 'ready', uid: Date.now() } as unknown as UploadFile)
}

async function doImport() {
  if (!importForm.accountId) { ElMessage.warning('请选择导入的目标账户'); return }
  if (!pendingFile.value) { ElMessage.warning('请选择要导入的 .pst 文件'); return }
  importing.value = true
  importTask.value = null
  try {
    const up: any = await transferApi.uploadImport(pendingFile.value, { silentError: true, timeout: 300000 } as any)
    const task: any = await transferApi.startImport({
      uploadId: up.uploadId,
      accountId: importForm.accountId,
      folderName: importForm.folderName || undefined
    })
    importTask.value = { taskId: task.taskId, status: task.status, progress: task.progress }
    ElMessage.success('导入任务已创建，正在后台解析')
    pollImport()
  } catch (e: any) {
    ElMessage.error('导入失败：' + (e?.message || '请检查账户与文件后重试'))
  } finally {
    importing.value = false
  }
}

async function pollImport() {
  if (!importTask.value?.taskId) return
  try {
    const s: any = await transferApi.status(importTask.value.taskId)
    importTask.value = s
    if (s.status === 'RUNNING' || s.status === 'PENDING') {
      scheduleImportPoll()
    } else {
      stopImportPoll()
      if (s.status === 'COMPLETED') ElMessage.success(s.message || '导入完成')
      else if (s.status === 'FAILED') ElMessage.error('导入失败：' + (s.error || s.message || '未知错误'))
    }
  } catch {
    stopImportPoll()
  }
}

function scheduleImportPoll() {
  stopImportPoll()
  importPollTimer = setTimeout(pollImport, 1500)
}

function stopImportPoll() {
  if (importPollTimer) { clearTimeout(importPollTimer); importPollTimer = null }
}

const importAlertType = computed(() => {
  const s = importTask.value?.status
  if (s === 'COMPLETED') return 'success'
  if (s === 'FAILED') return 'error'
  if (s === 'CANCELLED') return 'warning'
  return 'info'
})

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
    const task: any = await transferApi.startExport(exportForm.accountId, exportForm.folderIds, exportForm.format, { silentError: true })
    // 后端返回 {taskId,status,progress}，产物在轮询到完成时随状态返回
    exportTask.value = { taskId: task.taskId, status: task.status, progress: task.progress, attachments: [] }
    ElMessage.success('导出任务已创建，正在后台执行')
    pollExport()
  } catch (e: any) {
    ElMessage.error('创建导出任务失败：' + (e?.message || '请检查账户与文件夹选择后重试'))
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

const exportAttachments = computed<any[]>(() => exportTask.value?.attachments ?? [])

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

function fmtSize(bytes?: number) {
  if (bytes == null) return ''
  if (bytes < 1024) return `${bytes} B`
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KB`
  return `${(bytes / 1024 / 1024).toFixed(1)} MB`
}

function download(a: any) {
  if (!a.available) {
    ElMessage.warning('产物文件已不在磁盘上，无法下载')
    return
  }
  // 必须用同页锚点触发：window.open 打开的是顶层导航，会被 PWA 的兜底页接管并跳回邮件列表
  const link = document.createElement('a')
  link.href = taskApi.attachmentUrl(a.id)
  link.download = a.name || 'export.pst'
  link.click()
}

const exportAlertType = computed(() => {
  const s = exportTask.value?.status
  if (s === 'COMPLETED') return 'success'
  if (s === 'FAILED') return 'error'
  if (s === 'CANCELLED') return 'warning'
  return 'info'
})

onBeforeUnmount(() => { stopPoll(); stopImportPoll() })
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
.export-atts {
  margin-top: 10px;
  border: 1px solid var(--color-border);
  border-radius: var(--radius-md);
  padding: 10px 12px;
  display: flex;
  flex-direction: column;
  gap: 4px;
}
.atts-head { font-weight: 600; font-size: 13px; margin-bottom: 2px; }
.att-name { margin-left: 4px; }
.att-size { margin-left: 6px; color: var(--color-text-3); font-size: 12px; }
.atts-tip { color: var(--color-text-3); font-size: 12px; margin-top: 4px; }
.tip { color: var(--color-text-3); font-size: 12px; }
</style>