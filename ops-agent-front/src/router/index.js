import { createRouter, createWebHistory } from 'vue-router'
import { useUserStore } from '@/store/user'

const routes = [
  { path: '/login', component: () => import('@/views/Login.vue'), meta: { requiresAuth: false } },
  {
    path: '/',
    component: () => import('@/components/Layout.vue'),
    meta: { requiresAuth: true },
    children: [
      { path: '', redirect: '/dashboard' },
      { path: 'dashboard', component: () => import('@/views/Dashboard.vue') },
      { path: 'alerts', component: () => import('@/views/Alerts.vue') },
      { path: 'approvals', component: () => import('@/views/Approvals.vue') },
      { path: 'tasks', component: () => import('@/views/Task.vue') },
      { path: 'tools', component: () => import('@/views/Tool.vue') },
      { path: 'realtime', component: () => import('@/views/RealTime.vue') },
      { path: 'chatops', component: () => import('@/views/ChatOps.vue') }
    ]
  }
]

const router = createRouter({
  history: createWebHistory(),
  routes
})

router.beforeEach((to, from, next) => {
  const userStore = useUserStore()
  if (to.meta.requiresAuth && !userStore.token) {
    next('/login')
  } else if (to.path === '/login' && userStore.token) {
    next('/')
  } else {
    next()
  }
})

export default router