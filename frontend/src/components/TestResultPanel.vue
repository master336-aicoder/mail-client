<template>
  <div v-if="result" class="trp">
    <div v-for="s in sides" :key="s.label" class="trp-row">
      <el-tag size="small" :type="s.ok ? 'success' : 'danger'">{{ s.label }}</el-tag>
      <span class="trp-target">{{ s.target }}</span>
      <span v-if="s.error" class="trp-error">{{ s.error }}</span>
      <span v-else class="trp-ok">连接与认证成功</span>
    </div>
    <div class="trp-hint">
      {{ result.allOk
        ? '收发两侧均可用，可以放心保存。'
        : '请按上面的原因调整对应字段（主机、端口、加密方式或授权码）后再次测试。' }}
    </div>
    <!-- 证书类失败专属：多数内网/自建邮箱是自签名证书，给一个一键放行的入口，
         而不是让用户去找隐藏在表单里的开关 -->
    <div v-if="certIssue" class="trp-cert">
      <el-checkbox v-model="accepted">接受此证书 / 添加安全例外</el-checkbox>
      <span class="trp-cert-tip">
        跳过证书可信性校验（等价于 JavaMail 的 ssl.trust=*），仅建议在可信的内网邮箱使用。
      </span>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import type { AccountTestResult } from '@/api'

const props = defineProps<{ result: AccountTestResult | null; certAccepted?: boolean }>()
const emit = defineEmits<{ (e: 'update:certAccepted', v: boolean): void }>()

const accepted = ref(!!props.certAccepted)
watch(() => props.certAccepted, (v) => (accepted.value = !!v))
watch(accepted, (v) => emit('update:certAccepted', v))

// 收/发两侧统一成同一种行结构，避免模板里重复两段几乎一样的分支
const sides = computed(() => {
  const r = props.result
  if (!r) return []
  return [
    { ok: r.receiveOk, name: '收信', target: r.receiveTarget || '收件服务器', error: r.receiveError },
    { ok: r.sendOk, name: '发信', target: r.sendTarget || '发件服务器', error: r.sendError }
  ].map(s => ({ ...s, label: s.ok ? `${s.name}正常` : `${s.name}失败` }))
})

const certIssue = computed(() =>
  sides.value.some((s) => !s.ok && /证书|证书校验|TLS 握手|PKIX|certificate|SSLHandshake/i.test(s.error || '')))
</script>

<style scoped>
.trp {
  margin: 8px 0 12px;
  padding: 10px 12px;
  border: 1px solid var(--color-border, #dcdfe6);
  border-radius: 6px;
  background: var(--color-bg-2, #fafafa);
  font-size: 13px;
}
.trp-row { display: flex; align-items: baseline; gap: 8px; flex-wrap: wrap; line-height: 1.7; }
.trp-target { color: var(--color-text-3); font-family: monospace; }
.trp-error { color: var(--color-danger, #f56c6c); }
.trp-ok { color: var(--color-success, #67c23a); }
.trp-hint { margin-top: 6px; color: var(--color-text-3); }
.trp-cert { margin-top: 8px; display: flex; align-items: center; gap: 8px; flex-wrap: wrap; }
.trp-cert-tip { color: var(--color-text-3); font-size: 12px; }
</style>
