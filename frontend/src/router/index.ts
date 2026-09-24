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
      { path: 'settings/rules', name: 'settings-rules', component: () => import('@/views/RuleSettingsView.vue') },
      { path: 'settings/general', name: 'settings-general', component: () => import('@/views/GeneralSettingsView.vue') },
      { path: 'tasks', name: 'tasks', component: () => import('@/views/TasksView.vue') },
      { path: 'transfer', name: 'transfer', component: () => import('@/views/TransferView.vue') },
      { path: 'admin', name: 'admin', component: () => import('@/views/AdminView.vue') }
    ]
  },
  { path: '/:pathMatch(.*)*', redirect: '/mail' }
]

const router = createRouter({
  history: createWebHistory(),
  routes
})

router.beforeEach((to) => {
  const token = localStorage.getItem('access_token')
  if (!to.meta.public && !token) {
    return { name: 'login' }
  }
  if (to.name === 'login' && token) {
    return { name: 'mail' }
  }
  return true
})

export default router