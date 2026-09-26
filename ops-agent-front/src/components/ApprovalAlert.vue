<template>
  <!--
    全局审批提醒。
    挂在 Layout 里，任何页面都能弹出来 —— 这是 manual 审批模式的唯一活路：
    agent 发起审批时用户往往不在审批页，靠页面级提示必然错过 55 秒窗口。
  -->
  <el-dialog
    v-model="visible"
    :show-close="false"
    :close-on-click-modal="false"
    :close-on-press-escape="false"
    width="520px"
    class="approval-dialog"
    align-center
  >
    <template #header>
      <div class="approval-head">
        <div class="approval-head__icon">
          <el-icon :size="22"><BellFilled /></el-icon>
        </div>
        <div>
          <div class="approval-head__title">AI 请求人工审批</div>
          <div class="approval-head__sub">高危操作需你确认后才会执行</div>
        </div>
      </div>
    </template>

    <div class="approval-body">
      <div class="approval-row">
        <span class="approval-row__label">操作</span>
        <span class="approval-row__value mono">{{ approval.operation || '—' }}</span>
      </div>
      <div class="approval-row">
        <span class="approval-row__label">原因</span>
        <span class="approval-row__value">{{ approval.reason || '—' }}</span>
      </div>
      <div class="approval-row">
        <span class="approval-row__label">请求号</span>
        <span class="approval-row__value mono">{{ approval.requestId }}</span>
      </div>

      <!-- 倒计时进度条：秒数在走，紧迫感一目了然 -->
      <div class="approval-countdown">
        <div class="approval-countdown__text">
          <el-icon><Timer /></el-icon>
          <span>剩余 <b>{{ remain }}</b> 秒，超时后系统将自动拒绝</span>
        </div>
        <el-progress
          :percentage="percent"
          :show-text="false"
          :stroke-width="6"
          :color="progressColor"
        />
      </div>
    </div>

    <template #footer>
      <div class="approval-foot">
        <el-button @click="decide(false)" :loading="submitting === 'reject'">
          拒绝
        </el-button>
        <el-button type="primary" @click="decide(true)" :loading="submitting === 'approve'">
          批准执行
        </el-button>
      </div>
    </template>
  </el-dialog>
</template>

<script setup>
import { ref, computed, watch, onUnmounted } from 'vue'
import { ElMessage } from 'element-plus'
import { BellFilled, Timer } from '@element-plus/icons-vue'
import { useWebSocketStore } from '@/store/websocket'
import { submitDecision } from '@/api/approval'

const wsStore = useWebSocketStore()

const visible = ref(false)
const approval = ref({})
const remain = ref(0)
const submitting = ref('')
let timer = null

const percent = computed(() => {
  const total = approval.value.timeout || 55
  if (!total) return 0
  return Math.max(0, Math.min(100, (remain.value / total) * 100))
})

// 剩余时间越少越红，从蓝 -> 橙 -> 红
const progressColor = computed(() => {
  if (percent.value > 50) return '#2563eb'
  if (percent.value > 20) return '#f59e0b'
  return '#ef4444'
})

// 监听 store 里的待审批项：一有就弹窗 + 提示音
watch(() => wsStore.pendingApproval, (val) => {
  if (val) {
    approval.value = val
    remain.value = val.timeout || 55
    visible.value = true
    startCountdown()
    playChime()
  } else {
    // 已决定或已超时：立即关闭，不让用户操作一个失效的框
    visible.value = false
    stopCountdown()
  }
}, { deep: true })

const startCountdown = () => {
  stopCountdown()
  timer = setInterval(() => {
    remain.value -= 1
    if (remain.value <= 0) {
      stopCountdown()
      visible.value = false
      ElMessage.warning('审批已超时，系统自动拒绝')
    }
  }, 1000)
}

const stopCountdown = () => {
  if (timer) {
    clearInterval(timer)
    timer = null
  }
}

/**
 * 提示音：用 WebAudio 合成，不依赖任何音频文件。
 * 审批窗口只有 55 秒且用户可能不在屏幕前，纯视觉提示会漏。
 */
const playChime = () => {
  try {
    const Ctx = window.AudioContext || window.webkitAudioContext
    if (!Ctx) return
    const ctx = new Ctx()
    // 两声递进的短音，比单音更"抓耳朵"但不刺耳
    const notes = [[880, 0], [1174.66, 0.18]]
    notes.forEach(([freq, delay]) => {
      const osc = ctx.createOscillator()
      const gain = ctx.createGain()
      osc.type = 'sine'
      osc.frequency.value = freq
      gain.gain.setValueAtTime(0.0001, ctx.currentTime + delay)
      gain.gain.exponentialRampToValueAtTime(0.25, ctx.currentTime + delay + 0.01)
      gain.gain.exponentialRampToValueAtTime(0.0001, ctx.currentTime + delay + 0.25)
      osc.connect(gain)
      gain.connect(ctx.destination)
      osc.start(ctx.currentTime + delay)
      osc.stop(ctx.currentTime + delay + 0.3)
    })
    // 播完关掉上下文，避免长时间挂着占用音频通道
    setTimeout(() => ctx.close(), 1200)
  } catch {
    // 浏览器未授权音频等情况：静默降级为纯视觉提醒
  }
}

const decide = async (approved) => {
  submitting.value = approved ? 'approve' : 'reject'
  try {
    await submitDecision(approval.value.requestId, {
      approved,
      note: approved ? '从全局提醒弹窗批准' : '从全局提醒弹窗拒绝'
    })
    ElMessage.success(approved ? '已批准，AI 将继续执行' : '已拒绝，AI 将改用安全方案')
    wsStore.pendingApproval = null
    visible.value = false
    stopCountdown()
  } catch (e) {
    ElMessage.error(e?.response?.data?.message || '审批提交失败，请到人工审批页处理')
  } finally {
    submitting.value = ''
  }
}

onUnmounted(stopCountdown)
</script>

<style scoped>
.approval-head {
  display: flex;
  align-items: center;
  gap: 12px;
}

.approval-head__icon {
  width: 40px;
  height: 40px;
  border-radius: 10px;
  background: var(--c-warning-soft);
  color: var(--c-warning);
  display: flex;
  align-items: center;
  justify-content: center;
  flex-shrink: 0;
}

.approval-head__title {
  font-size: 15.5px;
  font-weight: 600;
  color: var(--c-text);
}

.approval-head__sub {
  font-size: 12px;
  color: var(--c-text-muted);
  margin-top: 2px;
}

.approval-body {
  padding: 2px 0;
}

.approval-row {
  display: flex;
  gap: 12px;
  padding: 9px 0;
  border-bottom: 1px dashed var(--c-border);
}

.approval-row:last-of-type {
  border-bottom: none;
}

.approval-row__label {
  width: 52px;
  flex-shrink: 0;
  font-size: 12.5px;
  color: var(--c-text-muted);
}

.approval-row__value {
  flex: 1;
  font-size: 13px;
  color: var(--c-text);
  word-break: break-all;
}

.approval-countdown {
  margin-top: 14px;
  padding: 12px 14px;
  background: var(--c-surface-hover);
  border-radius: var(--radius-md);
}

.approval-countdown__text {
  display: flex;
  align-items: center;
  gap: 6px;
  font-size: 12.5px;
  color: var(--c-text-secondary);
  margin-bottom: 8px;
}

.approval-countdown__text b {
  color: var(--c-danger);
  font-size: 14px;
  margin: 0 2px;
}

.approval-foot {
  display: flex;
  justify-content: flex-end;
  gap: 8px;
}
</style>
