<template>
  <div class="task page">
    <!-- ============ 左：任务列表 ============ -->
    <el-card class="list-panel" body-style="padding: 0;">
      <div class="list-panel__head glass-card__body">
        <div class="list-panel__title">
          <el-icon size="16"><List /></el-icon>
          <span>任务列表</span>
          <el-tag size="small" type="info" effect="plain">{{ total }}</el-tag>
        </div>
        <el-button :icon="Refresh" circle size="small" @click="loadTasks(1)" :loading="loading" />
      </div>

      <div class="list-panel__filters">
        <el-input
          v-model="filters.keyword"
          placeholder="搜索任务ID / 服务名 / 报告内容"
          :prefix-icon="Search"
          clearable
          size="small"
          @input="onFilterChange"
          @keyup.enter="loadTasks(1)"
          @clear="loadTasks(1)"
        />
        <el-select v-model="filters.status" placeholder="全部状态" clearable size="small"
                   style="width: 130px; margin-top: 8px;" @change="loadTasks(1)">
          <el-option label="排队中" value="PENDING" />
          <el-option label="分析中" value="RUNNING" />
          <el-option label="验证中" value="VERIFYING" />
          <el-option label="已完成" value="SUCCESS" />
          <el-option label="已拒绝" value="REJECTED" />
          <el-option label="已取消" value="CANCELLED" />
          <el-option label="修复后复现" value="REGRESSED" />
          <el-option label="失败" value="FAILED" />
          <el-option label="超时" value="TIMEOUT" />
        </el-select>
      </div>

      <el-scrollbar class="list-panel__scroll">
        <div v-if="!loading && !tasks.length" class="list-empty">
          <el-empty :description="hasFilter ? '没有符合条件的任务' : '暂无任务'" :image-size="80" />
        </div>

        <div
          v-for="t in tasks"
          :key="t.id"
          class="task-item"
          :class="{
            'task-item--active': currentTask && currentTask.id === t.id,
            'task-item--new': newIds.has(t.id)
          }"
          @click="selectTask(t)"
        >
          <div class="task-item__top">
            <StatusTag :status="t.status" kind="task" size="small" />
            <el-tag v-if="newIds.has(t.id)" size="small" type="danger" effect="dark" class="task-item__new-tag">
              新
            </el-tag>
            <span class="task-item__time">{{ fromNow(t.createdAt) }}</span>
          </div>
          <div class="task-item__input">{{ firstLine(t.input) }}</div>
          <div class="task-item__meta">
            <CopyId :id="t.id" />
            <span v-if="t.agentSteps?.length" class="task-item__steps">
              {{ t.agentSteps.length }} 步
            </span>
          </div>
        </div>
      </el-scrollbar>

      <div class="list-panel__pager">
        <el-pagination
          small
          layout="prev, pager, next"
          :total="total"
          :page-size="size"
          :current-page="page"
          @current-change="loadTasks"
        />
      </div>
    </el-card>

    <!-- ============ 右：任务详情 ============ -->
    <el-card class="detail-panel" body-style="padding: 0;">
      <div v-if="!currentTask" class="detail-empty">
        <el-empty description="从左侧选择一个任务查看详情">
          <template #description>
            <p style="color: var(--c-text-muted); line-height: 1.9; margin: 0;">
              从左侧列表点选任务即可查看推理过程与分析报告。<br />
              无需再手工输入任务 ID。
            </p>
          </template>
        </el-empty>
      </div>

      <div v-else class="detail-body">
        <div class="detail-head">
          <div>
            <h3 class="page-title" style="font-size: 15px;">
              <el-button
                v-if="cameFromOtherPage"
                link
                type="primary"
                :icon="ArrowLeft"
                class="detail-head__back"
                @click="goBack"
              >
                返回
              </el-button>
              <el-icon><Document /></el-icon>任务详情
            </h3>
            <div class="detail-id">
              <span class="text-muted">任务 ID：</span>
              <CopyId :id="currentTask.id" />
            </div>
          </div>
          <div class="detail-head__actions">
            <el-tag v-if="polling" size="small" type="warning" effect="plain">
              <el-icon class="is-loading"><Loading /></el-icon> 实时跟踪中
            </el-tag>
            <el-button :icon="Refresh" circle size="small" @click="reloadCurrent" />
          </div>
        </div>

        <el-scrollbar class="detail-scroll">
          <div class="detail-content">
            <!-- 验证中提示 -->
            <VerifyStatusBar
              v-if="currentTask.status === 'VERIFYING'"
              :round="Number(currentTask.verificationRound) || 0"
              :max="Number(currentTask.maxRetries) || 3"
              :deadline="currentTask.verificationDeadline || ''"
              :note="currentTask.verifyNote || ''"
              style="margin-bottom: 16px;"
            />

            <el-descriptions :column="2" border size="small">
              <el-descriptions-item label="状态">
                <StatusTag :status="currentTask.status" kind="task" size="small" />
                <span v-if="currentTask.status === 'VERIFYING' && currentTask.verificationRound"
                      class="verify-round-tag">
                  {{ verificationRoundText(currentTask) }}
                </span>
              </el-descriptions-item>
              <el-descriptions-item label="类型">{{ currentTask.type || '—' }}</el-descriptions-item>
              <el-descriptions-item label="创建时间">
                {{ formatTime(currentTask.createdAt) }}
              </el-descriptions-item>
              <el-descriptions-item label="耗时">
                {{ durationBetween(currentTask.createdAt, currentTask.updatedAt) }}
              </el-descriptions-item>
              <el-descriptions-item label="关联告警" :span="2">
                <el-link v-if="currentTask.alertId" type="primary"
                         @click="goAlert(currentTask.alertId)">
                  {{ currentTask.alertId }}
                </el-link>
                <span v-else class="text-muted">—</span>
              </el-descriptions-item>
              <el-descriptions-item label="触发输入" :span="2">
                <div class="detail-input">{{ currentTask.input || '无' }}</div>
              </el-descriptions-item>
            </el-descriptions>

            <!-- 修复后复现的提示条 -->
            <el-alert
              v-if="currentTask.status === 'REGRESSED'"
              type="error"
              :closable="false"
              show-icon
              title="修复后复现"
              style="margin-top: 16px;"
              description="该任务曾执行修复并成功，但在回归观察期内故障再次出现。结论是「修过但没保住」，需重点排查为什么修复没有保持，而不只是重复执行修复动作。"
            />

            <!-- 推理步骤 -->
            <div v-if="currentTask.agentSteps?.length" class="section">
              <div class="section__head">
                <h4 class="section__title">
                  <el-icon><Connection /></el-icon>Agent 推理过程
                  <el-tag size="small" type="info" effect="plain">{{ currentTask.agentSteps.length }} 步</el-tag>
                </h4>
                <el-button link type="primary" size="small" @click="showAllSteps = !showAllSteps">
                  {{ showAllSteps ? '收起' : '展开全部' }}
                </el-button>
              </div>
              <el-timeline class="steps">
                <el-timeline-item
                  v-for="(step, idx) in visibleSteps"
                  :key="idx"
                  :timestamp="`步骤 ${idx + 1}`"
                  :type="timelineType(step)"
                  :icon="timelineIcon(step)"
                  placement="top"
                >
                  <div class="step">
                    <el-tag v-if="stepToolName(step)" size="small" effect="plain" class="mono">
                      {{ stepToolName(step) }}
                    </el-tag>
                    <span class="step__text">{{ cleanStep(step) }}</span>
                  </div>
                </el-timeline-item>
              </el-timeline>
              <div v-if="currentTask.agentSteps.length > COLLAPSE_STEPS && !showAllSteps" class="steps-fold">
                已折叠 {{ currentTask.agentSteps.length - COLLAPSE_STEPS }} 步，点击「展开全部」查看
              </div>
            </div>

            <!-- 分析报告 -->
            <div v-if="currentTask.output" class="section">
              <div class="section__head">
                <h4 class="section__title">
                  <el-icon><Document /></el-icon>分析报告
                </h4>
                <el-button link type="primary" size="small" :icon="DocumentCopy" @click="copyReport">
                  复制报告
                </el-button>
              </div>
              <div class="markdown-body" v-html="renderedReport"></div>
            </div>

            <el-empty v-if="!currentTask.output && !currentTask.agentSteps?.length"
                      description="该任务尚无分析产出" :image-size="90" />
          </div>
        </el-scrollbar>
      </div>
    </el-card>
  </div>
</template>

<script setup>
import { ref, computed, onMounted, onUnmounted, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { getTask, getTasks } from '@/api/task'
import { ElMessage } from 'element-plus'
import { marked } from 'marked'
import {
  List, Search, Refresh, Document, Connection, DocumentCopy, Loading, ArrowLeft
} from '@element-plus/icons-vue'
import StatusTag from '@/components/StatusTag.vue'
import CopyId from '@/components/CopyId.vue'
import VerifyStatusBar from '@/components/VerifyStatusBar.vue'
import {
  formatTime, fromNow, durationBetween, isFinalTaskStatus,
  stepKind, stepToolName, copyText, verificationRoundText
} from '@/utils/format'

const route = useRoute()
const router = useRouter()

// ---------------------------- 列表 ----------------------------
const tasks = ref([])
const total = ref(0)
const page = ref(1)
const size = 12
const loading = ref(false)
const filters = ref({ status: '', keyword: '' })

const hasFilter = computed(() => !!(filters.value.status || filters.value.keyword))

const firstLine = (text) => {
  if (!text) return '（无输入）'
  const line = String(text).split('\n').map(s => s.trim()).filter(Boolean)[0] || ''
  return line.length > 90 ? line.slice(0, 90) + '…' : line
}

const newIds = ref(new Set())

const loadTasks = async (p = page.value, { silent = false } = {}) => {
  if (!silent) loading.value = true
  try {
    const params = { page: p, size }
    if (filters.value.status) params.status = filters.value.status
    if (filters.value.keyword) params.keyword = filters.value.keyword.trim()

    const res = await getTasks(params)
    const records = res.data?.records || []

    if (silent && tasks.value.length) {
      const known = new Set(tasks.value.map(t => t.id))
      const fresh = records.filter(t => t.id && !known.has(t.id)).map(t => t.id)
      if (fresh.length) {
        newIds.value = new Set([...newIds.value, ...fresh])
        setTimeout(() => {
          const next = new Set(newIds.value)
          fresh.forEach(id => next.delete(id))
          newIds.value = next
        }, 6000)
      }
    }

    tasks.value = records
    total.value = res.data?.total || 0
    page.value = p
  } catch {
    if (!silent) {
      tasks.value = []
      total.value = 0
    }
  } finally {
    if (!silent) loading.value = false
  }
}

// 自动轮询
let listTimer = null
const startListPolling = () => {
  stopListPolling()
  listTimer = setInterval(() => {
    if (document.hidden) return
    loadTasks(page.value, { silent: true })
  }, 30000)
}
const stopListPolling = () => {
  if (listTimer) {
    clearInterval(listTimer)
    listTimer = null
  }
}

let searchTimer = null
const onFilterChange = () => {
  if (searchTimer) clearTimeout(searchTimer)
  searchTimer = setTimeout(() => loadTasks(1), 400)
}

// ---------------------------- 详情 ----------------------------
const currentTask = ref(null)
const showAllSteps = ref(false)
const polling = ref(false)
const COLLAPSE_STEPS = 8

let pollTimer = null

const visibleSteps = computed(() => {
  const steps = currentTask.value?.agentSteps || []
  return showAllSteps.value ? steps : steps.slice(0, COLLAPSE_STEPS)
})

const cleanStep = (s) => String(s || '').replace(/^\[[A-Za-z0-9_]+\]\s*/, '')

/* 对 marked 渲染结果做轻量级 XSS 防护：去掉 script 标签、事件属性与 javascript: 链接 */
const stripDangerous = (html) => {
  return html
    .replace(/<script\b[^<]*(?:(?!<\/script>)<[^<]*)*<\/script>/gi, '')
    .replace(/\bon\w+\s*=\s*("[^"]*"|'[^']*'|[^>\s]+)/gi, '')
    .replace(/href\s*=\s*"javascript:[^"]*"/gi, 'href="#"')
    .replace(/href\s*=\s*'javascript:[^']*'/gi, "href='#'")
}

const timelineType = (step) => {
  const k = stepKind(step)
  if (k === 'error') return 'danger'
  if (k === 'tool') return 'primary'
  if (k === 'repair') return 'warning'
  if (k === 'approval') return 'warning'
  return 'success'
}

const timelineIcon = (step) => {
  const k = stepKind(step)
  if (k === 'error') return 'CloseBold'
  if (k === 'tool') return 'Connection'
  if (k === 'repair') return 'Tools'
  if (k === 'approval') return 'Stamp'
  return 'CircleCheck'
}

const renderedReport = computed(() => {
  const report = currentTask.value?.output
  if (!report) return ''
  try {
    return stripDangerous(marked.parse(report))
  } catch {
    return stripDangerous(String(report))
  }
})

const copyReport = async () => {
  const ok = await copyText(currentTask.value?.output || '')
  ok ? ElMessage.success('报告已复制到剪贴板') : ElMessage.error('复制失败')
}

const stopPolling = () => {
  if (pollTimer) {
    clearInterval(pollTimer)
    pollTimer = null
  }
  polling.value = false
}

const startPolling = (id) => {
  stopPolling()
  polling.value = true
  pollTimer = setInterval(async () => {
    try {
      const res = await getTask(id)
      const t = res.data
      if (!t) return
      currentTask.value = t
      const idx = tasks.value.findIndex(x => x.id === id)
      if (idx >= 0) tasks.value[idx] = { ...tasks.value[idx], ...t }
      if (isFinalTaskStatus(t.status)) {
        stopPolling()
        ElMessage.success('任务已到终态，实时跟踪结束')
      }
    } catch {
      // 单次轮询失败不打断，下一轮继续
    }
  }, 3000)
}

const cameFromOtherPage = ref(false)

const goBack = () => {
  if (window.history.length > 1) {
    router.back()
  } else {
    router.push('/dashboard')
  }
}

const selectTask = async (t) => {
  showAllSteps.value = false
  cameFromOtherPage.value = false
  currentTask.value = t
  router.replace({ path: '/tasks', query: { id: t.id } })

  try {
    const res = await getTask(t.id)
    if (res.data) currentTask.value = res.data
  } catch {
    // 详情拉取失败时保留列表数据
  }

  if (!isFinalTaskStatus(currentTask.value?.status)) {
    startPolling(t.id)
  } else {
    stopPolling()
  }
}

const reloadCurrent = async () => {
  if (!currentTask.value) return
  try {
    const res = await getTask(currentTask.value.id)
    if (res.data) currentTask.value = res.data
    ElMessage.success('已刷新')
  } catch {
    ElMessage.error('刷新失败')
  }
}

const goAlert = (alertId) => {
  router.push({ path: '/alerts', query: { alertId } })
}

const loadByIdFromQuery = async () => {
  const id = route.query.id
  if (!id) return
  cameFromOtherPage.value = true
  try {
    const res = await getTask(id)
    if (res.data) {
      currentTask.value = res.data
      showAllSteps.value = false
      if (!isFinalTaskStatus(res.data.status)) startPolling(id)
    }
  } catch {
    ElMessage.error('任务不存在或查询失败')
  }
}

onMounted(async () => {
  await loadTasks(1)
  await loadByIdFromQuery()
  startListPolling()
})

watch(() => route.query.id, (id) => {
  if (id && id !== currentTask.value?.id) {
    loadByIdFromQuery()
  }
})

onUnmounted(() => {
  stopPolling()
  stopListPolling()
  if (searchTimer) clearTimeout(searchTimer)
})
</script>

<style scoped>
.task-layout {
  display: grid;
  grid-template-columns: 400px 1fr;
  gap: var(--space-page);
  height: calc(100vh - 64px - var(--space-page) * 2);
}

.list-panel,
.detail-panel {
  display: flex;
  flex-direction: column;
  overflow: hidden;
}

/* ---------- 左侧列表 ---------- */
.list-panel__head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 14px 16px;
  border-bottom: 1px solid var(--c-border);
  flex-shrink: 0;
}

.list-panel__title {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 14px;
  font-weight: 600;
  color: var(--c-text);
}

.list-panel__title .el-icon {
  color: var(--c-primary);
}

.verify-round-tag {
  margin-left: 8px;
  font-size: 12px;
  color: var(--c-primary);
  font-weight: 600;
}

.list-panel__filters {
  padding: 12px 16px;
  border-bottom: 1px solid var(--c-border);
  flex-shrink: 0;
  background: var(--c-surface-hover);
  display: flex;
  gap: 12px;
  align-items: flex-start;
}

.list-panel__scroll {
  flex: 1;
  min-height: 0;
}

.list-empty {
  padding: 40px 0;
}

.list-panel__pager {
  flex-shrink: 0;
  display: flex;
  justify-content: center;
  padding: 10px;
  border-top: 1px solid var(--c-border);
}

.task-item {
  padding: 12px 16px;
  border-bottom: 1px solid var(--c-border);
  cursor: pointer;
  transition: background 0.15s;
  border-left: 3px solid transparent;
}

.task-item:hover {
  background: var(--c-surface-hover);
}

.task-item--active {
  background: var(--c-primary-soft);
  border-left-color: var(--c-primary);
}

.task-item--new {
  background: var(--c-danger-soft);
  border-left-color: var(--c-danger);
  animation: task-flash 1.2s ease-out;
}

@keyframes task-flash {
  0% { background: var(--c-danger); }
  100% { background: var(--c-danger-soft); }
}

.task-item__new-tag {
  margin-right: auto;
  margin-left: 6px;
}

.task-item__top {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 6px;
}

.task-item__time {
  font-size: 11.5px;
  color: var(--c-text-muted);
}

.task-item__input {
  font-size: 12.5px;
  color: var(--c-text-secondary);
  line-height: 1.55;
  display: -webkit-box;
  -webkit-line-clamp: 2;
  line-clamp: 2;
  -webkit-box-orient: vertical;
  overflow: hidden;
  margin-bottom: 6px;
}

.task-item__meta {
  display: flex;
  align-items: center;
  justify-content: space-between;
}

.task-item__steps {
  font-size: 11.5px;
  color: var(--c-text-muted);
}

/* ---------- 右侧详情 ---------- */
.detail-panel {
  min-width: 0;
}

.detail-empty {
  flex: 1;
  display: flex;
  align-items: center;
  justify-content: center;
}

.detail-body {
  display: flex;
  flex-direction: column;
  height: 100%;
  min-height: 0;
}

.detail-head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  padding: 14px 20px;
  border-bottom: 1px solid var(--c-border);
  flex-shrink: 0;
}

.detail-head__actions {
  display: flex;
  align-items: center;
  gap: 8px;
}

.detail-head__back {
  font-size: 13px;
  margin-right: 8px;
  padding-right: 10px;
  border-right: 1px solid var(--c-border);
  border-radius: 0;
}

.detail-id {
  margin-top: 6px;
  font-size: 12.5px;
  display: flex;
  align-items: center;
  gap: 4px;
}

.detail-scroll {
  flex: 1;
  min-height: 0;
}

.detail-content {
  padding: 20px;
}

.detail-input {
  font-size: 13px;
  color: var(--c-text-secondary);
  line-height: 1.7;
  white-space: pre-wrap;
  word-break: break-word;
}

/* ---------- 通用区块 ---------- */
.section {
  margin-top: 20px;
}

.section__head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  margin-bottom: 12px;
}

.section__title {
  margin: 0;
  font-size: 14.5px;
  font-weight: 600;
  color: var(--c-text);
  display: flex;
  align-items: center;
  gap: 8px;
}

.steps :deep(.el-timeline-item__timestamp) {
  color: var(--c-text-muted);
  font-size: 12px;
}

.step {
  display: flex;
  align-items: flex-start;
  gap: 8px;
  line-height: 1.65;
}

.step__text {
  font-size: 13px;
  color: var(--c-text-secondary);
  word-break: break-word;
}

.steps-fold {
  text-align: center;
  color: var(--c-text-muted);
  font-size: 12.5px;
  padding: 6px 0;
}

.text-muted {
  color: var(--c-text-muted);
}

/* 窄屏：上下堆叠 */
@media (max-width: 1100px) {
  .task-layout {
    grid-template-columns: 1fr;
    height: auto;
  }

  .list-panel {
    max-height: 420px;
  }

  .detail-panel {
    min-height: 500px;
  }
}
</style>