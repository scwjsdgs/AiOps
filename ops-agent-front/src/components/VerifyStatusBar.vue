<template>
  <!--
    验证中提示（生产级闭环）。
    修复批准并执行完成后，系统不是立刻宣布「已修复」，而是进入 VERIFYING：
    在验证窗口内持续复查服务健康度。这个组件把「系统正在努力」讲清楚——
      当前第几轮（防烧钱预算 3 轮）+ 剩余倒计时 + 末次复查结论。
    用户不用猜为什么任务还没结束。
  -->
  <div class="verify-bar" :class="{ 'verify-bar--compact': compact }">
    <div class="verify-bar__main">
      <el-icon class="verify-bar__icon is-loading"><Loading /></el-icon>
      <div class="verify-bar__text">
        <span class="verify-bar__title">
          {{ round > 0 ? `验证中 · 第 ${round}/${max} 轮` : '健康观察中' }}
        </span>
        <span class="verify-bar__sub">{{ subtitle }}</span>
      </div>
    </div>
    <div class="verify-bar__right">
      <span v-if="remain !== null" class="verify-bar__countdown mono">{{ countdownText }}</span>
      <el-progress
        v-if="percent !== null"
        :percentage="percent"
        :show-text="false"
        :stroke-width="4"
        :color="color"
        class="verify-bar__progress"
      />
    </div>
  </div>
</template>

<script setup>
import { ref, computed, onMounted, onUnmounted } from 'vue'
import { Loading } from '@element-plus/icons-vue'
import { secondsUntil, formatCountdown } from '@/utils/format'

const props = defineProps({
  /** 当前验证轮次，0 = 只读诊断观察（不占修复重试预算） */
  round: { type: Number, default: 0 },
  /** 最大自动重试轮次 */
  max: { type: Number, default: 3 },
  /** 本轮验证截止时间（后端 LocalDateTime 串） */
  deadline: { type: String, default: '' },
  /** 后端附带的验证说明（末次复查结论等） */
  note: { type: String, default: '' },
  compact: { type: Boolean, default: false }
})

const remain = ref(null)
// 进度条总量：验证窗口默认 5 分钟（与后端 observe-minutes 对齐），
// 这里只用于观感，真实截止时间以 deadline 为准。
const WINDOW_SECONDS = 5 * 60
let timer = null

const tick = () => {
  remain.value = secondsUntil(props.deadline)
}

const subtitle = computed(() => {
  if (props.note) return props.note
  if (props.round > 0) {
    return `修复已执行，系统正在观察服务是否真正恢复；若未恢复将自动重试（最多 ${props.max} 轮）`
  }
  return '诊断完成后进入健康观察，确认服务是否真正恢复'
})

const countdownText = computed(() => `剩余 ${formatCountdown(remain.value)}`)

const percent = computed(() => {
  if (remain.value === null) return null
  return Math.max(0, Math.min(100, Math.round((remain.value / WINDOW_SECONDS) * 100)))
})

const color = computed(() => {
  if (percent.value === null) return '#2563eb'
  if (percent.value > 50) return '#2563eb'
  if (percent.value > 20) return '#f59e0b'
  return '#ef4444'
})

onMounted(() => {
  tick()
  timer = setInterval(tick, 1000)
})

onUnmounted(() => {
  if (timer) clearInterval(timer)
})
</script>

<style scoped>
.verify-bar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  padding: 12px 16px;
  background: var(--c-info-soft);
  border: 1px solid var(--c-border);
  border-radius: var(--radius-md);
}

.verify-bar--compact {
  padding: 8px 12px;
}

.verify-bar__main {
  display: flex;
  align-items: center;
  gap: 10px;
  min-width: 0;
}

.verify-bar__icon {
  color: var(--c-primary);
  font-size: 18px;
  flex-shrink: 0;
}

.verify-bar__text {
  display: flex;
  flex-direction: column;
  gap: 2px;
  min-width: 0;
}

.verify-bar__title {
  font-size: 13px;
  font-weight: 600;
  color: var(--c-text);
}

.verify-bar__sub {
  font-size: 12px;
  color: var(--c-text-muted);
  line-height: 1.5;
}

.verify-bar__right {
  display: flex;
  align-items: center;
  gap: 10px;
  flex-shrink: 0;
}

.verify-bar__countdown {
  font-size: 13px;
  font-weight: 600;
  color: var(--c-primary-dark, var(--c-primary));
}

.verify-bar__progress {
  width: 120px;
}
</style>
