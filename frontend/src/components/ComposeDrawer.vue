<template>
  <div class="compose">
    <el-form label-position="top" class="compose-form">
      <el-form-item label="发件账户">
        <el-select v-model="accountId" placeholder="选择账户" style="width: 100%">
          <el-option v-for="a in accounts" :key="a.id" :label="a.email" :value="a.id" />
        </el-select>
      </el-form-item>
      <el-form-item label="收件人">
        <el-select v-model="to" multiple filterable allow-create default-first-option
          placeholder="输入或多个收件人，回车确认" style="width: 100%" />
      </el-form-item>
      <el-form-item label="抄送" class="cc-field">
        <el-select v-model="cc" multiple filterable allow-create default-first-option
          placeholder="抄送（可选）" style="width: 100%" />
      </el-form-item>
      <el-form-item label="密送" class="cc-field">
        <el-select v-model="bcc" multiple filterable allow-create default-first-option
          placeholder="密送（收件人不可见）" style="width: 100%" />
      </el-form-item>
      <el-form-item label="主题">
        <el-input v-model="subject" placeholder="请输入主题" />
      </el-form-item>
      <el-form-item label="正文" class="body-field">
        <div class="editor-wrap">
          <div ref="toolbarEl" class="compose-toolbar"></div>
          <div ref="editorEl" class="compose-editor"></div>
        </div>
      </el-form-item>
      <el-form-item label="附件">
        <div v-if="carried.length" class="carried">
          <span class="carried-tip">转发带入（发送时会另存一份，原邮件附件不受影响）：</span>
          <el-tag v-for="c in carried" :key="c.id" closable type="info" class="carried-tag"
            @close="dropCarried(c.id)">
            {{ c.name }}{{ c.size ? `（${fmtSize(c.size)}）` : '' }}
          </el-tag>
        </div>
        <el-upload :auto-upload="false" :on-change="onFileAdd" :on-remove="onFileRemove" :file-list="fileList" :limit="5">
          <el-button size="small" type="primary" plain>选择文件</el-button>
        </el-upload>
      </el-form-item>
    </el-form>
    <div class="compose-actions">
      <el-button type="primary" :loading="sending" @click="send">发送</el-button>
      <el-button :loading="savingDraft" @click="saveDraft">存草稿</el-button>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, shallowRef, computed, onMounted, onBeforeUnmount } from 'vue'
import { ElMessage } from 'element-plus'
import { createEditor, createToolbar } from '@wangeditor/editor'
import '@wangeditor/editor/dist/css/style.css'
import { mailApi, attachmentApi } from '@/api'

const props = defineProps<{
  accounts: any[]
  initial?: any
}>()
const emit = defineEmits<{ (e: 'sent'): void; (e: 'saved'): void }>()

const accountId = ref<number | undefined>(props.accounts[0]?.id)
const to = ref<string[]>([])
const cc = ref<string[]>([])
const bcc = ref<string[]>([])
const subject = ref('')
const files = ref<Array<{ file: File; id?: number }>>([])
// 转发带入的原邮件附件（每项含原 id/name/size，提交时才复制成新记录）
const carried = ref<any[]>([])
const sending = ref(false)
const savingDraft = ref(false)
// 编辑中的草稿 ID（用于更新而非新建）
const editingDraftId = ref<number | null>(null)

const toolbarEl = ref<HTMLElement>()
const editorEl = ref<HTMLElement>()
// 编辑器实例必须是 shallowRef/markRaw，避免 Vue 响应式代理破坏 Slate 内部状态
const editorRef = shallowRef<any>(null)

// 常用中英文字体（name 显示，value 为 CSS font-family）
// 注意：value 会被写入下拉项的 data-value 属性（wangEditor 未做转义），
// 含双引号会截断属性导致选择无效，因此一律使用不带引号的字体名。
const fontList = [
  { name: '微软雅黑', value: 'Microsoft YaHei, PingFang SC, sans-serif' },
  { name: '宋体', value: 'SimSun, Songti SC, serif' },
  { name: '黑体', value: 'SimHei, sans-serif' },
  { name: '楷体', value: 'KaiTi, Kaiti SC, serif' },
  { name: '仿宋', value: 'FangSong, serif' },
  { name: 'Arial', value: 'Arial, Helvetica, sans-serif' },
  { name: 'Georgia', value: 'Georgia, Times New Roman, serif' },
  { name: 'Times New Roman', value: 'Times New Roman, Times, serif' },
  { name: 'Courier New', value: 'Courier New, Courier, monospace' }
]
const sizeList = ['12px', '13px', '14px', '16px', '18px', '20px', '24px', '28px', '32px', '48px']

const toolbarConfig = {
  // 注意：toolbarKeys 只能是 wangEditor 已注册的菜单 key，写入不存在的 key
  // 会抛 "Not found menu item factory"，导致后续菜单未注册、整条工具栏点击无效。
  toolbarKeys: [
    'undo', 'redo', '|',
    'headerSelect', 'fontFamily', 'fontSize', 'lineHeight', 'color', 'bgColor', '|',
    'bold', 'italic', 'underline', 'through', 'sub', 'sup', '|',
    'bulletedList', 'numberedList', 'todo', '|',
    'justifyLeft', 'justifyCenter', 'justifyRight', 'indent', 'delIndent', '|',
    'insertLink', 'insertImage', 'uploadImage', 'insertTable', 'blockquote', 'code', 'divider', 'emotion', '|',
    'clearStyle', 'fullScreen'
  ]
}

const editorConfig = {
  placeholder: '请输入邮件正文…',
  mode: 'default',
  MENU_CONF: {
    fontFamily: { fontFamilyList: fontList },
    fontSize: { fontSizeList: sizeList },
    // 上传图片：走本系统附件接口，正文中以 /api/attachments/{id}/content 引用；
    // base64LimitSize=0 禁止内联 base64，避免正文体积膨胀且便于发送时改写为 CID 内联附件。
    uploadImage: {
      customUpload: onCustomUploadImage,
      allowedFileTypes: ['image/*'],
      base64LimitSize: 0
    }
  }
}

onMounted(() => {
  const editor = createEditor({
    selector: editorEl.value!,
    config: editorConfig,
    mode: 'default'
  })
  createToolbar({
    editor,
    selector: toolbarEl.value!,
    config: toolbarConfig,
    mode: 'default'
  })
  editorRef.value = editor

  if (props.initial) {
    if (props.initial.accountId != null) accountId.value = Number(props.initial.accountId)
    to.value = props.initial.to || []
    cc.value = props.initial.cc || []
    bcc.value = props.initial.bcc || []
    subject.value = props.initial.subject || ''
    carried.value = (props.initial.attachments || []).map((a: any) => ({ ...a }))
    // 编辑草稿时记录草稿 ID，用于后续更新而非新建
    if (props.initial.draftId) editingDraftId.value = Number(props.initial.draftId)
    const html = props.initial.bodyHtml || ''
    if (html) editor.setHtml(wrapPlainText(html))
  }
})

onBeforeUnmount(() => {
  if (editorRef.value) {
    try { editorRef.value.destroy() } catch { /* noop */ }
    editorRef.value = null
  }
})

async function onCustomUploadImage(file: File, insertFn: (url: string, alt: string, href: string) => void) {
  try {
    const meta: any = await attachmentApi.upload(file, accountId.value, { silentError: true })
    const url = attachmentApi.downloadUrl(meta.id)
    insertFn(url, meta.name || '', url)
  } catch (e: any) {
    ElMessage.error('图片插入失败：' + (e?.message || '上传未成功，请重试'))
  }
}

// 兜底：回复/转发可能带入纯文本（无块级标签），按行转 <p> 以适配 wangEditor
function wrapPlainText(html: string) {
  if (!/<(p|div|h[1-6]|ul|ol|table|blockquote|pre|img|br)\b/i.test(html)) {
    const esc = html.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;')
    return esc.split(/\r?\n/).map(l => `<p>${l || '<br>'}</p>`).join('')
  }
  return html
}

function currentBodyHtml(): string {
  return editorRef.value ? editorRef.value.getHtml() : ''
}

function onFileAdd(file: any) {
  if (file.raw) files.value.push({ file: file.raw })
}

// el-upload 的 file-list 需要响应式绑定，以便 on-remove 能正确移除文件
const fileList = computed(() => files.value.map((f, idx) => ({ name: f.file.name, uid: f.file.lastModified + idx })))

function onFileRemove(file: any) {
  // 从 files 数组中移除对应的文件
  files.value = files.value.filter((f, idx) => {
    const uid = f.file.lastModified + idx
    return uid !== file.uid
  })
}

async function uploadAttachments(): Promise<number[]> {
  const ids: number[] = []
  for (const item of files.value) {
    if (item.id == null) {
      const meta = await attachmentApi.upload(item.file, accountId.value)
      item.id = meta.id
    }
    ids.push(item.id)
  }
  return ids
}

/**
 * 转发带入的原邮件附件：提交时才复制成新记录。
 * 直接把原 id 交给后端会把附件改挂到新邮件下，原邮件的附件列表随之变空。
 * copiedId 缓存让「存草稿后再发送」不会重复复制。
 */
async function copyCarriedAttachments(): Promise<number[]> {
  const ids: number[] = []
  for (const c of carried.value) {
    if (c.copiedId == null) {
      const meta = await attachmentApi.copy(c.id, { silentError: true })
      c.copiedId = meta.id
    }
    ids.push(c.copiedId)
  }
  return ids
}

function dropCarried(id: number) {
  carried.value = carried.value.filter((c: any) => c.id !== id)
}

function fmtSize(s: number) {
  return s > 1024 * 1024 ? (s / 1024 / 1024).toFixed(1) + ' MB' : Math.ceil(s / 1024) + ' KB'
}

const ADDRESS_RE = /^[^@\s,;]+@[^@\s,;]+\.[^@\s,;]+$/

/** 发送前先在本机查地址格式，避免等一次网络往返才收到服务端报错。 */
function badAddressHint(): string {
  const groups: [string, string[]][] = [['收件人', to.value], ['抄送', cc.value], ['密送', bcc.value]]
  for (const [label, list] of groups) {
    for (const item of list) {
      const v = String(item || '').trim()
      if (v && !ADDRESS_RE.test(v)) {
        return `${label}「${v}」格式不正确，请填写如 name@example.com 的完整地址`
      }
    }
  }
  return ''
}

async function send() {
  if (!accountId.value) {
    ElMessage.warning('请选择发件账户')
    return
  }
  if (to.value.length === 0) {
    ElMessage.warning('请填写收件人')
    return
  }
  const bad = badAddressHint()
  if (bad) {
    ElMessage.warning(bad)
    return
  }
  sending.value = true
  try {
    const attachments = [...(await copyCarriedAttachments()), ...(await uploadAttachments())]
    await mailApi.send({
      accountId: accountId.value,
      to: to.value,
      cc: cc.value,
      bcc: bcc.value,
      subject: subject.value,
      bodyHtml: currentBodyHtml(),
      attachments
    })
    // 如果是从草稿编辑发送，发送后删除原草稿
    if (editingDraftId.value) {
      await mailApi.remove(editingDraftId.value)
    }
    ElMessage.success('邮件已发送')
    emit('sent')
  } finally {
    sending.value = false
  }
}

async function saveDraft() {
  if (savingDraft.value) return // 防止重复点击
  savingDraft.value = true
  try {
    const attachments = [...(await copyCarriedAttachments()), ...(await uploadAttachments())]
    const payload = {
      accountId: accountId.value,
      to: to.value,
      cc: cc.value,
      bcc: bcc.value,
      subject: subject.value,
      bodyHtml: currentBodyHtml(),
      attachments
    }
    // 如果正在编辑已有草稿，则更新；否则新建
    if (editingDraftId.value) {
      await mailApi.updateDraft(editingDraftId.value, payload)
    } else {
      await mailApi.saveDraft(payload)
    }
    ElMessage.success('草稿已保存')
    emit('saved')
  } finally {
    savingDraft.value = false
  }
}
</script>

<style scoped>
.compose {
  padding: 8px 4px;
}
.compose-form {
  display: flex;
  flex-direction: column;
  gap: 2px;
}
.cc-field :deep(.el-form-item__label) {
  padding-bottom: 2px;
}
.body-field :deep(.el-form-item__content) {
  align-items: stretch;
}
.editor-wrap {
  width: 100%;
  display: flex;
  flex-direction: column;
  border: 1px solid var(--color-border);
  border-radius: var(--radius-sm);
}
.compose-toolbar {
  border-bottom: 1px solid var(--color-border);
  background: var(--color-bg);
  border-radius: var(--radius-sm) var(--radius-sm) 0 0;
  flex-shrink: 0;
}
/* 正文区高度必须 > 300px，否则 wangEditor 的 modal/hoverbar 定位会异常（图片、链接弹窗错位） */
.compose-editor {
  height: 340px;
}
.editor-wrap :deep(.w-e-text-container) {
  min-height: 320px;
}

.compose-actions {
  margin-top: 12px;
  display: flex;
  gap: 8px;
}

.carried {
  width: 100%;
  margin-bottom: 6px;
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 6px;
}
.carried-tip {
  font-size: 12px;
  color: var(--el-text-color-secondary);
}
.carried-tag {
  max-width: 100%;
}
</style>
