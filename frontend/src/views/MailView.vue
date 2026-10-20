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
          <el-dropdown trigger="click" size="small" @command="(cmd: string) => onFolderAction(cmd, f)" @click.stop>
            <el-button size="small" text class="folder-action-btn" @click.stop>
              <el-icon><MoreFilled /></el-icon>
            </el-button>
            <template #dropdown>
              <el-dropdown-menu>
                <el-dropdown-item command="rename">重命名</el-dropdown-item>
                <el-dropdown-item command="delete" divided>删除</el-dropdown-item>
              </el-dropdown-menu>
            </template>
          </el-dropdown>
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
      <!-- 批量操作条 -->
      <div class="batch-bar">
        <el-checkbox :model-value="allChecked" :indeterminate="someChecked" size="small" @change="toggleAll">
          全选本页
        </el-checkbox>
        <span class="batch-count">{{ checkedIds.length ? `已选 ${checkedIds.length} 封` : `共 ${mail.total} 封` }}</span>
        <span class="batch-flex" />
        <el-button size="small" text :disabled="!checkedIds.length" @click="batchFlags({ read: true })">已读</el-button>
        <el-button size="small" text :disabled="!checkedIds.length" @click="batchFlags({ read: false })">未读</el-button>
        <el-button size="small" text :disabled="!checkedIds.length" @click="batchFlags({ starred: true })">
          星标
        </el-button>
        <el-button size="small" text :disabled="!checkedIds.length" @click="batchFlags({ starred: false })">
          取消星标
        </el-button>
        <el-dropdown trigger="click" size="small" @command="onMoveTo">
          <el-button size="small" text :disabled="!checkedIds.length">
            移动到<el-icon><ArrowDown /></el-icon>
          </el-button>
          <template #dropdown>
            <el-dropdown-menu>
              <el-dropdown-item v-if="!moveTargets.length" disabled>没有可用的目标文件夹</el-dropdown-item>
              <el-dropdown-item v-for="t in moveTargets" :key="t.id" :command="t.id">{{ t.label }}</el-dropdown-item>
            </el-dropdown-menu>
          </template>
        </el-dropdown>
        <el-button size="small" text type="danger" :disabled="!checkedIds.length" @click="batchDeleteSelected">
          删除
        </el-button>
        <el-dropdown trigger="click" size="small" @command="onFolderBatch">
          <el-button size="small" text>
            整个文件夹<el-icon><ArrowDown /></el-icon>
          </el-button>
          <template #dropdown>
            <el-dropdown-menu>
              <el-dropdown-item command="read">全部标记已读</el-dropdown-item>
              <el-dropdown-item command="clear" divided>清空该文件夹</el-dropdown-item>
            </el-dropdown-menu>
          </template>
        </el-dropdown>
      </div>
      <el-scrollbar class="list-scroll">
        <div v-loading="mail.loading" class="msg-list">
          <div v-for="m in mail.messages" :key="m.id" class="msg-item"
            :class="{ unread: !m.read, selected: activeMessage === m.id }" @click="selectMessage(m)">
            <el-checkbox class="msg-check" :model-value="isChecked(m.id)" @click.stop
              @change="() => toggleCheck(m.id)" />
            <div class="msg-body">
              <div class="msg-subject">{{ m.subject || '（无主题）' }}</div>
              <div class="msg-meta">
                <span class="msg-sender" :class="{ bold: !m.read }">
                  <el-icon v-if="m.hasAttach"><Paperclip /></el-icon>
                  {{ m.sender }}
                </span>
                <span class="msg-date">{{ fmtDate(m.date) }}</span>
              </div>
              <div class="msg-preview">{{ m.preview || '' }}</div>
            </div>
          </div>
          <el-empty v-if="!mail.loading && mail.messages.length === 0" description="暂无邮件" />
        </div>
      </el-scrollbar>
      <div class="list-pager">
        <el-pagination size="small" layout="prev, pager, next" :total="mail.total"
          :page-size="50" :current-page="curPage" @current-change="load" />
      </div>
    </div>

    <!-- 阅读区 -->
    <div class="reading-pane" v-if="detail">
      <div class="detail-header">
        <div class="detail-title">{{ detail.subject }}</div>
        <div class="detail-meta">
          <div class="meta-row" v-if="senderAddr">
            <span class="meta-label">发件人：</span>
            <span class="addr">
              <template v-if="senderAddr.name"><span class="addr-name">{{ senderAddr.name }}</span><span class="addr-mail"
                @click="onAddrClick(senderAddr.email)">&lt;{{ senderAddr.email }}&gt;</span></template>
              <span v-else class="addr-mail" @click="onAddrClick(senderAddr.email)">{{ senderAddr.email }}</span>
            </span>
          </div>
          <div class="meta-row" v-if="recipientAddrs.length">
            <span class="meta-label">收件人：</span>
            <span v-for="(a, i) in recipientAddrs" :key="'r' + i" class="addr-wrap">
              <span v-if="i > 0" class="addr-sep">；</span>
              <span class="addr">
                <template v-if="a.name"><span class="addr-name">{{ a.name }}</span><span class="addr-mail"
                  @click="onAddrClick(a.email)">&lt;{{ a.email }}&gt;</span></template>
                <span v-else class="addr-mail" @click="onAddrClick(a.email)">{{ a.email }}</span>
              </span>
            </span>
          </div>
          <div class="meta-row" v-if="ccAddrs.length">
            <span class="meta-label">抄送：</span>
            <span v-for="(a, i) in ccAddrs" :key="'c' + i" class="addr-wrap">
              <span v-if="i > 0" class="addr-sep">；</span>
              <span class="addr">
                <template v-if="a.name"><span class="addr-name">{{ a.name }}</span><span class="addr-mail"
                  @click="onAddrClick(a.email)">&lt;{{ a.email }}&gt;</span></template>
                <span v-else class="addr-mail" @click="onAddrClick(a.email)">{{ a.email }}</span>
              </span>
            </span>
          </div>
          <div class="meta-row">
            <span class="meta-label">时间：</span>
            <span>{{ fmtDate(detail.date) }}</span>
          </div>
        </div>
        <div class="detail-actions">
          <el-button size="small" circle :type="isStar ? 'warning' : 'default'" @click="toggleStarDetail">
            <el-icon><Star /></el-icon>
          </el-button>
          <!-- 转发对收件箱/已发送/草稿箱都可用：草稿转发是「以此内容另起一封」，不回写原草稿 -->
          <el-button size="small" @click="openForward">
            <el-icon><Share /></el-icon> 转发
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
            <el-button size="small" @click="openReplyAll">
              <el-icon><RefreshLeft /></el-icon> 回复全部
            </el-button>
            <el-button size="small" type="danger" plain @click="removeDetail">
              <el-icon><Delete /></el-icon> 删除
            </el-button>
          </template>
        </div>
      </div>
      <div v-if="detail.attachments?.length" class="attach-panel">
        <div class="attach-title">附件</div>
        <div class="attach-cards">
          <div v-for="at in detail.attachments" :key="at.id" class="attach-card"
            title="点击下载" @click="download(at)">
            <el-icon class="a-icon"><Document /></el-icon>
            <div class="a-info">
              <div class="a-name">{{ at.name }}</div>
              <div class="a-size">{{ fmtSize(at.size) }}</div>
            </div>
          </div>
        </div>
      </div>
      <iframe ref="frameEl" class="body-frame" frameborder="0" :srcdoc="frameDoc" scrolling="no"
        @load="resizeFrame" title="邮件正文"></iframe>
    </div>
    <div class="reading-pane empty" v-else>
      <el-empty description="选择一封邮件阅读" />
    </div>

    <!-- 回复 / 编辑草稿 抽屉 -->
    <el-drawer v-model="replyOpen" :title="composeTitle" size="780px" :close-on-press-escape="false" :close-on-click-modal="false">
      <ComposeDrawer v-if="replyOpen" :accounts="mail.accounts" :initial="replyInit" @sent="onComposeSent" @saved="onComposeSaved" />
    </el-drawer>

    <!-- 新建文件夹 -->
    <el-dialog v-model="foldOpen" title="新建文件夹" width="420px">
      <el-form label-width="90px">
        <el-form-item label="账户" required>
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

    <!-- 重命名文件夹 -->
    <el-dialog v-model="renameOpen" title="重命名文件夹" width="420px">
      <el-form label-width="90px">
        <el-form-item label="文件夹名" required>
          <el-input v-model="renameName" placeholder="输入新名称" maxlength="128" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="renameOpen = false">取消</el-button>
        <el-button type="primary" :loading="renameSaving" @click="renameFolder">确定</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { ref, computed, onMounted, watch, onUnmounted, nextTick } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { useMailStore } from '@/stores/mail'
import { mailApi, folderApi, syncApi } from '@/api'
import ComposeDrawer from '@/components/ComposeDrawer.vue'
import { Search, Plus, Refresh, Edit, Promotion, Share, MoreFilled } from '@element-plus/icons-vue'
import dayjs from 'dayjs'
import DOMPurify from 'dompurify'

const mail = useMailStore()
const route = useRoute()
const router = useRouter()

const keyword = ref('')
const detail = ref<any>(null)
const replyOpen = ref(false)
const replyInit = ref<any>(null)
const composeTitle = ref('回复邮件')
const sendingDraft = ref(false)

// 渲染用正文：把正文里的 cid: 引用解析为内嵌资源地址。
// - 若同步阶段已捕获该内嵌图片（detail.attachments 中有对应 Content-ID），改写为 /api/inline/{id}；
// - 若邮件未携带 / 未捕获到图片字节，则替换为透明占位图，避免浏览器尝试加载 cid: 触发失败与破图。
const PLACEHOLDER_IMG = 'data:image/gif;base64,R0lGODlhAQABAIAAAAAAAP///yH5BAEAAAAALAAAAAABAAEAAAIBRAA7'
const displayBody = computed(() => {
  const html = detail.value?.bodyHtml
  const text = detail.value?.bodyText
  return renderBody(html ?? (text ? `<p>${text}</p>` : ''))
})
function normalizeCid(raw: string) {
  return String(raw).trim().replace(/^[<]/, '').replace(/[>]$/, '')
}
function renderBody(html: string) {
  if (!html) return ''
  // 建立 Content-ID → 附件 id 映射（后端 AttachmentMeta.contentId 已随详情返回）
  const byCid = new Map<string, number>()
  for (const a of (detail.value?.attachments || [])) {
    if (a && a.contentId) byCid.set(normalizeCid(a.contentId), a.id)
  }
  // 1) 改写正文中的 cid: 引用（src/href 属性，以及 CSS url(cid:...)）
  let out = html.replace(/((?:src|href)\s*=\s*)(["'])cid:([^"']+)\2/gi, (_m, attr, q, cid) => {
    const id = byCid.get(normalizeCid(cid))
    return id != null ? `${attr}${q}/api/inline/${id}${q}` : `${attr}${q}${PLACEHOLDER_IMG}${q}`
  })
  out = out.replace(/url\(\s*["']?cid:([^"')]+)["']?\s*\)/gi, (_m, cid) => {
    const id = byCid.get(normalizeCid(cid))
    return id != null ? `url("/api/inline/${id}")` : `url("${PLACEHOLDER_IMG}")`
  })
  // 内嵌资源经 HttpOnly 会话 Cookie 鉴权，无需在 URL 追加 token
  // 2) 净化：邮件 HTML 来自不可信发件人，去除脚本/事件处理器等，防存储型 XSS
  return DOMPurify.sanitize(out, {
    USE_PROFILES: { html: true },
    // style 标签会在注入页面时把选择器作用于整个应用（选中邮件后整页样式漂移的根因），
    // 只保留内联 style 属性；iframe 隔离后仍禁止 <style> 以防万一
    FORBID_TAGS: ['style'],
    ADD_ATTR: ['target'],
    ALLOW_DATA_ATTR: false
  })
}

// 邮件正文包成完整 srcdoc 文档：iframe 天然隔离邮件 CSS/标签，不再影响外层页面布局。
const frameDoc = computed(() => `<!doctype html><html><head><meta charset="utf-8">` +
  `<base target="_blank"><style>` +
  `html{overflow:hidden}body{margin:0;padding:12px 16px;background:#fff;color:#374151;` +
  `font-family:-apple-system,"Segoe UI","Microsoft YaHei",Arial,sans-serif;` +
  `font-size:14px;line-height:1.7;word-break:break-word;overflow-wrap:anywhere}` +
  `img{max-width:100%;height:auto}table{max-width:100%}` +
  `a{color:#2563EB}` +
  `</style></head><body>${displayBody.value}</body></html>`)

// srcdoc 与主页面同源：父窗口可直接量取正文高度来撑开 iframe（正文已被 DOMPurify
// 去除脚本，iframe 内不会执行邮件自带 JS）。图片为异步加载，需在其完成后复测，
// 否则长邮件/带图邮件会被截断显示不全。
const frameEl = ref<HTMLIFrameElement>()
function resizeFrame() {
  const f = frameEl.value
  const doc = f?.contentDocument
  if (!f || !doc) return
  const apply = () => {
    const h = Math.max(doc.body?.scrollHeight || 0, doc.documentElement?.scrollHeight || 0)
    if (h > 0) f.style.height = (h + 16) + 'px'
  }
  apply()
  doc.querySelectorAll?.('img').forEach((img) => {
    if (!(img as HTMLImageElement).complete) {
      img.addEventListener('load', apply)
      img.addEventListener('error', apply)
    }
  })
  setTimeout(apply, 600)
  setTimeout(apply, 1800)
}
watch(frameDoc, () => nextTick(resizeFrame))
const foldOpen = ref(false)
const foldSaving = ref(false)
const foldAccountId = ref<number | undefined>(undefined)
const foldName = ref('')
const renameOpen = ref(false)
const renameSaving = ref(false)
const renameFolderId = ref<number | null>(null)
const renameName = ref('')
const selectedId = ref<number>(0)
const refreshing = ref(false)
const isStar = computed(() => (detail.value?.flags || 0) & 2)

// ---------------------------------------------------------------- 批量操作
const checkedIds = ref<number[]>([])
const curPage = ref(1)
const allChecked = computed(() =>
  mail.messages.length > 0 && checkedIds.value.length === mail.messages.length)
const someChecked = computed(() => checkedIds.value.length > 0 && !allChecked.value)

function isChecked(id: number) {
  return checkedIds.value.includes(id)
}
function toggleCheck(id: number) {
  checkedIds.value = isChecked(id)
    ? checkedIds.value.filter((x) => x !== id)
    : [...checkedIds.value, id]
}
function toggleAll() {
  checkedIds.value = allChecked.value ? [] : mail.messages.map((m) => m.id)
}

async function batchFlags(flags: { read?: boolean; starred?: boolean; important?: boolean }) {
  const ids = [...checkedIds.value]
  if (!ids.length) return
  const res: any = await mailApi.batchFlags(ids, flags)
  ElMessage.success(`已更新 ${res?.updated ?? ids.length} 封`)
  await afterBatch()
}

async function batchDeleteSelected() {
  const ids = [...checkedIds.value]
  if (!ids.length) return
  try {
    await ElMessageBox.confirm(
      `确定删除选中的 ${ids.length} 封邮件吗？删除后不可恢复。`,
      '批量删除',
      { type: 'warning', confirmButtonText: '删除', cancelButtonText: '取消' }
    )
  } catch {
    return
  }
  const res: any = await mailApi.batchDelete(ids)
  ElMessage.success(`已删除 ${res?.deleted ?? ids.length} 封`)
  if (detail.value && ids.includes(detail.value.id)) {
    detail.value = null
    selectedId.value = 0
  }
  await afterBatch()
}

async function onMoveTo(folderId: number) {
  const ids = [...checkedIds.value]
  if (!ids.length) return
  const res: any = await mailApi.batchMove(ids, folderId)
  const moved = res?.moved ?? 0
  ElMessage.success(moved ? `已移动 ${moved} 封` : '选中的邮件已在该文件夹中')
  await afterBatch()
}

async function onFolderBatch(cmd: string) {
  const folderId = Number(activeFolder.value)
  if (!folderId) return
  const folderName = currentFolderName.value
  if (cmd === 'read') {
    const res: any = await mailApi.markFolderRead(folderId)
    ElMessage.success(`已将 ${res?.updated ?? 0} 封标记为已读`)
    await afterBatch()
    return
  }
  if (cmd === 'clear') {
    try {
      await ElMessageBox.confirm(
        `确定清空文件夹「${folderName}」吗？该文件夹内所有邮件都会被删除，操作不可恢复。`,
        '清空文件夹',
        { type: 'warning', confirmButtonText: '确认清空', cancelButtonText: '取消' }
      )
    } catch {
      return
    }
    const res: any = await mailApi.clearFolder(folderId)
    ElMessage.success(`已清空「${folderName}」，共移除 ${res?.deleted ?? 0} 封`)
    await afterBatch()
  }
}

/** 批量操作后统一收尾：清选择态、刷新列表与文件夹计数。 */
async function afterBatch() {
  checkedIds.value = []
  await load(curPage.value)
  mail.loadFolders().catch(() => {})
}

const activeFolder = computed(() => route.params.id ? String(route.params.id) : '1')
const currentFolderName = computed(() => {
  const flat = (mail.folders || []).flatMap((f: any) => [f, ...(f.children || [])])
  return flat.find((f: any) => String(f.id) === activeFolder.value)?.name || '当前文件夹'
})
const activeAccount = computed(() => route.query.accountId ? Number(route.query.accountId) : 0)
/**
 * 「移动到」候选：只有具体账户下的文件夹才是邮件的真实存放位置，
 * 落到合并视图上邮件就再也查不到了，因此合并视图与当前文件夹都不列入。
 */
const moveTargets = computed(() => {
  const cur = Number(activeFolder.value)
  const out: Array<{ id: number; label: string }> = []
  for (const f of (mail.folders || []) as any[]) {
    if (f.isMerged || f.accountId == null) continue
    const owner = f.accountEmail || '账户' + f.accountId
    for (const node of [f, ...(f.children || [])]) {
      if (node.id === cur) continue
      out.push({ id: node.id, label: `${node.nameZh || node.name} · ${owner}` })
    }
  }
  return out
})
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
  // 无路由指定文件夹时，定位“当前用户自己的”默认收件箱并跳转，
  // 不能写死文件夹 id（不同用户的收件箱 id 不同，写死会误访问他人文件夹）。
  if (!route.params.id) {
    if (!mail.folders || mail.folders.length === 0) {
      try { await mail.loadFolders() } catch { /* 拦截器已提示 */ }
    }
    const folders = mail.folders || []
    const inbox =
      folders.find((f: any) => f.isMerged && f.name === '收件箱') ||
      folders.find((f: any) => f.isMerged)
    if (inbox) {
      await router.replace(`/mail/folder/${inbox.id}`)
      return
    }
  }
  const folderId = Number(activeFolder.value)
  const accountId = activeAccount.value || undefined
  const q = keyword.value.trim() || undefined
  selectedId.value = 0
  checkedIds.value = []
  curPage.value = page
  detail.value = null
  try {
    await mail.loadMessages(folderId, page, 50, { accountId, q })
  } catch { /* 请求失败已由 http 拦截器统一提示，避免 onMounted 未捕获异常 */ }
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
  // F15: 切换文件夹时清空搜索关键词，避免旧搜索条件影响新文件夹的列表
  keyword.value = ''
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
  const isDraft = !!detail.value.isDraft
  const label = isDraft ? '草稿' : '邮件'
  try {
    await ElMessageBox.confirm(
      `确定删除该${label}吗？删除后不可恢复。`,
      `删除${label}`,
      { type: 'warning', confirmButtonText: '删除', cancelButtonText: '取消', confirmButtonClass: 'el-btn--danger' }
    )
  } catch {
    return // 用户取消
  }
  await mail.remove(detail.value.id)
  detail.value = null
  selectedId.value = 0
  // F20: 删除后刷新列表与侧栏计数
  await load(1)
  await mail.loadFolders()
}

// 当前阅读邮件所属账户的邮箱（回复全部时把自己从收件人里剔除）
function ownEmailOf(d: any): string {
  const acc = mail.accounts.find((a: any) => Number(a.id) === Number(d?.accountId))
  return (acc?.email || '').toLowerCase()
}

function openReply() {
  if (!detail.value) return
  replyInit.value = { to: [detail.value.senderEmail], subject: `Re: ${detail.value.subject || ''}` }
  composeTitle.value = '回复邮件'
  replyOpen.value = true
}

// 回复全部：发件人 + 原收件人 + 抄送，去重并剔除本账户自身地址，确保「所有收件人」都在列。
function openReplyAll() {
  if (!detail.value) return
  const d = detail.value
  const own = ownEmailOf(d)
  const seen = new Set<string>()
  const to: string[] = []
  const push = (e?: string) => {
    const k = (e || '').trim().toLowerCase()
    if (!k || k === own || seen.has(k)) return
    seen.add(k); to.push((e || '').trim())
  }
  push(d.senderEmail)
  emailList(d.recipients).forEach(push)
  emailList(d.cc).forEach(push)
  replyInit.value = { to: to.length ? to : [d.senderEmail], subject: `Re: ${d.subject || ''}` }
  composeTitle.value = '回复全部'
  replyOpen.value = true
}

// —— 阅读页收发件人展示：把存储的地址串解析为 {name,email}，供模板渲染成「名称<地址>」卡片 ——
function parseAddr(raw: string): { name: string; email: string } {
  const s = (raw || '').trim()
  if (!s) return { name: '', email: '' }
  const m = s.match(/^(.*?)<([^>]+)>$/)
  if (m) return { name: m[1].trim().replace(/^"|"$/g, ''), email: m[2].trim() }
  return { name: '', email: s }
}
function addrList(s?: string) {
  return splitRecipients(s).map(parseAddr).filter((a) => a.email)
}
const senderAddr = computed(() => {
  const d = detail.value
  if (!d) return null
  const email = (d.senderEmail || '').trim()
  const name = (d.sender || '').trim()
  const addr = email || name
  if (!addr) return null
  return { name: name && name !== addr ? name : '', email: addr }
})
const recipientAddrs = computed(() => addrList(detail.value?.recipients))
const ccAddrs = computed(() => addrList(detail.value?.cc))

// 点击邮件地址：复制到剪贴板，并以该地址预填回复窗口（可复制 + 可回复）
async function onAddrClick(email: string) {
  if (!email) return
  await copyText(email)
  ElMessage.success(`已复制 ${email}`)
  replyTo(email)
}
function replyTo(email: string) {
  if (!detail.value) return
  replyInit.value = { to: [email], subject: `Re: ${detail.value.subject || ''}` }
  composeTitle.value = '回复邮件'
  replyOpen.value = true
}
async function copyText(text: string) {
  try {
    await navigator.clipboard.writeText(text)
  } catch {
    const ta = document.createElement('textarea')
    ta.value = text
    ta.style.position = 'fixed'
    ta.style.opacity = '0'
    document.body.appendChild(ta)
    ta.select()
    try { document.execCommand('copy') } catch { /* ignore */ }
    document.body.removeChild(ta)
  }
}

// 转发：正文头部带上原邮件信息，正文按阅读区同一套 cid 改写 + DOMPurify 净化后再引用，
// 普通附件（非内嵌图片）作为「带入附件」交给写信抽屉，发送时复制成新附件，原邮件附件不受影响。
function openForward() {
  if (!detail.value) return
  const d = detail.value
  const head = (label: string, value?: string) =>
    value ? `<p>${label}：${escapeHtml(value)}</p>` : ''
  const quote =
    `<p>---------- 转发邮件 ----------</p>` +
    head('发件人', d.sender || d.senderEmail) +
    head('日期', d.date ? dayjs(d.date).format('YYYY-MM-DD HH:mm') : '') +
    head('主题', d.subject || '(无主题)') +
    head('收件人', d.recipients) +
    `<p><br></p>`
  replyInit.value = {
    accountId: d.accountId,
    subject: `Fwd: ${d.subject || ''}`,
    bodyHtml: quote + (displayBody.value || `<p>${escapeHtml(d.bodyText || '')}</p>`),
    attachments: (d.attachments || []).filter((a: any) => !a.contentId && a.disposition !== 'inline')
  }
  composeTitle.value = '转发邮件'
  replyOpen.value = true
}

function escapeHtml(s: string) {
  return String(s ?? '').replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;')
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

// 写信抽屉发送成功：关闭抽屉并刷新列表
async function onComposeSent() {
  replyOpen.value = false
  replyInit.value = {}
  // F20: 发送后刷新列表与侧栏计数（如果是从草稿发送，草稿会被删除）
  await load(1)
  await mail.loadFolders()
}

// 写信抽屉保存草稿成功：关闭抽屉并刷新列表（草稿计数）
async function onComposeSaved() {
  replyOpen.value = false
  replyInit.value = {}
  await load()
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
      bodyHtml: d.bodyHtml || '',
      attachments: (d.attachments || []).map((a: any) => a.id)
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

// 按 RFC 822 语义切分地址列表：引号 "..." 与尖括号 <...> 内部的逗号/分号不作分隔符，
// 这样 `"Zhang,Dong(RI/BJ)" <a@x>, b@y` 不会被拆成残片。中英文逗号/分号均可作顶层分隔。
function splitRecipients(s?: string): string[] {
  if (!s) return []
  const out: string[] = []
  let cur = ''
  let inQuote = false
  let angle = 0
  for (let i = 0; i < s.length; i++) {
    const ch = s[i]
    if (ch === '"' && s[i - 1] !== '\\') { inQuote = !inQuote; cur += ch; continue }
    if (!inQuote && ch === '<') { angle++; cur += ch; continue }
    if (!inQuote && ch === '>' && angle > 0) { angle--; cur += ch; continue }
    if (!inQuote && angle === 0 && (ch === ',' || ch === ';' || ch === '；' || ch === '，')) {
      if (cur.trim()) out.push(cur.trim())
      cur = ''
      continue
    }
    cur += ch
  }
  if (cur.trim()) out.push(cur.trim())
  return out
}

// 从存储的地址串提取纯邮箱列表（去重、保序），供回复全部 / 转发 / 发送草稿预填收件人。
function emailList(s?: string): string[] {
  const seen = new Set<string>()
  const res: string[] = []
  for (const a of addrList(s)) {
    const e = (a.email || '').toLowerCase()
    if (e && !seen.has(e)) { seen.add(e); res.push(a.email) }
  }
  return res
}

function download(at: any) {
  const a = document.createElement('a')
  a.href = `/api/attachments/${at.id}/content`
  a.download = at.name
  a.click()
}

function openNewFolder() {
  foldAccountId.value = mail.accounts[0]?.id
  foldName.value = ''
  foldOpen.value = true
}

async function createFolder() {
  const name = foldName.value.trim()
  if (!name) {
    ElMessage.warning('请输入文件夹名称')
    return
  }
  if (!foldAccountId.value) {
    ElMessage.warning('请选择目标账户')
    return
  }
  foldSaving.value = true
  try {
    await folderApi.create({
      name,
      accountId: foldAccountId.value
    })
    ElMessage.success('文件夹已创建')
    foldOpen.value = false
    await mail.loadFolders()
  } finally {
    foldSaving.value = false
  }
}

function onFolderAction(cmd: string, f: any) {
  if (cmd === 'rename') {
    renameFolderId.value = f.id
    renameName.value = f.name
    renameOpen.value = true
  } else if (cmd === 'delete') {
    deleteFolder(f)
  }
}

async function renameFolder() {
  const name = renameName.value.trim()
  if (!name) {
    ElMessage.warning('请输入文件夹名称')
    return
  }
  if (!renameFolderId.value) return
  renameSaving.value = true
  try {
    await folderApi.update(renameFolderId.value, { name })
    ElMessage.success('文件夹已重命名')
    renameOpen.value = false
    await mail.loadFolders()
  } finally {
    renameSaving.value = false
  }
}

async function deleteFolder(f: any) {
  try {
    await ElMessageBox.confirm(
      `确定删除文件夹「${f.name}」吗？该文件夹内的所有邮件都会被删除，操作不可恢复。`,
      '删除文件夹',
      { type: 'warning', confirmButtonText: '删除', cancelButtonText: '取消', confirmButtonClass: 'el-btn--danger' }
    )
  } catch {
    return
  }
  await folderApi.remove(f.id)
  ElMessage.success('文件夹已删除')
  // 如果删除的是当前正在查看的文件夹，跳转到默认收件箱
  if (String(f.id) === activeFolder.value) {
    await mail.loadFolders()
    const folders = mail.folders || []
    const inbox = folders.find((x: any) => x.isMerged && x.name === '收件箱') || folders.find((x: any) => x.isMerged)
    if (inbox) {
      await router.push(`/mail/folder/${inbox.id}`)
    }
  } else {
    await mail.loadFolders()
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
.folder-action-btn {
  opacity: 0;
  transition: opacity 0.2s;
  padding: 2px;
  margin-left: auto;
}
.folder-item:hover .folder-action-btn { opacity: 1; }
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
.batch-bar {
  display: flex;
  align-items: center;
  gap: 4px;
  padding: 4px 10px;
  border-bottom: 1px solid var(--color-border-light);
  background: var(--color-bg);
  font-size: 12px;
  flex-wrap: wrap;
}
.batch-count { color: var(--color-text-3); }
.batch-flex { flex: 1; }
.msg-check { margin-top: 2px; }
.msg-item {
  display: flex;
  align-items: flex-start;
  gap: 8px;
  padding: 12px 14px;
  border-bottom: 1px solid var(--color-border-light);
  cursor: pointer;
}
.msg-item:hover { background: var(--color-bg); }
.msg-item.selected { background: var(--color-primary-light); }
.msg-item.unread .msg-subject { font-weight: 600; }
.msg-body { flex: 1; min-width: 0; }
.msg-sender {
  color: var(--color-text-2); font-size: 13px;
  overflow: hidden; text-overflow: ellipsis; white-space: nowrap;
}
.msg-sender.bold { font-weight: 600; }
.msg-date { color: var(--color-text-4); font-size: 12px; flex: none; }
.msg-meta {
  margin-top: 4px; display: flex; justify-content: space-between;
  align-items: baseline; gap: 12px;
}
.msg-subject {
  color: var(--color-text); font-size: 14px;
  overflow: hidden; text-overflow: ellipsis; white-space: nowrap;
}
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
.detail-meta { display: flex; flex-direction: column; gap: 6px; margin-top: 8px; color: var(--color-text-3); font-size: 13px; }
.meta-row { display: flex; flex-wrap: wrap; align-items: center; }
.meta-label { color: var(--color-text-3); flex-shrink: 0; }
.addr-wrap { display: inline-flex; align-items: center; }
.addr {
  display: inline-flex; align-items: center;
  background: var(--color-primary-light); border-radius: var(--radius-sm); padding: 1px 8px;
}
.addr-name { color: var(--color-text-2); }
.addr-mail { color: var(--color-primary); cursor: pointer; word-break: break-all; }
.addr-mail:hover { text-decoration: underline; }
.addr-sep { color: var(--color-text-3); margin: 0 2px; }
.detail-actions { margin-top: 10px; display: flex; gap: 6px; }
.body-frame {
  width: 100%;
  flex: 1;
  min-height: 240px;
  margin-top: 16px;
  border: 1px solid var(--color-border-light);
  border-radius: var(--radius-sm);
  background: #fff;
}
.attach-panel {
  margin-top: 16px;
  padding: 12px 14px;
  background: var(--color-primary-light);
  border: 1px solid var(--color-border-light);
  border-radius: var(--radius-md);
}
.attach-title { font-size: 13px; font-weight: 600; color: var(--color-text-2); margin-bottom: 10px; }
.attach-cards { display: flex; flex-wrap: wrap; gap: 10px; }
.attach-card {
  display: flex; align-items: center; gap: 10px;
  min-width: 160px; max-width: 240px;
  padding: 8px 12px; cursor: pointer;
  background: #fff; border: 1px solid var(--color-border-light); border-radius: var(--radius-sm);
  transition: box-shadow .15s, border-color .15s, transform .15s;
}
.attach-card:hover {
  border-color: var(--color-primary);
  box-shadow: 0 2px 8px rgba(37, 99, 235, .12);
  transform: translateY(-1px);
}
.a-icon { font-size: 22px; color: var(--color-primary); flex-shrink: 0; }
.a-info { display: flex; flex-direction: column; min-width: 0; }
.a-name { font-size: 13px; color: var(--color-text-2); overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.a-size { font-size: 12px; color: var(--color-text-3); }

@media (max-width: 1024px) {
  .mail-view { grid-template-columns: 180px 320px 1fr; }
}
@media (max-width: 768px) {
  .mail-view { grid-template-columns: 1fr; grid-template-rows: 1fr 1fr; overflow-y: auto; }
  .folder-pane { height: 160px; }
  .reading-pane { min-height: 400px; }
}
</style>