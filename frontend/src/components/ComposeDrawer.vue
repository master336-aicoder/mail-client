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
          <div class="editor-toolbar">
            <el-button-group>
              <el-button size="small" @click="exec('bold')" title="加粗"><b>B</b></el-button>
              <el-button size="small" @click="exec('italic')" title="斜体"><i>I</i></el-button>
              <el-button size="small" @click="exec('underline')" title="下划线"><u>U</u></el-button>
            </el-button-group>
            <el-divider direction="vertical" />
            <el-button-group>
              <el-button size="small" @click="exec('insertUnorderedList')" title="无序列表">• 列表</el-button>
              <el-button size="small" @click="exec('insertOrderedList')" title="有序列表">1. 列表</el-button>
            </el-button-group>
            <el-divider direction="vertical" />
            <el-button size="small" @click="insertLink" title="插入链接">链接</el-button>
            <el-button size="small" @click="insertEmoji" title="插入表情">😀</el-button>
          </div>
          <div ref="editor" class="editor" contenteditable="true" @input="onInput"
            placeholder="请输入邮件正文…"></div>
        </div>
      </el-form-item>
      <el-form-item label="附件">
        <el-upload :auto-upload="false" :on-change="onFileAdd" :limit="5">
          <el-button size="small" type="primary" plain>选择文件</el-button>
        </el-upload>
      </el-form-item>
    </el-form>
    <div class="compose-actions">
      <el-button type="primary" :loading="sending" @click="send">发送</el-button>
      <el-button @click="saveDraft">存草稿</el-button>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { ElMessage } from 'element-plus'
import { mailApi } from '@/api'

const props = defineProps<{
  accounts: any[]
  initial?: any
}>()
const emit = defineEmits<{ (e: 'sent'): void }>()

const accountId = ref<number | undefined>(props.accounts[0]?.id)
const to = ref<string[]>([])
const cc = ref<string[]>([])
const bcc = ref<string[]>([])
const subject = ref('')
const editor = ref<HTMLElement>()
const files = ref<File[]>([])
const sending = ref(false)

onMounted(() => {
  if (props.initial) {
    if (props.initial.accountId != null) accountId.value = Number(props.initial.accountId)
    to.value = props.initial.to || []
    cc.value = props.initial.cc || []
    bcc.value = props.initial.bcc || []
    subject.value = props.initial.subject || ''
    if (editor.value) {
      // 编辑草稿时回填正文，否则留空
      editor.value.innerHTML = props.initial.bodyHtml || ''
    }
  }
})

function exec(cmd: string) {
  editor.value?.focus()
  document.execCommand(cmd, false)
}
function insertLink() {
  editor.value?.focus()
  const url = window.prompt('链接地址')
  if (url) document.execCommand('createLink', false, url)
}
function insertEmoji() {
  editor.value?.focus()
  document.execCommand('insertText', false, '😀')
}
function onInput() {
  /* contenteditable 输入处理 */
}
function onFileAdd(file: any) {
  files.value.push(file.raw)
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
  sending.value = true
  try {
    await mailApi.send({
      accountId: accountId.value,
      to: to.value,
      cc: cc.value,
      bcc: bcc.value,
      subject: subject.value,
      bodyHtml: editor.value?.innerHTML || ''
    })
    ElMessage.success('邮件已发送')
    emit('sent')
  } finally {
    sending.value = false
  }
}

async function saveDraft() {
  await mailApi.saveDraft({
    accountId: accountId.value,
    to: to.value,
    cc: cc.value,
    bcc: bcc.value,
    subject: subject.value,
    bodyHtml: editor.value?.innerHTML || ''
  })
  ElMessage.success('草稿已保存')
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
  overflow: hidden;
}
.editor-toolbar {
  display: flex;
  align-items: center;
  gap: 4px;
  padding: 4px 6px;
  border-bottom: 1px solid var(--color-border);
  background: var(--color-bg);
  flex-shrink: 0;
  flex-wrap: wrap;
}
.editor {
  min-height: 220px;
  padding: 12px;
  outline: none;
  line-height: 1.7;
  font-size: 14px;
  color: var(--color-text);
  word-break: break-word;
}
.editor:empty::before {
  content: attr(placeholder);
  color: var(--color-text-3);
  pointer-events: none;
}

.compose-actions {
  margin-top: 12px;
  display: flex;
  gap: 8px;
}
</style>