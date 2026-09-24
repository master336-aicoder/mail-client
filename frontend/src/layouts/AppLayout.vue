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
        <router-link to="/settings/rules" class="nav-item" active-class="active">
          <el-icon><SetUp /></el-icon>
          <span v-show="!collapsed">筛选规则</span>
        </router-link>
        <router-link to="/tasks" class="nav-item" active-class="active">
          <el-icon><Timer /></el-icon>
          <span v-show="!collapsed">后台任务</span>
        </router-link>
        <router-link to="/transfer" class="nav-item" active-class="active">
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
                    <el-link type="danger" :underline="false" @click.stop="deleteAccount(a.id, a.email)">删除</el-link>
                  </span>
                </el-dropdown-item>
                <el-dropdown-item divided command="manage">账户管理</el-dropdown-item>
              </el-dropdown-menu>
            </template>
          </el-dropdown>
        </div>
        <div class="header-right" v-if="auth.user">
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
    <el-drawer v-model="composeOpen" title="写邮件" size="600px" :destroy-on-close="false">
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
import { accountApi } from '@/api'
import ComposeDrawer from '@/components/ComposeDrawer.vue'

const auth = useAuthStore()
const mail = useMailStore()
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
@media (max-width: 768px) {
  .sidebar {
    width: 60px;
  }
  .nav-item span,
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