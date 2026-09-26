<template>
  <div class="page">
    <!-- ============ 页头 ============ -->
    <div class="dash-head">
      <div>
        <h2 class="dash-head__title">运维总览</h2>
        <p class="dash-head__sub">
          集群健康、告警态势与 AI 诊断入口。数据每 30 秒自动刷新。
        </p>
      </div>
      <div class="dash-head__actions">
        <span class="dash-head__time">更新于 {{ lastUpdated }}</span>
        <el-button :icon="Refresh" size="small" @click="loadDashboardData" :loading="statsLoading">
          刷新
        </el-button>
      </div>
    </div>

    <!-- ============ 关键指标 ============ -->
    <el-row :gutter="16">
      <el-col :xs="12" :sm="6" v-for="stat in stats" :key="stat.label">
        <div class="stat-card" :class="`stat-card--${stat.tone}`">
          <div class="stat-card__body">
            <div class="stat-card__label">{{ stat.label }}</div>
            <div class="stat-card__value">{{ stat.value }}</div>
            <div v-if="stat.hint" class="stat-card__hint">{{ stat.hint }}</div>
          </div>
          <!-- 图标从 14% 透明度提到 100%：原来淡到几乎看不见，
               用户反馈"图标不显示"其实就是这个 —— 渲染了，但看不清。 -->
          <div class="stat-card__icon">
            <el-icon><component :is="stat.icon" /></el-icon>
          </div>
        </div>
      </el-col>
    </el-row>

    <!-- ============ 一键 AI 诊断 ============ -->
    <div class="section-title">
      <el-icon><MagicStick /></el-icon>
      <span>AI 智能诊断</span>
      <span class="section-title__line"></span>
    </div>

    <el-card class="diag-card">
      <div class="page-header" style="margin-bottom: 0;">
        <div>
          <h3 class="page-title">
            <el-icon><MagicStick /></el-icon>一键 AI 诊断
          </h3>
          <p class="page-subtitle">
            对整个集群做一次全服务健康巡检，agent 自主调用工具排查并输出综合报告。
          </p>
        </div>
        <el-button type="primary" :icon="Promotion" @click="startAiDiagnosis" :loading="aiRunning">
          {{ aiRunning ? '正在触发…' : '开始 AI 诊断' }}
        </el-button>
      </div>

      <div v-if="diagnosisTaskId" class="diag-panel">
        <div class="diag-panel__head">
          <div class="diag-panel__info">
            <span class="text-muted">任务</span>
            <CopyId :id="diagnosisTaskId" />
            <el-tag :type="wsConnected ? 'success' : 'info'" size="small" effect="light">
              <span class="dot" :class="{ 'dot--on': wsConnected }"></span>
              {{ wsConnected ? '实时连接中' : '等待连接…' }}
            </el-tag>
          </div>
          <el-link type="primary" :icon="Document" @click="openTaskLog(diagnosisTaskId)">
            查看完整报告
          </el-link>
        </div>

        <!-- 实时推理日志 -->
        <div class="log-toolbar">
          <h4 class="log-toolbar__title">实时推理日志</h4>
          <span class="text-muted" style="font-size: 12px;">
            {{ filteredMessages.length }} 条
          </span>
        </div>
        <div ref="logContainerRef" class="log-container">
          <div v-for="(msg, idx) in filteredMessages" :key="idx" class="log-item">
            <span class="log-time">{{ formatTime(msg.timestamp) }}</span>
            <el-tag size="small" effect="plain" class="log-step">
              {{ msg.stepName || msg.type || '步骤' }}
            </el-tag>
            <span class="log-content">{{ msg.content }}</span>
          </div>
          <div v-if="filteredMessages.length === 0" class="log-waiting">
            <span class="thinking__dot"></span>
            <span class="thinking__dot"></span>
            <span class="thinking__dot"></span>
            <span style="margin-left: 8px;">等待推理步骤…</span>
          </div>
        </div>
      </div>
    </el-card>

    <!-- ============ 趋势与分布 ============ -->
    <div class="section-title">
      <el-icon><TrendCharts /></el-icon>
      <span>告警态势</span>
      <span class="section-title__line"></span>
    </div>

    <el-row :gutter="16">
      <el-col :xs="24" :lg="14">
        <el-card>
          <h4 class="chart-title">最近 7 天告警趋势</h4>
          <div ref="trendChartRef" class="chart chart--md"></div>
        </el-card>
      </el-col>
      <el-col :xs="24" :lg="10">
        <el-card>
          <h4 class="chart-title">告警级别分布</h4>
          <div ref="pieChartRef" class="chart chart--md"></div>
        </el-card>
      </el-col>
    </el-row>

    <!-- ============ 集群状态 ============ -->
    <div class="section-title">
      <el-icon><Grid /></el-icon>
      <span>集群健康</span>
      <span class="section-title__line"></span>
    </div>

    <el-card>
      <div class="card-head">
        <h3 class="page-title" style="font-size: 15px;">
          <el-icon><Grid /></el-icon>集群状态
          <el-tag v-if="clusterInfo.connected" size="small" type="success" effect="light">
            namespace: {{ clusterInfo.namespace }}
          </el-tag>
          <el-tag v-else size="small" type="danger" effect="light">集群不可用</el-tag>
        </h3>
        <el-button size="small" :icon="Refresh" @click="loadClusterStatus" :loading="clusterLoading">
          刷新
        </el-button>
      </div>

      <el-table :data="clusterInfo.deployments" style="width: 100%;" v-loading="clusterLoading"
                row-key="name">
        <el-table-column prop="name" label="Deployment" min-width="180">
          <template #default="{ row }">
            <span class="mono cell-name">{{ row.name }}</span>
          </template>
        </el-table-column>
        <el-table-column label="状态" width="110">
          <template #default="{ row }">
            <el-tag :type="statusTag(row.status)" size="small">{{ statusText(row.status) }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="副本" width="140">
          <template #default="{ row }">
            <div class="replica">
              <span class="replica__text" :class="{ 'replica__text--bad': row.readyReplicas < row.replicas }">
                {{ row.readyReplicas }}/{{ row.replicas }}
              </span>
              <el-progress
                :percentage="row.replicas ? Math.round((row.readyReplicas / row.replicas) * 100) : 0"
                :stroke-width="4"
                :show-text="false"
                :color="row.readyReplicas === row.replicas ? '#10b981' : '#f59e0b'"
                class="replica__bar"
              />
            </div>
          </template>
        </el-table-column>
        <el-table-column prop="image" label="镜像" min-width="220">
          <template #default="{ row }">
            <el-tooltip :content="row.image" placement="top-left" :show-after="400">
              <span class="mono cell-image">{{ row.image }}</span>
            </el-tooltip>
          </template>
        </el-table-column>
        <el-table-column label="创建时间" width="160">
          <template #default="{ row }">
            <span class="text-muted" style="font-size: 12.5px;">{{ fromNow(row.createdAt) }}</span>
          </template>
        </el-table-column>
        <template #empty>
          <el-empty description="暂无 Deployment" />
        </template>
      </el-table>
    </el-card>

    <!-- ============ Prometheus 指标 ============ -->
    <div class="section-title">
      <el-icon><DataLine /></el-icon>
      <span>性能指标</span>
      <span class="section-title__line"></span>
    </div>

    <el-card>
      <div class="card-head">
        <h3 class="page-title" style="font-size: 15px;">
          <el-icon><DataLine /></el-icon>指标大盘
          <el-tag v-if="metricsConnected" size="small" type="success" effect="light">Prometheus 已连接</el-tag>
          <el-tag v-else size="small" type="danger" effect="light">Prometheus 不可用</el-tag>
        </h3>
        <el-button size="small" :icon="Refresh" @click="loadMetrics" :loading="metricsLoading">刷新指标</el-button>
      </div>

      <el-row :gutter="16">
        <el-col :xs="24" :lg="12">
          <h4 class="chart-title">容器 CPU（近 3 分钟均值，cores）</h4>
          <div ref="cpuChartRef" class="chart chart--sm"></div>
        </el-col>
        <el-col :xs="24" :lg="12">
          <h4 class="chart-title">容器内存（working set，MB）</h4>
          <div ref="memChartRef" class="chart chart--sm"></div>
        </el-col>
      </el-row>

      <h4 class="chart-title" style="margin-top: 12px;">各 Deployment 就绪副本数</h4>
      <div ref="replicasChartRef" class="chart chart--xs"></div>
    </el-card>

    <!-- ============ 基线偏离 ============ -->
    <div class="section-title">
      <el-icon><TrendCharts /></el-icon>
      <span>预测性预警</span>
      <span class="section-title__line"></span>
    </div>

    <el-card>
      <div class="card-head">
        <h3 class="page-title" style="font-size: 15px;">
          <el-icon><TrendCharts /></el-icon>基线偏离
          <el-tag v-if="baseline.enabled" size="small" type="warning" effect="light">
            {{ baseline.sigma }}σ 阈值
          </el-tag>
          <el-tag v-else size="small" type="info" effect="light">基线学习已关闭</el-tag>
        </h3>
        <el-button size="small" :icon="Refresh" @click="loadBaseline" :loading="baselineLoading">刷新</el-button>
      </div>

      <p class="card-desc">
        系统持续学习各服务的正常水位，偏离超过阈值即在告警规则触发前主动生成预测性预警。
        样本不足的服务显示「积累中」。
      </p>

      <el-table :data="baseline.deployments" style="width: 100%;" v-loading="baselineLoading">
        <el-table-column prop="deployment" label="Deployment" min-width="160">
          <template #default="{ row }">
            <span class="mono cell-name">{{ row.deployment }}</span>
          </template>
        </el-table-column>

        <el-table-column label="CPU 当前值" width="120" align="right">
          <template #default="{ row }">
            <span class="mono" :class="{ 'val--bad': row.cpu?.abnormal }">{{ fmtMetric(row.cpu, 4) }}</span>
          </template>
        </el-table-column>

        <el-table-column label="CPU 基线区间" width="180" align="right">
          <template #default="{ row }">
            <span v-if="row.cpu?.baselineReady" class="mono baseline-range">
              {{ row.cpu.lower.toFixed(4) }} ~ {{ row.cpu.upper.toFixed(4) }}
            </span>
            <span v-else class="baseline-pending">{{ pendingText(row.cpu) }}</span>
          </template>
        </el-table-column>

        <el-table-column label="内存当前值" width="120" align="right">
          <template #default="{ row }">
            <span class="mono" :class="{ 'val--bad': row.memory?.abnormal }">{{ fmtBytes(row.memory) }}</span>
          </template>
        </el-table-column>

        <el-table-column label="内存基线区间" min-width="190" align="right">
          <template #default="{ row }">
            <span v-if="row.memory?.baselineReady" class="mono baseline-range">
              {{ toMB(row.memory.lower) }} ~ {{ toMB(row.memory.upper) }} MB
            </span>
            <span v-else class="baseline-pending">{{ pendingText(row.memory) }}</span>
          </template>
        </el-table-column>

        <el-table-column label="判定" width="120" align="center">
          <template #default="{ row }">
            <el-tag v-if="row.cpu?.abnormal || row.memory?.abnormal" type="danger" size="small">偏离基线</el-tag>
            <el-tag v-else-if="row.cpu?.baselineReady || row.memory?.baselineReady" type="success" size="small">正常</el-tag>
            <el-tag v-else-if="isFlat(row)" type="info" size="small">水位恒定</el-tag>
            <el-tag v-else type="info" size="small">学习中</el-tag>
          </template>
        </el-table-column>
      </el-table>
    </el-card>

    <!-- ============ 最近告警 ============ -->
    <el-card>
      <div class="card-head">
        <h3 class="page-title" style="font-size: 15px;">
          <el-icon><Warning /></el-icon>最近告警
        </h3>
        <el-link type="primary" @click="goAlerts">查看全部</el-link>
      </div>

      <el-table :data="recentAlerts" style="width: 100%;">
        <el-table-column label="级别" width="92">
          <template #default="{ row }">
            <StatusTag :status="row.severity" kind="severity" size="small" />
          </template>
        </el-table-column>
        <el-table-column label="标题" min-width="240">
          <template #default="{ row }">
            <el-tooltip :content="row.title" placement="top-left" :show-after="400">
              <span class="cell-title__text">{{ row.title || '—' }}</span>
            </el-tooltip>
          </template>
        </el-table-column>
        <el-table-column label="服务" width="160">
          <template #default="{ row }">
            <span v-if="row.serviceName && row.serviceName !== 'unknown'" class="mono cell-service">
              {{ row.serviceName }}
            </span>
            <span v-else class="text-muted">—</span>
          </template>
        </el-table-column>
        <el-table-column label="状态" width="104">
          <template #default="{ row }">
            <StatusTag :status="row.status" kind="alert" size="small" />
          </template>
        </el-table-column>
        <el-table-column label="时间" width="150">
          <template #default="{ row }">
            <el-tooltip :content="formatTime(row.timestamp || row.createTime)" placement="top">
              <span class="cell-time">{{ fromNow(row.timestamp || row.createTime) }}</span>
            </el-tooltip>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="100" fixed="right">
          <template #default="{ row }">
            <el-button type="primary" link size="small" @click.stop="viewAnalysis(row)">查看分析</el-button>
          </template>
        </el-table-column>
        <template #empty>
          <el-empty description="暂无告警" />
        </template>
      </el-table>
    </el-card>
  </div>
</template>

<script setup>
import { ref, computed, watch, onMounted, onUnmounted, nextTick } from 'vue'
import { useRouter } from 'vue-router'
import { getAlerts, getAlertStats, sendAlert } from '@/api/alert'
import { getClusterStatus, getClusterMetrics, getClusterBaseline } from '@/api/cluster'
import { getTools } from '@/api/tool'
import { getTasks } from '@/api/task'
import { ElMessage } from 'element-plus'
import * as echarts from 'echarts'
import { useWebSocketStore } from '@/store/websocket'
import {
  MagicStick, Promotion, Document, Grid, DataLine, TrendCharts,
  Warning, Refresh, Bell, Clock, Loading, CircleCheck, Connection
} from '@element-plus/icons-vue'
import StatusTag from '@/components/StatusTag.vue'
import CopyId from '@/components/CopyId.vue'
import { formatTime, fromNow, toMB } from '@/utils/format'

const router = useRouter()
const wsStore = useWebSocketStore()

// 统一图表配色，与设计系统一致（ECharts 不认 CSS 变量，这里取同值）
const CHART_COLORS = ['#2563eb', '#8b5cf6', '#f59e0b', '#10b981', '#ef4444', '#06b6d4']

const stats = ref([
  { label: '总告警', value: 0, tone: 'primary', icon: Bell, hint: '' },
  { label: '待处理', value: 0, tone: 'warning', icon: Clock, hint: '含分析中' },
  { label: '已处理', value: 0, tone: 'success', icon: CircleCheck, hint: '' },
  { label: '可用工具', value: 0, tone: 'info', icon: Connection, hint: '不含通用命令' }
])
const recentAlerts = ref([])

const aiRunning = ref(false)
const diagnosisTaskId = ref('')

const trendChartRef = ref(null)
const pieChartRef = ref(null)
let trendChart = null
let pieChart = null

const logContainerRef = ref(null)

const wsConnected = computed(() => wsStore.connected)
const filteredMessages = computed(() =>
  wsStore.messages.filter(m => !diagnosisTaskId.value || m.taskId === diagnosisTaskId.value)
)

watch(filteredMessages, () => {
  nextTick(() => {
    if (logContainerRef.value) {
      logContainerRef.value.scrollTop = logContainerRef.value.scrollHeight
    }
  })
}, { deep: true })

const dailyStats = ref([])
const severityStats = ref([])

// ---------- 集群状态 ----------
const clusterInfo = ref({ connected: false, namespace: '', deployments: [] })
const clusterLoading = ref(false)

const statusTag = (s) => {
  if (s === 'running') return 'success'
  if (s === 'degraded') return 'danger'
  if (s === 'pending') return 'warning'
  return 'info'
}
const statusText = (s) => {
  if (s === 'running') return '运行中'
  if (s === 'degraded') return '降级'
  if (s === 'pending') return '等待中'
  if (s === 'stopped') return '已停止'
  return s || '未知'
}

// ---------- Prometheus 指标 ----------
const cpuChartRef = ref(null)
const memChartRef = ref(null)
const replicasChartRef = ref(null)
const metricsConnected = ref(false)
const metricsLoading = ref(false)
let cpuChart = null
let memChart = null
let replicasChart = null

const loadMetrics = async () => {
  try {
    metricsLoading.value = true
    const res = await getClusterMetrics()
    const data = res.data || {}
    metricsConnected.value = !!data.connected
    renderMetrics(data)
  } catch {
    metricsConnected.value = false
  } finally {
    metricsLoading.value = false
  }
}

// 从 Prometheus 结果行里提取 "pod名 -> 数值"，按 pod 排序取前 N
const buildPodSeries = (rows, max = 12, transform = v => v) => {
  if (!Array.isArray(rows)) return { pods: [], values: [] }
  const items = rows
    .map(r => ({ pod: (r.metric?.pod || r.metric?.deployment || '?'), value: transform(parseFloat(r.value || 0)) }))
    .filter(i => !Number.isNaN(i.value))
    .sort((a, b) => b.value - a.value)
    .slice(0, max)
  return { pods: items.map(i => i.pod), values: items.map(i => Number(i.value.toFixed(3))) }
}

const buildReplicaSeries = (rows) => {
  if (!Array.isArray(rows)) return { deps: [], values: [] }
  const items = rows.map(r => ({ dep: r.metric?.deployment || '?', value: parseInt(r.value || 0, 10) }))
  return { deps: items.map(i => i.dep), values: items.map(i => i.value) }
}

// 柱状图通用配置：统一网格留白、坐标轴字体、圆角柱
const barOption = (pods, values, unit, color) => ({
  tooltip: { trigger: 'axis', axisPointer: { type: 'shadow' } },
  grid: { left: 60, right: 20, bottom: 70, top: 20 },
  xAxis: {
    type: 'category',
    data: pods,
    axisLabel: { rotate: 30, interval: 0, fontSize: 11, color: '#94a3b8' },
    axisLine: { lineStyle: { color: '#e2e8f0' } },
    axisTick: { show: false }
  },
  yAxis: {
    type: 'value',
    name: unit,
    nameTextStyle: { fontSize: 11, color: '#94a3b8' },
    axisLabel: { fontSize: 11, color: '#94a3b8' },
    splitLine: { lineStyle: { color: '#f1f5f9' } }
  },
  series: [{
    type: 'bar',
    data: values,
    barMaxWidth: 34,
    itemStyle: { color, borderRadius: [4, 4, 0, 0] }
  }]
})

const renderMetrics = (data) => {
  nextTick(() => {
    if (!cpuChartRef.value || !memChartRef.value || !replicasChartRef.value) return

    if (!cpuChart) cpuChart = echarts.init(cpuChartRef.value)
    const cpu = buildPodSeries(data.containerCpu, 10, v => v)
    cpuChart.setOption(barOption(cpu.pods, cpu.values, 'cores', '#2563eb'))

    if (!memChart) memChart = echarts.init(memChartRef.value)
    const mem = buildPodSeries(data.containerMemory, 10, v => v / 1024 / 1024)
    memChart.setOption(barOption(mem.pods, mem.values, 'MB', '#8b5cf6'))

    if (!replicasChart) replicasChart = echarts.init(replicasChartRef.value)
    const rep = buildReplicaSeries(data.deploymentReplicas)
    const opt = barOption(rep.deps, rep.values, 'replicas', '#10b981')
    opt.yAxis.minInterval = 1
    replicasChart.setOption(opt)
  })
}

// ---------- 基线 ----------
const baseline = ref({ enabled: true, sigma: 3, deployments: [] })
const baselineLoading = ref(false)

const loadBaseline = async () => {
  try {
    baselineLoading.value = true
    const res = await getClusterBaseline()
    baseline.value = res.data || { enabled: true, sigma: 3, deployments: [] }
  } catch {
    baseline.value = { enabled: false, sigma: 3, deployments: [] }
  } finally {
    baselineLoading.value = false
  }
}

// CPU 是 cores，小数位多，固定 4 位才看得清量级
const fmtMetric = (m, digits = 4) => {
  if (!m || m.current === undefined || m.current === null) return '—'
  return Number(m.current).toFixed(digits)
}

const fmtBytes = (m) =>
  (m && m.current !== undefined && m.current !== null ? toMB(m.current) + ' MB' : '—')

// 基线未就绪有两种原因：样本还在积累，或信号长期恒定（如无流量时 CPU 恒为 0）。
// 后者等再久也不会可判定，不能提示"积累中"让人一直等。
const pendingText = (m) => {
  if (!m) return '—'
  if (m.reason === 'FLAT_SIGNAL') return '水位恒定，无需预警'
  return `积累中（${m.samples || 0}/${m.minSamples || 12}）`
}

const isFlat = (row) =>
  (row.cpu?.reason === 'FLAT_SIGNAL' || row.memory?.reason === 'FLAT_SIGNAL') &&
  !row.cpu?.baselineReady && !row.memory?.baselineReady

const loadClusterStatus = async () => {
  try {
    clusterLoading.value = true
    const res = await getClusterStatus()
    clusterInfo.value = res.data || { connected: false, namespace: '', deployments: [] }
  } catch {
    clusterInfo.value = { connected: false, namespace: '', deployments: [] }
  } finally {
    clusterLoading.value = false
  }
}

// ---------- 概览与图表 ----------
const statsLoading = ref(false)
const lastUpdated = ref('—')

const loadDashboardData = async () => {
  statsLoading.value = true
  try {
    const alertsRes = await getAlerts({ page: 1, size: 5 })
    recentAlerts.value = alertsRes.data?.records || []
    stats.value[0].value = alertsRes.data?.total || 0

    const statsRes = await getAlertStats(7)
    const data = statsRes.data || {}
    dailyStats.value = data.daily || []
    severityStats.value = data.bySeverity || []

    const byStatus = data.byStatus || {}
    stats.value[1].value = (byStatus.PENDING || 0) + (byStatus.ANALYZING || 0) + (byStatus.FIRING || 0)
    stats.value[2].value = byStatus.RESOLVED || 0

    renderCharts()
    lastUpdated.value = new Date().toLocaleTimeString('zh-CN', { hour12: false })
  } catch {
    recentAlerts.value = []
  } finally {
    statsLoading.value = false
  }
}

// ---------- 自动刷新 ----------
// 首页此前只在挂载时拉一次数据，用户放着不动看到的就是进入那一刻的快照。
// 30 秒轮询一次，既保持"实时感"又不会给后端造成压力。
let dashTimer = null
const startDashPolling = () => {
  stopDashPolling()
  dashTimer = setInterval(() => {
    // 页面在后台时不刷：省资源，也避免回到前台时图表闪动
    if (document.hidden) return
    loadDashboardData()
  }, 30000)
}
const stopDashPolling = () => {
  if (dashTimer) {
    clearInterval(dashTimer)
    dashTimer = null
  }
}

const renderCharts = () => {
  nextTick(() => {
    if (!trendChartRef.value || !pieChartRef.value) return

    const dates = []
    const severitySet = new Set()
    dailyStats.value.forEach(item => {
      dates.push(item.date)
      severitySet.add(item.severity)
    })
    const uniqueDates = [...new Set(dates)].sort()

    const series = [...severitySet].map((sev, i) => {
      const data = uniqueDates.map(d => {
        const item = dailyStats.value.find(it => it.date === d && it.severity === sev)
        return item ? item.count : 0
      })
      return {
        name: sev,
        type: 'bar',
        stack: 'total',
        data,
        barMaxWidth: 32,
        itemStyle: { color: CHART_COLORS[i % CHART_COLORS.length] }
      }
    })

    if (trendChart) trendChart.dispose()
    trendChart = echarts.init(trendChartRef.value)
    trendChart.setOption({
      tooltip: { trigger: 'axis', axisPointer: { type: 'shadow' } },
      legend: { top: 0, icon: 'circle', textStyle: { fontSize: 11, color: '#475569' } },
      grid: { left: 50, right: 20, bottom: 40, top: 40 },
      xAxis: {
        type: 'category',
        data: uniqueDates,
        axisLabel: { fontSize: 11, color: '#94a3b8' },
        axisLine: { lineStyle: { color: '#e2e8f0' } },
        axisTick: { show: false }
      },
      yAxis: {
        type: 'value',
        axisLabel: { fontSize: 11, color: '#94a3b8' },
        splitLine: { lineStyle: { color: '#f1f5f9' } }
      },
      series
    })

    if (pieChart) pieChart.dispose()
    pieChart = echarts.init(pieChartRef.value)
    pieChart.setOption({
      tooltip: { trigger: 'item', formatter: '{b}: {c} ({d}%)' },
      legend: { orient: 'vertical', left: 'left', icon: 'circle', textStyle: { fontSize: 11, color: '#475569' } },
      color: CHART_COLORS,
      series: [{
        name: '告警级别',
        type: 'pie',
        radius: ['45%', '68%'],
        center: ['62%', '50%'],
        avoidLabelOverlap: true,
        itemStyle: { borderColor: '#fff', borderWidth: 2, borderRadius: 4 },
        label: { show: true, formatter: '{b}\n{c}', fontSize: 11, color: '#475569' },
        data: severityStats.value.map(item => ({ value: item.count, name: item.severity }))
      }]
    })
  })
}

// ---------- 一键诊断 ----------
const startAiDiagnosis = async () => {
  aiRunning.value = true
  try {
    await sendAlert({
      id: `diag_${Date.now()}`,
      severity: 'INFO',
      title: 'Dashboard 一键 AI 诊断',
      description: '全服务健康检查（主动巡检）：请检查所有核心服务的状态、日志、资源使用情况，并给出综合报告。',
      serviceName: 'all',
      host: 'unknown'
    })
    ElMessage.success('AI 诊断已触发，正在建立实时连接')

    // 等后端把告警转成任务后再取 taskId（简单轮询）
    await new Promise(r => setTimeout(r, 2000))
    const res = await getAlerts({ page: 1, size: 1 })
    const alert = res.data?.records?.[0]
    if (alert) {
      const taskRes = await getTasks({ alertId: alert.id, page: 1, size: 1 })
      const task = taskRes.data?.records?.[0]
      if (task) {
        diagnosisTaskId.value = task.id
        wsStore.clearMessages()
        wsStore.connect(task.id)
      }
    }
    loadDashboardData()
  } catch {
    ElMessage.error('AI 诊断触发失败')
  } finally {
    aiRunning.value = false
  }
}

const openTaskLog = (taskId) => {
  router.push({ path: '/tasks', query: { id: taskId } })
}

const goAlerts = () => router.push('/alerts')

const viewAnalysis = async (row) => {
  try {
    const res = await getTasks({ alertId: row.id, page: 1, size: 1 })
    const records = res.data?.records || []
    if (records.length === 0) {
      ElMessage.info('该告警还没有关联的分析任务')
    } else {
      router.push({ path: '/tasks', query: { id: records[0].id } })
    }
  } catch {
    ElMessage.error('查询分析任务失败')
  }
}

// 可用工具数：数后端下发的 agentExposed 标记，不靠前端写死工具名
const loadToolCount = async () => {
  try {
    const res = await getTools()
    const list = res.data || []
    stats.value[3].value = list.filter(t => t.agentExposed).length
  } catch {
    // 工具接口失败保留默认值
  }
}

onMounted(() => {
  loadDashboardData()
  loadClusterStatus()
  loadMetrics()
  loadBaseline()
  loadToolCount()
  startDashPolling()
})

onUnmounted(() => {
  stopDashPolling()
  // 不再 wsStore.disconnect()：离开首页就掐掉 WebSocket 会导致审批提醒失效。
  // 连接由 Layout 常驻维持，只在登出时关闭。
  if (trendChart) trendChart.dispose()
  if (pieChart) pieChart.dispose()
  if (cpuChart) cpuChart.dispose()
  if (memChart) memChart.dispose()
  if (replicasChart) replicasChart.dispose()
})
</script>

<style scoped>
/* ---------- 页头 ---------- */
.dash-head {
  display: flex;
  align-items: flex-end;
  justify-content: space-between;
  margin-bottom: 18px;
  gap: 16px;
  flex-wrap: wrap;
}

.dash-head__title {
  font-size: 21px;
  font-weight: 600;
  color: var(--c-text);
  margin: 0;
  letter-spacing: -0.01em;
}

.dash-head__sub {
  font-size: 13px;
  color: var(--c-text-muted);
  margin: 5px 0 0;
}

.dash-head__actions {
  display: flex;
  align-items: center;
  gap: 12px;
}

.dash-head__time {
  font-size: 12px;
  color: var(--c-text-muted);
}

/* ---------- 分节标题 ---------- */
/* 长页面靠它切分区块，比一路平铺卡片好读得多 */
.section-title {
  display: flex;
  align-items: center;
  gap: 8px;
  margin: 26px 0 14px;
  font-size: 14px;
  font-weight: 600;
  color: var(--c-text-secondary);
}

.section-title .el-icon {
  color: var(--c-primary);
  font-size: 16px;
}

/* 标题右侧延伸一条淡线，形成"分隔"而非"悬浮" */
.section-title__line {
  flex: 1;
  height: 1px;
  background: linear-gradient(90deg, var(--c-border), transparent);
  margin-left: 4px;
}

/* ---------- 概览卡 ---------- */
.stat-card {
  display: flex;
  align-items: center;
  justify-content: space-between;
  background: var(--c-surface);
  border: 1px solid var(--c-border);
  border-radius: var(--radius-lg);
  padding: 18px 20px;
  box-shadow: var(--shadow-sm);
  margin-bottom: 16px;
  transition: all 0.18s;
  position: relative;
  overflow: hidden;
}

.stat-card::before {
  content: '';
  position: absolute;
  left: 0;
  top: 0;
  bottom: 0;
  width: 3px;
}

.stat-card--primary::before { background: var(--c-primary); }
.stat-card--warning::before { background: var(--c-warning); }
.stat-card--success::before { background: var(--c-success); }
.stat-card--info::before { background: var(--c-info); }

.stat-card:hover {
  box-shadow: var(--shadow-md);
  transform: translateY(-2px);
}

.stat-card__label {
  font-size: 12.5px;
  color: var(--c-text-muted);
  margin-bottom: 6px;
}

.stat-card__value {
  font-size: 28px;
  font-weight: 700;
  line-height: 1.1;
  color: var(--c-text);
}

.stat-card__hint {
  font-size: 11.5px;
  color: var(--c-text-muted);
  margin-top: 4px;
}

/* 图标做成带底色的徽章，不再用 14% 透明度的大水印。
   原设计把图标压到几乎透明，用户反馈"图标不显示"就是这里 —— 它一直在，
   只是淡到看不见。现在改为清晰的圆角色块，既是装饰也是视觉锚点。 */
.stat-card__icon {
  width: 46px;
  height: 46px;
  border-radius: 12px;
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 22px;
  flex-shrink: 0;
}

.stat-card--primary .stat-card__icon {
  color: var(--c-primary);
  background: var(--c-primary-soft);
}

.stat-card--warning .stat-card__icon {
  color: var(--c-warning);
  background: var(--c-warning-soft);
}

.stat-card--success .stat-card__icon {
  color: var(--c-success);
  background: var(--c-success-soft);
}

.stat-card--info .stat-card__icon {
  color: var(--c-info);
  background: var(--c-info-soft);
}

/* ---------- 卡片头与说明 ---------- */
.card-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  margin-bottom: 14px;
}

.card-desc {
  color: var(--c-text-muted);
  font-size: 12.5px;
  line-height: 1.7;
  margin: 0 0 14px;
}

.chart-title {
  margin: 0 0 10px;
  font-size: 13.5px;
  font-weight: 600;
  color: var(--c-text-secondary);
}

/* 图表容器固定高度，避免 ECharts 初始化时高度为 0 */
.chart { width: 100%; }
.chart--md { height: 300px; }
.chart--sm { height: 260px; }
.chart--xs { height: 220px; }

/* ---------- 诊断面板 ---------- */
.diag-panel {
  margin-top: 16px;
  padding: 16px;
  background: var(--c-surface-hover);
  border: 1px solid var(--c-border);
  border-radius: var(--radius-md);
}

.diag-panel__head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  margin-bottom: 14px;
  flex-wrap: wrap;
}

.diag-panel__info {
  display: flex;
  align-items: center;
  gap: 10px;
  font-size: 12.5px;
}

.dot {
  width: 6px;
  height: 6px;
  border-radius: 50%;
  background: var(--c-text-muted);
  display: inline-block;
  margin-right: 5px;
}

.dot--on {
  background: var(--c-success);
  box-shadow: 0 0 0 3px rgba(16, 185, 129, 0.2);
}

.log-toolbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 8px;
}

.log-toolbar__title {
  margin: 0;
  font-size: 13px;
  font-weight: 600;
  color: var(--c-text-secondary);
}

.log-container {
  height: 260px;
  overflow-y: auto;
  background: var(--c-sidebar-from);
  border-radius: var(--radius-md);
  padding: 10px 14px;
  font-family: 'JetBrains Mono', Consolas, monospace;
  font-size: 12.5px;
}

.log-item {
  display: flex;
  gap: 12px;
  line-height: 1.65;
  padding: 5px 0;
  border-bottom: 1px solid rgba(255, 255, 255, 0.06);
  align-items: flex-start;
}

.log-item:last-child { border-bottom: none; }

.log-time {
  color: #64748b;
  width: 132px;
  flex-shrink: 0;
  font-size: 11.5px;
}

.log-step { flex-shrink: 0; }

.log-content {
  color: #cbd5e1;
  white-space: pre-wrap;
  word-break: break-word;
  flex: 1;
}

.log-waiting {
  display: flex;
  align-items: center;
  color: #64748b;
  padding: 16px 4px;
  font-size: 12.5px;
}

.thinking__dot {
  width: 5px;
  height: 5px;
  border-radius: 50%;
  background: var(--c-primary-light);
  margin-right: 4px;
  animation: bounce 1.2s infinite;
}

.thinking__dot:nth-child(2) { animation-delay: 0.15s; }
.thinking__dot:nth-child(3) { animation-delay: 0.3s; }

@keyframes bounce {
  0%, 60%, 100% { transform: translateY(0); opacity: 0.35; }
  30% { transform: translateY(-4px); opacity: 1; }
}

/* ---------- 表格单元 ---------- */
.cell-name {
  color: var(--c-text);
  font-weight: 500;
}

.cell-image,
.cell-service {
  color: var(--c-primary-dark);
}

.cell-service {
  background: var(--c-primary-soft);
  padding: 2px 8px;
  border-radius: 4px;
}

.cell-title__text {
  display: block;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  font-weight: 500;
  color: var(--c-text);
}

.cell-time {
  color: var(--c-text-secondary);
  font-size: 12.5px;
  cursor: default;
}

.text-muted {
  color: var(--c-text-muted);
}

/* 副本数：数字 + 进度条双重视觉 */
.replica {
  display: flex;
  flex-direction: column;
  gap: 4px;
}

.replica__text {
  font-family: 'JetBrains Mono', Consolas, monospace;
  font-size: 12.5px;
  color: var(--c-success);
  font-weight: 600;
}

.replica__text--bad {
  color: var(--c-warning);
}

.replica__bar {
  width: 72px;
}

/* 基线表数值 */
.val--bad {
  color: var(--c-danger);
  font-weight: 700;
}

.baseline-range {
  color: var(--c-text-secondary);
  font-size: 12px;
}

.baseline-pending {
  color: var(--c-text-muted);
  font-size: 12px;
}
</style>
