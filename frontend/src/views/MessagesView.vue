<template>
  <div class="settings-page content-limited">
    <h2 class="page-title">消息中心</h2>

    <el-card class="card" shadow="never" style="margin-top: 16px">
      <div class="mc-bar">
        <el-input v-model="query.keyword" placeholder="搜索标题或内容" size="small" clearable
          style="width: 200px" @keyup.enter="search" @clear="search" />
        <el-select v-model="query.type" size="small" style="width: 118px" @change="search">
          <el-option label="全部类型" value="" />
          <el-option v-for="(label, key) in NOTIF_TYPES" :key="key" :label="label" :value="key" />
        </el-select>
        <el-checkbox v-model="query.unreadOnly" size="small" @change="search">只看未读</el-checkbox>
        <el-button size="small" type="primary" @click="search">查询</el-button>
        <span class="mc-flex" />
        <el-button size="small" :disabled="!selected.length" @click="removeSelected">
          删除选中（{{ selected.length }}）
        </el-button>
        <el-button size="small" :disabled="!notif.unread" @click="readAll">全部标记已读</el-button>
        <el-button size="small" @click="clearRead">清空已读</el-button>
      </div>

      <el-table :data="rows" style="width: 100%" v-loading="loading" row-key="id"
        @selection-change="(v: any[]) => (selected = v)" @row-click="openDetail">
        <el-table-column type="selection" width="44" />
        <el-table-column label="类型" width="104">
          <template #default="{ row }">
            <el-tag size="small" :type="notifTagType(row.type)" effect="plain">{{ notifTypeLabel(row.type) }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="标题" min-width="220">
          <template #default="{ row }">
            <span :class="{ 'mc-unread': !row.read }">{{ row.title }}</span>
          </template>
        </el-table-column>
        <el-table-column label="内容" min-width="300">
          <template #default="{ row }">
            <span class="mc-excerpt">{{ excerpt(row.content) }}</span>
          </template>
        </el-table-column>
        <el-table-column label="时间" width="140">
          <template #default="{ row }">{{ fmt(row.createdAt) }}</template>
        </el-table-column>
        <el-table-column label="操作" width="116">
          <template #default="{ row }">
            <el-button link type="primary" size="small" @click.stop="openDetail(row)">查看</el-button>
            <el-button link type="danger" size="small" @click.stop="removeOne(row)">删除</el-button>
          </template>
        </el-table-column>
      </el-table>

      <el-pagination class="mc-pager" layout="total, sizes, prev, pager, next" :total="total"
        :page-size="query.size" :current-page="query.page" :page-sizes="[10, 20, 50, 100]"
        @current-change="onPage" @size-change="onSize" />
    </el-card>

    <el-dialog v-model="detailVisible" title="消息详情" width="560px">
      <div v-if="current" class="mc-detail">
        <div class="md-head">
          <el-tag size="small" :type="notifTagType(current.type)" effect="plain">
            {{ notifTypeLabel(current.type) }}
          </el-tag>
          <span class="md-time">{{ fmt(current.createdAt) }}</span>
        </div>
        <div class="md-title">{{ current.title }}</div>
        <div class="md-content">{{ current.content || '（无正文）' }}</div>
      </div>
      <template #footer>
        <el-button v-if="current?.link" type="primary" @click="goto(current?.link)">前往查看</el-button>
        <el-button @click="detailVisible = false">关闭</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { ref, reactive, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import dayjs from 'dayjs'
import { notificationApi, type NotificationItem } from '@/api'
import { useNotificationStore, NOTIF_TYPES, notifTagType, notifTypeLabel } from '@/stores/notification'

const notif = useNotificationStore()
const router = useRouter()

const rows = ref<NotificationItem[]>([])
const total = ref(0)
const loading = ref(false)
const selected = ref<NotificationItem[]>([])
const detailVisible = ref(false)
const current = ref<NotificationItem | null>(null)

const query = reactive({ page: 1, size: 20, type: '', keyword: '', unreadOnly: false })

onMounted(load)

async function load() {
  loading.value = true
  try {
    const res: any = await notificationApi.list({
      page: query.page,
      size: query.size,
      type: query.type || undefined,
      keyword: query.keyword || undefined,
      unreadOnly: query.unreadOnly
    })
    rows.value = res?.list || []
    total.value = res?.total || 0
  } finally {
    loading.value = false
  }
}

function search() {
  query.page = 1
  load()
}
function onPage(p: number) {
  query.page = p
  load()
}
function onSize(s: number) {
  query.size = s
  query.page = 1
  load()
}

/** 打开详情即视为已读：消息中心里的「看过」和铃铛要保持一致，故同步顶栏未读数。 */
async function openDetail(row: NotificationItem) {
  current.value = row
  detailVisible.value = true
  if (!row.read) {
    await notificationApi.markRead(row.id).catch(() => {})
    row.read = true
    notif.loadUnread().catch(() => {})
  }
}

function goto(link?: string) {
  detailVisible.value = false
  if (link) router.push(link)
}

async function readAll() {
  await notificationApi.markAllRead()
  rows.value.forEach((r) => (r.read = true))
  notif.loadUnread().catch(() => {})
  ElMessage.success('已全部标记为已读')
}

async function removeOne(row: NotificationItem) {
  await removeIds([row.id], '删除这条消息？')
}

async function removeSelected() {
  await removeIds(selected.value.map((r) => r.id), `确认删除选中的 ${selected.value.length} 条消息？`)
}

async function removeIds(ids: number[], tip: string) {
  if (!ids.length) return
  try {
    await ElMessageBox.confirm(tip, '删除消息', { type: 'warning', confirmButtonText: '删除', cancelButtonText: '取消' })
  } catch {
    return
  }
  await notificationApi.batchDelete(ids)
  ElMessage.success('已删除')
  selected.value = []
  await load()
  notif.loadUnread().catch(() => {})
}

async function clearRead() {
  try {
    await ElMessageBox.confirm('确认清空所有已读消息？未读消息会保留。', '清空已读', { type: 'warning' })
  } catch {
    return
  }
  const res: any = await notificationApi.clearRead()
  ElMessage.success(`已清空 ${res?.deleted ?? 0} 条已读消息`)
  await load()
}

function excerpt(content?: string) {
  if (!content) return ''
  return content.length > 46 ? content.slice(0, 46) + '…' : content
}

function fmt(d?: string) {
  return d ? dayjs(d).format('YYYY-MM-DD HH:mm') : ''
}
</script>

<style scoped>
.mc-bar {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-bottom: 12px;
  flex-wrap: wrap;
}
.mc-flex {
  flex: 1;
}
.mc-unread {
  font-weight: 600;
  color: var(--color-text);
}
.mc-excerpt {
  color: var(--color-text-3);
  font-size: 12px;
}
.mc-pager {
  margin-top: 14px;
  justify-content: flex-end;
}
.md-head {
  display: flex;
  align-items: center;
  gap: 10px;
}
.md-time {
  color: var(--color-text-4);
  font-size: 12px;
}
.md-title {
  margin-top: 10px;
  font-size: 15px;
  font-weight: 600;
}
.md-content {
  margin-top: 8px;
  font-size: 13px;
  color: var(--color-text-2);
  line-height: 1.7;
  white-space: pre-wrap;
  word-break: break-word;
}
</style>
