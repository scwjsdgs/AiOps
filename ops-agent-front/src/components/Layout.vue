<template>
  <el-container class="layout-container">
    <el-aside width="200px">
      <div class="logo">AIOps 平台</div>
      <el-menu router default-active="/dashboard" background-color="#1f2d3d" text-color="#bfcbd9" active-text-color="#409eff">
        <el-menu-item index="/dashboard"><el-icon><DataBoard /></el-icon>仪表板</el-menu-item>
        <el-menu-item index="/alerts"><el-icon><Warning /></el-icon>告警列表</el-menu-item>
        <el-menu-item index="/approvals"><el-icon><Stamp /></el-icon>人工审批</el-menu-item>
        <el-menu-item index="/tasks"><el-icon><List /></el-icon>任务管理</el-menu-item>
        <el-menu-item index="/tools"><el-icon><Tools /></el-icon>工具管理</el-menu-item>
        <el-menu-item index="/realtime"><el-icon><Connection /></el-icon>实时监控</el-menu-item>
      </el-menu>
    </el-aside>
    <el-container>
      <el-header>
        <div class="header-right">
          <span>{{ username }}</span>
          <el-button type="danger" link @click="logout">退出</el-button>
        </div>
      </el-header>
      <el-main>
        <router-view />
      </el-main>
    </el-container>
  </el-container>
</template>

<script setup>
import { computed } from 'vue'
import { useRouter } from 'vue-router'
import { useUserStore } from '@/store/user'
import { ElMessage } from 'element-plus'

const router = useRouter()
const userStore = useUserStore()
const username = computed(() => userStore.username)

const logout = () => {
  userStore.logout()
  ElMessage.success('已退出')
  router.push('/login')
}
</script>

<style scoped>
.layout-container {
  height: 100vh;
}
.el-aside {
  background-color: #1f2d3d;
  color: #fff;
}
.logo {
  height: 60px;
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 20px;
  font-weight: bold;
  color: #fff;
  border-bottom: 1px solid #2c3e50;
}
.el-header {
  background: #fff;
  border-bottom: 1px solid #e4e7ed;
  display: flex;
  justify-content: flex-end;
  align-items: center;
  padding: 0 20px;
}
.header-right {
  display: flex;
  align-items: center;
  gap: 15px;
}
.el-main {
  background: #f5f7fa;
  padding: 20px;
}
</style>