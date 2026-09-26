<template>
  <el-container class="layout">
    <!-- 侧边栏：深色渐变 + 分组标题，7 个菜单项按"看 / 管 / 查"分组，不再是一长条平铺 -->
    <el-aside :width="collapsed ? '64px' : '220px'" class="sidebar">
      <div class="brand">
        <div class="brand__logo">
          <el-icon :size="20"><Monitor /></el-icon>
        </div>
        <transition name="fade">
          <div v-if="!collapsed" class="brand__text">
            <div class="brand__title">AIOps 平台</div>
            <div class="brand__sub">智能运维中枢</div>
          </div>
        </transition>
      </div>

      <el-scrollbar class="sidebar__scroll">
        <el-menu
          router
          :default-active="activeMenu"
          :collapse="collapsed"
          :collapse-transition="false"
          class="sidebar__menu"
        >
          <template v-for="group in menuGroups" :key="group.title">
            <div v-if="!collapsed" class="menu-group__title">{{ group.title }}</div>
            <el-menu-item v-for="item in group.items" :key="item.path" :index="item.path">
              <el-icon><component :is="item.icon" /></el-icon>
              <template #title>{{ item.label }}</template>
            </el-menu-item>
          </template>
        </el-menu>
      </el-scrollbar>

      <!-- 实时监控的 WebSocket 连接状态：常驻侧边栏底部，跨页面可见 -->
      <div v-if="!collapsed" class="sidebar__footer">
        <span class="ws-dot" :class="{ 'ws-dot--on': wsConnected }"></span>
        <span class="ws-text">{{ wsConnected ? '实时通道已连接' : '实时通道未连接' }}</span>
      </div>
    </el-aside>

    <el-container>
      <el-header class="topbar">
        <div class="topbar__left">
          <el-icon class="topbar__toggle" @click="collapsed = !collapsed">
            <component :is="collapsed ? 'Expand' : 'Fold'" />
          </el-icon>
          <el-breadcrumb separator="/">
            <el-breadcrumb-item :to="{ path: '/dashboard' }">首页</el-breadcrumb-item>
            <el-breadcrumb-item>{{ currentTitle }}</el-breadcrumb-item>
          </el-breadcrumb>
        </div>

        <div class="topbar__right">
          <el-tooltip content="刷新当前页数据" placement="bottom">
            <el-icon class="topbar__icon" @click="reload"><Refresh /></el-icon>
          </el-tooltip>

          <el-dropdown trigger="click" @command="onCommand">
            <div class="user">
              <div class="user__avatar">{{ (username || 'U').charAt(0).toUpperCase() }}</div>
              <span class="user__name">{{ username || '未登录' }}</span>
              <el-icon><ArrowDown /></el-icon>
            </div>
            <template #dropdown>
              <el-dropdown-menu>
                <el-dropdown-item disabled>
                  <span class="user__role">角色：{{ role || '—' }}</span>
                </el-dropdown-item>
                <el-dropdown-item divided command="logout">
                  <el-icon><SwitchButton /></el-icon>退出登录
                </el-dropdown-item>
              </el-dropdown-menu>
            </template>
          </el-dropdown>
        </div>
      </el-header>

      <el-main class="main">
        <router-view v-slot="{ Component }">
          <!-- 路由切换淡入，避免整页闪烁 -->
          <transition name="fade-slide" mode="out-in">
            <component :is="Component" :key="route.path" />
          </transition>
        </router-view>
      </el-main>
    </el-container>

    <!-- 全局审批提醒：挂在这里才能在任何页面弹出 -->
    <ApprovalAlert />
  </el-container>
</template>

<script setup>
import { ref, computed, onMounted, onUnmounted } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { useUserStore } from '@/store/user'
import { useWebSocketStore } from '@/store/websocket'
import ApprovalAlert from '@/components/ApprovalAlert.vue'
import {
  Monitor, DataBoard, Warning, Stamp, List, Tools, Connection,
  ChatDotRound, Refresh, ArrowDown, SwitchButton
} from '@element-plus/icons-vue'

const route = useRoute()
const router = useRouter()
const userStore = useUserStore()
const wsStore = useWebSocketStore()

const collapsed = ref(false)
const username = computed(() => userStore.username)
const role = computed(() => userStore.role)
const wsConnected = computed(() => wsStore.connected)

// 菜单分组：按使用场景分三段，而不是 7 项平铺
const menuGroups = [
  {
    title: '监控',
    items: [
      { path: '/dashboard', label: '仪表板', icon: DataBoard },
      { path: '/alerts', label: '告警列表', icon: Warning },
      { path: '/realtime', label: '实时监控', icon: Connection }
    ]
  },
  {
    title: '处置',
    items: [
      { path: '/approvals', label: '人工审批', icon: Stamp },
      { path: '/tasks', label: '任务管理', icon: List }
    ]
  },
  {
    title: '工具',
    items: [
      { path: '/chatops', label: 'ChatOps', icon: ChatDotRound },
      { path: '/tools', label: '工具管理', icon: Tools }
    ]
  }
]

// 当前高亮项：子路由（如 /tasks?id= 跳转）也归到父路径
const activeMenu = computed(() => route.path)

const TITLE_MAP = {
  '/dashboard': '仪表板',
  '/alerts': '告警列表',
  '/approvals': '人工审批',
  '/tasks': '任务管理',
  '/tools': '工具管理',
  '/realtime': '实时监控',
  '/chatops': 'ChatOps 交互诊断'
}
const currentTitle = computed(() => TITLE_MAP[route.path] || '页面')

// 刷新：通过对 router-view 重新挂载实现（reloadKey 变化）
const reload = () => {
  window.location.reload()
}

const onCommand = async (cmd) => {
  if (cmd !== 'logout') return
  try {
    await ElMessageBox.confirm('确定要退出登录吗？', '退出确认', {
      confirmButtonText: '退出',
      cancelButtonText: '取消',
      type: 'warning'
    })
  } catch {
    return // 用户取消
  }
  userStore.logout()
  wsStore.disconnect()
  ElMessage.success('已退出登录')
  router.push('/login')
}

// 登录态下建立常驻 WebSocket：审批等全局事件必须任何页面都能收到。
// 任务页 / 实时监控页仍会按需连到具体 taskId（store.connect 幂等，不会重复建连）。
onMounted(() => {
  if (userStore.token) {
    wsStore.connect()
  }
})

onUnmounted(() => {
  // 不在这里断开：Layout 卸载通常是路由切换到登录页，由 logout 负责关闭。
  // 若在此断开，任何一次 Layout 重建都会掐掉审批通道。
})

</script>

<style scoped>
.layout {
  height: 100vh;
}

/* ---------- 侧边栏 ---------- */
.sidebar {
  background: linear-gradient(180deg, var(--c-sidebar-from) 0%, var(--c-sidebar-to) 100%);
  display: flex;
  flex-direction: column;
  transition: width 0.2s ease;
  overflow: hidden;
}

.brand {
  height: 64px;
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 0 16px;
  flex-shrink: 0;
  border-bottom: 1px solid rgba(255, 255, 255, 0.08);
}

.brand__logo {
  width: 34px;
  height: 34px;
  border-radius: 9px;
  background: linear-gradient(135deg, var(--c-primary-light), var(--c-primary-dark));
  display: flex;
  align-items: center;
  justify-content: center;
  color: #fff;
  flex-shrink: 0;
  box-shadow: 0 3px 10px rgba(37, 99, 235, 0.45);
}

.brand__title {
  color: #fff;
  font-size: 15px;
  font-weight: 600;
  line-height: 1.2;
  white-space: nowrap;
}

.brand__sub {
  color: rgba(255, 255, 255, 0.4);
  font-size: 11px;
  margin-top: 2px;
  white-space: nowrap;
}

.sidebar__scroll {
  flex: 1;
}

.sidebar__menu {
  border-right: none;
  background: transparent;
  padding: 8px;
}

/* 菜单分组小标题 */
.menu-group__title {
  padding: 14px 12px 6px;
  font-size: 11px;
  font-weight: 600;
  letter-spacing: 0.08em;
  color: rgba(255, 255, 255, 0.32);
  text-transform: uppercase;
}

.sidebar__menu :deep(.el-menu-item) {
  height: 42px;
  line-height: 42px;
  border-radius: var(--radius-md);
  margin-bottom: 2px;
  color: rgba(255, 255, 255, 0.65);
  font-size: 13.5px;
}

.sidebar__menu :deep(.el-menu-item:hover) {
  background: rgba(255, 255, 255, 0.07);
  color: #fff;
}

.sidebar__menu :deep(.el-menu-item.is-active) {
  background: linear-gradient(90deg, var(--c-primary) 0%, var(--c-primary-dark) 100%);
  color: #fff;
  font-weight: 500;
  box-shadow: 0 3px 10px rgba(37, 99, 235, 0.35);
}

.sidebar__footer {
  flex-shrink: 0;
  padding: 12px 18px;
  border-top: 1px solid rgba(255, 255, 255, 0.08);
  display: flex;
  align-items: center;
  gap: 8px;
}

.ws-dot {
  width: 7px;
  height: 7px;
  border-radius: 50%;
  background: #64748b;
  flex-shrink: 0;
}

.ws-dot--on {
  background: var(--c-success);
  box-shadow: 0 0 0 3px rgba(16, 185, 129, 0.2);
}

.ws-text {
  color: rgba(255, 255, 255, 0.45);
  font-size: 11.5px;
  white-space: nowrap;
}

/* ---------- 顶栏 ---------- */
.topbar {
  height: 64px;
  background: var(--c-surface);
  border-bottom: 1px solid var(--c-border);
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 0 20px;
  flex-shrink: 0;
}

.topbar__left {
  display: flex;
  align-items: center;
  gap: 16px;
}

.topbar__toggle {
  font-size: 18px;
  color: var(--c-text-secondary);
  cursor: pointer;
  padding: 6px;
  border-radius: var(--radius-sm);
  transition: all 0.15s;
}

.topbar__toggle:hover {
  background: var(--c-primary-soft);
  color: var(--c-primary);
}

.topbar__right {
  display: flex;
  align-items: center;
  gap: 16px;
}

.topbar__icon {
  font-size: 17px;
  color: var(--c-text-secondary);
  cursor: pointer;
  padding: 6px;
  border-radius: var(--radius-sm);
  transition: all 0.15s;
}

.topbar__icon:hover {
  background: var(--c-primary-soft);
  color: var(--c-primary);
}

.user {
  display: flex;
  align-items: center;
  gap: 8px;
  cursor: pointer;
  padding: 5px 10px 5px 5px;
  border-radius: 999px;
  transition: background 0.15s;
  outline: none;
}

.user:hover {
  background: var(--c-surface-hover);
}

.user__avatar {
  width: 30px;
  height: 30px;
  border-radius: 50%;
  background: linear-gradient(135deg, var(--c-primary-light), var(--c-primary-dark));
  color: #fff;
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 13px;
  font-weight: 600;
}

.user__name {
  font-size: 13.5px;
  color: var(--c-text);
  font-weight: 500;
}

.user__role {
  font-size: 12px;
  color: var(--c-text-muted);
}

/* ---------- 主内容 ---------- */
.main {
  background: var(--c-bg);
  padding: var(--space-page);
  overflow-y: auto;
}

/* ---------- 过渡 ---------- */
.fade-enter-active,
.fade-leave-active {
  transition: opacity 0.2s;
}

.fade-enter-from,
.fade-leave-to {
  opacity: 0;
}

.fade-slide-enter-active {
  transition: all 0.25s ease;
}

.fade-slide-leave-active {
  transition: all 0.15s ease;
}

.fade-slide-enter-from {
  opacity: 0;
  transform: translateY(8px);
}

.fade-slide-leave-to {
  opacity: 0;
}
</style>
