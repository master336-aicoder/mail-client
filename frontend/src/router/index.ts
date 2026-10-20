import { createRouter, createWebHistory } from 'vue-router'

const routes = [
  {
    path: '/login',
    name: 'login',
    component: () => import('@/views/LoginView.vue'),
    meta: { public: true }
  },
  {
    path: '/',
    component: () => import('@/layouts/AppLayout.vue'),
    redirect: '/mail',
    children: [
      { path: 'mail', name: 'mail', component: () => import('@/views/MailView.vue') },
      { path: 'mail/folder/:id', name: 'mail-folder', component: () => import('@/views/MailView.vue') },
      { path: 'mail/message/:id', name: 'mail-message', component: () => import('@/views/MailView.vue') },
      { path: 'settings/accounts', name: 'settings-accounts', component: () => import('@/views/AccountSettingsView.vue') },
      { path: 'settings/rules', name: 'settings-rules', component: () => import('@/views/RuleSettingsView.vue'), meta: { admin: true } },
      { path: 'settings/general', name: 'settings-general', component: () => import('@/views/GeneralSettingsView.vue') },
      { path: 'tasks', name: 'tasks', component: () => import('@/views/TasksView.vue'), meta: { admin: true } },
      { path: 'messages', name: 'messages', component: () => import('@/views/MessagesView.vue') },
      { path: 'transfer', name: 'transfer', component: () => import('@/views/TransferView.vue'), meta: { admin: true } },
      { path: 'admin', name: 'admin', component: () => import('@/views/AdminView.vue'), meta: { admin: true } }
    ]
  },
  { path: '/:pathMatch(.*)*', redirect: '/mail' }
]

const router = createRouter({
  history: createWebHistory(),
  routes
})

router.beforeEach((to) => {
  const authed = localStorage.getItem('authed') === '1'
  if (!to.meta.public && !authed) {
    return { name: 'login' }
  }
  if (to.name === 'login' && authed) {
    return { name: 'mail' }
  }
  // 管理员页面按本地角色挡在门外（后端 /api/admin/** 另有 hasRole('ADMIN')），
  // 避免普通用户点进去只看到一片 403 报错
  if (to.meta.admin) {
    let role = ''
    try {
      role = JSON.parse(localStorage.getItem('user') || '{}')?.role || ''
    } catch {
      role = ''
    }
    if (role !== 'ADMIN') return { name: 'mail' }
  }
  return true
})

export default router