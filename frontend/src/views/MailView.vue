<template>
  <div class="mail-view">
    <!-- 文件夹树 -->
    <div class="folder-pane">
      <div class="pane-title">
        <span>文件夹</span>
        <el-button size="small" text type="primary" :icon="Plus" @click="openNewFolder">新建文件夹</el-button>
      </div>
      <!-- 合并视图（全部账户） -->
      <template v-if="mergedFolders.length">
        <div class="group-title">全部账户</div>
        <div v-for="f in mergedFolders" :key="f.id" class="folder-item"
          :class="{ active: String(f.id) === activeFolder }" @click="selectFolder(f)">
          <el-icon><Folder /></el-icon>
          <span class="f-name">{{ f.name }}</span>
          <span v-if="f.total > 0" class="f-count">{{ f.total }}</span>
        </div>
      </template>

      <!-- 各账户文件夹 -->
      <template v-for="grp in accountGroups" :key="grp.accountId">
        <div class="group-title ellipsis" :title="grp.email">{{ grp.email }}</div>
        <div v-for="f in grp.folders" :key="f.id" class="folder-item"
          :class="{ active: String(f.id) === activeFolder }" @click="selectFolder(f)">
          <el-icon><Folder /></el-icon>
          <span class="f-name">{{ f.name }}</span>
          <span v-if="f.total > 0" class="f-count">{{ f.total }}</span>
        </div>
      </template>
    </div>

    <!-- 邮件列表 -->
    <div class="list-pane">
      <div class="list-toolbar">
        <el-input v-model="keyword" placeholder="搜索主题 / 发件人" clearable size="small"
          style="flex: 1" :prefix-icon="Search" @keyup.enter="onSearch" @clear="load(1)" />
        <el-button size="small" @click="load(1)">搜索</el-button>
        <el-button size="small" type="primary" plain :loading="refreshing" @click="refreshMails">
          <el-icon><Refresh /></el-icon> 刷新
        </el-button>
      </div>
      <el-scrollbar class="list-scroll">
        <div v-loading="mail.loading" class="msg-list">
          <div v-for="m in mail.messages" :key="m.id" class="msg-item"
            :class="{ unread: !m.read, selected: activeMessage === m.id }" @click="selectMessage(m)">
            <div class="msg-top">
              <span class="msg-sender" :class="{ bold: !m.read }">
                <el-icon v-if="m.hasAttach"><Paperclip /></el-icon>
                {{ m.sender }}
              </span>
              <span class="msg-date">{{ fmtDate(m.date) }}</span>
            </div>
            <div class="msg-subject">{{ m.subject || '（无主题）' }}</div>
            <div class="msg-preview">{{ m.preview || '' }}</div>
          </div>
          <el-empty v-if="!mail.loading && mail.messages.length === 0" description="暂无邮件" />
        </div>
      </el-scrollbar>
      <div class="list-pager">
        <el-pagination small layout="prev, pager, next" :total="mail.total"
          :page-size="50" @current-change="load" />
      </div>
    </div>

    <!-- 阅读区 -->
    <div class="reading-pane" v-if="detail">
      <div class="detail-header">
        <div class="detail-title">{{ detail.subject }}</div>
        <div class="detail-meta">
          <span>发件人：{{ detail.sender }}</span>
          <span>{{ fmtDate(detail.date) }}</span>
        </div>
        <div class="detail-actions">
          <el-button size="small" circle :type="isStar ? 'warning' : 'default'" @click="toggleStarDetail">
            <el-icon><Star /></el-icon>
          </el-button>
          <!-- 草稿：仅 编辑 / 发送 / 删除，不提供回复 -->
          <template v-if="detail.isDraft">
            <el-button size="small" type="primary" @click="openEditDraft">
              <el-icon><Edit /></el-icon> 编辑
            </el-button>
            <el-button size="small" type="success" :loading="sendingDraft" @click="sendDraft">
              <el-icon><Promotion /></el-icon> 发送
            </el-button>
            <el-button size="small" type="danger" plain @click="removeDetail">
              <el-icon><Delete /></el-icon> 删除
            </el-button>
          </template>
          <template v-else>
            <el-button size="small" @click="openReply">
              <el-icon><RefreshLeft /></el-icon> 回复
            </el-button>
            <el-button size="small" type="danger" plain @click="removeDetail">
              <el-icon><Delete /></el-icon> 删除
            </el-button>
          </template>
        </div>
      </div>
      <el-scrollbar class="body-scroll">
        <div class="detail-body" v-html="displayBody"></div>
      </el-scrollbar>
      <div v-if="detail.attachments?.length" class="attach-list">
        <div v-for="at in detail.attachments" :key="at.id" class="attach-item">
          <el-icon><Document /></el-icon>
          <span class="a-name">{{ at.name }}</span>
          <el-button size="small" type="primary" link @click="download(at)">
            {{ fmtSize(at.size) }}
          </el-button>
        </div>
      </div>
    </div>
    <div class="reading-pane empty" v-else>
      <el-empty description="选择一封邮件阅读" />
    </div>

    <!-- 回复 / 编辑草稿 抽屉 -->
    <el-drawer v-model="replyOpen" :title="composeTitle" size="560px">
      <ComposeDrawer v-if="replyOpen" :accounts="mail.accounts" :initial="replyInit" @sent="replyOpen = false" />
    </el-drawer>

    <!-- 新建文件夹 -->
    <el-dialog v-model="foldOpen" title="新建文件夹" width="420px">
      <el-form label-width="90px">
        <el-form-item label="归属" required>
          <el-radio-group v-model="foldScope">
            <el-radio value="all">全部账户</el-radio>
            <el-radio value="account">指定账户</el-radio>
          </el-radio-group>
        </el-form-item>
        <el-form-item v-if="foldScope === 'account'" label="账户" required>
          <el-select v-model="foldAccountId" placeholder="选择账户" style="width: 100%">
            <el-option v-for="a in mail.accounts" :key="a.id" :label="a.email" :value="a.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="文件夹名" required>
          <el-input v-model="foldName" placeholder="如：工作、项目归档" maxlength="128" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="foldOpen = false">取消</el-button>
        <el-button type="primary" :loading="foldSaving" @click="createFolder">创建</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { ref, computed, onMounted, watch, onUnmounted } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { useMailStore } from '@/stores/mail'
import { mailApi, folderApi, syncApi } from '@/api'
import ComposeDrawer from '@/components/ComposeDrawer.vue'
import { Search, Plus, Refresh, Edit, Promotion } from '@element-plus/icons-vue'
import dayjs from 'dayjs'

const mail = useMailStore()
const route = useRoute()
const router = useRouter()

const keyword = ref('')
const detail = ref<any>(null)
const replyOpen = ref(false)
const replyInit = ref<any>(null)
const composeTitle = ref('回复邮件')
const sendingDraft = ref(false)

// 渲染用正文：为内嵌图片地址追加 access_token，使 <img> 可正常加载（无 Authorization 头）
const displayBody = computed(() => {
  const html = detail.value?.bodyHtml
  const text = detail.value?.bodyText
  return renderBody(html ?? (text ? `<p>${text}</p>` : ''))
})
function renderBody(html: string) {
  if (!html) return ''
  const token = localStorage.getItem('access_token')
  if (!token) return html
  // 给所有未带 token 的内嵌图片地址追加 access_token
  return html.replace(/(src=["'])\.?(\/api\/inline\/[^"']*?)["']/g, (m, q, url) => {
    const sep = url.includes('?') ? '&' : '?'
    return `${q}${url}${sep}access_token=${encodeURIComponent(token)}${q}`
  })
}
const foldOpen = ref(false)
const foldSaving = ref(false)
const foldScope = ref<'all' | 'account'>('all')
const foldAccountId = ref<number | undefined>(undefined)
const foldName = ref('')
const selectedId = ref<number>(0)
const refreshing = ref(false)
const isStar = computed(() => (detail.value?.flags || 0) & 2)

const activeFolder = computed(() => route.params.id ? String(route.params.id) : '1')
const activeAccount = computed(() => route.query.accountId ? Number(route.query.accountId) : 0)
const activeMessage = computed(() => route.params.messageId ? Number(route.params.messageId) : selectedId.value)

// 合并视图（全部账户）：accountId 为 null
const mergedFolders = computed(() => (mail.folders || []).filter((f: any) => f.isMerged))
// 各账户文件夹分组
const accountGroups = computed(() => {
  const groups: any[] = []
  const map = new Map<number, any>()
  for (const f of mail.folders || []) {
    if (f.isMerged) continue
    const aid = f.accountId as number
    if (aid === undefined || aid === null) continue
    if (!map.has(aid)) {
      const g = { accountId: aid, email: f.accountEmail || '', folders: [] as any[] }
      map.set(aid, g)
      groups.push(g)
    }
    map.get(aid)!.folders.push(f)
  }
  return groups
})

function fmtDate(d?: string) {
  if (!d) return ''
  const t = dayjs(d)
  return t.format('MM-DD HH:mm')
}
function fmtSize(s?: number) {
  if (!s) return ''
  return s > 1024 * 1024 ? (s / 1024 / 1024).toFixed(1) + ' MB' : Math.ceil(s / 1024) + ' KB'
}

async function load(page = 1) {
  const folderId = Number(activeFolder.value)
  const accountId = activeAccount.value || undefined
  const q = keyword.value.trim() || undefined
  selectedId.value = 0
  detail.value = null
  await mail.loadMessages(folderId, page, 50, { accountId, q })
}

function onSearch() {
  load(1)
}

async function refreshMails() {
  refreshing.value = true
  try {
    await syncApi.all()
    ElMessage.success('已提交收取任务，正在后台同步…')
    // 稍等片刻后刷新当前文件夹
    setTimeout(() => { load(1) }, 500)
  } finally {
    refreshing.value = false
  }
}

function selectFolder(f: any) {
  const q = f.accountId ? `?accountId=${f.accountId}` : ''
  router.push(`/mail/folder/${f.id}${q}`)
}

async function selectMessage(m: any) {
  selectedId.value = m.id
  try {
    const d = await mailApi.detail(m.id)
    detail.value = d
    if (!d.read) {
      mail.setRead(d.id, true)
      d.read = true
    }
  } catch {
    detail.value = null
  }
}

async function toggleStarDetail() {
  if (!detail.value) return
  await mail.toggleStar(detail.value.id)
  detail.value.flags = isStar.value ? (detail.value.flags || 0) & ~2 : (detail.value.flags || 0) | 2
}

async function removeDetail() {
  if (!detail.value) return
  await mail.remove(detail.value.id)
  detail.value = null
  selectedId.value = 0
}

function openReply() {
  if (!detail.value) return
  replyInit.value = { to: [detail.value.senderEmail], subject: `Re: ${detail.value.subject || ''}` }
  composeTitle.value = '回复邮件'
  replyOpen.value = true
}

// 打开草稿编辑：以草稿的数据回填写邮件窗口
function openEditDraft() {
  if (!detail.value) return
  const d = detail.value
  replyInit.value = {
    accountId: d.accountId,
    to: splitRecipients(d.recipients),
    cc: splitRecipients(d.cc),
    bcc: splitRecipients(d.bcc),
    subject: d.subject || '',
    bodyHtml: d.bodyHtml || '',
    draftId: d.id
  }
  composeTitle.value = '编辑草稿'
  replyOpen.value = true
}

// 发送草稿（等效于点开草稿后点发送，直接按草稿内容走 SMTP 发送）
async function sendDraft() {
  if (!detail.value) return
  const d = detail.value
  if (!d.accountId) {
    ElMessage.warning('草稿缺少发件账户')
    return
  }
  sendingDraft.value = true
  try {
    await mailApi.send({
      accountId: d.accountId,
      to: splitRecipients(d.recipients),
      cc: splitRecipients(d.cc),
      bcc: splitRecipients(d.bcc),
      subject: d.subject || '',
      bodyHtml: d.bodyHtml || ''
    })
    ElMessage.success('邮件已发送')
    // 已发送则删除草稿
    await mail.remove(d.id)
    detail.value = null
    selectedId.value = 0
    await load()
  } finally {
    sendingDraft.value = false
  }
}

function splitRecipients(s?: string): string[] {
  if (!s) return []
  return s.split(/[,;；，]/).map((x) => x.trim()).filter(Boolean)
}

function download(at: any) {
  const a = document.createElement('a')
  a.href = `/api/attachments/${at.id}/content`
  a.download = at.name
  a.click()
}

function openNewFolder() {
  foldScope.value = 'all'
  foldAccountId.value = undefined
  foldName.value = ''
  foldOpen.value = true
}

async function createFolder() {
  const name = foldName.value.trim()
  if (!name) {
    ElMessage.warning('请输入文件夹名称')
    return
  }
  if (foldScope.value === 'account' && !foldAccountId.value) {
    ElMessage.warning('请选择目标账户')
    return
  }
  foldSaving.value = true
  try {
    await folderApi.create({
      name,
      accountId: foldScope.value === 'account' ? foldAccountId.value : null
    })
    ElMessage.success('文件夹已创建')
    foldOpen.value = false
    await mail.loadFolders()
  } finally {
    foldSaving.value = false
  }
}

watch(() => route.params.id, () => { detail.value = null; selectedId.value = 0; load(); })
watch(() => route.params.messageId, () => { const id = route.params.messageId; if (id) selectMessage({ id: Number(id) }) })

onMounted(load)
onUnmounted(() => {})
</script>

<style scoped>
.mail-view {
  display: grid;
  grid-template-columns: 220px 360px 1fr;
  height: 100%;
}
.folder-pane {
  border-right: 1px solid var(--color-border);
  background: var(--color-surface);
  padding: 8px;
  overflow-y: auto;
}
.pane-title {
  font-size: 12px;
  color: var(--color-text-3);
  padding: 6px 8px;
  text-transform: uppercase;
  letter-spacing: 0.5px;
  display: flex;
  align-items: center;
  justify-content: space-between;
}
.group-title {
  font-size: 12px;
  font-weight: 600;
  color: var(--color-text-3);
  padding: 10px 10px 4px;
  letter-spacing: 0.3px;
}
.folder-item {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 8px 10px;
  border-radius: var(--radius-md);
  color: var(--color-text-2);
  cursor: pointer;
  margin-bottom: 2px;
}
.folder-item:hover { background: var(--color-bg); }
.folder-item.active { background: var(--color-primary-light); color: var(--color-primary); }
.f-name { flex: 1; }
.f-count {
  font-size: 12px;
  background: var(--color-primary-light);
  color: var(--color-primary);
  padding: 0 6px;
  border-radius: 10px;
}
.ellipsis { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }

.list-pane {
  border-right: 1px solid var(--color-border);
  display: flex;
  flex-direction: column;
  background: var(--color-surface);
  min-width: 0;
}
.list-toolbar {
  display: flex;
  gap: 8px;
  padding: 10px;
  border-bottom: 1px solid var(--color-border-light);
}
.list-scroll { flex: 1; }
.msg-item {
  padding: 12px 14px;
  border-bottom: 1px solid var(--color-border-light);
  cursor: pointer;
}
.msg-item:hover { background: var(--color-bg); }
.msg-item.selected { background: var(--color-primary-light); }
.msg-item.unread .msg-subject { font-weight: 600; }
.msg-top { display: flex; justify-content: space-between; align-items: center; }
.msg-sender { color: var(--color-text-2); font-size: 13px; }
.msg-sender.bold { font-weight: 600; }
.msg-date { color: var(--color-text-4); font-size: 12px; }
.msg-subject { margin-top: 4px; color: var(--color-text); font-size: 14px; }
.msg-preview {
  margin-top: 2px; color: var(--color-text-3); font-size: 12px;
  overflow: hidden; text-overflow: ellipsis; white-space: nowrap;
}
.list-pager {
  padding: 8px;
  border-top: 1px solid var(--color-border-light);
  display: flex;
  justify-content: center;
}

.reading-pane {
  display: flex;
  flex-direction: column;
  padding: 20px;
  background: var(--color-surface);
  overflow: hidden;
  min-width: 0;
}
.reading-pane.empty { align-items: center; justify-content: center; }
.detail-header { border-bottom: 1px solid var(--color-border-light); padding-bottom: 12px; }
.detail-title { font-size: 18px; font-weight: 600; }
.detail-meta { display: flex; gap: 16px; margin-top: 8px; color: var(--color-text-3); font-size: 13px; }
.detail-actions { margin-top: 10px; display: flex; gap: 6px; }
.body-scroll { flex: 1; margin-top: 16px; }
.detail-body { line-height: 1.7; color: var(--color-text-2); word-break: break-word; }
.attach-list { border-top: 1px solid var(--color-border-light); padding-top: 12px; }
.attach-item {
  display: flex; align-items: center; gap: 8px; padding: 6px 0;
}
.a-name { flex: 1; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }

@media (max-width: 1024px) {
  .mail-view { grid-template-columns: 180px 320px 1fr; }
}
@media (max-width: 768px) {
  .mail-view { grid-template-columns: 1fr; grid-template-rows: 1fr 1fr; overflow-y: auto; }
  .folder-pane { height: 160px; }
  .reading-pane { min-height: 400px; }
}
</style>