<template>
  <div class="login">
    <!-- 背景装饰：柔光斑，避免纯色渐变显得廉价 -->
    <div class="login__bg">
      <span class="blob blob--1"></span>
      <span class="blob blob--2"></span>
      <span class="blob blob--3"></span>
    </div>

    <div class="login__panel">
      <!-- 左侧品牌区：大屏下展示平台定位，小屏隐藏 -->
      <div class="login__brand">
        <div class="brand-logo">
          <el-icon :size="26"><Monitor /></el-icon>
        </div>
        <h1 class="brand-h1">AIOps 智能运维平台</h1>
        <p class="brand-desc">
          告警自动接入 · ReAct 多轮推理 · K8s 真实操作 · 高危人工审批
        </p>
        <ul class="brand-points">
          <li><el-icon><CircleCheck /></el-icon>全自动告警闭环，无需人工介入</li>
          <li><el-icon><CircleCheck /></el-icon>故障影响面与根因图谱</li>
          <li><el-icon><CircleCheck /></el-icon>指标基线学习，故障前预警</li>
          <li><el-icon><CircleCheck /></el-icon>修复后持续回归验证</li>
        </ul>
      </div>

      <!-- 右侧表单区 -->
      <div class="login__form">
        <div class="form-head">
          <h2>欢迎回来</h2>
          <p>请登录以继续使用平台</p>
        </div>

        <el-form :model="form" :rules="rules" ref="formRef" size="large" @keyup.enter="handleLogin">
          <el-form-item prop="username">
            <el-input v-model="form.username" placeholder="用户名" :prefix-icon="User" clearable />
          </el-form-item>
          <el-form-item prop="password">
            <el-input
              v-model="form.password"
              type="password"
              placeholder="密码"
              :prefix-icon="Lock"
              show-password
            />
          </el-form-item>
          <el-form-item>
            <el-button type="primary" @click="handleLogin" :loading="loading" class="submit-btn">
              {{ loading ? '登录中…' : '登 录' }}
            </el-button>
          </el-form-item>
        </el-form>

        <div class="form-hint">
          <el-icon><InfoFilled /></el-icon>
          演示账号：admin / admin123
        </div>
      </div>
    </div>
  </div>
</template>

<script setup>
import { reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { User, Lock, Monitor, CircleCheck, InfoFilled } from '@element-plus/icons-vue'
import { login } from '@/api/auth'
import { useUserStore } from '@/store/user'

const router = useRouter()
const userStore = useUserStore()
const formRef = ref()
const loading = ref(false)

const form = reactive({
  username: 'admin',
  password: ''
})

const rules = {
  username: [{ required: true, message: '请输入用户名', trigger: 'blur' }],
  password: [{ required: true, message: '请输入密码', trigger: 'blur' }]
}

const handleLogin = async () => {
  try {
    await formRef.value.validate()
  } catch {
    return // 校验失败，不进入加载态
  }
  loading.value = true
  try {
    const res = await login(form.username, form.password)
    const { token, username, role } = res.data
    userStore.setUser(token, username, role)
    ElMessage.success('登录成功')
    router.push('/')
  } catch (error) {
    ElMessage.error(error.message || '登录失败')
  } finally {
    loading.value = false
  }
}
</script>

<style scoped>
.login {
  min-height: 100vh;
  display: flex;
  align-items: center;
  justify-content: center;
  background: linear-gradient(135deg, #0f172a 0%, #1e293b 50%, #1e3a8a 100%);
  position: relative;
  overflow: hidden;
  padding: 24px;
}

/* ---------- 背景光斑 ---------- */
.login__bg {
  position: absolute;
  inset: 0;
  pointer-events: none;
}

.blob {
  position: absolute;
  border-radius: 50%;
  filter: blur(80px);
  opacity: 0.35;
}

.blob--1 {
  width: 420px;
  height: 420px;
  background: #3b82f6;
  top: -120px;
  left: -80px;
}

.blob--2 {
  width: 360px;
  height: 360px;
  background: #8b5cf6;
  bottom: -100px;
  right: -60px;
}

.blob--3 {
  width: 280px;
  height: 280px;
  background: #10b981;
  bottom: 20%;
  left: 35%;
  opacity: 0.18;
}

/* ---------- 面板 ---------- */
.login__panel {
  position: relative;
  z-index: 1;
  display: flex;
  width: 100%;
  max-width: 880px;
  background: rgba(255, 255, 255, 0.98);
  border-radius: 18px;
  overflow: hidden;
  box-shadow: 0 24px 60px rgba(0, 0, 0, 0.35);
}

/* ---------- 左侧品牌 ---------- */
.login__brand {
  flex: 1;
  padding: 44px 40px;
  background: linear-gradient(160deg, #1e293b 0%, #0f172a 100%);
  color: #fff;
  display: flex;
  flex-direction: column;
  justify-content: center;
}

.brand-logo {
  width: 52px;
  height: 52px;
  border-radius: 14px;
  background: linear-gradient(135deg, var(--c-primary-light), var(--c-primary-dark));
  display: flex;
  align-items: center;
  justify-content: center;
  margin-bottom: 22px;
  box-shadow: 0 8px 20px rgba(37, 99, 235, 0.45);
}

.brand-h1 {
  font-size: 22px;
  font-weight: 600;
  margin: 0 0 12px;
  line-height: 1.35;
}

.brand-desc {
  font-size: 13px;
  color: rgba(255, 255, 255, 0.55);
  line-height: 1.7;
  margin: 0 0 28px;
}

.brand-points {
  list-style: none;
  padding: 0;
  margin: 0;
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.brand-points li {
  display: flex;
  align-items: center;
  gap: 9px;
  font-size: 13px;
  color: rgba(255, 255, 255, 0.78);
}

.brand-points .el-icon {
  color: var(--c-success);
  flex-shrink: 0;
}

/* ---------- 右侧表单 ---------- */
.login__form {
  width: 380px;
  flex-shrink: 0;
  padding: 48px 40px;
  display: flex;
  flex-direction: column;
  justify-content: center;
}

.form-head {
  margin-bottom: 26px;
}

.form-head h2 {
  font-size: 22px;
  font-weight: 600;
  margin: 0 0 6px;
  color: var(--c-text);
}

.form-head p {
  font-size: 13px;
  color: var(--c-text-muted);
  margin: 0;
}

.submit-btn {
  width: 100%;
  height: 42px;
  font-size: 15px;
  letter-spacing: 0.1em;
}

.form-hint {
  margin-top: 18px;
  padding: 10px 14px;
  background: var(--c-primary-soft);
  border-radius: var(--radius-md);
  font-size: 12.5px;
  color: var(--c-primary-dark);
  display: flex;
  align-items: center;
  gap: 7px;
}

/* 窄屏：隐藏品牌区，只留表单 */
@media (max-width: 760px) {
  .login__brand {
    display: none;
  }

  .login__form {
    width: 100%;
  }
}
</style>
