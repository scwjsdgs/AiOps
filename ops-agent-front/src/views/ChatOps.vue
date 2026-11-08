<template>
  <div class="chatops page">
    <div class="chatops-shell glass-card">
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
            <!-- 空状态 -->
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
import { ref, computed, nextTick, onMounted } from 'vue'

defineOptions({ name: 'ChatOps' })
import { useUserStore } from '@/store/user'
import { marked } from 'marked'
import {
  ChatDotRound, Delete, Promotion, Loading, CircleCheck,
  MagicStick, WarningFilled, Plus
} from '@element-plus/icons-vue'
import {
  listSessions, createSession, getSessionMessages, deleteSession, getChatTask, cancelChatTask
} from '@/api/chatops'
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

const formatTime = (t) => {
  if (!t) return ''
  return String(t).replace('T', ' ').slice(0, 19)
}

/* 对 marked 渲染结果做轻量级 XSS 防护：去掉 script 标签、事件属性与 javascript: 链接 */
const stripDangerous = (html) => {
  return html
    .replace(/<script\b[^<]*(?:(?!<\/script>)<[^<]*)*<\/script>/gi, '')
    .replace(/\bon\w+\s*=\s*("[^"]*"|'[^']*'|[^>\s]+)/gi, '')
    .replace(/href\s*=\s*"javascript:[^"]*"/gi, 'href="#"')
    .replace(/href\s*=\s*'javascript:[^']*'/gi, "href='#'")
}

const rendered = (text) => {
  try {
    return stripDangerous(marked.parse(text || ''))
  } catch {
    return stripDangerous(String(text || ''))
  }
}

const askSample = (q) => {
  input.value = q
  send()
}

const createNewSession = async () => {
  try {
    const res = await createSession()
    const id = res.data?.session_id || res.data?.id
    if (id) {
      currentSessionId.value = id
      sessions.value.unshift({ session_id: id, title: '新对话', updated_at: new Date().toISOString() })
      messages.value = []
    }
  } catch {
    ElMessage.error('创建对话失败')
  }
}

const loadSessions = async () => {
  loadingSessions.value = true
  try {
    const res = await listSessions()
    sessions.value = res.data || []
  } catch {
    sessions.value = []
  } finally {
    loadingSessions.value = false
  }
}

const switchSession = async (id) => {
  currentSessionId.value = id
  try {
    const res = await getSessionMessages(id)
    messages.value = res.data || []
  } catch {
    messages.value = []
  }
}

const removeSession = async (id) => {
  try {
    await deleteSession(id)
    sessions.value = sessions.value.filter(s => s.session_id !== id)
    if (currentSessionId.value === id) {
      currentSessionId.value = ''
      messages.value = []
    }
  } catch {
    ElMessage.error('删除失败')
  }
}

const send = async () => {
  if (!input.value.trim() || busy.value) return
  const text = input.value
  input.value = ''
  busy.value = true

  messages.value.push({
    role: 'user',
    text,
    timestamp: new Date().toISOString()
  })

  const agentMsg = {
    role: 'agent',
    toolsStarted: [],
    steps: [],
    report: '',
    reportDone: false,
    error: ''
  }
  messages.value.push(agentMsg)

  scrollToBottom()

  try {
    const res = await getChatTask(text)
    // 简化：把返回结果填入 agent 消息
    agentMsg.report = res.data?.report || res.data?.output || JSON.stringify(res.data)
    agentMsg.reportDone = true
  } catch (e) {
    agentMsg.error = e.message || '推理失败'
  } finally {
    busy.value = false
  }
}

const scrollToBottom = () => {
  nextTick(() => {
    if (chatContainer.value) {
      chatContainer.value.scrollTop = chatContainer.value.scrollHeight
    }
  })
}

const clearChat = () => {
  messages.value = []
}

onMounted(() => {
  loadSessions()
})
</script>

<style scoped>
.chatops-shell {
  display: flex;
  height: calc(100vh - 64px - var(--space-page) * 2);
  gap: var(--space-lg);
  overflow: hidden;
}

/* 会话侧边栏 */
.session-sidebar {
  width: 320px;
  flex-shrink: 0;
  display: flex;
  flex-direction: column;
}

.session-sidebar__head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 16px 20px;
  border-bottom: 1px solid var(--c-border);
  background: var(--c-surface);
}

.session-sidebar__title {
  font-size: 14px;
  font-weight: 600;
  color: var(--c-text);
}

.session-sidebar__new {
  padding: 6px 12px;
  font-size: 12.5px;
}

.session-list {
  flex: 1;
  overflow-y: auto;
  background: var(--c-bg);
}

.session-item {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 12px 16px;
  cursor: pointer;
  transition: background 0.15s;
  border-bottom: 1px solid var(--c-border);
}

.session-item:hover {
  background: var(--c-surface-hover);
}

.session-item--active {
  background: var(--c-primary-soft);
  border-left: 3px solid var(--c-primary);
}

.session-item__main {
  flex: 1;
  min-width: 0;
}

.session-item__title {
  font-size: 13.5px;
  color: var(--c-text);
  font-weight: 500;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.session-item__meta {
  font-size: 11.5px;
  color: var(--c-text-muted);
  margin-top: 2px;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.session-item__delete {
  font-size: 14px;
  color: var(--c-text-muted);
}

.session-item__delete:hover {
  color: var(--c-danger);
}

.session-empty {
  padding: 40px 20px;
  text-align: center;
  color: var(--c-text-muted);
  font-size: 13px;
}

.session-loading {
  padding: 20px;
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 12.5px;
  color: var(--c-text-muted);
}

/* 对话主体 */
.chat-main {
  flex: 1;
  display: flex;
  flex-direction: column;
  min-width: 0;
  background: var(--c-bg);
}

.chat-card {
  height: 100%;
  display: flex;
  flex-direction: column;
  background: var(--c-bg);
  border: none !important;
  box-shadow: none !important;
  border-radius: 0 !important;
}

.chat-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 20px 24px;
  border-bottom: 1px solid var(--c-border);
  background: var(--c-surface);
  flex-shrink: 0;
}

.chat-head h3 {
  margin: 0;
  font-size: 16px;
  font-weight: 600;
  color: var(--c-text);
  display: flex;
  align-items: center;
  gap: 8px;
}

.chat-head .page-subtitle {
  margin: 4px 0 0;
  font-size: 13px;
  color: var(--c-text-muted);
}

.chat-body {
  flex: 1;
  overflow-y: auto;
  padding: 24px;
  display: flex;
  flex-direction: column;
  gap: 20px;
  min-height: 0;
}

/* 空状态 */
.chat-empty {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  text-align: center;
  padding: 48px 20px;
  color: var(--c-text-muted);
}

.chat-empty__icon {
  font-size: 48px;
  color: var(--c-border-soft);
  margin-bottom: 16px;
}

.chat-empty__title {
  margin: 0 0 8px;
  font-size: 18px;
  font-weight: 600;
  color: var(--c-text);
}

.chat-empty__desc {
  margin: 0 0 20px;
  font-size: 14px;
  line-height: 1.6;
}

.chat-empty__chips {
  display: flex;
  gap: 10px;
  flex-wrap: wrap;
  justify-content: center;
}

/* 消息气泡 */
.msg {
  display: flex;
  gap: 12px;
  animation: msg-fade 0.25s var(--ease-out);
}

@keyframes msg-fade {
  from { opacity: 0; transform: translateY(8px); }
  to { opacity: 1; transform: translateY(0); }
}

.msg--user {
  flex-direction: row-reverse;
}

.msg__avatar {
  width: 34px;
  height: 34px;
  border-radius: 50%;
  display: flex;
  align-items: center;
  justify-content: center;
  flex-shrink: 0;
  font-size: 14px;
}

.msg--agent .msg__avatar {
  background: var(--c-primary-soft);
  color: var(--c-primary);
}

.msg--user .msg__avatar {
  background: var(--c-surface-hover);
  color: var(--c-text-secondary);
}

.msg__content {
  flex: 1;
  max-width: 700px;
}

.bubble {
  padding: 12px 16px;
  border-radius: var(--radius-lg);
  max-width: 100%;
}

.msg--user .bubble {
  background: var(--c-primary);
  color: #fff;
}

.msg--user .user-text {
  font-size: 14px;
  line-height: 1.6;
  white-space: pre-wrap;
}

.msg--agent .bubble {
  background: var(--c-surface);
  border: 1px solid var(--c-border);
  color: var(--c-text);
}

.tool-cards {
  display: flex;
  gap: 10px;
  margin-bottom: 12px;
  flex-wrap: wrap;
}

.tool-card {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 8px 12px;
  border-radius: var(--radius-md);
  border: 1px solid var(--c-border);
  background: var(--c-surface);
  font-size: 12.5px;
  color: var(--c-text-secondary);
  transition: all 0.2s;
}

.tool-card--done {
  border-color: var(--c-success);
  background: var(--c-success-soft);
  color: var(--c-success);
}

.tool-card__icon {
  width: 20px;
  height: 20px;
  display: flex;
  align-items: center;
  justify-content: center;
  flex-shrink: 0;
}

.tool-card__name {
  font-weight: 500;
  min-width: 0;
}

.tool-card__status {
  font-size: 11.5px;
  margin-left: 4px;
}

.steps {
  margin: 12px 0;
}

.step-line {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 6px 0;
  font-size: 13px;
  color: var(--c-text-secondary);
}

.step-line--done {
  color: var(--c-text);
}

.step-line__icon {
  width: 16px;
  height: 16px;
  flex-shrink: 0;
}

.thinking {
  display: flex;
  align-items: center;
  gap: 8px;
  margin: 12px 0;
  font-size: 13px;
  color: var(--c-text-muted);
}

.thinking__dot {
  width: 7px;
  height: 7px;
  border-radius: 50%;
  background: var(--c-text-muted);
  animation: rt-pulse 1.4s ease-in-out infinite;
}

.thinking__dot:nth-child(2) { animation-delay: 0.2s; }
.thinking__dot:nth-child(3) { animation-delay: 0.4s; }

.thinking__text {
  font-size: 12.5px;
}

.report {
  margin-top: 12px;
  padding: 16px;
  background: var(--c-surface-hover);
  border: 1px solid var(--c-border);
  border-radius: var(--radius-md);
  font-size: 13px;
  line-height: 1.75;
  color: var(--c-text-secondary);
  overflow-x: auto;
  white-space: pre-wrap;
}

.report--streaming {
  animation: rt-pulse 2s ease-in-out infinite;
}

.error-text {
  margin-top: 12px;
  padding: 12px 16px;
  background: var(--c-danger-soft);
  border: 1px solid var(--c-danger-soft);
  border-radius: var(--radius-md);
  font-size: 13px;
  color: var(--c-danger);
  display: flex;
  align-items: center;
  gap: 8px;
}

/* 输入区 */
.chat-input {
  display: flex;
  align-items: flex-end;
  gap: 10px;
  padding: 16px 24px;
  background: var(--c-surface);
  border-top: 1px solid var(--c-border);
  flex-shrink: 0;
}

.chat-input /deep/ .el-input__inner {
  font-size: 13px;
  resize: none;
}

.chat-input .el-button {
  font-size: 13px;
  height: auto;
  padding: 8px 20px;
}

@keyframes rt-pulse {
  0%, 100% { opacity: 1; transform: scale(1); }
  50% { opacity: 0.4; transform: scale(0.8); }
}

/* 窄屏 */
@media (max-width: 900px) {
  .chatops-shell {
    flex-direction: column;
    height: auto;
  }

  .session-sidebar {
    width: 100%;
    height: 300px;
  }

  .msg__content {
    max-width: 100%;
  }
}
</style>