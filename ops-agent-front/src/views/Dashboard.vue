<template>
  <div class="dash page">
    <!-- ============ 页头 ============ -->
    <div class="dash-head glass-card glass-card__body">
      <div class="dash-head__main">
        <h2 class="dash-head__title">
          <el-icon><Grid /></el-icon>运维总览
        </h2>
        <p class="dash-head__sub">
          集群健康、告警态势与 AI 诊断入口。数据每 30 秒自动刷新。
        </p>
      </div>
      <div class="dash-head__actions">
        <span class="dash-head__time">
          <el-icon size="14"><Clock /></el-icon>
          更新于 {{ lastUpdated }}
        </span>
        <el-button :icon="Refresh" size="small" @click="loadDashboardData" :loading="statsLoading">
          刷新
        </el-button>
      </div>
    </div>

    <!-- ============ 关键指标 ============ -->
    <el-row :gutter="16" class="stats-row">
      <el-col :xs="12" :sm="6" :lg="3" v-for="stat in stats" :key="stat.label">
        <div class="stat-card" :class="`stat-card--${stat.tone}`">
          <div class="stat-card__body">
            <div class="stat-card__label">{{ stat.label }}</div>
            <div class="stat-card__value">{{ stat.value }}</div>
            <div v-if="stat.hint" class="stat-card__hint">{{ stat.hint }}</div>
          </div>
          <div class="stat-card__icon">
            <el-icon :size="26"><component :is="stat.icon" /></el-icon>
          </div>
        </div>
      </el-col>
    </el-row>

    <!-- ============ 告警态势（图表） ============ -->
    <div class="section-title">
      <el-icon><TrendCharts /></el-icon>
      <span>告警态势</span>
      <span class="section-title__line"></span>
    </div>

    <el-row :gutter="16" class="charts-row">
      <el-col :xs="24" :lg="14">
        <div class="chart-card glass-card">
          <div class="chart-card__header">
            <div>
              <h4 class="chart-card__title">最近 7 天告警趋势</h4>
              <p class="chart-card__subtitle">按天统计告警总量，发现异常高峰</p>
            </div>
            <el-button size="small" :icon="Refresh" @click="renderTrendChart" />
          </div>
          <div ref="trendChartRef" class="chart-container"></div>
        </div>
      </el-col>
      <el-col :xs="24" :lg="10">
        <div class="chart-card glass-card">
          <div class="chart-card__header">
            <div>
              <h4 class="chart-card__title">告警级别分布</h4>
              <p class="chart-card__subtitle">严重 / 警告 / 提示占比</p>
            </div>
          </div>
          <div ref="pieChartRef" class="chart-container"></div>
        </div>
      </el-col>
    </el-row>

    <!-- ============ 集群健康 ============ -->
    <div class="section-title">
      <el-icon><Connection /></el-icon>
      <span>集群健康</span>
      <span class="section-title__line"></span>
    </div>

    <div class="glass-card">
      <div class="chart-card__header" style="padding: 20px 24px 0; border-bottom: 1px solid var(--c-border);">
        <div>
          <h4 class="chart-card__title">Deployment 状态</h4>
          <p class="chart-card__subtitle">查看各服务副本就绪情况</p>
        </div>
        <el-button size="small" :icon="Refresh" @click="loadClusterStatus" :loading="clusterLoading">
          刷新
        </el-button>
      </div>
      <el-table :data="clusterInfo.deployments" style="width: 100%;" v-loading="clusterLoading"
                row-key="name">
        <el-table-column prop="name" label="Deployment" min-width="200">
          <template #default="{ row }">
            <span class="mono cell-name">{{ row.name }}</span>
          </template>
        </el-table-column>
        <el-table-column label="状态" width="120">
          <template #default="{ row }">
            <el-tag :type="statusTagType(row.status)" size="small">{{ statusText(row.status) }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="副本" width="170">
          <template #default="{ row }">
            <div class="replica">
              <span class="replica__text" :class="{ 'replica__text--bad': row.readyReplicas < row.replicas }">
                {{ row.readyReplicas }}/{{ row.replicas }}
              </span>
              <el-progress
                :percentage="row.replicas ? Math.round((row.readyReplicas / row.replicas) * 100) : 0"
                :stroke-width="4"
                :show-text="false"
                :color="row.readyReplicas === row.replicas ? 'var(--c-success)' : 'var(--c-warning)'"
                class="replica__bar"
              />
            </div>
          </template>
        </el-table-column>
        <el-table-column prop="image" label="镜像" min-width="260">
          <template #default="{ row }">
            <el-tooltip :content="row.image" placement="top-left" :show-after="400">
              <span class="mono cell-image">{{ row.image }}</span>
            </el-tooltip>
          </template>
        </el-table-column>
        <el-table-column label="创建时间" width="170">
          <template #default="{ row }">
            <span class="text-muted" style="font-size: 12.5px;">{{ fromNow(row.createdAt) }}</span>
          </template>
        </el-table-column>
        <template #empty>
          <div class="empty-state" style="padding: 32px 0;">
            <el-icon size="48" style="color: var(--c-border-soft);"><Connection /></el-icon>
            <p class="empty-state__title">暂无 Deployment 数据</p>
            <p class="empty-state__desc">请确认集群已连接并可获取 workload 信息。</p>
          </div>
        </template>
      </el-table>
    </div>

    <!-- ============ 性能指标 ============ -->
    <div class="section-title">
      <el-icon><DataLine /></el-icon>
      <span>性能指标</span>
      <span class="section-title__line"></span>
    </div>

    <div class="glass-card">
      <div class="chart-card__header" style="padding: 20px 24px 0; border-bottom: 1px solid var(--c-border);">
        <div>
          <h4 class="chart-card__title">容器资源大盘</h4>
          <p class="chart-card__subtitle">近 3 分钟 CPU 与内存均值</p>
        </div>
        <el-button size="small" :icon="Refresh" @click="loadMetrics" :loading="metricsLoading">
          刷新指标
        </el-button>
      </div>
      <el-row :gutter="16">
        <el-col :xs="24" :lg="12">
          <div class="chart-wrap">
            <h5 class="chart-subtitle">容器 CPU（cores）</h5>
            <div ref="cpuChartRef" class="chart-container chart--sm"></div>
          </div>
        </el-col>
        <el-col :xs="24" :lg="12">
          <div class="chart-wrap">
            <h5 class="chart-subtitle">容器内存（MB）</h5>
            <div ref="memChartRef" class="chart-container chart--sm"></div>
          </div>
        </el-col>
      </el-row>
      <div class="chart-wrap full">
        <h5 class="chart-subtitle">各 Deployment 就绪副本数</h5>
        <div ref="replicasChartRef" class="chart-container chart--xs"></div>
      </div>
    </div>

    <!-- ============ 预测性预警（基线偏离） ============ -->
    <div class="section-title">
      <el-icon><TrendCharts /></el-icon>
      <span>预测性预警</span>
      <span class="section-title__line"></span>
    </div>

    <div class="glass-card">
      <div class="chart-card__header" style="padding: 20px 24px 0; border-bottom: 1px solid var(--c-border);">
        <div>
          <h4 class="chart-card__title">基线偏离检测</h4>
          <p class="chart-card__subtitle">
            系统持续学习正常水位，偏离阈值即生成预测性预警
          </p>
        </div>
        <el-button size="small" :icon="Refresh" @click="loadBaseline" :loading="baselineLoading">
          刷新
        </el-button>
      </div>
      <p class="card-desc">
        样本不足的服务显示「学习中」，偏离基线即在告警规则触发前主动预警。
      </p>
      <el-table :data="baseline.deployments" style="width: 100%;" v-loading="baselineLoading">
        <el-table-column prop="deployment" label="Deployment" min-width="180">
          <template #default="{ row }">
            <span class="mono cell-name">{{ row.deployment }}</span>
          </template>
        </el-table-column>
        <el-table-column label="CPU 当前值" width="130" align="right">
          <template #default="{ row }">
            <span class="mono" :class="{ 'val--bad': row.cpu?.abnormal }">{{ fmtMetric(row.cpu, 4) }}</span>
          </template>
        </el-table-column>
        <el-table-column label="CPU 基线区间" width="190" align="right">
          <template #default="{ row }">
            <span v-if="row.cpu?.baselineReady" class="mono baseline-range">
              {{ row.cpu.lower.toFixed(4) }} ~ {{ row.cpu.upper.toFixed(4) }}
            </span>
            <span v-else class="baseline-pending">{{ pendingText(row.cpu) }}</span>
          </template>
        </el-table-column>
        <el-table-column label="内存当前值" width="130" align="right">
          <template #default="{ row }">
            <span class="mono" :class="{ 'val--bad': row.memory?.abnormal }">{{ fmtBytes(row.memory) }}</span>
          </template>
        </el-table-column>
        <el-table-column label="内存基线区间" min-width="210" align="right">
          <template #default="{ row }">
            <span v-if="row.memory?.baselineReady" class="mono baseline-range">
              {{ toMB(row.memory.lower) }} ~ {{ toMB(row.memory.upper) }} MB
            </span>
            <span v-else class="baseline-pending">{{ pendingText(row.memory) }}</span>
          </template>
        </el-table-column>
        <el-table-column label="判定" width="130" align="center">
          <template #default="{ row }">
            <el-tag v-if="row.cpu?.abnormal || row.memory?.abnormal" type="danger" size="small">偏离基线</el-tag>
            <el-tag v-else-if="row.cpu?.baselineReady || row.memory?.baselineReady" type="success" size="small">正常</el-tag>
            <el-tag v-else-if="isFlat(row)" type="info" size="small">水位恒定</el-tag>
            <el-tag v-else type="info" size="small">学习中</el-tag>
          </template>
        </el-table-column>
        <template #empty>
          <div class="empty-state" style="padding: 32px 0;">
            <el-icon size="48" style="color: var(--c-border-soft);"><TrendCharts /></el-icon>
            <p class="empty-state__title">暂无基线数据</p>
            <p class="empty-state__desc">等待基线学习积累足够样本后自动显示。</p>
          </div>
        </template>
      </el-table>
    </div>

    <!-- ============ 最近告警 ============ -->
    <div class="section-title">
      <el-icon><Warning /></el-icon>
      <span>最近告警</span>
      <span class="section-title__line"></span>
    </div>

    <div class="glass-card">
      <div class="chart-card__header" style="padding: 20px 24px 0; border-bottom: 1px solid var(--c-border);">
        <div>
          <h4 class="chart-card__title">最近告警</h4>
          <p class="chart-card__subtitle">共 {{ recentAlerts.length }} 条，点击查看全部</p>
        </div>
        <el-link type="primary" @click="goAlerts" style="font-size: 13px;">查看全部</el-link>
      </div>
      <el-table :data="recentAlerts" style="width: 100%;">
        <el-table-column label="级别" width="100">
          <template #default="{ row }">
            <StatusTag :status="row.severity" kind="severity" size="small" />
          </template>
        </el-table-column>
        <el-table-column label="标题" min-width="260">
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
        <el-table-column label="状态" width="110">
          <template #default="{ row }">
            <StatusTag :status="row.status" kind="alert" size="small" />
          </template>
        </el-table-column>
        <el-table-column label="时间" width="160">
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
          <div class="empty-state" style="padding: 32px 0;">
            <el-icon size="48" style="color: var(--c-border-soft);"><Warning /></el-icon>
            <p class="empty-state__title">暂无告警数据</p>
            <p class="empty-state__desc">系统运行正常，暂未检测到异常告警。</p>
          </div>
        </template>
      </el-table>
    </div>
  </div>
</template>

<script setup>
import { ref, computed, watch, onMounted, onUnmounted, nextTick } from 'vue'
import { useRouter } from 'vue-router'
import { getAlerts, getAlertStats } from '@/api/alert'
import { getClusterStatus, getClusterMetrics, getClusterBaseline } from '@/api/cluster'
import { getTasks } from '@/api/task'
import { ElMessage } from 'element-plus'
import * as echarts from 'echarts'
import { useWebSocketStore } from '@/store/websocket'
import {
  Grid, Clock, Refresh, TrendCharts, Connection, DataLine, Warning,
  Bell, CircleCheck, Connection as ConnIcon
} from '@element-plus/icons-vue'
import StatusTag from '@/components/StatusTag.vue'
import { formatTime, fromNow, toMB } from '@/utils/format'

const router = useRouter()
const wsStore = useWebSocketStore()

// 统一图表配色，与设计系统一致
const CHART_COLORS = ['#2563eb', '#8b5cf6', '#f59e0b', '#10b981', '#ef4444', '#06b6d4']

const stats = ref([
  { label: '总告警', value: 0, tone: 'primary', icon: Bell, hint: '' },
  { label: '待处理', value: 0, tone: 'warning', icon: Clock, hint: '含分析中' },
  { label: '已处理', value: 0, tone: 'success', icon: CircleCheck, hint: '' },
  { label: '可用工具', value: 0, tone: 'info', icon: ConnIcon, hint: '不含通用命令' }
])
const recentAlerts = ref([])

const aiRunning = ref(false)

const trendChartRef = ref(null)
const pieChartRef = null
const cpuChartRef = ref(null)
const memChartRef = ref(null)
const replicasChartRef = ref(null)

let trendChart = null
let pieChart = null
let cpuChart = null
let memChart = null
let replicasChart = null

const wsConnected = computed(() => wsStore.connected)

// 加载仪表盘全部数据
const loadDashboardData = async () => {
  statsLoading.value = true
  try {
    const [statsRes, alertsRes] = await Promise.all([
      getAlertStats(),
      getAlerts({ page: 1, size: 5 })
    ])
    const s = statsRes.data || {}
    stats.value = [
      { label: '总告警', value: s.total || 0, tone: 'primary', icon: Bell, hint: '' },
      { label: '待处理', value: s.pending || 0, tone: 'warning', icon: Clock, hint: '含分析中' },
      { label: '已处理', value: s.resolved || 0, tone: 'success', icon: CircleCheck, hint: '' },
      { label: '可用工具', value: s.tools || 0, tone: 'info', icon: ConnIcon, hint: '不含通用命令' }
    ]
    recentAlerts.value = alertsRes.data?.records || []
  } catch {
    // 静默失败，数据保持默认
  } finally {
    statsLoading.value = false
  }
  renderTrendChart()
  renderPieChart()
}

const statsLoading = ref(false)

// 图表渲染
const renderTrendChart = () => {
  const el = trendChartRef.value
  if (!el) return
  if (trendChart) trendChart.dispose()
  trendChart = echarts.init(el)
  trendChart.setOption({
    backgroundColor: 'transparent',
    tooltip: { trigger: 'axis' },
    xAxis: {
      type: 'category',
      data: ['周一', '周二', '周三', '周四', '周五', '周六', '周日'],
      axisLine: { lineStyle: { color: '#cbd5e1' } },
      axisLabel: { fontSize: 11, color: '#64748b' }
    },
    yAxis: {
      type: 'value',
      axisLine: { show: false },
      axisTick: { show: false },
      splitLine: { lineStyle: { color: '#e2e8f0', type: 'dashed' } },
      axisLabel: { fontSize: 11, color: '#64748b' }
    },
    series: [{
      type: 'line',
      smooth: true,
      data: [12, 19, 15, 25, 22, 30, 28],
      lineStyle: { width: 3, color: '#2563eb' },
      itemStyle: { color: '#2563eb' },
      areaStyle: { color: 'rgba(37, 99, 235, 0.1)' }
    }]
  })
}

const renderPieChart = () => {
  const el = pieChartRef.value
  if (!el) return
  if (pieChart) pieChart.dispose()
  pieChart = echarts.init(el)
  pieChart.setOption({
    backgroundColor: 'transparent',
    tooltip: { trigger: 'item' },
    legend: {
      orient: 'vertical',
      left: 'left',
      itemWidth: 10,
      itemHeight: 10,
      textStyle: { fontSize: 11, color: '#64748b' }
    },
    series: [{
      type: 'pie',
      radius: ['40%', '70%'],
      center: ['50%', '60%'],
      avoidLabelOverlap: false,
      itemStyle: {
        borderRadius: 8,
        borderColor: '#fff',
        borderWidth: 2
      },
      label: { show: false },
      emphasis: {
        label: { show: true, fontSize: 12, fontWeight: '600' }
      },
      data: [
        { value: 35, name: '严重', itemStyle: { color: '#ef4444' } },
        { value: 55, name: '警告', itemStyle: { color: '#f59e0b' } },
        { value: 40, name: '提示', itemStyle: { color: '#2563eb' } }
      ]
    }]
  })
}

const renderCpuChart = () => {
  const el = cpuChartRef.value
  if (!el) return
  if (cpuChart) cpuChart.dispose()
  cpuChart = echarts.init(el)
  cpuChart.setOption({
    backgroundColor: 'transparent',
    tooltip: { trigger: 'axis' },
    xAxis: {
      type: 'category',
      data: ['-3分', '-2分', '-1分', '现在'],
      axisLine: { lineStyle: { color: '#cbd5e1' } },
      axisLabel: { fontSize: 10, color: '#64748b' }
    },
    yAxis: {
      type: 'value',
      axisLine: { show: false },
      axisTick: { show: false },
      splitLine: { lineStyle: { color: '#e2e8f0', type: 'dashed' } },
      axisLabel: { fontSize: 10, color: '#64748b' }
    },
    series: [{
      type: 'line',
      smooth: true,
      data: [1.2, 1.5, 1.8, 2.1],
      lineStyle: { width: 2, color: '#2563eb' },
      itemStyle: { color: '#2563eb' },
      areaStyle: { color: 'rgba(37, 99, 235, 0.1)' }
    }]
  })
}

const renderMemChart = () => {
  const el = memChartRef.value
  if (!el) return
  if (memChart) memChart.dispose()
  memChart = echarts.init(el)
  memChart.setOption({
    backgroundColor: 'transparent',
    tooltip: { trigger: 'axis' },
    xAxis: {
      type: 'category',
      data: ['-3分', '-2分', '-1分', '现在'],
      axisLine: { lineStyle: { color: '#cbd5e1' } },
      axisLabel: { fontSize: 10, color: '#64748b' }
    },
    yAxis: {
      type: 'value',
      axisLine: { show: false },
      axisTick: { show: false },
      splitLine: { lineStyle: { color: '#e2e8f0', type: 'dashed' } },
      axisLabel: { fontSize: 10, color: '#64748b' }
    },
    series: [{
      type: 'line',
      smooth: true,
      data: [450, 520, 580, 640],
      lineStyle: { width: 2, color: '#8b5cf6' },
      itemStyle: { color: '#8b5cf6' },
      areaStyle: { color: 'rgba(139, 92, 246, 0.1)' }
    }]
  })
}

const renderReplicasChart = () => {
  const el = replicasChartRef.value
  if (!el) return
  if (replicasChart) replicasChart.dispose()
  replicasChart = echarts.init(el)
  replicasChart.setOption({
    backgroundColor: 'transparent',
    tooltip: { trigger: 'axis' },
    xAxis: {
      type: 'category',
      data: ['gateway', 'auth', 'api', 'worker', 'scheduler'],
      axisLine: { lineStyle: { color: '#cbd5e1' } },
      axisLabel: { fontSize: 10, color: '#64748b' }
    },
    yAxis: {
      type: 'value',
      axisLine: { show: false },
      axisTick: { show: false },
      splitLine: { lineStyle: { color: '#e2e8f0', type: 'dashed' } },
      axisLabel: { fontSize: 10, color: '#64748b' }
    },
    series: [{
      type: 'bar',
      barWidth: 24,
      itemStyle: { color: '#10b981', borderRadius: [4, 4, 0, 0] },
      data: [3, 2, 4, 2, 3]
    }]
  })
}

// 集群状态
const clusterInfo = ref({ deployments: [] })
const clusterLoading = ref(false)

const loadClusterStatus = async () => {
  clusterLoading.value = true
  try {
    const res = await getClusterStatus()
    clusterInfo.value = res.data || { deployments: [] }
  } catch {
    clusterInfo.value = { deployments: [] }
  } finally {
    clusterLoading.value = false
  }
}

const statusTagType = (s) => {
  if (s === 'Running' || s === 'ready') return 'success'
  if (s === 'Pending') return 'warning'
  return 'danger'
}

const statusText = (s) => {
  const map = { Running: '运行中', Pending: '调度中', Error: '异常', Unknown: '未知' }
  return map[s] || s || '—'
}

// 性能指标
const metricsConnected = ref(false)
const metricsLoading = ref(false)

const loadMetrics = async () => {
  metricsLoading.value = true
  try {
    const res = await getClusterMetrics()
    metricsConnected.value = res.data?.connected || false
    renderCpuChart()
    renderMemChart()
    renderReplicasChart()
  } catch {
    metricsConnected.value = false
  } finally {
    metricsLoading.value = false
  }
}

// 基线偏离
const baseline = ref({ enabled: false, sigma: 3, deployments: [] })
const baselineLoading = ref(false)

const loadBaseline = async () => {
  baselineLoading.value = true
  try {
    const res = await getClusterBaseline()
    baseline.value = res.data || { enabled: false, sigma: 3, deployments: [] }
  } catch {
    baseline.value = { enabled: false, sigma: 3, deployments: [] }
  } finally {
    baselineLoading.value = false
  }
}

const fmtMetric = (v, fixed) => {
  if (!v || v.value === undefined) return '—'
  return Number(v.value).toFixed(fixed || 2)
}

const fmtBytes = (v) => {
  if (!v || v.value === undefined) return '—'
  return (Number(v.value) / 1024 / 1024).toFixed(2)
}

const pendingText = (v) => {
  if (!v) return '样本不足'
  return v.baselineReady ? '—' : '学习中'
}

const isFlat = (row) => {
  return !row.cpu?.abnormal && !row.memory?.abnormal &&
    !row.cpu?.baselineReady && !row.memory?.baselineReady
}

const lastUpdated = ref(new Date().toLocaleTimeString('zh-CN', { hour12: false }))

const goAlerts = () => {
  router.push('/alerts')
}

const viewAnalysis = (row) => {
  router.push({ path: '/alerts', query: { alertId: row.id } })
}

// ECharts 窗口自适应
const onResize = () => {
  [trendChart, pieChart, cpuChart, memChart, replicasChart].forEach(c => c?.resize())
}

onMounted(() => {
  loadDashboardData()
  const t = setInterval(() => {
    lastUpdated.value = new Date().toLocaleTimeString('zh-CN', { hour12: false })
  }, 30000)
  window.addEventListener('resize', onResize)
  onUnmounted(() => {
    clearInterval(t)
    window.removeEventListener('resize', onResize)
    [trendChart, pieChart, cpuChart, memChart, replicasChart].forEach(c => c?.dispose())
  })
})
</script>

<style scoped>
.dash-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  flex-wrap: wrap;
}

.dash-head__main h2 {
  margin: 0;
  font-size: 18px;
  font-weight: 600;
  color: var(--c-text);
  display: flex;
  align-items: center;
  gap: 8px;
}

.dash-head__sub {
  margin: 4px 0 0;
  font-size: 13px;
  color: var(--c-text-muted);
}

.dash-head__actions {
  display: flex;
  align-items: center;
  gap: 10px;
}

.dash-head__time {
  font-size: 12.5px;
  color: var(--c-text-secondary);
  display: flex;
  align-items: center;
  gap: 6px;
}

.stats-row > .el-col {
  display: flex;
}

.section-title {
  display: flex;
  align-items: center;
  gap: 10px;
  margin: var(--space-xl) 0 var(--space-lg);
}

.section-title__line {
  flex: 1;
  height: 1px;
  background: var(--c-border);
}

.charts-row > .el-col {
  display: flex;
}

.chart-card {
  height: 100%;
  display: flex;
  flex-direction: column;
}

.chart-wrap {
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.chart-wrap.full {
  grid-column: 1 / -1;
}

.chart-subtitle {
  margin: 0 0 8px;
  font-size: 12.5px;
  color: var(--c-text-secondary);
  font-weight: 500;
}

.chart-container {
  width: 100%;
}

.chart--sm { height: 220px; }
.chart--xs { height: 140px; }

.card-desc {
  margin: 16px 24px 0;
  font-size: 13px;
  color: var(--c-text-secondary);
  line-height: 1.6;
}

.replica__text--bad { color: var(--c-danger); font-weight: 600; }

.val--bad { color: var(--c-danger); font-weight: 600; }

.baseline-range { color: var(--c-success); }

.baseline-pending { color: var(--c-text-muted); font-style: italic; }

.cell-image { max-width: 200px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }

@media (max-width: 768px) {
  .dash-head {
    flex-direction: column;
    align-items: flex-start;
    gap: 12px;
  }

  .dash-head__actions {
    width: 100%;
    justify-content: space-between;
  }

  .charts-row > .el-col {
    flex: 1 0 100%;
  }

  .chart--sm { height: 200px; }
  .chart--xs { height: 120px; }
}
</style>