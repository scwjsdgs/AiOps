<template>
  <div class="page">
    <el-card body-style="padding: 0;">
      <div class="chat-head">
        <div>
          <h3 class="page-title">
            <el-icon><ChatDotRound /></el-icon>ChatOps 交互式诊断
            <el-tag v-if="busy" type="warning" size="small" effect="light">
              <el-icon class="is-loading"><Loading /></el-icon> 推理中
            </el-tag>
          </h3>
          <p class="page-subtitle" style="margin-top: 4px;">
            直接问一句，agent 会现场查状态 / 日志 / 事件 / 指标，推理过程流式返回。
          </p>
        </div>
        <el-button :icon="Delete" :disabled="!messages.length || busy" @click="clearChat">清空对话</el-button>
      </div>

      <div class="chat-body" ref="chatContainer">
        <!-- 空状态：给几个能直接点的示例问题，比一句干巴巴的提示有用 -->
        <div v-if="!messages.length" class="chat-empty">
          <div class="chat-empty__icon">
            <el-icon :size="34"><ChatDotRound /></el-icon>
          </div>
          <h4 class="chat-empty__title">问点什么？</h4>
          <p class="chat-empty__desc">
            agent 会自主调用工具排查，并给出带证据链的诊断报告。
          </p>
          <div class="chat-empty__chips">
            <el-button
              v-for="q in SAMPLE_QUESTIONS"
              :key="q"
              size="small"
              round
              @click="askSample(q)"
            >{{ q }}</el-button>
          </div>
        </div>

        <div v-for="(m, idx) in messages" :key="idx" class="msg" :class="`msg--${m.role}`">
          <div class="msg__avatar">
            <el-icon v-if="m.role === 'agent'"><MagicStick /></el-icon>
            <span v-else>{{ userInitial }}</span>
          </div>

          <div class="msg__content">
            <div class="bubble">
              <!-- 用户消息 -->
              <template v-if="m.role === 'user'">
                <div class="user-text">{{ m.text }}</div>
              </template>

              <!-- Agent 消息：推理步骤 + 最终报告 -->
              <template v-else>
                <div v-if="m.steps.length" class="steps">
                  <div v-for="(s, i) in m.steps" :key="i" class="step-line" :class="{ 'step-line--done': s.done }">
                    <el-icon class="step-line__icon">
                      <Loading v-if="!s.done" class="is-loading" />
                      <CircleCheck v-else />
                    </el-icon>
                    <span class="step-line__text">{{ s.text }}</span>
                  </div>
                </div>

                <div v-if="!m.report && !m.error && m.steps.length" class="thinking">
                  <span class="thinking__dot"></span>
                  <span class="thinking__dot"></span>
                  <span class="thinking__dot"></span>
                  <span class="thinking__text">正在推理…</span>
                </div>

                <div v-if="m.report" class="report" v-html="rendered(m.report)"></div>
                <div v-if="m.error" class="error-text">
                  <el-icon><WarningFilled /></el-icon>{{ m.error }}
                </div>
              </template>
            </div>
          </div>
        </div>
      </div>

      <div class="chat-input">
        <el-input
          v-model="input"
          type="textarea"
          :rows="1"
          :autosize="{ minRows: 1, maxRows: 4 }"
          placeholder="输入问题，如：nginx 为什么重启？（Enter 发送，Shift+Enter 换行）"
          :disabled="busy"
          resize="none"
          @keydown.enter.exact.prevent="send"
        />
        <el-button
          type="primary"
          :icon="Promotion"
          :disabled="busy || !input.trim()"
          @click="send"
        >
          {{ busy ? '推理中' : '发送' }}
        </el-button>
      </div>
    </el-card>
  </div>
</template>

<script setup>
import { ref, computed, nextTick } from 'vue'
import { useUserStore } from '@/store/user'
import { marked } from 'marked'
import {
  ChatDotRound, Delete, Promotion, Loading, CircleCheck,
  MagicStick, WarningFilled
} from '@element-plus/icons-vue'

const userStore = useUserStore()
const input = ref('')
const busy = ref(false)
const messages = ref([])
const chatContainer = ref(null)

const userInitial = computed(() => (userStore.username || 'U').charAt(0).toUpperCase())

// 示例问题：覆盖「查故障原因」「看影响面」「全面巡检」三类典型用法
const SAMPLE_QUESTIONS = [
  'nginx 为什么重启？',
  '集群里现在有哪些服务不健康？',
  '最近一次告警的根因是什么？'
]

const askSample = (q) => {
  input.value = q
  send()
}

// marked 渲染 markdown；ChatOps 报告来自自家 agent，风险可控
const rendered = (md) => {
  try {
    return marked.parse(md || '', { breaks: true })
  } catch {
    return md || ''
  }
}

const scrollBottom = () => {
  nextTick(() => {
    if (chatContainer.value) {
      chatContainer.value.scrollTop = chatContainer.value.scrollHeight
    }
  })
}

// 把 SSE 事件帧解析成 {type, data}
const parseFrame = (raw) => {
  if (!raw) return null
  try {
    return JSON.parse(raw)
  } catch {
    return null // 偶发半帧（流被截断）忽略
  }
}

const send = async () => {
  const text = input.value.trim()
  if (!text || busy.value) return
  input.value = ''
  busy.value = true

  messages.value.push({ role: 'user', text })
  // agent 消息占位（steps 逐步填充）
  const agentMsg = { role: 'agent', steps: [], report: '', error: '' }
  messages.value.push(agentMsg)
  scrollBottom()

  const taskId = `chatops-${Date.now()}`
  try {
    // SSE 用 fetch + ReadableStream（EventSource 只支持 GET）
    const resp = await fetch('/api/chatops/stream', {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        'Authorization': `Bearer ${userStore.token}`
      },
      body: JSON.stringify({ taskId, message: text })
    })
    if (!resp.ok || !resp.body) {
      agentMsg.error = `请求失败: HTTP ${resp.status}`
      busy.value = false
      return
    }

    const reader = resp.body.getReader()
    const decoder = new TextDecoder('utf-8')
    let buffer = ''

    while (true) {
      const { done, value } = await reader.read()
      if (done) break
      buffer += decoder.decode(value, { stream: true })
      // SSE 帧以空行分隔
      const frames = buffer.split('\n\n')
      buffer = frames.pop() || ''
      for (const frame of frames) {
        for (const line of frame.split('\n')) {
          if (!line.startsWith('data:')) continue
          const evt = parseFrame(line.slice(5).trim())
          if (!evt) continue
          if (evt.type === 'end') {
            agentMsg.steps.push({ done: true, text: '处理完成' })
          } else if (evt.type === 'error') {
            agentMsg.error = evt.data
          } else if (evt.type === 'final') {
            agentMsg.report = evt.data
          } else if (evt.type === 'action' || evt.type === 'observation' || evt.type === 'thought') {
            agentMsg.steps.push({ done: evt.type === 'observation', text: String(evt.data).slice(0, 200) })
          } else {
            agentMsg.steps.push({ done: false, text: String(evt.data).slice(0, 160) })
          }
          scrollBottom()
        }
      }
    }
  } catch (e) {
    agentMsg.error = `连接失败: ${e.message}`
  } finally {
    busy.value = false
    scrollBottom()
  }
}

const clearChat = () => {
  messages.value = []
}
</script>

<style scoped>
.chat-head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 16px;
  padding: 18px 20px;
  border-bottom: 1px solid var(--c-border);
}

.chat-body {
  height: calc(100vh - 64px - var(--space-page) * 2 - 230px);
  min-height: 320px;
  overflow-y: auto;
  padding: 20px;
  background: var(--c-bg);
}

/* ---------- 空状态 ---------- */
.chat-empty {
  height: 100%;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  text-align: center;
  gap: 6px;
}

.chat-empty__icon {
  width: 62px;
  height: 62px;
  border-radius: 18px;
  background: var(--c-primary-soft);
  color: var(--c-primary);
  display: flex;
  align-items: center;
  justify-content: center;
  margin-bottom: 10px;
}

.chat-empty__title {
  margin: 0;
  font-size: 16px;
  color: var(--c-text);
  font-weight: 600;
}

.chat-empty__desc {
  margin: 0;
  font-size: 13px;
  color: var(--c-text-muted);
}

.chat-empty__chips {
  display: flex;
  gap: 10px;
  flex-wrap: wrap;
  justify-content: center;
  margin-top: 14px;
}

/* ---------- 消息 ---------- */
.msg {
  display: flex;
  gap: 12px;
  margin-bottom: 18px;
}

.msg--user {
  flex-direction: row-reverse;
}

.msg__avatar {
  width: 34px;
  height: 34px;
  border-radius: 10px;
  flex-shrink: 0;
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 15px;
  font-weight: 600;
  color: #fff;
}

.msg--agent .msg__avatar {
  background: linear-gradient(135deg, #8b5cf6, #6366f1);
}

.msg--user .msg__avatar {
  background: linear-gradient(135deg, var(--c-primary-light), var(--c-primary-dark));
}

.msg__content {
  max-width: 76%;
  min-width: 0;
}

.bubble {
  padding: 12px 16px;
  border-radius: var(--radius-lg);
  font-size: 14px;
  line-height: 1.7;
  word-break: break-word;
}

.msg--agent .bubble {
  background: var(--c-surface);
  border: 1px solid var(--c-border);
  border-top-left-radius: 4px;
  box-shadow: var(--shadow-sm);
}

.msg--user .bubble {
  background: linear-gradient(135deg, var(--c-primary), var(--c-primary-dark));
  color: #fff;
  border-top-right-radius: 4px;
}

.user-text {
  white-space: pre-wrap;
}

/* ---------- 推理步骤 ---------- */
.steps {
  display: flex;
  flex-direction: column;
  gap: 6px;
}

.step-line {
  display: flex;
  align-items: flex-start;
  gap: 7px;
  font-size: 12.5px;
  color: var(--c-text-muted);
  line-height: 1.55;
}

.step-line--done {
  color: var(--c-success);
}

.step-line__icon {
  flex-shrink: 0;
  margin-top: 2px;
}

.step-line__text {
  word-break: break-word;
}

/* 推理中动画 */
.thinking {
  display: flex;
  align-items: center;
  gap: 4px;
  margin-top: 10px;
  padding-top: 8px;
  border-top: 1px dashed var(--c-border);
}

.thinking__dot {
  width: 6px;
  height: 6px;
  border-radius: 50%;
  background: var(--c-primary);
  animation: bounce 1.2s infinite;
}

.thinking__dot:nth-child(2) { animation-delay: 0.15s; }
.thinking__dot:nth-child(3) { animation-delay: 0.3s; }

.thinking__text {
  font-size: 12px;
  color: var(--c-text-muted);
  margin-left: 6px;
}

@keyframes bounce {
  0%, 60%, 100% { transform: translateY(0); opacity: 0.35; }
  30% { transform: translateY(-4px); opacity: 1; }
}

.error-text {
  color: var(--c-danger);
  font-size: 13px;
  margin-top: 8px;
  display: flex;
  align-items: center;
  gap: 6px;
}

/* ---------- 报告 ---------- */
.report {
  margin-top: 4px;
}

.report :deep(h1),
.report :deep(h2),
.report :deep(h3) {
  font-size: 14.5px;
  margin: 12px 0 6px;
  color: var(--c-text);
}

.report :deep(p) {
  margin: 6px 0;
}

.report :deep(table) {
  border-collapse: collapse;
  margin: 8px 0;
  font-size: 12px;
  width: 100%;
}

.report :deep(th),
.report :deep(td) {
  border: 1px solid var(--c-border);
  padding: 5px 9px;
  text-align: left;
}

.report :deep(th) {
  background: var(--c-primary-soft);
}

.report :deep(code) {
  background: var(--c-info-soft);
  padding: 1px 5px;
  border-radius: 3px;
  font-size: 12px;
}

/* ---------- 输入区 ---------- */
.chat-input {
  display: flex;
  gap: 10px;
  align-items: flex-end;
  padding: 14px 20px;
  border-top: 1px solid var(--c-border);
  background: var(--c-surface);
}

.chat-input :deep(.el-textarea__inner) {
  border-radius: var(--radius-md);
}
</style>
