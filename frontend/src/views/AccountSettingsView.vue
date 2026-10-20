<template>
  <div class="settings-page">
    <h2 class="page-title">账户管理</h2>

    <!-- 添加账户向导 -->
    <el-card class="card" shadow="never">
      <template #header>
        <div class="card-head">
          <span>添加邮箱账户</span>
        </div>
      </template>
      <el-steps :active="step" simple style="margin-bottom: 20px">
        <el-step title="邮箱地址" />
        <el-step title="服务器配置" />
        <el-step title="验证凭据" />
      </el-steps>

      <div v-if="step === 0">
        <el-form label-width="90px">
          <el-form-item label="邮箱地址">
            <el-input v-model="wizard.email" placeholder="you@example.com" style="width: 320px" />
          </el-form-item>
        </el-form>
        <el-button type="primary" :loading="detecting" @click="detect">自动检测服务器</el-button>
        <el-button plain @click="step = 1">手动配置</el-button>
        <div v-if="detectBox" class="detect-result">
          <div v-for="p in detectBox" :key="p.protocol" class="detect-item">
            <el-tag size="small" type="primary">{{ p.protocol }}</el-tag>
            <span>{{ p.host }}:{{ p.port }}</span>
            <el-button size="small" type="primary" link @click="useProbe(p)">使用此配置</el-button>
          </div>
        </div>
      </div>

      <div v-else-if="step === 1">
        <el-form ref="wizardFormRef" :model="wizard" :rules="wizardRules" label-width="120px">
          <el-form-item label="协议">
            <el-radio-group v-model="wizard.protocol" @change="syncPort(wizard, 'recv')">
              <el-radio value="IMAP">IMAP4</el-radio>
              <el-radio value="POP3">POP3</el-radio>
            </el-radio-group>
          </el-form-item>
          <el-form-item label="收件服务器" prop="host">
            <el-input v-model="wizard.host" placeholder="imap.example.com" style="width: 260px" />
          </el-form-item>
          <el-form-item label="端口" prop="port">
            <el-input-number v-model="wizard.port" :min="1" :max="65535" />
            <span class="port-hint">{{ portHint(wizard.protocol, wizard.encryption, wizard.port, 'recv') }}</span>
          </el-form-item>
          <el-form-item label="收信加密方式" prop="encryption">
            <el-select v-model="wizard.encryption" style="width: 180px" @change="syncPort(wizard, 'recv')">
              <el-option label="SSL/TLS（隐式 TLS，通常 993/995）" value="SSL" />
              <el-option label="STARTTLS（通常 143/587）" value="STARTTLS" />
              <el-option label="无加密（仅限内网/测试）" value="NONE" />
            </el-select>
          </el-form-item>
          <el-form-item label="发件服务器" prop="smtpHost">
            <el-input v-model="wizard.smtpHost" placeholder="smtp.example.com" style="width: 260px" />
          </el-form-item>
          <el-form-item label="SMTP 端口" prop="smtpPort">
            <el-input-number v-model="wizard.smtpPort" :min="1" :max="65535" />
            <span class="port-hint">{{ portHint('SMTP', wizard.smtpEncryption, wizard.smtpPort, 'send') }}</span>
          </el-form-item>
          <el-form-item label="发信加密方式" prop="smtpEncryption">
            <el-select v-model="wizard.smtpEncryption" style="width: 220px" @change="syncPort(wizard, 'send')">
              <el-option label="SSL(implicit TLS, 通常 465)" value="SSL" />
              <el-option label="STARTTLS(通常 587)" value="STARTTLS" />
              <el-option label="NONE(不加密)" value="NONE" />
            </el-select>
          </el-form-item>
          <el-form-item label="证书例外">
            <el-checkbox v-model="wizard.ignoreCertError">接受此证书 / 添加安全例外</el-checkbox>
            <div class="tip">服务器使用自签名、域名不匹配或已过期证书时勾选：跳过证书可信性校验（相当于浏览器「添加安全例外」），仅建议在可信内网邮箱开启。</div>
          </el-form-item>
        </el-form>
        <el-button type="primary" @click="nextStep">下一步</el-button>
        <el-button plain @click="step = 0">上一步</el-button>
      </div>

      <div v-else>
        <el-form ref="credFormRef" :model="wizard" :rules="credRules" label-width="120px">
          <el-form-item label="邮箱地址" prop="email">
            <el-input v-model="wizard.email" placeholder="you@example.com" style="width: 260px" />
            <div class="tip">用于标识该邮箱账户，也是发件人地址。</div>
          </el-form-item>
          <el-form-item label="用户名" prop="username">
            <el-input v-model="wizard.username" :placeholder="wizard.email || '登录用户名，通常即邮箱地址'"
              style="width: 260px" />
          </el-form-item>
          <el-form-item label="密码 / 授权码" prop="secret">
            <el-input v-model="wizard.secret" type="password" show-password
              placeholder="邮箱密码或应用授权码" style="width: 260px" />
            <div class="tip">QQ / 163 / Gmail 及多数企业邮箱需填「授权码 / 应用专用密码」，不是网页登录密码。</div>
          </el-form-item>
          <el-form-item label="显示名称">
            <el-input v-model="wizard.displayName" placeholder="发件显示名" style="width: 260px" />
          </el-form-item>
        </el-form>
        <div class="warn">凭据将使用 AES-256-GCM 加密存储，仅用于收发邮件。</div>
        <TestResultPanel :result="wizardTest" :cert-accepted="wizard.ignoreCertError"
          @update:cert-accepted="(v: boolean) => acceptCert(wizard, v, 'wizard')" />
        <el-button type="primary" :loading="saving" @click="save">完成并验证</el-button>
        <el-button :loading="wizardTesting" @click="testWizard">测试连接</el-button>
        <el-button plain @click="step = 1">上一步</el-button>
      </div>
    </el-card>

    <!-- 已绑定账户 -->
    <el-card class="card" shadow="never" style="margin-top: 16px">
      <template #header>
        <div class="card-head" style="display: flex; justify-content: space-between; align-items: center">
          <span>已绑定账户</span>
          <el-button size="small" type="primary" plain :loading="refreshAllLoading" @click="refreshAll">
            全部立即收取
          </el-button>
        </div>
      </template>
      <el-table :data="mail.accounts" style="width: 100%">
        <el-table-column prop="id" label="ID" width="60" />
        <el-table-column prop="email" label="邮箱" min-width="200" />
        <el-table-column prop="protocol" label="协议" width="90" />
        <el-table-column prop="host" label="服务器" min-width="180" />
        <el-table-column label="收信加密" width="110">
          <template #default="{ row }">
            <el-tag size="small" :type="row.encryption === 'NONE' ? 'info' : 'primary'">
              {{ row.encryption || '-' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="发信加密" width="110">
          <template #default="{ row }">
            <el-tooltip v-if="row.smtpHost" :content="`${row.smtpHost}:${row.smtpPort ?? ''}`" placement="top">
              <el-tag size="small" :type="row.smtpEncryption === 'NONE' ? 'info' : 'primary'">
                {{ row.smtpEncryption || '-' }}
              </el-tag>
            </el-tooltip>
            <el-tag v-else size="small" type="info">{{ row.smtpEncryption || '-' }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="状态" width="100">
          <template #default="{ row }">
            <el-tag :type="row.active ? 'success' : 'info'" size="small">
              {{ row.active ? '启用' : '停用' }}
            </el-tag>
            <el-tooltip v-if="row.ignoreCertError" content="已忽略 TLS 证书校验错误" placement="top">
              <el-tag size="small" type="warning" effect="plain">证书例外</el-tag>
            </el-tooltip>
          </template>
        </el-table-column>
        <el-table-column label="收取频率" width="210">
          <template #default="{ row }">
            <span v-if="row.syncMode === 'DAILY'" class="freq-daily">每天 {{ dailyLabel(row.syncDailyTime) }}</span>
            <div v-else-if="freq[row.id]" class="freq-edit">
              <el-input-number v-model="freq[row.id].value" :min="1" :max="maxValue(freq[row.id].unit)"
                :disabled="savingFreq === row.id" size="small" style="width: 92px" />
              <el-select v-model="freq[row.id].unit" :disabled="savingFreq === row.id" size="small" style="width: 78px">
                <el-option label="分钟" value="m" />
                <el-option label="小时" value="h" />
                <el-option label="天" value="d" />
              </el-select>
            </div>
            <span v-else>-</span>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="480">
          <template #default="{ row }">
            <el-button size="small" @click="openEdit(row)">编辑</el-button>
            <el-button size="small" :loading="savingFreq === row.id" :disabled="row.syncMode === 'DAILY'"
              @click="saveFreq(row)">保存频率</el-button>
            <el-button size="small" :loading="syncing === row.id" :disabled="!row.active" @click="syncNow(row.id)">同步</el-button>
            <el-button size="small" :loading="toggling === row.id" :type="row.active ? 'info' : 'success'" plain
              @click="toggleActive(row)">
              {{ row.active ? '停用' : '启用' }}
            </el-button>
            <el-tooltip :content="row.active
              ? '重置前需先停用账户（停用后其周期同步任务自动失效），避免与后台轮询抢数据'
              : '清空该账户本地全部文件夹邮件并重新拉取（不删除服务器邮件）'" placement="top">
              <el-button size="small" type="warning" plain :disabled="row.active" :loading="resetting === row.id"
                @click="resetAccount(row)">重置</el-button>
            </el-tooltip>
            <el-button size="small" type="danger" plain @click="remove(row.id)">删除</el-button>
          </template>
        </el-table-column>
      </el-table>
    </el-card>

    <!-- 编辑账户 -->
    <el-dialog v-model="editVisible" title="编辑账户" width="600px" :close-on-click-modal="false">
      <el-form ref="editFormRef" :model="editForm" :rules="editRules" label-width="120px">
        <el-form-item label="协议">
          <el-radio-group v-model="editForm.protocol" @change="syncPort(editForm, 'recv')">
            <el-radio value="IMAP">IMAP4</el-radio>
            <el-radio value="POP3">POP3</el-radio>
          </el-radio-group>
        </el-form-item>
        <el-form-item label="邮箱地址" prop="email">
          <el-input v-model="editForm.email" placeholder="you@example.com" />
        </el-form-item>
        <el-form-item label="备注名">
          <el-input v-model="editForm.displayName" placeholder="发件显示名" />
        </el-form-item>
        <el-form-item label="收件服务器" prop="host">
          <el-input v-model="editForm.host" placeholder="imap.example.com" style="width: 260px" />
        </el-form-item>
        <el-form-item label="收件端口" prop="port">
          <el-input-number v-model="editForm.port" :min="1" :max="65535" />
          <span class="port-hint">{{ portHint(editForm.protocol, editForm.encryption, editForm.port, 'recv') }}</span>
        </el-form-item>
        <el-form-item label="收信加密方式" prop="encryption">
          <el-select v-model="editForm.encryption" style="width: 220px" @change="syncPort(editForm, 'recv')">
            <el-option label="SSL/TLS（隐式 TLS，通常 993/995）" value="SSL" />
            <el-option label="STARTTLS（通常 143/587）" value="STARTTLS" />
            <el-option label="无加密（仅限内网/测试）" value="NONE" />
          </el-select>
        </el-form-item>
        <el-form-item label="发件服务器" prop="smtpHost">
          <el-input v-model="editForm.smtpHost" placeholder="smtp.example.com" style="width: 260px" />
        </el-form-item>
        <el-form-item label="发件端口" prop="smtpPort">
          <el-input-number v-model="editForm.smtpPort" :min="1" :max="65535" />
          <span class="port-hint">{{ portHint('SMTP', editForm.smtpEncryption, editForm.smtpPort, 'send') }}</span>
        </el-form-item>
        <el-form-item label="发信加密方式" prop="smtpEncryption">
          <el-select v-model="editForm.smtpEncryption" style="width: 220px" @change="syncPort(editForm, 'send')">
            <el-option label="SSL(implicit TLS, 通常 465)" value="SSL" />
            <el-option label="STARTTLS(通常 587)" value="STARTTLS" />
            <el-option label="NONE(不加密)" value="NONE" />
          </el-select>
        </el-form-item>
        <el-form-item label="证书例外">
          <el-checkbox v-model="editForm.ignoreCertError">接受此证书 / 添加安全例外</el-checkbox>
          <div class="tip">服务器使用自签名、域名不匹配或已过期证书时勾选：跳过证书可信性校验（相当于浏览器「添加安全例外」），仅建议在可信内网邮箱开启。</div>
        </el-form-item>
        <el-form-item label="认证用户名" prop="username">
          <el-input v-model="editForm.username" placeholder="登录用户名/邮箱" style="width: 260px" />
        </el-form-item>
        <el-form-item label="密码">
          <el-input v-model="editForm.secret" type="password" show-password
            placeholder="留空表示不修改密码" style="width: 260px" />
          <div class="tip">出于安全考虑不回显已存密码；留空保存即保持原密码不变，测试连接时也会沿用原密码。</div>
        </el-form-item>
        <el-form-item label="收信排程">
          <el-radio-group v-model="editForm.syncMode">
            <el-radio value="INTERVAL">按间隔轮询</el-radio>
            <el-radio value="DAILY">每天定点</el-radio>
          </el-radio-group>
        </el-form-item>
        <el-form-item v-if="editForm.syncMode === 'INTERVAL'" label="收取间隔">
          <el-input-number v-model="editForm.syncValue" :min="1" :max="maxValue(editForm.syncUnit)" style="width: 110px" />
          <el-select v-model="editForm.syncUnit" style="width: 88px; margin-left: 8px">
            <el-option label="分钟" value="m" />
            <el-option label="小时" value="h" />
            <el-option label="天" value="d" />
          </el-select>
          <div class="tip">账户未设置时跟随「通用设置」的默认频率，最短 15 秒。</div>
        </el-form-item>
        <el-form-item v-else label="每日时刻">
          <el-time-picker v-model="editForm.syncDailyTime" format="HH:mm" value-format="HH:mm"
            placeholder="每天几点收信，如 08:30" style="width: 180px" />
          <div class="tip">按服务器本地时区，每天到点收取一次；错过不补跑，下一个到点继续。</div>
        </el-form-item>
        <el-form-item label="启用">
          <el-switch v-model="editForm.active" />
        </el-form-item>
      </el-form>
      <TestResultPanel :result="editTest" :cert-accepted="editForm.ignoreCertError"
        @update:cert-accepted="(v: boolean) => acceptCert(editForm, v, 'edit')" />
      <template #footer>
        <span class="foot-tip">测试连接使用上方当前填写的配置，无需先保存。</span>
        <el-button :loading="editTesting" @click="testConn">测试连接</el-button>
        <el-button @click="editVisible = false">取消</el-button>
        <el-button type="primary" :loading="editSaving" @click="saveEdit">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { ref, reactive, onMounted, watch } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox, type FormInstance, type FormRules } from 'element-plus'
import { useMailStore } from '@/stores/mail'
import TestResultPanel from '@/components/TestResultPanel.vue'
import { accountApi, syncApi, type AccountUpdateReq, type AccountTestResult } from '@/api'

const mail = useMailStore()
const router = useRouter()
const step = ref(0)
const detectBox = ref<any[]>([])
const detecting = ref(false)
const saving = ref(false)
const syncing = ref<number | null>(null)
const resetting = ref<number | null>(null)
const toggling = ref<number | null>(null)
const savingFreq = ref<number | null>(null)
const refreshAllLoading = ref(false)
const editVisible = ref(false)
const editSaving = ref(false)
const editTesting = ref(false)
const editId = ref<number | null>(null)
const wizardFormRef = ref<FormInstance>()
const credFormRef = ref<FormInstance>()
const editFormRef = ref<FormInstance>()
const wizardTesting = ref(false)
const wizardTest = ref<AccountTestResult | null>(null)
const editTest = ref<AccountTestResult | null>(null)

/** 各协议/加密方式的常用端口，用于切换加密时自动带出端口并提示不匹配。 */
const PORTS = {
  recv: { IMAP: { SSL: 993, STARTTLS: 143, NONE: 143 }, POP3: { SSL: 995, STARTTLS: 995, NONE: 110 } },
  send: { SSL: 465, STARTTLS: 587, NONE: 25 }
}

function suggestPort(protocol: string, enc: string, kind: 'recv' | 'send'): number | null {
  const e = (enc || '').toUpperCase()
  if (kind === 'send') return PORTS.send[e as keyof typeof PORTS.send] ?? null
  const byProto = PORTS.recv[(protocol || 'IMAP').toUpperCase() as 'IMAP' | 'POP3']
  return byProto ? (byProto as any)[e] ?? null : null
}

/**
 * 切换加密方式/协议时带出推荐端口，但只在端口仍是「上一个推荐值」时改，
 * 避免覆盖用户手工填过的特殊端口。
 */
function syncPort(form: any, kind: 'recv' | 'send') {
  const cur = kind === 'send' ? form.smtpPort : form.port
  const proto = kind === 'send' ? 'SMTP' : form.protocol
  const enc = kind === 'send' ? form.smtpEncryption : form.encryption
  const prev = suggestPort(proto, enc, kind)
  const oldPrev = kind === 'send'
    ? (cur === 465 || cur === 587 || cur === 25)
    : (cur === 993 || cur === 143 || cur === 995 || cur === 110)
  if (prev != null && (oldPrev || !cur)) {
    if (kind === 'send') form.smtpPort = prev
    else form.port = prev
  }
}

/** 端口与加密方式明显不匹配时给出可操作提示（仅提示，不阻止保存）。 */
function portHint(protocol: string, enc: string, port: number | null, kind: 'recv' | 'send'): string {
  const want = suggestPort(protocol, enc, kind)
  if (!port || want == null || port === want) return ''
  const label = kind === 'send' ? 'SMTP' : (protocol || 'IMAP')
  return `端口 ${port} 与 ${enc || '未选加密'} 常用端口 ${want} 不一致，若握手失败请点加密方式自动纠正`
}

const hostRule = { required: true, message: '请填写服务器地址', trigger: 'blur' }
const portRule = { type: 'number', min: 1, max: 65535, message: '端口范围 1-65535', trigger: 'blur' }
const emailRule = { required: true, message: '请填写邮箱地址', trigger: 'blur' }
const emailFmt = { type: 'email', message: '邮箱格式不正确，应为 name@example.com', trigger: 'blur' }

const wizardRules: FormRules = {
  host: [hostRule],
  port: [portRule],
  smtpHost: [hostRule],
  smtpPort: [portRule],
  email: [emailRule, emailFmt],
  username: [{ required: true, message: '请填写登录用户名（通常即邮箱地址）', trigger: 'blur' }],
  secret: [{ required: true, message: '请填写密码或服务商授权码', trigger: 'blur' }]
}
const credRules: FormRules = wizardRules
const editRules: FormRules = {
  email: [emailRule, emailFmt],
  host: [hostRule],
  port: [portRule],
  smtpHost: [hostRule],
  smtpPort: [portRule],
  username: [{ required: true, message: '请填写登录用户名', trigger: 'blur' }]
}

const editForm = reactive<any>({
  protocol: 'IMAP',
  email: '',
  displayName: '',
  host: '',
  port: 993,
  encryption: 'SSL',
  smtpHost: '',
  smtpPort: 587,
  smtpEncryption: 'STARTTLS',
  username: '',
  secret: '',
  syncMode: 'INTERVAL',
  syncValue: 5,
  syncUnit: 'm',
  syncDailyTime: '08:00',
  active: true,
  ignoreCertError: false
})

const wizard = reactive<any>({
  email: '',
  protocol: 'IMAP',
  host: '',
  port: 993,
  encryption: 'SSL',
  smtpHost: '',
  smtpPort: 587,
  smtpEncryption: 'STARTTLS',
  username: '',
  secret: '',
  displayName: '',
  ignoreCertError: false
})

// ---------------------------------------------------------------- 收信排程
/** 界面按分钟/小时/天录入，落库仍是秒，兼容后端的 syncIntervalSec */
const UNIT_SEC: Record<string, number> = { m: 60, h: 3600, d: 86400 }
const UNIT_MAX: Record<string, number> = { m: 43200, h: 720, d: 30 }

function maxValue(unit: string) {
  return UNIT_MAX[unit] ?? 43200
}

function dailyLabel(t?: string) {
  return (t || '').slice(0, 5) || '未设置'
}

/** 把已存秒数反推成「数值 + 单位」，优先用能整除的最大单位 */
function toFreq(sec?: number) {
  const s = sec && sec >= 15 ? sec : 300
  const unit = s % UNIT_SEC.d === 0 && s >= UNIT_SEC.d ? 'd'
    : s % UNIT_SEC.h === 0 && s >= UNIT_SEC.h ? 'h' : 'm'
  return { value: Math.max(1, Math.round(s / UNIT_SEC[unit])), unit }
}

/** 表格行内编辑态（按账户 id 存放），加载账户列表时按当前配置重建 */
const freq = reactive<Record<number, { value: number; unit: string }>>({})
watch(() => mail.accounts, (list: any[]) => {
  for (const row of list || []) {
    if (row.syncMode !== 'DAILY') freq[row.id] = toFreq(row.syncIntervalSec)
  }
}, { immediate: true })

onMounted(mail.loadAccounts)

async function detect() {
  if (!wizard.email.includes('@')) {
    ElMessage.warning('请输入完整邮箱地址')
    return
  }
  detecting.value = true
  try {
    const res: any = await accountApi.autoDetect({ email: wizard.email })
    detectBox.value = res || []
    ElMessage.success('已生成推荐配置')
  } finally {
    detecting.value = false
  }
}

function useProbe(p: any) {
  wizard.protocol = p.protocol
  wizard.host = p.host
  wizard.port = p.port
  wizard.encryption = p.encryption || 'SSL'
  wizard.smtpHost = p.smtpHost
  wizard.smtpPort = p.smtpPort
  wizard.smtpEncryption = p.smtpEncryption || 'STARTTLS'
  wizard.username = wizard.email
  step.value = 2
  ElMessage.success('已应用推荐配置')
}

async function nextStep() {
  // 先做服务器配置校验，避免带着空主机进入凭据步骤
  const ok = await wizardFormRef.value?.validate().then(() => true).catch(() => false)
  if (ok === false) {
    ElMessage.warning('请先补全收件/发件服务器与端口')
    return
  }
  step.value = 2
}

/** 组装 test-config 请求体：向导与编辑弹窗共用同一份字段。 */
function testPayload(form: any): AccountUpdateReq & { accountId?: number } {
  return {
    protocol: form.protocol,
    email: form.email,
    displayName: form.displayName,
    host: form.host,
    port: form.port,
    encryption: form.encryption,
    smtpHost: form.smtpHost,
    smtpPort: form.smtpPort,
    smtpEncryption: form.smtpEncryption,
    username: form.username || form.email,
    secret: form.secret || undefined,
    ignoreCertError: !!form.ignoreCertError
  }
}

/**
 * 发起连接测试。返回 false 表示未通过（供「完成并验证」决定是否放行）。
 * 后端以 code!=0 抛错时也把原因落到面板上，而不是只弹一个 toast。
 */
async function runTest(form: any, target: typeof wizardTest, loading: typeof wizardTesting,
  accountId?: number | null): Promise<boolean> {
  target.value = null
  loading.value = true
  try {
    const res = await accountApi.testConfig({ ...testPayload(form), accountId })
    target.value = res
    return !!res.allOk
  } catch (e: any) {
    target.value = {
      receiveOk: false, sendOk: false, allOk: false,
      receiveTarget: `${form.protocol || 'IMAP'} ${form.host}:${form.port ?? ''}`,
      sendTarget: `SMTP ${form.smtpHost || form.host}:${form.smtpPort ?? ''}`,
      receiveError: e?.message || '测试失败，请检查配置',
      sendError: null
    }
    return false
  } finally {
    loading.value = false
  }
}

/**
 * 面板上勾选「接受此证书」：写入表单配置并立即用新配置复测一次，
 * 让用户当场看到「原来只是证书不受信」，而不是回去翻表单开关。
 */
async function acceptCert(form: any, on: boolean, which: 'wizard' | 'edit') {
  form.ignoreCertError = on
  if (!on) return
  if (which === 'wizard') await runTest(wizard, wizardTest, wizardTesting)
  else await runTest(editForm, editTest, editTesting, editId.value)
}

async function testWizard() {
  const ok = await credFormRef.value?.validate().then(() => true).catch(() => false)
  if (ok === false) {
    ElMessage.warning('请先补全邮箱地址、用户名与密码')
    return
  }
  const pass = await runTest(wizard, wizardTest, wizardTesting)
  if (pass) ElMessage.success('收信与发信连接均正常，可直接保存')
}

async function save() {
  const ok = await credFormRef.value?.validate().then(() => true).catch(() => false)
  if (ok === false) {
    ElMessage.warning('请补全邮箱地址、用户名与密码后再保存')
    return
  }
  saving.value = true
  try {
    await accountApi.create({ ...wizard })
    ElMessage.success('账户添加成功')
    step.value = 0
    wizard.email = ''
    wizardTest.value = null
    detectBox.value = []
    await mail.loadAccounts()
  } finally {
    saving.value = false
  }
}

async function syncNow(id: number) {
  syncing.value = id
  try {
    // 响应拦截器已解包信封，这里直接就是 {taskId}
    const res: any = await syncApi.trigger(id)
    ElMessage.success(res?.taskId ? `账户同步任务 #${res.taskId} 已开始本轮收取` : '同步任务已提交')
    // 短暂停留后跳转后台任务页查看进度
    setTimeout(() => { router.push('/tasks') }, 600)
  } finally {
    syncing.value = null
  }
}

async function saveFreq(row: any) {
  const f = freq[row.id]
  if (!f) return
  savingFreq.value = row.id
  try {
    await accountApi.update(row.id, {
      syncMode: 'INTERVAL',
      syncIntervalSec: Math.max(1, f.value) * UNIT_SEC[f.unit]
    })
    ElMessage.success('收取频率已保存，下次收信按新频率安排')
  } finally {
    savingFreq.value = null
  }
}

async function refreshAll() {
  refreshAllLoading.value = true
  try {
    const res: any = await syncApi.all()
    const count = res?.count ?? 0
    ElMessage.success(count ? `已启动 ${count} 个账户的收取任务，可在「后台任务」查看进度` : '没有启用中的账户，请先启用账户')
    if (count) setTimeout(() => { router.push('/tasks') }, 600)
  } finally {
    refreshAllLoading.value = false
  }
}

function openEdit(row: any) {
  editId.value = row.id
  editForm.protocol = row.protocol || 'IMAP'
  editForm.email = row.email || ''
  editForm.displayName = row.displayName || ''
  editForm.host = row.host || ''
  editForm.port = row.port ?? 993
  editForm.encryption = row.encryption || 'SSL'
  editForm.smtpHost = row.smtpHost || ''
  editForm.smtpPort = row.smtpPort ?? 587
  editForm.smtpEncryption = row.smtpEncryption || 'STARTTLS'
  editForm.username = row.username || ''
  // 密码永不回填
  editForm.secret = ''
  const f = toFreq(row.syncIntervalSec)
  editForm.syncMode = row.syncMode === 'DAILY' ? 'DAILY' : 'INTERVAL'
  editForm.syncValue = f.value
  editForm.syncUnit = f.unit
  editForm.syncDailyTime = (row.syncDailyTime || '08:00').slice(0, 5)
  editForm.active = row.active !== false
  editForm.ignoreCertError = !!row.ignoreCertError
  editTest.value = null
  editFormRef.value?.clearValidate()
  editVisible.value = true
}

async function saveEdit() {
  if (editId.value == null) return
  const ok = await editFormRef.value?.validate().then(() => true).catch(() => false)
  if (ok === false) {
    ElMessage.warning('表单有必填项未填写或格式不正确，请检查标红字段')
    return
  }
  if (editForm.syncMode === 'DAILY' && !editForm.syncDailyTime) {
    ElMessage.warning('请选择每日收信时刻')
    return
  }
  editSaving.value = true
  try {
    const payload: AccountUpdateReq = {
      protocol: editForm.protocol,
      email: editForm.email,
      displayName: editForm.displayName,
      host: editForm.host,
      port: editForm.port,
      encryption: editForm.encryption,
      smtpHost: editForm.smtpHost,
      smtpPort: editForm.smtpPort,
      smtpEncryption: editForm.smtpEncryption,
      username: editForm.username,
      syncMode: editForm.syncMode,
      syncIntervalSec: Math.max(1, editForm.syncValue) * (UNIT_SEC[editForm.syncUnit] ?? 60),
      syncDailyTime: editForm.syncDailyTime,
      active: editForm.active,
      ignoreCertError: !!editForm.ignoreCertError
    }
    // 留空表示不修改密码，不提交 secret 字段
    if (editForm.secret) payload.secret = editForm.secret
    await accountApi.update(editId.value, payload)
    ElMessage.success('账户已更新')
    editVisible.value = false
    await mail.loadAccounts()
  } finally {
    editSaving.value = false
  }
}

async function testConn() {
  if (editId.value == null) return
  const ok = await editFormRef.value?.validate().then(() => true).catch(() => false)
  if (ok === false) {
    ElMessage.warning('请先补全服务器、端口与用户名')
    return
  }
  const pass = await runTest(editForm, editTest, editTesting, editId.value)
  if (pass) ElMessage.success('收信与发信连接均正常')
}

async function resetAccount(row: any) {
  if (row.active) {
    ElMessage.warning('请先点「停用」停止该账户，其周期同步任务会一并失效，然后再重置')
    return
  }
  const label = row.email || row.host || `#${row.id}`
  await ElMessageBox.confirm(
    `确认重置账户「${label}」？\n将清空该账户在本机的所有文件夹邮件与附件，然后自动重新拉取。服务器上的邮件不会被删除。`,
    '重置账户',
    { type: 'warning', confirmButtonText: '确认重置', cancelButtonText: '取消' }
  )
  resetting.value = row.id
  try {
    const r = await accountApi.reset(row.id)
    ElMessage.success(`已清空 ${r?.messages ?? 0} 封邮件、${r?.attachments ?? 0} 个附件，正在重新拉取…`)
    setTimeout(() => { router.push('/tasks') }, 600)
  } finally {
    resetting.value = null
  }
}

/** 快速启用/停用：停用后后端会连带把该账户的周期同步任务置为失效。 */
async function toggleActive(row: any) {
  toggling.value = row.id
  try {
    const next = !row.active
    await accountApi.update(row.id, { active: next })
    await mail.loadAccounts()
    ElMessage.success(next
      ? `已启用「${row.email}」，周期同步任务恢复调度`
      : `已停用「${row.email}」，其周期同步任务已失效；如需重置现在可以点「重置」`)
  } finally {
    toggling.value = null
  }
}

async function remove(id: number) {
  await ElMessageBox.confirm('确认删除该账户？', '提示', { type: 'warning' })
  await accountApi.remove(id)
  ElMessage.success('已删除')
  await mail.loadAccounts()
}
</script>

<style scoped>
.settings-page { padding: 20px; }
.page-title { font-size: 20px; margin-bottom: 16px; }
.card-head { font-weight: 600; }
.detect-result { margin-top: 14px; }
.detect-item {
  display: flex; align-items: center; gap: 12px; padding: 8px;
  border: 1px solid var(--color-border-light); border-radius: var(--radius-md);
  margin-bottom: 8px;
}
.warn { color: var(--color-warning); font-size: 12px; margin-bottom: 12px; }
.tip { color: var(--color-text-3); font-size: 12px; line-height: 1.5; padding-top: 2px; }
.freq-edit { display: flex; align-items: center; gap: 6px; }
.freq-daily { color: var(--color-text); font-size: 13px; }
.port-hint { margin-left: 10px; color: var(--color-warning); font-size: 12px; }
.foot-tip { float: left; color: var(--color-text-3); font-size: 12px; line-height: 32px; }
</style>