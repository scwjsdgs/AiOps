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
      <div v-if="approval.serviceName" class="approval-service">
        <span class="approval-service__label">目标服务</span>
        <b class="approval-service__name">{{ approval.serviceName }}</b>
        <span class="approval-service__ns">(namespace: {{ approval.namespace || 'default' }})</span>
      </div>
      <div class="approval-row">
        <span class="approval-row__label">操作</span>
        <span class="approval-row__value mono">
          {{ approval.operation || '—' }}<template v-if="targetHint">（目标：{{ targetHint }}）</template>
        </span>
      </div>
      <div class="approval-row approval-row--reason">
        <span class="approval-row__label">原因</span>
        <div class="approval-reason">
          <!-- 折叠摘要：只显示 ROOT_CAUSE/前 3 行，避免 Markdown 长文淹没关键信息 -->
          <div v-if="!expanded" class="approval-reason__summary">{{ reasonSummary }}</div>
          <!-- 展开后 Markdown 渲染（##/粗体正常显示） -->
          <div v-if="expanded" class="approval-reason__full markdown" v-html="renderedReason"></div>
          <el-button v-if="reasonLong" link type="primary" size="small" @click="expanded = !expanded">
            {{ expanded ? '收起完整诊断依据' : '展开查看完整诊断依据' }}
          </el-button>
        </div>
      </div>
      <div class="approval-row">
        <span class="approval-row__label">请求号</span>
        <span class="approval-row__value mono">{{ approval.requestId }}</span>
      </div>

      <!-- 倒计时进度条：秒数在走，紧迫感一目了然 -->
      <div class="approval-countdown">
        <div class="approval-countdown__text">
          <el-icon><Timer /></el-icon>
          <span>剩余 <b>{{ remain }}</b> 秒，超时后系统将自动拒绝并进入 15 分钟静默冷却期</span>
        </div>
        <el-progress
          :percentage="percent"
          :show-text="false"
          :stroke-width="6"
          :color="progressColor"
        />
      </div>

      <!-- 批准后的行为说明：执行完不是立刻成功，而是进入最多 3 轮验证 -->
      <div class="approval-hint">
        <el-icon><InfoFilled /></el-icon>
        <span>
          批准后 agent 执行变更，并进入验证期（每轮 5 分钟，最多 3 轮）；
          验证失败会再次请求审批，连续 3 轮失败则停止自动修复并生成最终失败报告。
          拒绝则进入 15 分钟冷却，期间同源告警只累计次数、不再弹窗。
        </span>
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
import { BellFilled, Timer, InfoFilled } from '@element-plus/icons-vue'
import { useWebSocketStore } from '@/store/websocket'
import { submitDecision } from '@/api/approval'
import { marked } from 'marked'

const expanded = ref(false)
const targetHint = computed(() => {
  const p = approval.value.podName || ''
  if (p) return p
  const m = String(approval.value.reason || '').match(/\b([a-z0-9]+-[a-z0-9]{6,10}-[a-z0-9]{5})\b/)
  return m ? m[1] : ''
})
const reasonLong = computed(() => String(approval.value.reason || '').length > 80)
// 折叠摘要：优先取 ROOT_CAUSE 一行，否则取前 3 行并截断到约 50 字
const reasonSummary = computed(() => {
  const raw = String(approval.value.reason || '')
  const lines = raw.split('\n').map(s => s.trim()).filter(Boolean)
  const root = lines.find(l => /^ROOT_CAUSE\s*[:=]/i.test(l))
  const base = root || lines[0] || raw
  return base.length > 50 ? base.slice(0, 50) + '…' : base
})
const renderedReason = computed(() => {
  try {
    return marked.parse(String(approval.value.reason || ''), { breaks: true })
  } catch {
    return approval.value.reason || ''
  }
})

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
    expanded.value = false
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
      // 同时清掉全局待审批状态，避免审批已超时但 store 里仍残留僵尸审批。
      wsStore.pendingApproval = null
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
    ElMessage.success(approved ? '已批准，AI 将继续执行，执行后进入验证期' : '已拒绝，进入 15 分钟静默冷却期')
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

.approval-service {
  display: flex;
  align-items: baseline;
  gap: 6px;
  padding: 10px 12px;
  margin: 2px 0 8px;
  background: var(--c-primary-soft);
  border-radius: var(--radius-md);
}

.approval-service__label {
  font-size: 12px;
  color: var(--c-text-muted);
}

.approval-service__name {
  font-size: 14px;
  font-family: Consolas, monospace;
  color: var(--c-primary);
}

.approval-service__ns {
  font-size: 11.5px;
  color: var(--c-text-muted);
}

.approval-row--reason {
  align-items: flex-start;
}

.approval-reason {
  flex: 1;
  min-width: 0;
  font-size: 12.5px;
  color: var(--c-text-secondary);
  line-height: 1.55;
}

.approval-reason__summary {
  white-space: pre-wrap;
  word-break: break-word;
}

.approval-reason__full.markdown :deep(h1),
.approval-reason__full.markdown :deep(h2),
.approval-reason__full.markdown :deep(h3) {
  font-size: 13.5px;
  margin: 8px 0 4px;
  color: var(--c-text);
}

.approval-reason__full.markdown :deep(p) {
  margin: 5px 0;
}

.approval-reason__full.markdown :deep(strong) {
  color: var(--c-text);
}

.approval-reason__full.markdown :deep(code) {
  background: var(--c-info-soft);
  padding: 1px 5px;
  border-radius: 3px;
  font-size: 12px;
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

.approval-hint {
  display: flex;
  align-items: flex-start;
  gap: 6px;
  margin-top: 10px;
  font-size: 12px;
  line-height: 1.55;
  color: var(--c-text-muted);
}

.approval-hint .el-icon {
  margin-top: 2px;
  flex-shrink: 0;
  color: var(--c-info);
}
</style>
