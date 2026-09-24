<template>
  <div class="settings-page content-limited">
    <h2 class="page-title">系统管理</h2>

    <el-card class="card" shadow="never" style="margin-top: 16px">
      <template #header><div class="card-head">用户列表</div></template>
      <el-table :data="users" style="width: 100%" v-loading="loading">
        <el-table-column prop="id" label="ID" width="70" />
        <el-table-column prop="username" label="用户名" min-width="140" />
        <el-table-column prop="email" label="邮箱" min-width="200" />
        <el-table-column label="角色" width="140">
          <template #default="{ row }">
            <el-tag :type="row.role === 'ADMIN' ? 'danger' : 'info'" size="small">{{ row.role }}</el-tag>
          </template>
        </el-table-column>
      </el-table>
    </el-card>

    <el-card class="card" shadow="never" style="margin-top: 16px">
      <template #header><div class="card-head">服务状态</div></template>
      <el-descriptions :column="2" border>
        <el-descriptions-item label="服务端">
          <el-tag type="success" size="small">UP</el-tag>
        </el-descriptions-item>
        <el-descriptions-item label="数据库">
          <el-tag type="success" size="small">UP</el-tag>
        </el-descriptions-item>
        <el-descriptions-item label="实时推送">
          <el-tag :type="mail.connected ? 'success' : 'info'" size="small">
            {{ mail.connected ? '已连接' : '未连接' }}
          </el-tag>
        </el-descriptions-item>
        <el-descriptions-item label="附件存储">
          <el-tag size="small" type="info">本地</el-tag>
        </el-descriptions-item>
      </el-descriptions>
    </el-card>
  </div>
</template>

<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { useMailStore } from '@/stores/mail'

const mail = useMailStore()
const users = ref<any[]>([])
const loading = ref(false)

onMounted(load)

async function load() {
  loading.value = true
  try {
    users.value = await (await import('@/api')).adminApi.list()
  } catch {
    /* 无权限或加载失败已由 http 拦截器统一提示，避免 mounted 未捕获异常 */
  } finally {
    loading.value = false
  }
}
</script>

<style scoped>
.settings-page { padding: 20px; }
.content-limited { max-width: 900px; }
.page-title { font-size: 20px; }
.card-head { font-weight: 600; }
</style>