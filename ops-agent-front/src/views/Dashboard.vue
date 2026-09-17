<template>
  <div>
    <!-- 顶部概览卡片 -->
    <el-row :gutter="20">
      <el-col :span="6" v-for="stat in stats" :key="stat.label">
        <el-card class="stat-card">
          <div class="stat-value">{{ stat.value }}</div>
          <div class="stat-label">{{ stat.label }}</div>
        </el-card>
      </el-col>
    </el-row>

    <!-- 一键 AI 诊断 -->
    <el-card style="margin-top: 20px;">
      <div style="display:flex; justify-content:space-between; align-items:center;">
        <h3>一键 AI 诊断</h3>
        <el-button type="primary" @click="startAiDiagnosis" :loading="aiRunning">开始 AI 诊断</el-button>
      </div>
      <div v-if="diagnosisTaskId" style="margin-top:12px; color:#606266;">
        任务 ID：{{ diagnosisTaskId }}，{{ wsConnected ? '实时连接中' : '等待连接...' }}
        <el-link type="primary" @click="openTaskLog(diagnosisTaskId)">查看完整报告</el-link>
      </div>

      <!-- 实时推理日志面板 -->
      <div v-if="diagnosisTaskId" style="margin-top:16px;">
        <h4 style="margin:0 0 8px;">实时推理日志</h4>
        <div ref="logContainerRef" class="log-container">
          <div v-for="(msg, idx) in filteredMessages" :key="idx" class="log-item">
            <span class="log-time">{{ formatTime(msg.timestamp) }}</span>
            <span class="log-step">{{ msg.stepName || msg.type || '' }}</span>
            <span class="log-content">{{ msg.content }}</span>
          </div>
          <div v-if="filteredMessages.length === 0" style="color:#909399; padding:12px;">等待推理步骤...</div>
        </div>
      </div>
    </el-card>

    <!-- ECharts 统计 -->
    <el-row :gutter="20" style="margin-top:20px;">
      <el-col :span="12">
        <el-card>
          <h4 style="margin:0 0 12px;">最近 7 天告警趋势</h4>
          <div ref="trendChartRef" style="height:300px;"></div>
        </el-card>
      </el-col>
      <el-col :span="12">
        <el-card>
          <h4 style="margin:0 0 12px;">告警级别分布</h4>
          <div ref="pieChartRef" style="height:300px;"></div>
        </el-card>
      </el-col>
    </el-row>

    <!-- 集群状态 -->
    <el-card style="margin-top: 20px;">
      <div style="display:flex; justify-content:space-between; align-items:center;">
        <h3 style="margin:0;">集群状态
          <el-tag v-if="clusterInfo.connected" size="small" type="success" style="margin-left:8px;">
            {{ clusterInfo.namespace }}
          </el-tag>
          <el-tag v-else size="small" type="danger" style="margin-left:8px;">集群不可用</el-tag>
        </h3>
        <el-button size="small" @click="loadClusterStatus" :loading="clusterLoading">刷新</el-button>
      </div>
      <el-table :data="clusterInfo.deployments" style="width: 100%; margin-top: 12px;" v-loading="clusterLoading">
        <el-table-column prop="name" label="Deployment" min-width="140" />
        <el-table-column prop="status" label="状态" width="110">
          <template #default="{ row }">
            <el-tag :type="statusTag(row.status)" size="small">{{ statusText(row.status) }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="副本" width="110">
          <template #default="{ row }">
            {{ row.readyReplicas }}/{{ row.replicas }}
          </template>
        </el-table-column>
        <el-table-column prop="image" label="镜像" min-width="200" show-overflow-tooltip />
        <el-table-column prop="createdAt" label="创建时间" width="180">
          <template #default="{ row }">
            {{ formatTime(row.createdAt) }}
          </template>
        </el-table-column>
      </el-table>
    </el-card>

    <!-- 最近告警 -->
    <el-card style="margin-top: 20px;">
      <h3>最近告警</h3>
      <el-table :data="recentAlerts" style="width: 100%">
        <el-table-column prop="title" label="标题" />
        <el-table-column prop="severity" label="级别" width="100">
          <template #default="{ row }">
            <el-tag :type="row.severity === 'CRITICAL' ? 'danger' : 'warning'">{{ row.severity }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="status" label="状态" width="120">
          <template #default="{ row }">
            <el-tag :type="row.status === 'RESOLVED' ? 'success' : 'info'">{{ row.status }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="timestamp" label="时间" width="180" />
        <el-table-column label="操作" width="120">
          <template #default="{ row }">
            <el-button type="text" size="small" @click="viewAnalysis(row)">查看分析</el-button>
          </template>
        </el-table-column>
      </el-table>
    </el-card>
  </div>
</template>

<script setup>
import { ref, computed, watch, onMounted, onUnmounted, nextTick } from 'vue'
import { useRouter } from 'vue-router'
import { getAlerts, getAlertStats, sendAlert } from '@/api/alert'
import { getClusterStatus } from '@/api/cluster'
import { getTools } from '@/api/tool'
import { getTasks } from '@/api/task'
import { ElMessage } from 'element-plus'
import * as echarts from 'echarts'
import { useWebSocketStore } from '@/store/websocket'

const router = useRouter()
const wsStore = useWebSocketStore()

const stats = ref([
  { label: '总告警', value: 0 },
  { label: '待处理', value: 0 },
  { label: '已处理', value: 0 },
  { label: '在线工具', value: 4 }
])
const recentAlerts = ref([])

// AI 诊断相关
const aiRunning = ref(false)
const diagnosisTaskId = ref('')

// ECharts 引用
const trendChartRef = ref(null)
const pieChartRef = ref(null)
let trendChart = null
let pieChart = null

// 实时日志容器
const logContainerRef = ref(null)

// WebSocket 状态与消息（只显示当前诊断任务的）
const wsConnected = computed(() => wsStore.connected)
const filteredMessages = computed(() =>
  wsStore.messages.filter(m => !diagnosisTaskId.value || m.taskId === diagnosisTaskId.value)
)

// 时间戳格式化：ISO 字符串截到秒即可
const formatTime = (ts) => {
  if (!ts) return ''
  return String(ts).replace('T', ' ').slice(0, 19)
}

// 新消息到达时滚动到底部
watch(filteredMessages, () => {
  nextTick(() => {
    if (logContainerRef.value) {
      logContainerRef.value.scrollTop = logContainerRef.value.scrollHeight
    }
  })
}, { deep: true })

// 统计数据
const dailyStats = ref([])
const severityStats = ref([])

// 集群状态
const clusterInfo = ref({ connected: false, namespace: '', deployments: [] })
const clusterLoading = ref(false)

// Deployment 状态 -> 标签颜色/文本
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

const loadClusterStatus = async () => {
  try {
    clusterLoading.value = true
    const res = await getClusterStatus()
    clusterInfo.value = res.data || { connected: false, namespace: '', deployments: [] }
  } catch (e) {
    // request.js 拦截器已经弹过错误提示，这里保持"集群不可用"的兜底状态
    clusterInfo.value = { connected: false, namespace: '', deployments: [] }
  } finally {
    clusterLoading.value = false
  }
}

const loadDashboardData = async () => {
  try {
    // 1. 最近告警
    const alertsRes = await getAlerts({ page: 1, size: 5 })
    const records = alertsRes.data?.records || []
    recentAlerts.value = records
    stats.value[0].value = alertsRes.data?.total || 0

    // 2. 统计接口
    const statsRes = await getAlertStats(7)
    const data = statsRes.data || {}
    dailyStats.value = data.daily || []
    severityStats.value = data.bySeverity || []

    // 3. 概览卡片接真数据（改造前"待处理/已处理/在线工具"是硬编码假数字）
    const byStatus = data.byStatus || {}
    stats.value[1].value = (byStatus.PENDING || 0) + (byStatus.ANALYZING || 0)
    stats.value[2].value = byStatus.RESOLVED || 0

    renderCharts()
  } catch (e) {
    // 统计或告警接口失败：保留默认值，图表显示为空
    recentAlerts.value = []
  }
}

// 渲染 ECharts
const renderCharts = () => {
  nextTick(() => {
    if (!trendChartRef.value || !pieChartRef.value) return

    // 准备趋势数据
    const dates = []
    const severitySet = new Set()
    dailyStats.value.forEach(item => {
      dates.push(item.date)
      severitySet.add(item.severity)
    })

    // 去重排序日期
    const uniqueDates = [...new Set(dates)].sort()

    // 构造每种 severity 的 series
    const series = [...severitySet].map(sev => {
      const data = uniqueDates.map(d => {
        const item = dailyStats.value.find(i => i.date === d && i.severity === sev)
        return item ? item.count : 0
      })
      return { name: sev, type: 'bar', stack: 'total', data }
    })

    // 告警趋势图
    if (trendChart) trendChart.dispose()
    trendChart = echarts.init(trendChartRef.value)
    trendChart.setOption({
      tooltip: { trigger: 'axis', axisPointer: { type: 'shadow' } },
      legend: { top: 0 },
      xAxis: { type: 'category', data: uniqueDates },
      yAxis: { type: 'value' },
      series
    })

    // 饼图
    if (pieChart) pieChart.dispose()
    pieChart = echarts.init(pieChartRef.value)
    pieChart.setOption({
      tooltip: { trigger: 'item' },
      legend: { orient: 'vertical', left: 'left' },
      series: [{
        name: '告警级别',
        type: 'pie',
        radius: '60%',
        data: severityStats.value.map(item => ({ value: item.count, name: item.severity }))
      }]
    })
  })
}

// 一键 AI 诊断
const startAiDiagnosis = async () => {
  aiRunning.value = true

  try {
    // 发送一个综合健康检查告警触发 AI 分析
    await sendAlert({
      id: `diag_${Date.now()}`,
      severity: 'INFO',
      title: 'Dashboard 一键 AI 诊断',
      description: '全服务健康检查（主动巡检）：请检查所有核心服务的状态、日志、资源使用情况，并给出综合报告。',
      serviceName: 'all',
      host: 'unknown'
    })
    ElMessage.success('AI 诊断任务已触发，请查看最近告警')

    // 等待告警生成任务 ID（简单轮询）
    await new Promise(r => setTimeout(r, 2000))
    const res = await getAlerts({ page: 1, size: 1 })
    const alert = res.data?.records?.[0]
    if (alert) {
      const taskRes = await getTasks({ alertId: alert.id, page: 1, size: 1 })
      const task = taskRes.data?.records?.[0]
      if (task) {
        diagnosisTaskId.value = task.id
        // 自动连接 WebSocket 实时接收推理步骤
        wsStore.clearMessages()
        wsStore.connect(task.id)
        ElMessage.success(`实时连接已建立，任务 ${task.id}`)
      }
    }
  } catch (e) {
    ElMessage.error('AI 诊断触发失败')
  } finally {
    aiRunning.value = false
  }
}

const openTaskLog = (taskId) => {
  router.push({ path: '/tasks', query: { id: taskId } })
}

const viewAnalysis = async (row) => {
  try {
    const res = await getTasks({ alertId: row.id, page: 1, size: 1 })
    const records = res.data?.records || []
    if (records.length === 0) {
      ElMessage.info('该告警还没有关联的分析任务')
    } else {
      router.push({ path: '/tasks', query: { id: records[0].id } })
    }
  } catch (e) {
    ElMessage.error('查询分析任务失败')
  }
}

onMounted(() => {
  loadDashboardData()
  loadClusterStatus()
  loadToolCount()
})

// 在线工具数：工具列表的实际条数（改造前硬编码 4）
const loadToolCount = async () => {
  try {
    const res = await getTools()
    stats.value[3].value = res.data?.length || 0
  } catch (e) {
    // 工具接口失败保留默认值即可
  }
}

onUnmounted(() => {
  wsStore.disconnect()
  if (trendChart) trendChart.dispose()
  if (pieChart) pieChart.dispose()
})
</script>

<style scoped>
.stat-card .stat-value {
  font-size: 28px;
  font-weight: bold;
}
.stat-card .stat-label {
  color: #909399;
  margin-top: 6px;
}
.log-container {
  height: 260px;
  overflow-y: auto;
  background: #f5f7fa;
  border: 1px solid #e4e7ed;
  border-radius: 4px;
  padding: 8px 12px;
  font-family: monospace;
  font-size: 13px;
}
.log-item {
  display: flex;
  gap: 12px;
  line-height: 1.6;
  border-bottom: 1px dashed #e4e7ed;
  padding: 4px 0;
}
.log-item:last-child { border-bottom: none; }
.log-time { color: #909399; width: 170px; flex-shrink: 0; }
.log-step { color: #409eff; width: 140px; flex-shrink: 0; }
.log-content { white-space: pre-wrap; word-break: break-word; }
</style>