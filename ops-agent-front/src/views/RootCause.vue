<template>
  <div class="page">
    <el-card>
      <div class="page-header">
        <div>
          <h3 class="page-title">
            <el-icon><Connection /></el-icon>告警根因图谱
          </h3>
          <p class="page-subtitle">
            基于已完成任务的推理步骤、服务关联、告警触发关系自动绘制传播链与根因候选。
          </p>
        </div>
        <div class="toolbar">
          <el-input v-model="deployment" placeholder="按服务过滤" clearable style="width:220px;" />
          <el-button type="primary" :icon="Search" :loading="loading" @click="loadGraph">生成图谱</el-button>
          <el-button :icon="Refresh" @click="loadGraph">刷新</el-button>
        </div>
      </div>

      <div v-if="graph.nodes?.length" class="graph-wrap">
        <div ref="graphRef" class="graph"></div>
        <div class="graph-legend">
          <span><i class="dot dot--service"></i>Service</span>
          <span><i class="dot dot--alert"></i>告警</span>
          <span><i class="dot dot--step"></i>步骤</span>
          <span><i class="dot dot--task"></i>任务</span>
        </div>
      </div>
      <el-empty v-else-if="!loading" description="暂无根因图谱数据。运行过 AI 诊断后这里会生成传播链" />
    </el-card>

    <el-card style="margin-top:16px;">
      <div class="page-header">
        <div>
          <h3 class="page-title" style="font-size:15px;">
            <el-icon><List /></el-icon>历史相似故障推荐
          </h3>
          <span class="text-muted">输入服务名/关键词，推荐历史诊断任务用于二次处置</span>
        </div>
        <el-button size="small" type="primary" :icon="Search" @click="loadSimilar">推荐</el-button>
      </div>

      <div class="similar-form">
        <el-input v-model="similarDeployment" placeholder="服务名（可选）" clearable style="width:220px;" />
        <el-input v-model="similarKeyword" placeholder="关键词，如 OOM / CrashLoop" clearable style="width:220px;" />
      </div>

      <el-table :data="similarTasks" v-loading="similarLoading" style="margin-top:14px;">
        <el-table-column label="任务 ID" width="170">
          <template #default="{ row }">
            <span class="mono">{{ row.id?.slice(0, 16) || '—' }}</span>
          </template>
        </el-table-column>
        <el-table-column prop="service" label="服务" width="150" />
        <el-table-column prop="status" label="状态" width="110">
          <template #default="{ row }">
            <el-tag :type="row.status === 'SUCCESS' ? 'success' : row.status === 'REGRESSED' ? 'danger' : 'info'"
                    size="small">{{ row.status }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="关键词" min-width="180">
          <template #default="{ row }">
            <el-tag v-for="kw in row.keywords" :key="kw" size="small" effect="plain" class="mono kw">
              {{ kw }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="recommendation" label="推荐说明" min-width="260" />
      </el-table>
    </el-card>
  </div>
</template>

<script setup>
import { ref, onMounted, nextTick } from 'vue'
import { getRootCauseGraph, getSimilarTasks } from '@/api/intelligence'
import * as echarts from 'echarts'
import { Connection, Search, Refresh, List } from '@element-plus/icons-vue'

const deployment = ref('')
const similarDeployment = ref('')
const similarKeyword = ref('')
const loading = ref(false)
const similarLoading = ref(false)
const graph = ref({ nodes: [], links: [] })
const similarTasks = ref([])
const graphRef = ref(null)

const loadGraph = async () => {
  loading.value = true
  try {
    const res = await getRootCauseGraph(deployment.value || undefined, 100)
    const data = res.data || {}
    graph.value = data
    renderGraph(data)
  } catch {
    graph.value = { nodes: [], links: [] }
  } finally {
    loading.value = false
  }
}

const renderGraph = (data) => {
  nextTick(() => {
    if (!graphRef.value) return
    const chart = echarts.init(graphRef.value)
    const color = {
      service: '#2563eb',
      alert: '#ef4444',
      step: '#10b981',
      task: '#f59e0b'
    }
    const nodes = (data.nodes || []).map(n => ({
      id: n.id,
      name: n.name,
      category: n.category,
      symbolSize: n.category === 'service' ? 50 : n.category === 'alert' ? 38 : 30,
      itemStyle: { color: color[n.category] || '#64748b' },
      value: n.label || n.name
    }))
    chart.setOption({
      tooltip: { formatter: (p) => p.dataType === 'edge' ? '' : `<b>${p.name}</b><br/>${p.data.value || ''}` },
      legend: { data: ['service', 'alert', 'step', 'task'], bottom: 0 },
      series: [{
        type: 'graph',
        layout: 'force',
        roam: true,
        draggable: true,
        label: { show: true, fontSize: 11, color: '#1e293b' },
        force: { repulsion: 320, edgeLength: 110, gravity: 0.08 },
        edgeSymbol: ['none', 'arrow'],
        lineStyle: { color: '#cbd5e1', width: 1.2, curveness: 0.08 },
        categories: [
          { name: 'service' }, { name: 'alert' }, { name: 'step' }, { name: 'task' }
        ],
        data: nodes,
        links: (data.links || []).map(l => ({ source: l.source, target: l.target }))
      }]
    })
  })
}

const loadSimilar = async () => {
  similarLoading.value = true
  try {
    const res = await getSimilarTasks({
      deployment: similarDeployment.value || undefined,
      keyword: similarKeyword.value || undefined,
      limit: 10
    })
    similarTasks.value = res.data || []
  } catch {
    similarTasks.value = []
  } finally {
    similarLoading.value = false
  }
}

onMounted(() => {
  loadGraph()
})
</script>

<style scoped>
.toolbar { display: flex; gap: 8px; align-items: center; }
.similar-form { display: flex; gap: 10px; margin-top: 12px; flex-wrap: wrap; }
.graph-wrap { position: relative; border: 1px solid var(--c-border); border-radius: var(--radius-md); overflow: hidden; }
.graph { height: 520px; }
.graph-legend {
  position: absolute;
  right: 14px;
  top: 12px;
  display: flex;
  gap: 12px;
  background: rgba(255,255,255,0.9);
  border: 1px solid var(--c-border);
  border-radius: 8px;
  padding: 7px 10px;
  font-size: 12px;
  color: var(--c-text-muted);
}
.dot { display: inline-block; width: 9px; height: 9px; border-radius: 50%; margin-right: 4px; vertical-align: -1px; }
.dot--service { background: #2563eb; }
.dot--alert { background: #ef4444; }
.dot--step { background: #10b981; }
.dot--task { background: #f59e0b; }
.mono { font-family: Consolas, monospace; }
.kw { margin-right: 5px; }
</style>