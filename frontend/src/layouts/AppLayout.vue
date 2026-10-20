<template>
  <div class="app-shell">
    <!-- 侧边栏 -->
    <aside class="sidebar" :class="{ collapsed: collapsed }">
      <div class="brand">
        <div class="brand-logo">@</div>
        <span v-show="!collapsed" class="brand-name">云邮 Mail</span>
      </div>

      <nav class="nav">
        <router-link to="/mail" class="nav-item" active-class="active" exact-active-class="active">
          <el-icon><Message /></el-icon>
          <span v-show="!collapsed">邮件管理</span>
        </router-link>
        <router-link to="/settings/accounts" class="nav-item" active-class="active">
          <el-icon><Connection /></el-icon>
          <span v-show="!collapsed">账户管理</span>
        </router-link>
        <router-link v-if="auth.isAdmin" to="/settings/rules" class="nav-item" active-class="active">
          <el-icon><SetUp /></el-icon>
          <span v-show="!collapsed">筛选规则</span>
        </router-link>
        <router-link to="/messages" class="nav-item" active-class="active">
          <el-icon><ChatDotSquare /></el-icon>
          <span v-show="!collapsed">消息中心</span>
          <el-badge v-show="notif.unread && !collapsed" :value="notif.unread" :max="99" class="nav-badge" />
        </router-link>
        <router-link v-if="auth.isAdmin" to="/tasks" class="nav-item" active-class="active">
          <el-icon><Timer /></el-icon>
          <span v-show="!collapsed">后台任务</span>
        </router-link>
        <router-link v-if="auth.isAdmin" to="/transfer" class="nav-item" active-class="active">
          <el-icon><Switch /></el-icon>
          <span v-show="!collapsed">PST 导入导出</span>
        </router-link>
        <router-link v-if="auth.isAdmin" to="/admin" class="nav-item" active-class="active">
          <el-icon><UserFilled /></el-icon>
          <span v-show="!collapsed">系统管理</span>
        </router-link>
      </nav>

      <div class="sidebar-bottom">
        <router-link to="/settings/general" class="nav-item" active-class="active">
          <el-icon><Setting /></el-icon>
          <span v-show="!collapsed">通用设置</span>
        </router-link>
        <div class="collapse-btn" @click="collapsed = !collapsed">
          <el-icon><Fold v-if="!collapsed" /><Expand v-else /></el-icon>
          <span v-show="!collapsed">收起</span>
        </div>
      </div>
    </aside>

    <!-- 主区域 -->
    <div class="main">
      <header class="header">
        <div class="header-left">
          <el-button type="primary" @click="openCompose">
            <el-icon><EditPen /></el-icon>
            写邮件
          </el-button>

          <el-dropdown trigger="click" @command="onAccountCommand">
            <span class="account-select">
              <el-icon><Connection /></el-icon>
              <span class="acct-name">{{ activeAccountLabel }}</span>
              <el-tag v-if="!mail.connected" size="small" type="info" effect="plain">离线</el-tag>
              <el-icon class="acct-caret"><ArrowDown /></el-icon>
            </span>
            <template #dropdown>
              <el-dropdown-menu>
                <el-dropdown-item command="all">
                  <el-icon><FolderOpened /></el-icon> 全部账户
                </el-dropdown-item>
                <el-dropdown-item v-for="a in mail.accounts" :key="a.id"
                  :command="'switch:' + a.id" :divided="true">
                  <span class="acct-opt">
                    <span class="acct-opt-label">{{ a.email }}</span>
                    <el-link type="danger" underline="never" @click.stop="deleteAccount(a.id, a.email)">删除</el-link>
                  </span>
                </el-dropdown-item>
                <el-dropdown-item divided command="manage">账户管理</el-dropdown-item>
              </el-dropdown-menu>
            </template>
          </el-dropdown>
        </div>
        <div class="header-right" v-if="auth.user">
          <el-popover placement="bottom-end" :width="380" trigger="click" popper-class="notif-pop"
            @show="notif.load(1)">
            <template #reference>
              <span class="bell" title="消息通知">
                <el-badge :value="notif.unread" :max="99" :hidden="!notif.unread">
                  <el-icon :size="19"><Bell /></el-icon>
                </el-badge>
              </span>
            </template>
            <div class="notif-head">
              <span class="notif-title">消息通知</span>
              <span class="notif-ops">
                <el-button link type="primary" size="small" :disabled="!notif.unread" @click="notif.markAllRead()">
                  全部已读
                </el-button>
                <el-button link size="small" @click="onClearRead">清空已读</el-button>
              </span>
            </div>
            <el-scrollbar max-height="380px">
              <div v-loading="notif.loading" class="notif-list">
                <div v-for="n in notif.items" :key="n.id" class="notif-item" :class="{ unread: !n.read }"
                  @click="openNotif(n)">
                  <div class="ni-top">
                    <el-tag size="small" :type="notifTagType(n.type)" effect="plain">{{ notifTypeLabel(n.type) }}</el-tag>
                    <span class="ni-time">{{ fmtAgo(n.createdAt) }}</span>
                    <el-icon class="ni-del" title="删除" @click.stop="notif.remove([n.id])"><Close /></el-icon>
                  </div>
                  <div class="ni-title">{{ n.title }}</div>
                  <div v-if="n.content" class="ni-content">{{ n.content }}</div>
                </div>
                <el-empty v-if="!notif.loading && notif.items.length === 0" description="暂无消息" :image-size="60" />
              </div>
            </el-scrollbar>
            <div class="notif-foot">
              <span>共 {{ notif.total }} 条，已读消息保留 30 天</span>
              <el-button link type="primary" size="small" @click="router.push('/messages')">消息中心</el-button>
            </div>
          </el-popover>

          <el-dropdown @command="onCommand">
            <span class="user-chip">
              <el-avatar :size="30" class="avatar">{{ initial }}</el-avatar>
              <span class="user-name">{{ auth.user.username }}</span>
            </span>
            <template #dropdown>
              <el-dropdown-menu>
                <el-dropdown-item command="accounts">账户管理</el-dropdown-item>
                <el-dropdown-item command="settings">通用设置</el-dropdown-item>
                <el-dropdown-item divided command="logout">退出登录</el-dropdown-item>
              </el-dropdown-menu>
            </template>
          </el-dropdown>
        </div>
      </header>

      <main class="content">
        <router-view />
      </main>
    </div>

    <!-- 写邮件抽屉 -->
    <el-drawer v-model="composeOpen" title="写邮件" size="780px" :destroy-on-close="false" :close-on-press-escape="false" :close-on-click-modal="false">
      <ComposeDrawer v-if="composeOpen" :accounts="mail.accounts" @sent="onSent" />
    </el-drawer>
  </div>
</template>

<script setup lang="ts">
import { ref, computed, onMounted, onUnmounted } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { useAuthStore } from '@/stores/auth'
import { useMailStore } from '@/stores/mail'
import { useNotificationStore, notifTagType, notifTypeLabel } from '@/stores/notification'
import { accountApi } from '@/api'
import ComposeDrawer from '@/components/ComposeDrawer.vue'
import dayjs from 'dayjs'

const auth = useAuthStore()
const mail = useMailStore()
const notif = useNotificationStore()
const router = useRouter()

const collapsed = ref(false)
const composeOpen = ref(false)
const activeAccountId = ref<number>(0)

const initial = computed(() => (auth.user?.username?.[0] || 'U').toUpperCase())
const activeAccountLabel = computed(() => {
  if (!activeAccountId.value) return '全部账户'
  const a = mail.accounts.find((x) => x.id === activeAccountId.value)
  return a ? a.email : '全部账户'
})

function openCompose() {
  composeOpen.value = true
}

function onSent() {
  composeOpen.value = false
  mail.loadFolders()
}

function onAccountCommand(cmd: string) {
  if (cmd === 'all') {
    activeAccountId.value = 0
    router.push('/mail')
    return
  }
  if (cmd === 'manage') {
    router.push('/settings/accounts')
    return
  }
  if (cmd.startsWith('switch:')) {
    const id = Number(cmd.split(':')[1])
    activeAccountId.value = id
    router.push(`/mail?accountId=${id}`)
  }
}

async function deleteAccount(id: number, email: string) {
  try {
    await ElMessageBox.confirm(`确认删除账户 ${email}？删除后需重新配置。`, '提示', { type: 'warning' })
  } catch {
    return
  }
  await accountApi.remove(id)
  if (activeAccountId.value === id) {
    activeAccountId.value = 0
    router.push('/mail')
  }
  ElMessage.success('已删除账户')
  mail.loadAccounts()
  mail.loadFolders()
}

function fmtAgo(d?: string) {
  if (!d) return ''
  const t = dayjs(d)
  return dayjs().diff(t, 'hour') < 24 ? t.format('MM-DD HH:mm') : t.format('YYYY-MM-DD')
}

async function openNotif(n: { id: number; link?: string }) {
  await notif.markRead(n.id).catch(() => {})
  if (n.link) router.push(n.link)
}

async function onClearRead() {
  try {
    await ElMessageBox.confirm('确认清空所有已读消息？未读消息会保留。', '清空已读', { type: 'warning' })
  } catch {
    return
  }
  await notif.clearRead()
  ElMessage.success('已清空读过的消息')
}

function onCommand(cmd: string) {
  if (cmd === 'logout') {
    auth.logout()
    router.push('/login')
  } else if (cmd === 'accounts') {
    router.push('/settings/accounts')
  } else if (cmd === 'settings') {
    router.push('/settings/general')
  }
}

onMounted(async () => {
  await auth.fetchMe().catch(() => {})
  await mail.loadAccounts().catch(() => {})
  mail.connect()
  await mail.loadFolders().catch(() => {})
  await notif.loadUnread().catch(() => {})
})

onUnmounted(() => {
  mail.disconnect()
})
</script>

<style scoped>
.app-shell {
  display: flex;
  height: 100vh;
  overflow: hidden;
}
.sidebar {
  width: var(--sidebar-w);
  background: var(--color-surface);
  border-right: 1px solid var(--color-border);
  display: flex;
  flex-direction: column;
  transition: width 0.2s;
  flex-shrink: 0;
}
.sidebar.collapsed {
  width: 60px;
}
.brand {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 16px;
  height: var(--header-h);
}
.brand-logo {
  width: 34px;
  height: 34px;
  border-radius: 10px;
  background: var(--color-primary);
  color: #fff;
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 18px;
  font-weight: 700;
  flex-shrink: 0;
}
.brand-name {
  font-weight: 600;
  font-size: 16px;
  white-space: nowrap;
}
.nav {
  flex: 1;
  padding: 8px;
  overflow-y: auto;
}
.nav-item {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 10px 12px;
  border-radius: var(--radius-md);
  color: var(--color-text-2);
  margin-bottom: 2px;
  white-space: nowrap;
  cursor: pointer;
}
.nav-item:hover {
  background: var(--color-bg);
}
.nav-item.active {
  background: var(--color-primary-light);
  color: var(--color-primary);
  font-weight: 500;
}
.sidebar-bottom {
  padding: 8px;
  border-top: 1px solid var(--color-border-light);
}
.collapse-btn {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 10px 12px;
  color: var(--color-text-3);
  cursor: pointer;
  border-radius: var(--radius-md);
  white-space: nowrap;
}
.collapse-btn:hover {
  background: var(--color-bg);
}
.main {
  flex: 1;
  display: flex;
  flex-direction: column;
  min-width: 0;
}
.header {
  height: var(--header-h);
  background: var(--color-surface);
  border-bottom: 1px solid var(--color-border);
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 0 16px;
  flex-shrink: 0;
}
.header-left,
.header-right {
  display: flex;
  align-items: center;
  gap: 12px;
}
.user-chip {
  display: flex;
  align-items: center;
  gap: 8px;
  cursor: pointer;
  padding: 4px 8px;
  border-radius: var(--radius-md);
}
.user-chip:hover {
  background: var(--color-bg);
}
.account-select {
  display: flex;
  align-items: center;
  gap: 6px;
  cursor: pointer;
  padding: 4px 10px;
  border: 1px solid var(--color-border);
  border-radius: var(--radius-md);
  color: var(--color-text-2);
  font-size: 13px;
  max-width: 240px;
}
.account-select:hover {
  background: var(--color-bg);
}
.acct-name {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.acct-caret {
  color: var(--color-text-3);
}
.acct-opt {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 24px;
  width: 220px;
}
.acct-opt-label {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.avatar {
  background: var(--color-primary);
  color: #fff;
}
.user-name {
  font-weight: 500;
}
.content {
  flex: 1;
  overflow: auto;
}
.bell {
  display: flex;
  align-items: center;
  padding: 4px 8px;
  border-radius: var(--radius-md);
  cursor: pointer;
  color: var(--color-text-2);
}
.bell:hover {
  background: var(--color-bg);
}
.notif-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding-bottom: 8px;
  border-bottom: 1px solid var(--color-border-light);
}
.notif-title {
  font-weight: 600;
  font-size: 14px;
}
.notif-ops {
  display: flex;
  gap: 4px;
}
.notif-list {
  min-height: 120px;
}
.notif-item {
  padding: 10px 8px;
  border-bottom: 1px solid var(--color-border-light);
  cursor: pointer;
  border-radius: var(--radius-sm);
}
.notif-item:hover {
  background: var(--color-bg);
}
.notif-item.unread .ni-title {
  font-weight: 600;
}
.notif-item.unread {
  border-left: 3px solid var(--color-primary);
  padding-left: 5px;
}
.ni-top {
  display: flex;
  align-items: center;
  gap: 8px;
}
.ni-time {
  flex: 1;
  font-size: 12px;
  color: var(--color-text-4);
}
.ni-del {
  color: var(--color-text-4);
}
.ni-del:hover {
  color: var(--color-danger);
}
.ni-title {
  margin-top: 4px;
  font-size: 13px;
  color: var(--color-text);
}
.ni-content {
  margin-top: 2px;
  font-size: 12px;
  color: var(--color-text-3);
  display: -webkit-box;
  -webkit-line-clamp: 2;
  -webkit-box-orient: vertical;
  overflow: hidden;
}
.notif-foot {
  padding-top: 8px;
  font-size: 12px;
  color: var(--color-text-4);
  display: flex;
  align-items: center;
  justify-content: space-between;
}
.nav-badge {
  margin-left: auto;
}
@media (max-width: 768px) {
  .sidebar {
    width: 60px;
  }  .nav-item span,
  .brand-name,
  .collapse-btn span {
    display: none !important;
  }
  .header {
    padding: 0 8px;
  }
  .user-name {
    display: none;
  }
}
</style>