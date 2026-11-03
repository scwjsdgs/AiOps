<template>
  <div class="page chatops-page">
    <div class="chatops-shell">
      <!-- 会话侧边栏 -->
      <aside class="session-sidebar">
        <div class="session-sidebar__head">
          <div class="session-sidebar__title">对话</div>
          <el-button
            class="session-sidebar__new"
            type="primary"
            :icon="Plus"
            :disabled="busy"
            @click="createNewSession"
          >新建对话</el-button>
        </div>
        <div class="session-list">
          <div
            v-for="s in sessions"
            :key="s.session_id"
            class="session-item"
            :class="{ 'session-item--active': s.session_id === currentSessionId }"
            @click="switchSession(s.session_id)"
          >
            <div class="session-item__main">
              <div class="session-item__title">{{ s.title || '未命名对话' }}</div>
              <div class="session-item__meta">{{ formatTime(s.updated_at) }}</div>
            </div>
            <el-button
              class="session-item__delete"
              text
              :icon="Delete"
              :disabled="busy"
              @click.stop="removeSession(s.session_id)"
            />
          </div>
          <div v-if="!sessions.length && !loadingSessions" class="session-empty">
            还没有历史对话
          </div>
          <div v-if="loadingSessions" class="session-loading">
            <el-icon class="is-loading"><Loading /></el-icon> 加载中
          </div>
        </div>
      </aside>

      <!-- 对话主体 -->
      <div class="chat-main">
        <el-card body-style="padding: 0;" class="chat-card">
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
            <el-button :icon="Delete" :disabled="!messages.length || busy" @click="clearChat">清空并新建</el-button>
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

                  <!-- Agent 消息：工具状态 + 推理步骤 + 最终报告 -->
                  <template v-else>
                    <div v-if="m.toolsStarted.length" class="tool-cards">
                      <div
                        v-for="tool in m.toolsStarted"
                        :key="tool.id"
                        class="tool-card"
                        :class="{ 'tool-card--done': tool.done }"
                      >
                        <el-icon class="tool-card__icon">
                          <Loading v-if="!tool.done" class="is-loading" />
                          <CircleCheck v-else />
                        </el-icon>
                        <span class="tool-card__name">{{ tool.name }}</span>
                        <span class="tool-card__status">{{ tool.done ? '已完成' : '正在调用' }}</span>
                      </div>
                    </div>

                    <div v-if="m.steps.length" class="steps">
                      <div v-for="(s, i) in m.steps" :key="i" class="step-line" :class="{ 'step-line--done': s.done }">
                        <el-icon class="step-line__icon">
                          <Loading v-if="!s.done" class="is-loading" />
                          <CircleCheck v-else />
                        </el-icon>
                        <span class="step-line__text">{{ s.text }}</span>
                      </div>
                    </div>

                    <div v-if="!m.report && !m.error && m.toolsStarted.length" class="thinking">
                      <span class="thinking__dot"></span>
                      <span class="thinking__dot"></span>
                      <span class="thinking__dot"></span>
                      <span class="thinking__text">正在推理…</span>
                    </div>

                    <div v-if="m.report">
                      <div v-if="!m.reportDone" class="report report--streaming">{{ m.report }}</div>
                      <div v-else class="report" v-html="rendered(m.report)"></div>
                    </div>
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
    </div>
  </div>
</template>

<script setup>
import { ref, computed, nextTick, onMounted, onDeactivated } from 'vue'

defineOptions({ name: 'ChatOps' })
import { useUserStore } from '@/store/user'
import { marked } from 'marked'
import {
  ChatDotRound, Delete, Promotion, Loading, CircleCheck,
  MagicStick, WarningFilled, Plus
} from '@element-plus/icons-vue'
import { listSessions, createSession, getSessionMessages, deleteSession, getChatTask, cancelChatTask } from '@/api/chatops'
import { ElMessage } from 'element-plus'

const userStore = useUserStore()
const input = ref('')
const busy = ref(false)
const messages = ref([])
const chatContainer = ref(null)
const sessions = ref([])
const currentSessionId = ref('')
const loadingSessions = ref(false)
let activeController = null

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

const formatTime = (value) => {
  if (!value) return ''
  const d = new Date(value)
  if (Number.isNaN(d.getTime())) return String(value)
  const pad = (n) => String(n).padStart(2, '0')
  return `${pad(d.getMonth() + 1)}-${pad(d.getDate())} ${pad(d.getHours())}:${pad(d.getMinutes())}`
}

// SSE 事件体可能有两种结构：
// - 旧后端：{ type, data }，data 为字符串
// - 新后端错误：{ type, content }
// 这里统一提取可渲染文本，避免 [object Object]。
const eventText = (evt) => {
  if (evt == null) return ''
  const original = evt.content
    ?? evt.data
    ?? evt.message
    ?? ''
  // 后端 JSON 里的换行经过 json.dumps 会变成字面量 \n，SSE 到浏览器后
  // 仍是两个字符。这里统一还原成真实换行，避免报告/步骤显示成一行 \n。
  const normalize = (v) => {
    if (typeof v !== 'string') return ''
    return v.replace(/\\n/g, '\n').replace(/\\t/g, '\t')
  }
  if (typeof evt.content === 'string') return normalize(evt.content)
  if (typeof evt.data === 'object' && evt.data) {
    if (typeof evt.data.content === 'string') return normalize(evt.data.content)
    if (typeof evt.data.text === 'string') return normalize(evt.data.text)
    return JSON.stringify(evt.data)
  }
  if (typeof evt.data === 'string') return normalize(evt.data)
  if (typeof evt.message === 'string') return normalize(evt.message)
  return normalize(String(original))
}

const toUserMessages = (rows) => {
  return rows
    .filter((m) => m && typeof m.content === 'string' && m.content.trim())
    .map((m) => ({
      role: m.role === 'assistant' ? 'agent' : 'user',
      text: m.content,
      steps: [],
      toolsStarted: [],
      report: m.role === 'assistant' ? m.content : '',
      reportDone: m.role === 'assistant',
      error: ''
    }))
}

const loadSessions = async () => {
  loadingSessions.value = true
  try {
    const res = await listSessions()
    sessions.value = res.data || []
  } catch (e) {
    ElMessage.error('会话列表加载失败')
  } finally {
    loadingSessions.value = false
  }
}

const loadSessionMessages = async (sessionId) => {
  try {
    const res = await getSessionMessages(sessionId)
    const data = res.data || {}
    messages.value = toUserMessages(data.messages || [])
  } catch (e) {
    messages.value = []
    ElMessage.error('历史消息加载失败')
  }
}

const createNewSession = async () => {
  try {
    const res = await createSession()
    const data = res.data || {}
    currentSessionId.value = data.session_id || `chat-${Date.now()}`
    messages.value = []
    await loadSessions()
    return currentSessionId.value
  } catch (e) {
    // 后端新建失败也要先清空当前会话状态，避免旧会话残留
    currentSessionId.value = `chat-${Date.now()}`
    messages.value = []
    ElMessage.error('新建对话失败，已创建本地临时会话')
    return currentSessionId.value
  }
}

const switchSession = async (sessionId) => {
  if (busy.value || sessionId === currentSessionId.value) return
  currentSessionId.value = sessionId
  messages.value = []
  await loadSessionMessages(sessionId)
  scrollBottom()
}

const clearChat = async () => {
  if (busy.value) return
  const oldSessionId = currentSessionId.value
  if (oldSessionId) {
    try {
      await deleteSession(oldSessionId)
    } catch (e) {
      // 后端索引已删但校验/网络异常时也继续切空会话，避免用户被卡住
    }
  }
  currentSessionId.value = ''
  messages.value = []
  await createNewSession()
}

const removeSession = async (sessionId) => {
  if (busy.value) return
  const removingCurrent = sessionId === currentSessionId.value
  try {
    await deleteSession(sessionId)
  } catch (e) {
    ElMessage.error('删除对话失败')
    return
  }
  sessions.value = sessions.value.filter((s) => s.session_id !== sessionId)
  if (removingCurrent) {
    // 先切断当前会话引用，再新建，避免旧 sessionId 残留导致下一条消息写入已删除会话
    currentSessionId.value = ''
    messages.value = []
    await createNewSession()
  }
}

// 把 SSE 事件帧解析成 {type, data/content}
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

  // 没有会话时先创建，保证每次追问都落到同一个 LangGraph thread_id
  if (!currentSessionId.value) {
    try {
      const res = await createSession()
      currentSessionId.value = (res.data || {}).session_id || `chat-${Date.now()}`
      await loadSessions()
    } catch (e) {
      currentSessionId.value = `chat-${Date.now()}`
    }
  }

  input.value = ''
  busy.value = true

  messages.value.push({ role: 'user', text })
  // agent 消息占位（toolsStarted/steps 逐步填充）
  const agentMsg = { role: 'agent', steps: [], toolsStarted: [], report: '', reportDone: false, error: '' }
  messages.value.push(agentMsg)
  scrollBottom()

  const sessionId = currentSessionId.value
  const taskId = `chatops-${Date.now()}`
  let streamEnded = false
  activeController?.abort()
  const controller = new AbortController()
  activeController = controller

  // 打字机队列：SSE 可能一瞬间到达多个 token，直接每帧全量设置报告
  // 在浏览器里仍是“全部输出”。这里先把 token 放进队列，由定时器按小批
  // 追加到可见报告，形成明确的逐字动画。
  const streamQueue = []
  let flushTimer = null
  const flushStream = () => {
    if (streamQueue.length) {
      const batch = streamQueue.splice(0, 4).join('')
      agentMsg.report = (agentMsg.report || '') + batch
      scrollBottom()
    }
  }

  // final 兜底：即使 Python 只发一个完整 final（没有逐字 text 帧），
  // 也切成小段播放，避免页面瞬间全量输出。
  const playRemaining = (text) => {
    if (flushTimer) {
      clearInterval(flushTimer)
      flushTimer = null
    }
    const remaining = streamQueue.splice(0).join('') + (text || '')
    if (!remaining) {
      agentMsg.reportDone = true
      return
    }
    let idx = 0
    agentMsg.report = agentMsg.report || ''
    flushTimer = setInterval(() => {
      if (idx < remaining.length) {
        const step = remaining.slice(idx, idx + 3)
        agentMsg.report += step
        idx += step.length
        scrollBottom()
      } else {
        clearInterval(flushTimer)
        flushTimer = null
        agentMsg.reportDone = true
      }
    }, 20)
  }

  // 流式边界：90 秒没有任何事件就主动断开。模型一条长报告可能几十秒，
  // 但超过 90 秒静默基本是 Java/Python 卡住，再等只会让后端继续跑完整图刷日志。
  let lastEventAt = Date.now()
  const idleTimer = setInterval(() => {
    if (Date.now() - lastEventAt > 90_000) {
      controller.abort()
    }
  }, 10_000)

  // 断开/超时后的状态轮询兜底：最多轮询 3 次，每次间隔 2 秒。
  // 配合后端写 Redis，刷新或断线后仍能拿到任务最新快照。
  const pollTask = async () => {
    try {
      const res = await getChatTask(taskId)
      const t = res.data || {}
      if (t.status === 'SUCCESS' && t.output) {
        // 快照兜底只在流式断开后使用；先把残余打字机队列吞掉，避免全量覆盖后再被追加。
        streamQueue.length = 0
        if (flushTimer) {
          clearInterval(flushTimer)
          flushTimer = null
        }
        agentMsg.report = t.output
        agentMsg.reportDone = true
        agentMsg.error = ''
        streamEnded = true
        return
      }
      if (t.status === 'FAILED') {
        agentMsg.error = t.output || '任务处理超时或失败，请稍后查看结果'
        streamEnded = true
        return
      }
      if (t.agentSteps?.length) {
        t.agentSteps.forEach((s) => {
          if (!agentMsg.steps.some((old) => old.text === s)) {
            agentMsg.steps.push({ done: true, text: String(s).slice(0, 160) })
          }
        })
      }
    } catch {
      // 快照查询失败不用再次报错，最终超时文案已经兜底
    }
  }

  let pollCount = 0
  const pollTimer = setInterval(async () => {
    if (pollCount >= 3 || streamEnded) {
      clearInterval(pollTimer)
      return
    }
    pollCount += 1
    await pollTask()
  }, 2000)

  try {
    // SSE 用 fetch + ReadableStream（EventSource 只支持 GET）
    const resp = await fetch('/api/chatops/stream', {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        'Authorization': `Bearer ${userStore.token}`
      },
      body: JSON.stringify({ taskId, message: text, sessionId }),
      signal: controller.signal
    })
    if (!resp.ok || !resp.body) {
      agentMsg.error = `请求失败: HTTP ${resp.status}`
      return
    }

    const reader = resp.body.getReader()
    const decoder = new TextDecoder('utf-8')
    let buffer = ''

    while (true) {
      const { done, value } = await reader.read()
      if (done) {
        streamEnded = true
        break
      }
      lastEventAt = Date.now()
      buffer += decoder.decode(value, { stream: true })
      // SSE 帧以空行分隔
      const frames = buffer.split('\n\n')
      buffer = frames.pop() || ''
      for (const frame of frames) {
        for (const line of frame.split('\n')) {
          if (!line.startsWith('data:')) continue
          const evt = parseFrame(line.slice(5).trim())
          if (!evt) continue
          const textValue = eventText(evt)
          if (evt.type === 'end') {
            clearInterval(flushTimer)
            flushTimer = null
            flushStream()
            streamEnded = true
            agentMsg.reportDone = true
            agentMsg.steps.push({ done: true, text: '处理完成' })
          } else if (evt.type === 'error') {
            clearInterval(flushTimer)
            flushTimer = null
            flushStream()
            agentMsg.error = textValue || '大模型服务暂不可用'
            streamEnded = true
            agentMsg.reportDone = true
          } else if (evt.type === 'text') {
            // 打字机：先入队，由 flushStream 按小批追加。
            // 避免 token 到达过快时一次全量进入 DOM。
            streamQueue.push(textValue)
            agentMsg.reportDone = false
            if (!flushTimer) {
              flushTimer = setInterval(flushStream, 35)
            }
          } else if (evt.type === 'tool_start') {
            const toolName = evt.tool || evt.name || evt.data?.tool || 'tool'
            if (!agentMsg.toolsStarted.some((t) => t.name === toolName && !t.done)) {
              agentMsg.toolsStarted.push({ id: `${toolName}-${agentMsg.toolsStarted.length}`, name: toolName, done: false })
            }
          } else if (evt.type === 'tool_end') {
            const toolName = evt.tool || evt.name || evt.data?.tool || 'tool'
            const card = agentMsg.toolsStarted.find((t) => t.name === toolName && !t.done)
            if (card) {
              card.done = true
              card.result = textValue
            }
          } else if (evt.type === 'report_token') {
            // 兼容旧协议：老 Python 进程仍可能发 report_token
            agentMsg.report = (agentMsg.report || '') + textValue
            agentMsg.reportDone = false
          } else if (evt.type === 'progress') {
            if (!agentMsg.steps.some((s) => s.text === textValue)) {
              agentMsg.steps.push({ done: true, text: String(textValue).slice(0, 160) })
            }
          } else if (evt.type === 'final') {
            // final 也走打字机：不整体覆盖，避免“一次全输出”。
            const full = textValue || ''
            if (full && full !== agentMsg.report) {
              streamQueue.push(full)
            }
            playRemaining('')
          } else if (evt.type === 'tool_call') {
            const toolName = evt.name || evt.tool || evt.data?.name || 'tool'
            const args = evt.args || evt.data?.args || {}
            const argsText = typeof args === 'string' ? args : JSON.stringify(args || {})
            agentMsg.steps.push({ done: false, text: `调用 ${toolName}：${String(argsText).slice(0, 200)}` })
          } else if (evt.type === 'tool_result') {
            const toolName = evt.name || evt.tool || evt.data?.name || 'tool'
            const resultText = evt.content || evt.result || evt.data?.content || ''
            agentMsg.steps.push({ done: true, text: `${toolName} 结果：${String(resultText).slice(0, 200)}` })
          } else if (evt.type === 'action' || evt.type === 'observation' || evt.type === 'thought') {
            agentMsg.steps.push({ done: evt.type === 'observation', text: String(textValue).slice(0, 200) })
          } else if (textValue) {
            agentMsg.steps.push({ done: false, text: String(textValue).slice(0, 160) })
          }
          scrollBottom()
        }
      }
    }
    if (streamEnded) {
      await refreshSessionMeta(sessionId)
    }
  } catch (e) {
    if (e?.name !== 'AbortError') {
      agentMsg.error = `连接失败: ${e.message}`
    } else {
      // 主动断开是超时机制触发的：先查快照，拿不到再给明确“超时”文案
      agentMsg.error = '任务处理超时，请稍后查看结果'
      await pollTask()
      if (!streamEnded) {
        streamEnded = true
        agentMsg.error = '任务处理超时，请稍后查看结果'
      }
    }
  } finally {
    clearInterval(idleTimer)
    clearInterval(pollTimer)
    if (flushTimer) {
      clearInterval(flushTimer)
      flushTimer = null
    }
    flushStream()
    if (activeController === controller) {
      activeController = null
    }
    // 主动断开/超时：通知 Python 级联取消，避免没有前端订阅时继续跑推理
    if (controller.signal.aborted && !streamEnded) {
      cancelChatTask(taskId).catch(() => {})
    }
    busy.value = false
    scrollBottom()
  }
}

const refreshSessionMeta = async (sessionId) => {
  try {
    const res = await listSessions()
    sessions.value = res.data || []
  } catch (e) {
    // 列表刷新失败不阻塞对话
  }
}

onMounted(async () => {
  await loadSessions()
  if (sessions.value.length) {
    currentSessionId.value = sessions.value[0].session_id
    await loadSessionMessages(currentSessionId.value)
    scrollBottom()
  } else {
    // 首次进入自动创建一个会话，前端展示更像 DeepSeek 网页版
    try {
      const res = await createSession()
      currentSessionId.value = (res.data || {}).session_id
      await loadSessions()
    } catch (e) {
      currentSessionId.value = `chat-${Date.now()}`
    }
  }
})

// KeepAlive 缓存页面时，切走后立即断开未结束的 SSE，避免 Java/Python 继续跑完整图。
onDeactivated(() => {
  if (activeController && !activeController.signal.aborted) {
    activeController.abort()
  }
})
</script>

<style scoped>
.chatops-page {
  height: 100%;
  min-height: 0;
}

.chatops-shell {
  display: flex;
  gap: 14px;
  height: calc(100vh - 64px - var(--space-page) * 2);
  min-height: 520px;
}

/* ---------- 会话侧边栏 ---------- */
.session-sidebar {
  width: 240px;
  flex-shrink: 0;
  display: flex;
  flex-direction: column;
  border: 1px solid var(--c-border);
  border-radius: var(--radius-lg);
  background: var(--c-surface);
  overflow: hidden;
}

.session-sidebar__head {
  padding: 12px 14px;
  border-bottom: 1px solid var(--c-border);
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
}

.session-sidebar__title {
  font-size: 15px;
  font-weight: 600;
  color: var(--c-text);
}

.session-sidebar__new {
  min-width: 78px;
}

.session-list {
  flex: 1;
  overflow-y: auto;
  padding: 8px;
}

.session-item {
  padding: 10px 12px;
  border-radius: var(--radius-md);
  cursor: pointer;
  border: 1px solid transparent;
  transition: background 0.15s ease, border-color 0.15s ease;
  display: flex;
  align-items: center;
  gap: 6px;
}

.session-item__main {
  flex: 1;
  min-width: 0;
}

.session-item__delete {
  flex-shrink: 0;
  color: var(--c-text-muted);
}

.session-item__delete:hover {
  color: var(--c-danger);
}

.session-item:hover {
  background: var(--c-primary-soft);
}

.session-item--active {
  background: var(--c-primary-soft);
  border-color: var(--c-primary);
}

.session-item__title {
  font-size: 13px;
  color: var(--c-text);
  line-height: 1.4;
  word-break: break-all;
  display: -webkit-box;
  -webkit-line-clamp: 2;
  -webkit-box-orient: vertical;
  overflow: hidden;
}

.session-item__meta {
  margin-top: 4px;
  font-size: 12px;
  color: var(--c-text-muted);
}

.session-empty,
.session-loading {
  padding: 24px 12px;
  text-align: center;
  color: var(--c-text-muted);
  font-size: 13px;
}

.session-loading {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 6px;
}

/* ---------- 对话主体 ---------- */
.chat-main {
  flex: 1;
  min-width: 0;
  display: flex;
}

.chat-card {
  flex: 1;
  display: flex;
  flex-direction: column;
  min-width: 0;
}

.chat-head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 16px;
  padding: 18px 20px;
  border-bottom: 1px solid var(--c-border);
}

.chat-body {
  height: calc(100% - 118px);
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

/* ---------- 工具状态卡片 ---------- */
.tool-cards {
  display: flex;
  flex-direction: column;
  gap: 8px;
  margin-bottom: 10px;
}

.tool-card {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 10px 12px;
  border: 1px solid var(--c-border);
  border-radius: var(--radius-md);
  background: var(--c-surface-hover);
  min-height: 40px;
}

.tool-card__icon {
  flex-shrink: 0;
  color: var(--c-primary);
}

.tool-card--done .tool-card__icon {
  color: var(--c-success);
}

.tool-card__name {
  flex: 1;
  min-width: 0;
  font-size: 13px;
  font-weight: 600;
  color: var(--c-text);
  font-family: var(--font-mono, monospace);
  word-break: break-all;
}

.tool-card__status {
  flex-shrink: 0;
  font-size: 12px;
  color: var(--c-text-muted);
}

.tool-card--done .tool-card__status {
  color: var(--c-success);
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

/* 流式阶段：不过度解析不完整的 Markdown，直接用纯文本呈现逐字打字机效果 */
.report--streaming {
  white-space: pre-wrap;
  word-break: break-word;
  font-family: inherit;
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

@media (max-width: 768px) {
  .chatops-shell {
    flex-direction: column;
  }

  .session-sidebar {
    width: 100%;
    height: 150px;
  }

  .session-list {
    display: flex;
    gap: 8px;
    overflow-x: auto;
  }

  .session-item {
    min-width: 180px;
    flex-shrink: 0;
  }

  .chat-main {
    min-height: 0;
  }
}
</style>
