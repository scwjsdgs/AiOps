<template>
  <div class="page">
    <!-- 概览统计：把"当前筛选结果"的数量关系一眼说清 -->
    <el-row :gutter="16">
      <el-col :xs="12" :sm="6" v-for="card in summaryCards" :key="card.key">
        <div class="mini-stat" :class="`mini-stat--${card.tone}`" @click="quickFilter(card.key)">
          <div class="mini-stat__body">
            <div class="mini-stat__value">{{ card.value }}</div>
            <div class="mini-stat__label">{{ card.label }}</div>
          </div>
          <el-icon class="mini-stat__icon"><component :is="card.icon" /></el-icon>
        </div>
      </el-col>
    </el-row>

    <el-card>
      <div class="page-header" style="margin-bottom: 16px;">
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
        <el-select v-model="filters.severity" placeholder="全部级别" clearable style="width: 140px"
                   @change="loadAlerts(1)">
          <el-option label="严重" value="CRITICAL" />
          <el-option label="警告" value="WARNING" />
          <el-option label="提示" value="INFO" />
        </el-select>

        <el-select v-model="filters.status" placeholder="全部状态" clearable style="width: 150px"
                   @change="loadAlerts(1)">
          <el-option v-for="(meta, key) in alertStatusOptions" :key="key" :label="meta.text" :value="key" />
        </el-select>

        <el-input
          v-model="filters.keyword"
          placeholder="搜索标题 / 服务名 / 描述，回车确认"
          :prefix-icon="Search"
          clearable
          style="flex: 1; min-width: 220px;"
          @keyup.enter="loadAlerts(1)"
          @clear="loadAlerts(1)"
        />
        <el-button type="primary" :icon="Search" @click="loadAlerts(1)">查询</el-button>
        <el-button v-if="hasFilter" link type="primary" @click="resetFilters">清空筛选</el-button>
      </div>

      <!-- 告警表格：列宽与格式化重排，UUID 缩短可复制，时间显示相对时间 -->
      <el-table
        :data="alerts"
        v-loading="loading"
        style="width: 100%; margin-top: 16px;"
        :row-class-name="rowClass"
      >
        <el-table-column label="级别" width="92">
          <template #default="{ row }">
            <StatusTag :status="row.severity" kind="severity" size="small" />
          </template>
        </el-table-column>

        <el-table-column label="状态" width="104">
          <template #default="{ row }">
            <StatusTag :status="row.status" kind="alert" size="small" />
          </template>
        </el-table-column>

        <el-table-column label="标题" min-width="260">
          <template #default="{ row }">
            <div class="cell-title">
              <el-tooltip :content="row.title" placement="top-left" :show-after="400">
                <span class="cell-title__text">{{ row.title || '—' }}</span>
              </el-tooltip>
              <el-tag v-if="row.source === 'anomaly-detector'" size="small" type="warning" effect="plain">
                预测性预警
              </el-tag>
              <el-tag v-else-if="row.source === 'recovery-verifier'" size="small" type="danger" effect="plain">
                修复复现
              </el-tag>
            </div>
          </template>
        </el-table-column>

        <el-table-column label="服务" width="170">
          <template #default="{ row }">
            <span v-if="row.serviceName && row.serviceName !== 'unknown'" class="mono cell-service">
              {{ row.serviceName }}
            </span>
            <span v-else class="text-muted">—</span>
          </template>
        </el-table-column>

        <el-table-column label="描述" min-width="240">
          <template #default="{ row }">
            <el-tooltip :content="row.description" placement="top-left" :show-after="400"
                        :disabled="!row.description">
              <span class="cell-desc">{{ row.description || '—' }}</span>
            </el-tooltip>
          </template>
        </el-table-column>

        <el-table-column label="来源" width="120">
          <template #default="{ row }">
            <span class="text-muted">{{ sourceText(row.source) }}</span>
          </template>
        </el-table-column>

        <el-table-column label="时间" width="150">
          <template #default="{ row }">
            <el-tooltip :content="formatTime(row.timestamp || row.createTime)" placement="top">
              <span class="cell-time">{{ fromNow(row.timestamp || row.createTime) }}</span>
            </el-tooltip>
          </template>
        </el-table-column>

        <el-table-column label="告警 ID" width="130">
          <template #default="{ row }">
            <CopyId :id="row.id" />
          </template>
        </el-table-column>

        <el-table-column label="操作" width="100" fixed="right">
          <template #default="{ row }">
            <el-button type="primary" link size="small" @click.stop="viewAnalysis(row)">查看分析</el-button>
          </template>
        </el-table-column>

        <template #empty>
          <el-empty :description="hasFilter ? '没有符合筛选条件的告警' : '暂无告警数据'" />
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
    </el-card>

    <!-- ============ 分析报告弹窗 ============ -->
    <el-dialog
      v-model="dialogVisible"
      title="Agent 分析报告"
      width="900px"
      top="5vh"
      destroy-on-close
      @closed="onDialogClosed"
    >
      <div v-if="taskLoading" v-loading="true" style="height: 200px;"></div>

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

        <!-- 故障影响面：先看"影响了谁"，再看推理过程 -->
        <div v-if="impactService" class="section">
          <div class="section__head">
            <h4 class="section__title">
              <el-icon><Share /></el-icon>故障影响面
            </h4>
            <el-tag v-if="impact && impact.outage" type="danger" size="small">已断流</el-tag>
            <el-tag v-else-if="impact && impact.available" type="success" size="small">入口正常</el-tag>
          </div>

          <!-- 影响面关键数字 -->
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

          <div v-show="hasTopology" ref="topologyChartRef" class="topology-chart"></div>
          <div v-if="!hasTopology" class="chart-empty">该服务未关联 Service，无拓扑关系可展示</div>
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

        <!-- 未设置的告警被点开时，提示它没有任务，但别让用户以为出错 -->
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
    <el-dialog v-model="dialogSendVisible" title="发送测试告警" width="520px">
      <el-form :model="sendForm" label-width="80px">
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
import { getTasks } from '@/api/task'
import { getClusterTopology, getClusterImpact } from '@/api/cluster'
import { ElMessage } from 'element-plus'
import { marked } from 'marked'
import * as echarts from 'echarts'
import {
  Warning, Search, Refresh, Plus, Share, List, Document, DocumentCopy
} from '@element-plus/icons-vue'
import StatusTag from '@/components/StatusTag.vue'
import CopyId from '@/components/CopyId.vue'
import {
  formatTime, fromNow, durationBetween, stepKind, stepToolName, copyText
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

// 顶部统计卡片：数字来自当前列表的 total，以及各状态字典
const summaryCards = computed(() => [
  { key: 'all', label: '当前条件告警数', value: total.value, tone: 'primary', icon: 'Bell' },
  { key: 'PENDING', label: '待分析', value: statusCount('PENDING') + statusCount('FIRING'), tone: 'warning', icon: 'Clock' },
  { key: 'ANALYZING', label: '分析中', value: statusCount('ANALYZING'), tone: 'info', icon: 'Loading' },
  { key: 'RESOLVED', label: '已解决', value: statusCount('RESOLVED'), tone: 'success', icon: 'CircleCheck' }
])

// 各状态计数：由后端 /alerts/stats 的 byStatus 提供（见 loadStatusCounts）
const statusCounts = ref({})
const statusCount = (k) => statusCounts.value[k] || 0

const loadStatusCounts = async () => {
  try {
    const res = await getAlertStats(7)
    statusCounts.value = res.data?.byStatus || {}
  } catch {
    statusCounts.value = {}
  }
}

// 点顶部卡片 = 快捷筛选
const quickFilter = (key) => {
  if (key === 'all') {
    resetFilters()
    return
  }
  if (key === 'PENDING') {
    filters.value.status = 'PENDING'
  } else {
    filters.value.status = key
  }
  loadAlerts(1)
}

const resetFilters = () => {
  filters.value = { severity: '', status: '', keyword: '' }
  loadAlerts(1)
}

// 来源的中文说明：前端测试 / 告警管理器 / 基线预警 / 回归验证
const sourceText = (s) => {
  const map = {
    '前端测试': '前端测试',
    'anomaly-detector': '基线预警',
    'recovery-verifier': '回归验证',
    'alertmanager': '告警管理器',
    'prometheus': 'Prometheus',
    'kafka': 'Kafka'
  }
  return map[s] || s || '—'
}

// 高亮未处理的行：FIRING/FAILED 左侧加一道红边，扫读时先看到问题
// 轮询新发现的告警 ID：用于高亮提示"有新告警进来了"
const newIds = ref(new Set())

const rowClass = ({ row }) => {
  // 新到告警优先高亮，压过下面的状态色
  if (newIds.value.has(row.id)) return 'row-new'
  if (row.status === 'FIRING' || row.status === 'FAILED') return 'row-attention'
  if (row.status === 'CORRELATED') return 'row-muted'
  return ''
}

const loadAlerts = async (p = page.value, { silent = false } = {}) => {
  // silent：自动轮询用，不显示 loading 蒙层（每 10 秒闪一次会很吵）
  if (!silent) loading.value = true
  try {
    const res = await getAlerts({
      page: p,
      size: size.value,
      severity: filters.value.severity || undefined,
      status: filters.value.status || undefined,
      keyword: filters.value.keyword || undefined
    })
    const records = res.data?.records || []

    if (silent && alerts.value.length) {
      const known = new Set(alerts.value.map(a => a.id))
      const fresh = records.filter(a => a.id && !known.has(a.id)).map(a => a.id)
      if (fresh.length) {
        newIds.value = new Set([...newIds.value, ...fresh])
        setTimeout(() => {
          const next = new Set(newIds.value)
          fresh.forEach(id => next.delete(id))
          newIds.value = next
        }, 8000)
      }
    }

    alerts.value = records
    total.value = res.data?.total || 0
    page.value = p
  } catch (e) {
    // 轮询失败保留原数据，避免网络抖动把列表清空
    if (!silent) {
      alerts.value = []
      total.value = 0
    }
  } finally {
    if (!silent) loading.value = false
  }
}

// ---------------------------- 自动轮询 ----------------------------
// 新告警由后端 Kafka 消费后落库，前端此前只能手动点刷新才看得到。
// 这里每 10 秒静默拉一次，新告警高亮标注，无需人工干预。
let listTimer = null
const startListPolling = () => {
  stopListPolling()
  listTimer = setInterval(() => {
    if (document.hidden) return
    // 弹窗打开时不刷新列表：避免用户正在看报告时背后列表跳动
    if (dialogVisible.value) return
    loadAlerts(page.value, { silent: true })
  }, 10000)
}
const stopListPolling = () => {
  if (listTimer) {
    clearInterval(listTimer)
    listTimer = null
  }
}

// ---------------------------- 分析报告弹窗 ----------------------------
const dialogVisible = ref(false)
const currentTask = ref(null)
const taskLoading = ref(false)

const COLLAPSE_STEPS = 6
const showAllSteps = ref(false)
const visibleSteps = computed(() => {
  const steps = currentTask.value?.agentSteps || []
  return showAllSteps.value ? steps : steps.slice(0, COLLAPSE_STEPS)
})

// 步骤文本里的 [tool_name] 前缀已单独渲染成标签，这里去掉避免重复
const cleanStep = (s) => String(s || '').replace(/^\[[A-Za-z0-9_]+\]\s*/, '')

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

// 影响面 / 拓扑
const topologyChartRef = ref(null)
const impact = ref(null)
const impactService = ref('')
const hasTopology = ref(false)
let topologyChart = null

// 任务的 input 是告警描述文本（「服务 leaky-app 发生告警：...」），
// 影响面接口要的是服务名。解析不到就不画关系图，不影响报告展示。
const parseServiceFromInput = (text) => {
  if (!text) return ''
  const m = String(text).match(/服务\s+([A-Za-z0-9][A-Za-z0-9_.-]*)/)
  return m ? m[1] : ''
}

const loadImpactAndTopology = async (service) => {
  impact.value = null
  impactService.value = service || ''
  hasTopology.value = false
  if (!service) return

  try {
    const res = await getClusterImpact(service)
    impact.value = res.data || null
  } catch {
    impact.value = null // 影响面是增强展示，拿不到就只显示报告
  }

  try {
    const res = await getClusterTopology(service)
    const data = res.data || {}
    hasTopology.value = (data.nodes || []).length > 0
    if (hasTopology.value) renderTopology(data)
  } catch {
    hasTopology.value = false
  }
}

// Service → Pod 关系图：红色节点 = 异常（断流的 Service / 未就绪的 Pod）
const renderTopology = (data) => {
  nextTick(() => {
    if (!topologyChartRef.value) return
    if (topologyChart) {
      topologyChart.dispose()
      topologyChart = null
    }
    topologyChart = echarts.init(topologyChartRef.value)

    const nodes = (data.nodes || []).map(n => ({
      id: n.id,
      name: n.name,
      category: n.category === 'service' ? 0 : 1,
      symbolSize: n.category === 'service' ? 48 : 32,
      itemStyle: {
        color: n.category === 'service'
          ? (n.outage ? '#ef4444' : '#2563eb')
          : (n.ready ? '#10b981' : '#f59e0b'),
        borderColor: '#fff',
        borderWidth: 2,
        shadowBlur: 8,
        shadowColor: 'rgba(15,23,42,0.15)'
      },
      value: n.category === 'service'
        ? `就绪端点 ${n.readyEndpoints ?? '?'} / 未就绪 ${n.notReadyEndpoints ?? '?'}${n.outage ? '（已断流）' : ''}`
        : `就绪：${n.ready ? '是' : '否'}${n.deployment ? '｜Deployment ' + n.deployment : ''}`
    }))

    topologyChart.setOption({
      tooltip: {
        formatter: (p) => (p.dataType === 'edge' ? '' : `<b>${p.name}</b><br/>${p.data.value || ''}`)
      },
      legend: {
        data: ['Service', 'Pod'],
        top: 0,
        icon: 'circle',
        textStyle: { fontSize: 12, color: '#475569' }
      },
      series: [{
        type: 'graph',
        layout: 'force',
        roam: true,
        draggable: true,
        label: { show: true, position: 'right', fontSize: 11, color: '#1e293b' },
        force: { repulsion: 380, edgeLength: 120, gravity: 0.08 },
        edgeSymbol: ['none', 'arrow'],
        edgeSymbolSize: 8,
        lineStyle: { color: '#cbd5e1', curveness: 0.08, width: 1.4 },
        categories: [{ name: 'Service' }, { name: 'Pod' }],
        data: nodes,
        links: (data.links || []).map(l => ({ source: l.source, target: l.target }))
      }]
    })
  })
}

// Agent 输出的是 Markdown（表格、标题、加粗），渲染成 HTML 再展示。
// marked 默认转义危险标签，注入风险可控。
const renderedReport = computed(() => {
  const report = currentTask.value?.output
  if (!report) return ''
  try {
    return marked.parse(report)
  } catch {
    return report
  }
})

const copyReport = async () => {
  const ok = await copyText(currentTask.value?.output || '')
  ok ? ElMessage.success('报告已复制到剪贴板') : ElMessage.error('复制失败')
}

const viewAnalysis = async (row) => {
  dialogVisible.value = true
  taskLoading.value = true
  currentTask.value = null
  showAllSteps.value = false

  try {
    const res = await getTasks({ alertId: row.id, page: 1, size: 1 })
    const records = res.data?.records || []
    currentTask.value = records[0] || null
  } catch {
    ElMessage.error('查询分析任务失败')
    currentTask.value = null
  } finally {
    taskLoading.value = false
  }

  // 弹窗打开后再画图：canvas 需要元素已渲染且可见，否则宽高是 0
  await nextTick()
  loadImpactAndTopology(parseServiceFromInput(currentTask.value?.input))
}

const onDialogClosed = () => {
  if (topologyChart) {
    topologyChart.dispose()
    topologyChart = null
  }
  impact.value = null
  hasTopology.value = false
}

// ---------------------------- 发送测试告警 ----------------------------
const dialogSendVisible = ref(false)
const sending = ref(false)
const sendForm = ref({
  title: '测试告警',
  serviceName: 'nginx',
  severity: 'WARNING',
  description: '这是一条来自前端的测试告警'
})

const sendTestAlert = async () => {
  if (!sendForm.value.title?.trim()) {
    ElMessage.warning('请填写告警标题')
    return
  }
  sending.value = true
  try {
    await sendAlert({
      source: '前端测试',
      severity: sendForm.value.severity,
      title: sendForm.value.title,
      description: sendForm.value.description,
      serviceName: sendForm.value.serviceName
    })
    ElMessage.success('告警发送成功，正在触发 AI 分析')
    dialogSendVisible.value = false
    loadAlerts(1)
    loadStatusCounts()
  } catch {
    ElMessage.error('发送失败')
  } finally {
    sending.value = false
  }
}

// ---------------------------- 生命周期 ----------------------------
onMounted(() => {
  loadAlerts(1)
  loadStatusCounts()
  startListPolling()
})

onUnmounted(() => {
  stopListPolling()
  if (topologyChart) topologyChart.dispose()
})
</script>

<style scoped>
/* ---------- 顶部统计卡 ---------- */
.mini-stat {
  display: flex;
  align-items: center;
  justify-content: space-between;
  background: var(--c-surface);
  border: 1px solid var(--c-border);
  border-radius: var(--radius-lg);
  padding: 16px 18px;
  cursor: pointer;
  transition: all 0.18s;
  box-shadow: var(--shadow-sm);
  margin-bottom: 16px;
}

.mini-stat:hover {
  transform: translateY(-2px);
  box-shadow: var(--shadow-md);
  border-color: var(--c-primary-light);
}

.mini-stat__value {
  font-size: 26px;
  font-weight: 700;
  line-height: 1.1;
  color: var(--c-text);
}

.mini-stat__label {
  font-size: 12.5px;
  color: var(--c-text-muted);
  margin-top: 4px;
}

/* 原来 opacity: 0.16 让图标淡到看不见，用户会以为"图标没显示"。
   改成带底色的徽章：既清晰又不抢数字的注意力。 */
.mini-stat__icon {
  width: 44px;
  height: 44px;
  border-radius: 12px;
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 21px;
  flex-shrink: 0;
  background: var(--c-info-soft);
}

.mini-stat--primary .mini-stat__icon { background: var(--c-primary-soft); }
.mini-stat--warning .mini-stat__icon { background: var(--c-warning-soft); }
.mini-stat--success .mini-stat__icon { background: var(--c-success-soft); }

.mini-stat--primary .mini-stat__value { color: var(--c-primary); }
.mini-stat--primary .mini-stat__icon { color: var(--c-primary); }
.mini-stat--warning .mini-stat__value { color: var(--c-warning); }
.mini-stat--warning .mini-stat__icon { color: var(--c-warning); }
.mini-stat--success .mini-stat__value { color: var(--c-success); }
.mini-stat--success .mini-stat__icon { color: var(--c-success); }
.mini-stat--info .mini-stat__value { color: var(--c-info); }
.mini-stat--info .mini-stat__icon { color: var(--c-info); }

/* ---------- 工具栏与筛选 ---------- */
.toolbar {
  display: flex;
  gap: 10px;
}

.filters {
  display: flex;
  gap: 12px;
  align-items: center;
  flex-wrap: wrap;
  padding: 14px 16px;
  background: var(--c-surface-hover);
  border: 1px solid var(--c-border);
  border-radius: var(--radius-md);
}

/* ---------- 表格单元 ---------- */
.cell-title {
  display: flex;
  align-items: center;
  gap: 8px;
  min-width: 0;
}

.cell-title__text {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  font-weight: 500;
  color: var(--c-text);
}

.cell-service {
  color: var(--c-primary-dark);
  background: var(--c-primary-soft);
  padding: 2px 8px;
  border-radius: 4px;
}

.cell-desc {
  display: -webkit-box;
  -webkit-line-clamp: 2;
  line-clamp: 2;
  -webkit-box-orient: vertical;
  overflow: hidden;
  color: var(--c-text-muted);
  font-size: 12.5px;
  line-height: 1.5;
}

.cell-time {
  color: var(--c-text-secondary);
  font-size: 12.5px;
  cursor: default;
}

.text-muted {
  color: var(--c-text-muted);
}

/* 未处理行左侧红边，扫读时先看到需要注意的 */
:deep(.row-attention) td:first-child {
  box-shadow: inset 3px 0 0 var(--c-danger);
}

/* 被降噪聚合的行整体压暗，表示"已归并，不是丢失" */
:deep(.row-muted) {
  opacity: 0.72;
}

/* 自动轮询新到的告警：整行闪一下浅红，扫一眼就知道有新东西 */
:deep(.row-new) td {
  animation: alert-flash 1.5s ease-out;
}

@keyframes alert-flash {
  0% { background-color: var(--c-danger-soft); }
  100% { background-color: transparent; }
}

:deep(.row-new) td:first-child {
  box-shadow: inset 3px 0 0 var(--c-danger);
}

.pager {
  display: flex;
  justify-content: flex-end;
  margin-top: 18px;
}

/* ---------- 弹窗内区块 ---------- */
.section {
  margin-top: 22px;
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

/* ---------- 影响面 ---------- */
.impact-stats {
  margin-bottom: 12px;
}

.impact-stat {
  background: var(--c-surface-hover);
  border: 1px solid var(--c-border);
  border-radius: var(--radius-md);
  padding: 12px 14px;
  text-align: center;
}

.impact-stat--bad {
  background: var(--c-danger-soft);
  border-color: #fecaca;
}

.impact-stat__value {
  font-size: 20px;
  font-weight: 700;
  color: var(--c-text);
  line-height: 1.2;
}

.impact-stat--bad .impact-stat__value {
  color: var(--c-danger);
}

.impact-stat__label {
  font-size: 12px;
  color: var(--c-text-muted);
  margin-top: 4px;
}

.impact-summary {
  background: var(--c-warning-soft);
  border-left: 3px solid var(--c-warning);
  padding: 11px 14px;
  border-radius: 0 var(--radius-sm) var(--radius-sm) 0;
  line-height: 1.7;
  font-size: 13px;
  color: var(--c-text-secondary);
}

.topology-chart {
  height: 340px;
  margin-top: 12px;
  border: 1px solid var(--c-border);
  border-radius: var(--radius-md);
  background: var(--c-surface);
}

.chart-empty {
  margin-top: 12px;
  padding: 40px 0;
  text-align: center;
  color: var(--c-text-muted);
  font-size: 13px;
  background: var(--c-surface-hover);
  border-radius: var(--radius-md);
}

/* ---------- 推理步骤 ---------- */
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
</style>
