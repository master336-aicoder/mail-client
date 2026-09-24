<template>
  <div class="settings-page">
    <h2 class="page-title">筛选规则</h2>
    <p class="desc">邮件收件时可自动执行规则：移动到文件夹、标记星标、标记已读、删除。</p>

    <el-card class="card" shadow="never" style="margin-top: 16px">
      <template #header>
        <div class="card-head">
          <span>规则列表</span>
          <el-button type="primary" size="small" @click="openEditor()">新建规则</el-button>
        </div>
      </template>

      <el-empty v-if="rules.length === 0" description="暂无规则" />
      <div v-else v-for="(r, i) in rules" :key="r.id" class="rule-row">
        <span class="rule-order">{{ i + 1 }}</span>
        <div class="rule-main">
          <div class="rule-name">{{ r.name }}</div>
          <div class="rule-brief">{{ summarize(r) }}</div>
        </div>
        <el-switch :model-value="r.enabled" @change="(v: any) => toggle(r, v)" />
        <el-button size="small" text @click="openEditor(r)">编辑</el-button>
        <el-button size="small" text type="danger" @click="remove(r)">删除</el-button>
      </div>
    </el-card>

    <el-dialog v-model="editorOpen" :title="editing?.id ? '编辑规则' : '新建规则'" width="560px" @open="onEditorOpen">
      <el-form label-width="90px">
        <el-form-item label="规则名称">
          <el-input v-model="form.name" placeholder="如：重要客户邮件" />
        </el-form-item>
        <el-form-item label="使用账户" required>
          <el-select v-model="form.accountId" placeholder="选择使用该规则的账户" style="width: 100%">
            <el-option v-for="a in mail.accounts" :key="a.id" :label="a.email" :value="a.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="条件">
          <div class="cond-row">
            <el-select v-model="form.condField" style="width: 110px">
              <el-option label="发件人" value="from" />
              <el-option label="主题" value="subject" />
              <el-option label="正文" value="body" />
              <el-option label="含附件" value="hasAttach" />
            </el-select>
            <el-select v-model="form.condOp" style="width: 120px">
              <el-option label="包含" value="contains" />
              <el-option label="等于" value="equals" />
              <el-option label="不包含" value="notContains" />
              <el-option label="开头为" value="startsWith" />
            </el-select>
            <el-input v-model="form.condValue" placeholder="匹配值" style="flex: 1" />
          </div>
        </el-form-item>
        <el-form-item label="执行动作">
          <el-checkbox-group v-model="form.actions">
            <el-checkbox value="star">标记星标</el-checkbox>
            <el-checkbox value="read">标记已读</el-checkbox>
            <el-checkbox value="move">移动到文件夹</el-checkbox>
            <el-checkbox value="delete">移入已删除</el-checkbox>
          </el-checkbox-group>
          <el-select
            v-if="form.actions.includes('move')"
            v-model="form.moveFolderId"
            :disabled="!form.accountId"
            placeholder="选择目标文件夹（该账户下）"
            style="width: 100%; margin-top: 8px">
            <el-option v-for="f in accountFolders" :key="f.id" :label="f.name" :value="f.id" />
          </el-select>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="editorOpen = false">取消</el-button>
        <el-button type="primary" @click="saveEditor">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { ref, reactive, computed, onMounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { ruleApi, type Rule } from '@/api'
import { useMailStore } from '@/stores/mail'

const mail = useMailStore()
const rules = ref<Rule[]>([])
const editorOpen = ref(false)
const editing = ref<Rule | null>(null)
const form = reactive({
  name: '',
  accountId: undefined as number | undefined,
  condField: 'from',
  condOp: 'contains',
  condValue: '',
  actions: [] as string[],
  moveFolderId: undefined as number | undefined
})

const accountFolders = computed(() => {
  if (!form.accountId) return []
  return (mail.folders || []).filter((f: any) => f.accountId === form.accountId && !f.isMerged)
})

onMounted(async () => {
  await load()
  await Promise.all([mail.loadAccounts(), mail.loadFolders()])
})

async function load() {
  rules.value = await ruleApi.list()
}

function summarize(r: Rule) {
  try {
    const c = JSON.parse(r.conditions) || []
    const a = JSON.parse(r.actions) || []
    const cf = c[0]
    let cond = ''
    if (cf) {
      const fm = ({ from: '发件人', subject: '主题', body: '正文', hasAttach: '含附件' } as any)[cf.field]
      cond = `${fm} ${cf.op === 'contains' ? '包含' : '匹配'} “${cf.value}”`
    }
    const acc = mail.accounts.find((x: any) => x.id === r.accountId)
    const accLabel = acc ? acc.email : ''
    const fmtAction = (x: any) => {
      if (x.type === 'move') {
        const f = mail.folders.find((fd: any) => fd.id === x.folderId)
        return `移动到${f ? f.name : '文件夹'}`
      }
      return ({ star: '星标', read: '已读', delete: '删除' } as any)[x.type] || x.type
    }
    const actionNames = a.map(fmtAction).join('、')
    return `${accLabel} ${cond} → ${actionNames}`
  } catch {
    return ''
  }
}

function openEditor(r?: Rule) {
  editing.value = r || null
  if (r) {
    const c = JSON.parse(r.conditions)?.[0] || {}
    const acts = JSON.parse(r.actions) || []
    const moveAct = acts.find((x: any) => x.type === 'move')
    form.name = r.name
    form.accountId = r.accountId
    form.condField = c.field || 'from'
    form.condOp = c.op || 'contains'
    form.condValue = c.value || ''
    form.actions = acts.map((x: any) => x.type)
    form.moveFolderId = moveAct ? moveAct.folderId : undefined
  } else {
    form.name = ''
    form.accountId = undefined
    form.condField = 'from'
    form.condOp = 'contains'
    form.condValue = ''
    form.actions = []
    form.moveFolderId = undefined
  }
  editorOpen.value = true
}

function onEditorOpen() {
  if (!mail.accounts.length) mail.loadAccounts()
  if (!mail.folders.length) mail.loadFolders()
}

async function saveEditor() {
  if (!form.name) {
    ElMessage.warning('请输入规则名称')
    return
  }
  if (!form.accountId) {
    ElMessage.warning('请选择使用规则的账户')
    return
  }
  if (form.actions.includes('move') && !form.moveFolderId) {
    ElMessage.warning('请选择移动的目标文件夹')
    return
  }
  const actions: any[] = form.actions.map((t) => ({ type: t }))
  const moveIdx = form.actions.indexOf('move')
  if (moveIdx >= 0) actions[moveIdx].folderId = form.moveFolderId
  const payload = {
    name: form.name,
    accountId: form.accountId,
    conditions: JSON.stringify([{ field: form.condField, op: form.condOp, value: form.condValue }]),
    actions: JSON.stringify(actions)
  }
  if (editing.value) await ruleApi.update(editing.value.id, payload)
  else await ruleApi.create(payload)
  ElMessage.success('已保存')
  editorOpen.value = false
  await load()
}

async function toggle(r: Rule, v: boolean) {
  await ruleApi.toggle(r.id, v)
  r.enabled = v
}

async function remove(r: Rule) {
  await ElMessageBox.confirm(`删除规则「${r.name}」？`, '提示', { type: 'warning' })
  await ruleApi.remove(r.id)
  await load()
}
</script>

<style scoped>
.settings-page { padding: 20px; }
.page-title { font-size: 20px; }
.desc { color: var(--color-text-3); font-size: 13px; margin-top: 6px; }
.card-head { display: flex; justify-content: space-between; align-items: center; font-weight: 600; }
.rule-row {
  display: flex; align-items: center; gap: 12px; padding: 12px 4px;
  border-bottom: 1px solid var(--color-border-light);
}
.rule-order {
  width: 22px; height: 22px; border-radius: 50%; background: var(--color-primary-light);
  color: var(--color-primary); display: flex; align-items: center; justify-content: center;
  font-size: 12px; flex-shrink: 0;
}
.rule-main { flex: 1; min-width: 0; }
.rule-name { font-weight: 500; }
.rule-brief { color: var(--color-text-3); font-size: 12px; margin-top: 2px; }
.cond-row { display: flex; gap: 8px; width: 100%; }
</style>