<template>
  <div class="page">
    <el-card body-style="padding: 0;">
      <div class="rt-head">
        <div>
          <h3 class="page-title">
            <el-icon><Connection /></el-icon>实时监控
            <span class="ws-badge" :class="{ 'ws-badge--on': connected }">
              {{ connected ? '已连接' : '未连接' }}
            </span>
          </h3>
          <p class="page-subtitle" style="margin-top: 4px;">
            选择任务后订阅其 WebSocket 推送，实时查看 agent 的推理步骤与执行结果。
          </p>
        </div>

        <div class="rt-controls">
          <!-- 任务下拉：免手输 ID。列出进行中与最近的任务，选中即连 -->
          <el-select
            v-model="selectedTaskId"
            filterable
            placeholder="选择任务（可搜索输入内容）"
            style="width: 340px;"
            :loading="tasksLoading"
            @change="connect"
          >
            <el-option
              v-for="t in taskOptions"
              :key="t.id"
              :label="optionLabel(t)"
              :value="t.id"
            >
              <div class="opt">
                <StatusTag :status="t.status" kind="task" size="small" />
                <span class="opt__text">{{ firstLine(t.input) }}</span>
                <span class="opt__time">{{ fromNow(t.createdAt) }}</span>
              </div>
            </el-option>
          </el-select>

          <el-button type="primary" :icon="Link" :disabled="!selectedTaskId || connected" @click="connect">
            连接
          </el-button>
          <el-button type="danger" plain :icon="SwitchButton" :disabled="!connected" @click="disconnect">
            断开
          </el-button>
          <el-button :icon="Delete" :disabled="!messages.length" @click="clear">清空</el-button>
        </div>
      </div>

      <div v-if="selectedTaskId" class="rt-meta">
        <span class="text-muted">当前订阅任务：</span>
        <CopyId :id="selectedTaskId" />
        <el-link v-if="selectedTaskId" type="primary" style="margin-left: 12px;"
                 @click="goTask(selectedTaskId)">
          查看完整报告
        </el-link>
      </div>

      <!-- ============ 进度总览 ============ -->
      <!-- 改造前这里只有一条裸消息流：用户得自己在滚动文字里拼凑「跑到哪一步了」。
           现在把状态、进度、耗时、当前动作提炼到顶部，一眼看清 agent 在干什么。 -->
      <div v-if="selectedTaskId" class="rt-progress">
        <div class="rt-progress__stats">
          <div class="rt-stat">
            <div class="rt-stat__label">任务状态</div>
            <div class="rt-stat__value">
              <StatusTag :status="taskStatus" kind="task" size="small" />
            </div>
          </div>
          <div class="rt-stat">
            <div class="rt-stat__label">已执行步骤</div>
            <div class="rt-stat__value">
              <b>{{ steps.length }}</b> 步
            </div>
          </div>
          <div class="rt-stat">
            <div class="rt-stat__label">工具调用</div>
            <div class="rt-stat__value">
              <b>{{ toolCallCount }}</b> 次
            </div>
          </div>
          <div class="rt-stat">
            <div class="rt-stat__label">已用时</div>
            <div class="rt-stat__value">
              <b>{{ elapsedText }}</b>
            </div>
          </div>
        </div>

        <!-- 当前动作：最新一步的内容，这个是"现在在干嘛"的答案 -->
        <div class="rt-current" :class="`rt-current--${msgKind({ content: currentStep?.content, stepName: currentStep?.stepName })}`">
          <div class="rt-current__head">
            <span class="rt-current__pulse"></span>
            当前动作
          </div>
          <div class="rt-current__text">{{ currentStep ? currentStep.content : '等待 agent 输出…' }}</div>
        </div>

        <!-- 进度条：按已完成步骤数估算，收尾时满格 -->
        <el-progress
          :percentage="progressPercent"
          :status="progressStatus"
          :stroke-width="8"
          :duration="3"
        />
      </div>

      <!-- ============ 步骤时间线 ============ -->
      <div class="rt-stream" ref="messageContainer">
        <div v-if="!messages.length" class="rt-empty">
          <el-icon :size="40" style="color: var(--c-text-muted);"><Connection /></el-icon>
          <p>暂无推送消息</p>
          <p class="rt-empty__hint">
            选择上方任务并点击「连接」后，agent 的每一步推理都会实时出现在这里。
          </p>
        </div>

        <div v-for="(msg, idx) in messages" :key="idx" class="msg" :class="`msg--${msgKind(msg)}`">
          <div class="msg__gutter">
            <span class="msg__dot"></span>
            <span v-if="idx < messages.length - 1" class="msg__line"></span>
          </div>
          <div class="msg__body">
            <div class="msg__head">
              <span class="msg__time">{{ formatTime(msg.timestamp) }}</span>
              <el-tag :type="msgTagType(msg)" size="small" effect="plain">
                {{ msg.stepName || msg.type || '消息' }}
              </el-tag>
              <span class="msg__elapsed">{{ elapsedOf(idx) }}</span>
            </div>
            <div class="msg__content">{{ msg.content }}</div>
          </div>
        </div>
      </div>
    </el-card>
  </div>
</template>

<script setup>
import { ref, computed, watch, nextTick, onMounted, onUnmounted } from 'vue'
import { useRouter } from 'vue-router'
import { useWebSocketStore } from '@/store/websocket'
import { getTasks } from '@/api/task'
import { ElMessage } from 'element-plus'
import { Connection, Link, SwitchButton, Delete } from '@element-plus/icons-vue'
import StatusTag from '@/components/StatusTag.vue'
import CopyId from '@/components/CopyId.vue'
import { formatTime, fromNow, stepKind } from '@/utils/format'

const router = useRouter()
const wsStore = useWebSocketStore()

const selectedTaskId = ref('')
const messages = ref([])
const messageContainer = ref(null)
const connected = computed(() => wsStore.connected)

// 任务下拉数据
const taskOptions = ref([])
const tasksLoading = ref(false)

const firstLine = (text) => {
  if (!text) return '（无输入）'
  const line = String(text).split('\n').map(s => s.trim()).filter(Boolean)[0] || ''
  return line.length > 40 ? line.slice(0, 40) + '…' : line
}

const optionLabel = (t) => firstLine(t.input)

const loadTaskOptions = async () => {
  tasksLoading.value = true
  try {
    const res = await getTasks({ page: 1, size: 50 })
    taskOptions.value = res.data?.records || []
  } catch {
    taskOptions.value = []
  } finally {
    tasksLoading.value = false
  }
}

// 消息按内容分型，左侧色点与连线随之变色
const msgKind = (msg) => {
  const s = `${msg.stepName || ''} ${msg.content || ''}`
  return stepKind(s)
}

// ---------------------------- 进度视图 ----------------------------
const currentTask = computed(() =>
  taskOptions.value.find(t => t.id === selectedTaskId.value) || null
)
const taskStatus = computed(() => currentTask.value?.status || 'RUNNING')

// 按「stepName + 内容」去重：同一步可能推多条（如先推"准备执行"再推"执行结果"），
// 进度按"步骤"而非"消息"算。
// 只用 stepName 做 key 会把两次独立的同类调用（比如连着查两次日志）合并成一步，
// 步骤数偏少、进度条虚高，所以内容也要参与。
const steps = computed(() => {
  const seen = new Map()
  messages.value.forEach((m, i) => {
    const key = `${m.stepName || ''}|${m.content || `msg-${i}`}`
    if (!seen.has(key)) seen.set(key, m)
  })
  return [...seen.values()]
})

const currentStep = computed(() => messages.value[messages.value.length - 1] || null)

const toolCallCount = computed(() =>
  messages.value.filter(m => msgKind(m) === 'tool').length
)

// 进度百分比：已出步骤数 / 预估总步数。
// 预估来自历史任务的典型步数（多在 6-10 步），封顶 95% ——任务没结束就不该显示 100%，
// 否则最后一步迟迟不来时会让人误以为已经完成。
const ESTIMATED_STEPS = 8
const progressPercent = computed(() => {
  if (isFinished.value) return 100
  return Math.min(95, Math.round((steps.value.length / ESTIMATED_STEPS) * 100))
})

const isFinished = computed(() =>
  ['SUCCESS', 'FAILED', 'REGRESSED', 'TIMEOUT'].includes(taskStatus.value)
)

const progressStatus = computed(() => {
  if (!isFinished.value) return undefined
  if (taskStatus.value === 'SUCCESS') return 'success'
  if (taskStatus.value === 'TIMEOUT') return 'warning'
  return 'exception'
})

// 已用时：从第一条消息到现在的时长，每 30 秒刷新一次（不需要秒级抖动）
const now = ref(Date.now())
let tickTimer = null

const elapsedText = computed(() => {
  const first = messages.value[0]
  if (!first?.timestamp) return '—'
  const start = new Date(first.timestamp).getTime()
  if (Number.isNaN(start)) return '—'
  return formatDuration(now.value - start)
})

const elapsedOf = (idx) => {
  if (idx === 0) return ''
  const prev = messages.value[idx - 1]
  const cur = messages.value[idx]
  if (!prev?.timestamp || !cur?.timestamp) return ''
  const d = new Date(cur.timestamp).getTime() - new Date(prev.timestamp).getTime()
  if (Number.isNaN(d) || d < 0) return ''
  // 步间间隔：看得出哪一步"卡"得久
  return d > 1000 ? `+${formatDuration(d)}` : ''
}

const formatDuration = (ms) => {
  const s = Math.max(0, Math.floor(ms / 1000))
  if (s < 60) return `${s} 秒`
  const m = Math.floor(s / 60)
  const rest = s % 60
  if (m < 60) return rest ? `${m} 分 ${rest} 秒` : `${m} 分`
  return `${Math.floor(m / 60)} 时 ${m % 60} 分`
}

const msgTagType = (msg) => {
  const k = msgKind(msg)
  if (k === 'error') return 'danger'
  if (k === 'tool') return 'primary'
  if (k === 'repair') return 'warning'
  if (k === 'approval') return 'warning'
  return 'success'
}

watch(() => wsStore.messages, (newMsgs) => {
  messages.value = [...newMsgs]
  nextTick(() => {
    if (messageContainer.value) {
      messageContainer.value.scrollTop = messageContainer.value.scrollHeight
    }
  })
}, { deep: true })

watch(() => wsStore.connected, (val) => {
  if (val) ElMessage.success('实时通道已连接')
})

const connect = () => {
  const id = selectedTaskId.value
  if (!id) {
    ElMessage.warning('请先选择任务')
    return
  }
  wsStore.clearMessages()
  wsStore.connect(id)
}

const disconnect = () => {
  // 切回只收广播（审批）的连接，而不是彻底断开：
  // 完全断开会让审批提醒失效，而那一侧才是 manual 模式的关键路径。
  wsStore.connect('')
  ElMessage.info('已取消任务订阅（审批提醒仍保持连接）')
}

const clear = () => {
  wsStore.clearMessages()
  messages.value = []
}

const goTask = (id) => {
  router.push({ path: '/tasks', query: { id } })
}

onMounted(() => {
  loadTaskOptions()
  // 每 30 秒刷新"已用时"显示。30 秒足够——秒级跳动既无信息量又费渲染。
  tickTimer = setInterval(() => { now.value = Date.now() }, 30000)
  // 若 URL 里带了任务 ID（从任务页跳来），自动选中并订阅
  const qid = router.currentRoute.value.query.taskId
  if (qid) {
    selectedTaskId.value = String(qid)
    connect()
  }
})

// 离开页面不再断开连接。
// 改造前这里 disconnect()，导致一离开实时监控页，审批等全局事件就收不到了——
// 而审批恰恰只给 55 秒窗口，断了就等于必然错过。连接由 Layout 常驻维持，
// 登出时才关闭。
onUnmounted(() => {
  if (tickTimer) {
    clearInterval(tickTimer)
    tickTimer = null
  }
  // 仅停止本地计时，不动 WebSocket
})
</script>

<style scoped>
.rt-head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 16px;
  padding: 18px 20px;
  border-bottom: 1px solid var(--c-border);
  flex-wrap: wrap;
}

.rt-controls {
  display: flex;
  align-items: center;
  gap: 10px;
  flex-wrap: wrap;
}

.ws-badge {
  font-size: 11.5px;
  font-weight: 500;
  padding: 3px 9px;
  border-radius: 999px;
  background: var(--c-info-soft);
  color: var(--c-text-muted);
}

.ws-badge--on {
  background: var(--c-success-soft);
  color: var(--c-success);
}

/* 下拉项：状态 + 摘要 + 时间三段布局 */
.opt {
  display: flex;
  align-items: center;
  gap: 8px;
}

.opt__text {
  flex: 1;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  font-size: 12.5px;
}

.opt__time {
  font-size: 11.5px;
  color: var(--c-text-muted);
  flex-shrink: 0;
}

.rt-meta {
  padding: 10px 20px;
  background: var(--c-primary-soft);
  font-size: 12.5px;
  display: flex;
  align-items: center;
  border-bottom: 1px solid var(--c-border);
}

/* ---------- 进度总览 ---------- */
.rt-progress {
  padding: 18px 20px;
  border-bottom: 1px solid var(--c-border);
  background: var(--c-surface);
}

.rt-progress__stats {
  display: grid;
  grid-template-columns: repeat(4, 1fr);
  gap: 12px;
  margin-bottom: 16px;
}

.rt-stat {
  padding: 12px 14px;
  background: var(--c-surface-hover);
  border-radius: var(--radius-md);
}

.rt-stat__label {
  font-size: 12px;
  color: var(--c-text-muted);
  margin-bottom: 6px;
}

.rt-stat__value {
  font-size: 13.5px;
  color: var(--c-text);
  display: flex;
  align-items: center;
  gap: 4px;
}

.rt-stat__value b {
  font-size: 19px;
  font-weight: 600;
  color: var(--c-text);
}

/* 当前动作：整块着色，跟着消息类型变（工具=蓝 / 错误=红 / 完成=绿） */
.rt-current {
  padding: 12px 14px;
  border-radius: var(--radius-md);
  background: var(--c-primary-soft);
  border-left: 3px solid var(--c-primary);
  margin-bottom: 16px;
}

.rt-current--error {
  background: var(--c-danger-soft);
  border-left-color: var(--c-danger);
}

.rt-current--approval {
  background: var(--c-warning-soft);
  border-left-color: var(--c-warning);
}

.rt-current--repair {
  background: var(--c-success-soft);
  border-left-color: var(--c-success);
}

.rt-current--final {
  background: var(--c-success-soft);
  border-left-color: var(--c-success);
}

.rt-current__head {
  display: flex;
  align-items: center;
  gap: 6px;
  font-size: 12px;
  font-weight: 600;
  color: var(--c-text-secondary);
  margin-bottom: 6px;
}

/* 呼吸点：暗示"正在进行中" */
.rt-current__pulse {
  width: 7px;
  height: 7px;
  border-radius: 50%;
  background: var(--c-primary);
  animation: rt-pulse 1.4s ease-in-out infinite;
}

.rt-current--error .rt-current__pulse { background: var(--c-danger); }
.rt-current--approval .rt-current__pulse { background: var(--c-warning); }
.rt-current--repair .rt-current__pulse { background: var(--c-success); }
.rt-current--final .rt-current__pulse { background: var(--c-success); animation: none; }

@keyframes rt-pulse {
  0%, 100% { opacity: 1; transform: scale(1); }
  50% { opacity: 0.4; transform: scale(0.8); }
}

.rt-current__text {
  font-size: 13px;
  color: var(--c-text);
  line-height: 1.55;
  word-break: break-word;
}

/* ---------- 消息流 ---------- */
.rt-stream {
  height: calc(100vh - 64px - var(--space-page) * 2 - 220px);
  min-height: 300px;
  overflow-y: auto;
  padding: 16px 20px;
  background: var(--c-surface);
}

.rt-empty {
  height: 100%;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  color: var(--c-text-muted);
  gap: 8px;
}

.rt-empty p {
  margin: 0;
  font-size: 13px;
}

.rt-empty__hint {
  font-size: 12px !important;
  color: var(--c-text-muted);
  opacity: 0.8;
}

.msg {
  display: flex;
  gap: 12px;
}

.msg__gutter {
  display: flex;
  flex-direction: column;
  align-items: center;
  width: 10px;
  flex-shrink: 0;
  padding-top: 6px;
}

.msg__dot {
  width: 9px;
  height: 9px;
  border-radius: 50%;
  flex-shrink: 0;
  background: var(--c-success);
  box-shadow: 0 0 0 3px var(--c-success-soft);
}

.msg__line {
  flex: 1;
  width: 1.5px;
  background: var(--c-border);
  margin-top: 2px;
}

.msg--tool .msg__dot { background: var(--c-primary); box-shadow: 0 0 0 3px var(--c-primary-soft); }
.msg--error .msg__dot { background: var(--c-danger); box-shadow: 0 0 0 3px var(--c-danger-soft); }
.msg--approval .msg__dot { background: var(--c-warning); box-shadow: 0 0 0 3px var(--c-warning-soft); }
/* 修复动作（重启/扩缩容/回滚）与只读查询区分开：它会真的改线上状态 */
.msg--repair .msg__dot { background: var(--c-success); box-shadow: 0 0 0 3px var(--c-success-soft); }

.msg__body {
  flex: 1;
  min-width: 0;
  padding-bottom: 14px;
}

.msg__head {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-bottom: 5px;
}

.msg__time {
  font-size: 11.5px;
  color: var(--c-text-muted);
  font-family: 'JetBrains Mono', Consolas, monospace;
}

/* 与上一步的间隔：哪一步耗得久一眼可见 */
.msg__elapsed {
  font-size: 11px;
  color: var(--c-warning);
  font-family: 'JetBrains Mono', Consolas, monospace;
  margin-left: auto;
}

.msg__content {
  font-size: 13px;
  color: var(--c-text-secondary);
  line-height: 1.65;
  white-space: pre-wrap;
  word-break: break-word;
  background: var(--c-surface-hover);
  padding: 8px 12px;
  border-radius: var(--radius-sm);
}

.text-muted {
  color: var(--c-text-muted);
}
</style>
