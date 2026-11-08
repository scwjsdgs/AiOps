<template>
  <div class="alerts page">
    <!-- ============ 概览统计 ============ -->
    <el-row :gutter="16" class="stats-row">
      <el-col :xs="12" :sm="6" v-for="card in summaryCards" :key="card.key">
        <div class="stat-card" :class="`stat-card--${card.tone}`" @click="quickFilter(card.key)">
          <div class="stat-card__body">
            <div class="stat-card__value">{{ card.value }}</div>
            <div class="stat-card__label">{{ card.label }}</div>
          </div>
          <div class="stat-card__icon">
            <el-icon :size="24"><component :is="card.icon" /></el-icon>
          </div>
        </div>
      </el-col>
    </el-row>

    <!-- ============ 告警列表卡片 ============ -->
    <div class="glass-card">
      <div class="page-header" style="margin-bottom: var(--space-lg);">
        <div>
          <h3 class="page-title">
            <el-icon><Warning /></el-icon>告警列表
          </h3>
          <p class="page-subtitle">
            共 {{ total }} 条告警。筛选条件实时生效并作用于全量数据，非仅当前页。
          </p>
        </div>
        <div class="toolbar">
          <el-button :icon="Refresh" @click="loadAlerts(1)" :loading="loading">刷新</el-button>
          <el-button type="primary" :icon="Plus" @click="dialogSendVisible = true">发送测试告警</el-button>
        </div>
      </div>

      <!-- 筛选条：级别 / 状态 / 关键词 -->
      <div class="filters">
        <el-select v-model="filters.severity" placeholder="全部级别" clearable style="width: 150px"
                   @change="loadAlerts(1)">
          <el-option label="严重" value="CRITICAL" />
          <el-option label="警告" value="WARNING" />
          <el-option label="提示" value="INFO" />
        </el-select>

        <el-select v-model="filters.status" placeholder="全部状态" clearable style="width: 160px"
                   @change="loadAlerts(1)">
          <el-option v-for="(meta, key) in alertStatusOptions" :key="key" :label="meta.text" :value="key" />
        </el-select>

        <el-input
          v-model="filters.keyword"
          placeholder="搜索标题 / 服务名 / 描述，回车确认"
          :prefix-icon="Search"
          clearable
          style="flex: 1; min-width: 240px;"
          @keyup.enter="loadAlerts(1)"
          @clear="loadAlerts(1)"
        />
        <el-button type="primary" :icon="Search" @click="loadAlerts(1)">查询</el-button>
        <el-button v-if="hasFilter" link type="primary" @click="resetFilters">清空筛选</el-button>
      </div>

      <!-- 告警表格 -->
      <el-table
        :data="alerts"
        v-loading="loading"
        style="width: 100%; margin-top: var(--space-lg);"
        :row-class-name="rowClass"
      >
        <el-table-column label="级别" width="100">
          <template #default="{ row }">
            <StatusTag :status="row.severity" kind="severity" size="small" />
          </template>
        </el-table-column>

        <el-table-column label="状态" width="110">
          <template #default="{ row }">
            <StatusTag :status="row.status" kind="alert" size="small" />
          </template>
        </el-table-column>

        <el-table-column label="验证 / 冷却" width="170">
          <template #default="{ row }">
            <el-tooltip v-if="row.status === 'VERIFYING'"
                        :content="verifyingTooltip(row)" placement="top">
              <el-tag type="primary" size="small" effect="plain" class="mono">
                <el-icon class="is-loading"><Loading /></el-icon>
                {{ row.verificationRound > 0 ? `第 ${row.verificationRound}/${row.maxRetries || 3} 轮` : '健康观察中' }}
              </el-tag>
            </el-tooltip>
            <el-tooltip v-else-if="isCoolingDown(row)"
                        content="用户拒绝后进入静默冷却期：期间仅累计次数、不再弹窗；冷却结束仍在报错会自动重新拉起"
                        placement="top">
              <el-tag type="warning" size="small" effect="plain">
                冷却 {{ coolingRemain(row) }}
              </el-tag>
            </el-tooltip>
            <el-tooltip v-else-if="row.retryExhaustedAt"
                        content="连续 3 轮验证失败，已停止自动修复，需人工介入" placement="top">
              <el-tag type="danger" size="small" effect="plain">已停止重试</el-tag>
            </el-tooltip>
            <span v-else class="text-muted">—</span>
          </template>
        </el-table-column>

        <el-table-column label="标题" min-width="280">
          <template #default="{ row }">
            <div class="cell-title">
              <el-tooltip :content="row.title" placement="top-left" :show-after="400">
                <span class="cell-title__text" :class="{ 'cell-title__text--escalated': isEscalated(row) }">
                  {{ row.title || '—' }}
                </span>
              </el-tooltip>
              <el-tag v-if="isEscalated(row)" size="small" type="danger" effect="dark" class="tag-escalated">
                严重恶化
              </el-tag>
              <el-tag v-if="row.source === 'anomaly-detector'" size="small" type="warning" effect="plain">
                预测性预警
              </el-tag>
              <el-tag v-else-if="row.source === 'recovery-verifier'" size="small" type="danger" effect="plain">
                修复复现
              </el-tag>
            </div>
          </template>
        </el-table-column>

        <el-table-column label="服务" width="180">
          <template #default="{ row }">
            <span v-if="row.serviceName && row.serviceName !== 'unknown'" class="mono cell-service">
              {{ row.serviceName }}
            </span>
            <span v-else class="text-muted">—</span>
          </template>
        </el-table-column>

        <el-table-column label="发生次数" width="120">
          <template #default="{ row }">
            <el-tooltip content="该故障已持续发生 X 次（同服务同指纹聚合）" placement="top">
              <el-tag v-if="row.occurrence > 1" type="warning" size="small" effect="plain" class="mono">
                {{ row.occurrence }} 次
              </el-tag>
              <span v-else class="text-muted">1 次</span>
            </el-tooltip>
          </template>
        </el-table-column>

        <el-table-column label="描述" min-width="260">
          <template #default="{ row }">
            <el-tooltip :content="row.description" placement="top-left" :show-after="400"
                        :disabled="!row.description">
              <span class="cell-desc">{{ row.description || '—' }}</span>
            </el-tooltip>
          </template>
        </el-table-column>

        <el-table-column label="来源" width="130">
          <template #default="{ row }">
            <span class="text-muted">{{ sourceText(row.source) }}</span>
          </template>
        </el-table-column>

        <el-table-column label="时间" width="160">
          <template #default="{ row }">
            <el-tooltip :content="formatTime(row.timestamp || row.createTime)" placement="top">
              <span class="cell-time">{{ fromNow(row.timestamp || row.createTime) }}</span>
            </el-tooltip>
          </template>
        </el-table-column>

        <el-table-column label="告警 ID" width="140">
          <template #default="{ row }">
            <CopyId :id="row.id" />
          </template>
        </el-table-column>

        <el-table-column label="操作" width="110" fixed="right">
          <template #default="{ row }">
            <el-button type="primary" link size="small" @click.stop="viewAnalysis(row)">查看分析</el-button>
          </template>
        </el-table-column>

        <template #empty>
          <div class="empty-state" style="padding: 40px 0;">
            <el-icon size="56" style="color: var(--c-border-soft);"><Warning /></el-icon>
            <p class="empty-state__title">{{ hasFilter ? '没有符合筛选条件的告警' : '暂无告警数据' }}</p>
            <p class="empty-state__desc">
              {{ hasFilter ? '尝试调整筛选条件后再查看' : '系统运行正常，暂未检测到异常告警。' }}
            </p>
          </div>
        </template>
      </el-table>

      <div class="pager">
        <el-pagination
          v-model:current-page="page"
          v-model:page-size="size"
          :page-sizes="[10, 20, 50, 100]"
          :total="total"
          layout="total, sizes, prev, pager, next, jumper"
          background
          @current-change="loadAlerts"
          @size-change="loadAlerts(1)"
        />
      </div>
    </div>

    <!-- ============ 分析报告弹窗 ============ -->
    <el-dialog
      v-model="dialogVisible"
      title="Agent 分析报告"
      width="960px"
      top="5vh"
      destroy-on-close
      @closed="onDialogClosed"
    >
      <div v-if="taskLoading" style="height: 300px; display: flex; align-items: center; justify-content: center;">
        <el-loading />
      </div>

      <div v-else-if="currentTask">
        <el-descriptions :column="2" border size="small">
          <el-descriptions-item label="任务 ID">
            <CopyId :id="currentTask.id" />
          </el-descriptions-item>
          <el-descriptions-item label="任务状态">
            <StatusTag :status="currentTask.status" kind="task" size="small" />
          </el-descriptions-item>
          <el-descriptions-item label="任务类型">{{ currentTask.type || '—' }}</el-descriptions-item>
          <el-descriptions-item label="耗时">
            {{ durationBetween(currentTask.createdAt, currentTask.updatedAt) }}
          </el-descriptions-item>
          <el-descriptions-item label="创建时间" :span="2">
            {{ formatTime(currentTask.createdAt) }}
            <span class="text-muted" style="margin-left:8px;">（{{ fromNow(currentTask.createdAt) }}）</span>
          </el-descriptions-item>
        </el-descriptions>

        <!-- 故障影响面 -->
        <div v-if="impactService" class="section">
          <div class="section__head">
            <h4 class="section__title">
              <el-icon><Share /></el-icon>故障影响面
            </h4>
            <el-tag v-if="impact && impact.outage" type="danger" size="small">已断流</el-tag>
            <el-tag v-else-if="impact && impact.available" type="success" size="small">入口正常</el-tag>
          </div>

          <el-row :gutter="12" v-if="impact" class="impact-stats">
            <el-col :span="8">
              <div class="impact-stat">
                <div class="impact-stat__value">{{ impact.affectedIngresses?.length ?? 0 }}</div>
                <div class="impact-stat__label">受影响入口</div>
              </div>
            </el-col>
            <el-col :span="8">
              <div class="impact-stat">
                <div class="impact-stat__value">{{ impact.readyEndpoints ?? '—' }}/{{ impact.totalEndpoints ?? '—' }}</div>
                <div class="impact-stat__label">就绪端点</div>
              </div>
            </el-col>
            <el-col :span="8">
              <div class="impact-stat" :class="{ 'impact-stat--bad': (impact.abnormalPods?.length ?? 0) > 0 }">
                <div class="impact-stat__value">{{ impact.abnormalPods?.length ?? 0 }}</div>
                <div class="impact-stat__label">异常实例</div>
              </div>
            </el-col>
          </el-row>

          <div v-if="impact && impact.summary" class="impact-summary">{{ impact.summary }}</div>

          <div v-if="hasTopology" ref="topologyChartRef" class="topology-chart"></div>
          <div v-if="!hasTopology" class="chart-empty">该服务未关联 Service，无拓�扑关系可展示</div>
        </div>

        <!-- 推理步骤 -->
        <div v-if="currentTask.agentSteps?.length" class="section">
          <div class="section__head">
            <h4 class="section__title">
              <el-icon><List /></el-icon>推理步骤
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
                  description="该任务尚无分析产出" />
      </div>

      <el-empty v-else description="该告警还没有关联的分析任务">
        <template #description>
          <p style="color: var(--c-text-muted); line-height: 1.8; margin: 0;">
            该告警还没有关联的分析任务。<br />
            <span style="font-size: 12px;">
              可能原因：告警被降噪聚合进了其他任务，或分析尚未开始。
            </span>
          </p>
        </template>
      </el-empty>
    </el-dialog>

    <!-- ============ 发送测试告警 ============ -->
    <el-dialog v-model="dialogSendVisible" title="发送测试告警" width="560px">
      <el-form :model="sendForm" label-width="90px">
        <el-form-item label="标题">
          <el-input v-model="sendForm.title" placeholder="如：nginx 频繁重启" />
        </el-form-item>
        <el-form-item label="服务名">
          <el-input v-model="sendForm.serviceName" placeholder="如：nginx（须为集群内真实 Deployment 名）" />
        </el-form-item>
        <el-form-item label="级别">
          <el-select v-model="sendForm.severity" style="width: 100%">
            <el-option label="严重 CRITICAL" value="CRITICAL" />
            <el-option label="警告 WARNING" value="WARNING" />
            <el-option label="提示 INFO" value="INFO" />
          </el-select>
        </el-form-item>
        <el-form-item label="描述">
          <el-input v-model="sendForm.description" type="textarea" :rows="3"
                    placeholder="描述故障现象，agent 会据此推理" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialogSendVisible = false">取消</el-button>
        <el-button type="primary" :loading="sending" @click="sendTestAlert">发送</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, computed, onMounted, onUnmounted, nextTick } from 'vue'
import { getAlerts, getAlertStats, sendAlert } from '@/api/alert'
import { getTask, getTasks } from '@/api/task'
import { getClusterTopology, getClusterImpact } from '@/api/cluster'
import { ElMessage } from 'element-plus'
import { marked } from 'marked'
import {
  Warning, Search, Refresh, Plus, Share, List, Document, DocumentCopy, Loading
} from '@element-plus/icons-vue'
import StatusTag from '@/components/StatusTag.vue'
import CopyId from '@/components/CopyId.vue'
import {
  formatTime, fromNow, durationBetween, stepKind, stepToolName, copyText, secondsUntil, formatCountdown
} from '@/utils/format'

// ---------------------------- 列表状态 ----------------------------
const alerts = ref([])
const total = ref(0)
const page = ref(1)
const size = ref(10)
const loading = ref(false)

const filters = ref({ severity: '', status: '', keyword: '' })
const hasFilter = computed(() =>
  !!(filters.value.severity || filters.value.status || filters.value.keyword)
)

// 状态筛选项与 StatusTag 用同一份枚举，避免两处文案漂移
const alertStatusOptions = {
  FIRING: { text: '触发中' },
  PENDING: { text: '待分析' },
  ANALYZING: { text: '分析中' },
  RESOLVED: { text: '已解决' },
  CORRELATED: { text: '已聚合' },
  FAILED: { text: '失败' },
  TIMEOUT: { text: '超时' }
}

const summaryCards = ref([
  { key: 'total', label: '总告警', value: 0, tone: 'primary', icon: Warning },
  { key: 'firing', label: '触发中', value: 0, tone: 'danger', icon: Warning },
  { key: 'pending', label: '待分析', value: 0, tone: 'warning', icon: Search },
  { key: 'resolved', label: '已解决', value: 0, tone: 'success', icon: DocumentCopy }
])

// ---------------------------- 数据加载 ----------------------------
const loadAlerts = async (p = page.value) => {
  try {
    loading.value = true
    const res = await getAlerts({
      page: p,
      size: size.value,
      severity: filters.value.severity || undefined,
      status: filters.value.status || undefined,
      keyword: filters.value.keyword || undefined
    })
    alerts.value = res.data?.records || []
    total.value = res.data?.total || 0
    page.value = p
  } catch {
    alerts.value = []
    total.value = 0
  } finally {
    loading.value = false
  }
}

const loadStats = async () => {
  try {
    const res = await getAlertStats()
    const s = res.data || {}
    summaryCards.value = [
      { key: 'total', label: '总告警', value: s.total || 0, tone: 'primary', icon: Warning },
      { key: 'firing', label: '触发中', value: s.firing || 0, tone: 'danger', icon: Warning },
      { key: 'pending', label: '待分析', value: s.pending || 0, tone: 'warning', icon: Search },
      { key: 'resolved', label: '已解决', value: s.resolved || 0, tone: 'success', icon: DocumentCopy }
    ]
  } catch {
    // 静默失败
  }
}

const loadDashboard = async () => {
  await Promise.all([loadAlerts(1), loadStats()])
}

const quickFilter = (key) => {
  const map = {
    total: { severity: '', status: '' },
    firing: { severity: '', status: 'FIRING' },
    pending: { severity: '', status: 'PENDING' },
    resolved: { severity: '', status: 'RESOLVED' }
  }
  filters.value = { ...map[key], keyword: '' }
  loadAlerts(1)
}

const resetFilters = () => {
  filters.value = { severity: '', status: '', keyword: '' }
  loadAlerts(1)
}

// ---------------------------- 辅助函数 ----------------------------
const rowClass = ({ row }) => {
  const cls = []
  if (isEscalated(row)) cls.push('row--escalated')
  if (row.status === 'VERIFYING') cls.push('row--verifying')
  return cls.join(' ')
}

const isEscalated = (row) => {
  return (row.title || '').startsWith('[严重恶化]')
}

const verifyingTooltip = (row) => {
  return `验证中：第 ${row.verificationRound || 0}/${row.maxRetries || 3} 轮，系统正在执行修复并观察是否恢复。`
}

const isCoolingDown = (row) => {
  return row.status === 'REJECTED' || row.status === 'TIMEOUT'
}

const coolingRemain = (row) => {
  return '15 分钟'
}

const sourceText = (source) => {
  const map = {
    'prometheus': 'Prometheus',
    'anomaly-detector': '异常检测',
    'recovery-verifier': '恢复验证',
    'manual': '手动录入'
  }
  return map[source] || source || '—'
}

// ---------------------------- 对话框：分析报告 ----------------------------
const dialogVisible = ref(false)
const dialogSendVisible = ref(false)
const currentTask = ref(null)
const taskLoading = ref(false)
const impactService = ref(null)
const impact = ref(null)
const hasTopology = ref(false)
const showAllSteps = ref(false)
const COLLAPSE_STEPS = 8
const sending = ref(false)

const visibleSteps = computed(() => {
  const steps = currentTask.value?.agentSteps || []
  return showAllSteps.value ? steps : steps.slice(0, COLLAPSE_STEPS)
})

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

const cleanStep = (s) => String(s || '').replace(/^\[[A-Za-z0-9_]+\]\s*/, '')

/* 对 marked 渲染结果做轻量级 XSS 防护：去掉 script 标签、事件属性与 javascript: 链接 */
const stripDangerous = (html) => {
  return html
    .replace(/<script\b[^<]*(?:(?!<\/script>)<[^<]*)*<\/script>/gi, '')
    .replace(/\bon\w+\s*=\s*("[^"]*"|'[^']*'|[^>\s]+)/gi, '')
    .replace(/href\s*=\s*"javascript:[^"]*"/gi, 'href="#"')
    .replace(/href\s*=\s*'javascript:[^']*'/gi, "href='#'")
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

const viewAnalysis = async (row) => {
  dialogVisible.value = true
  taskLoading.value = true
  currentTask.value = null
  impactService.value = null
  impact.value = null
  hasTopology.value = false

  try {
    const res = await getTask(row.id)
    const t = res.data
    if (t) {
      currentTask.value = t
    }
  } catch {
    ElMessage.error('获取分析任务失败')
  } finally {
    taskLoading.value = false
  }
}

const onDialogClosed = () => {
  currentTask.value = null
  impact.value = null
  hasTopology.value = false
  showAllSteps.value = false
}

const copyReport = async () => {
  const ok = await copyText(currentTask.value?.output || '')
  ok ? ElMessage.success('报告已复制到剪贴板') : ElMessage.error('复制失败')
}

// ---------------------------- 发送测试告警 ----------------------------
const sendForm = reactiveForm()

const sendTestAlert = async () => {
  sending.value = true
  try {
    await sendAlert(sendForm.title, sendForm.serviceName, sendForm.severity, sendForm.description)
    ElMessage.success('测试告警已发送')
    dialogSendVisible.value = false
    sendForm.title = ''
    sendForm.serviceName = ''
    sendForm.description = ''
    loadDashboard()
  } catch (error) {
    ElMessage.error(error.message || '发送失败')
  } finally {
    sending.value = false
  }
}

function reactiveForm() {
  return {
    title: '',
    serviceName: '',
    severity: 'CRITICAL',
    description: ''
  }
}

onMounted(() => {
  loadDashboard()
})
</script>

<style scoped>
.stats-row > .el-col {
  display: flex;
}

.page-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
}

.toolbar {
  display: flex;
  align-items: center;
  gap: 10px;
}

.filters {
  padding: 16px 20px;
  background: var(--c-surface-hover);
  border: 1px solid var(--c-border);
  border-radius: var(--radius-md);
  display: flex;
  align-items: center;
  gap: 12px;
  flex-wrap: wrap;
}

.filters > * {
  flex-shrink: 0;
}

.cell-title {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
}

.cell-title__text {
  font-size: 13px;
  color: var(--c-text);
  font-weight: 500;
  max-width: 200px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.cell-title__text--escalated {
  color: var(--c-danger);
  font-weight: 600;
}

.tag-escalated {
  background: var(--c-danger-soft);
  color: var(--c-danger);
  border-color: var(--c-danger-soft);
}

.cell-service {
  max-width: 140px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.cell-desc {
  font-size: 12.5px;
  color: var(--c-text-secondary);
  line-height: 1.5;
}

.cell-time {
  color: var(--c-text-secondary);
  font-size: 12.5px;
}

.row--escalated {
  background: #fef2f2 !important;
}

.row--verifying {
  background: #eff6ff !important;
}

.impact-stats {
  margin: 12px 0;
}

.impact-stat {
  padding: 12px 14px;
  background: var(--c-surface-hover);
  border-radius: var(--radius-md);
  border: 1px solid var(--c-border);
}

.impact-stat--bad {
  border-color: var(--c-danger);
}

.impact-stat__value {
  font-size: 18px;
  font-weight: 700;
  color: var(--c-text);
}

.impact-stat__label {
  font-size: 12px;
  color: var(--c-text-muted);
}

.impact-summary {
  margin-top: 12px;
  padding: 12px 16px;
  background: var(--c-primary-soft);
  border-radius: var(--radius-md);
  font-size: 13px;
  color: var(--c-text-secondary);
  line-height: 1.6;
}

.topology-chart {
  width: 100%;
  height: 300px;
  margin-top: 12px;
  border: 1px dashed var(--c-border);
  border-radius: var(--radius-md);
  display: flex;
  align-items: center;
  justify-content: center;
  color: var(--c-text-muted);
}

.chart-empty {
  padding: 32px 0;
  text-align: center;
  color: var(--c-text-muted);
  font-size: 13px;
}

.pager {
  display: flex;
  justify-content: flex-end;
  margin-top: var(--space-lg);
}

@media (max-width: 768px) {
  .filters {
    flex-direction: column;
    align-items: stretch;
  }

  .filters > * {
    width: 100%;
  }

  .page-header {
    flex-direction: column;
    align-items: flex-start;
    gap: 12px;
  }

  .toolbar {
    width: 100%;
    justify-content: space-between;
  }

  .pager {
    justify-content: center;
  }
}
</style>