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
import { ref, onMounted, onUnmounted, nextTick } from 'vue'
import { getRootCauseGraph, getSimilarTasks, getTaskDetail } from '@/api/intelligence'
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

let graphChart = null

let expandedStepNodes = []

const renderGraph = (data) => {
  nextTick(() => {
    if (!graphRef.value) return
    if (graphChart) {
      graphChart.dispose()
      graphChart = null
    }
    graphChart = echarts.init(graphRef.value)
    // 颜色与图例严格对齐：service 蓝、alert 绿、step 深色、task 橙。
    const color = {
      service: '#2563eb',
      alert: '#10b981',
      step: '#334155',
      task: '#f97316'
    }

    // 节点名统一截断，tooltip 里再给完整 label
    const shortName = (s) => {
      const str = String(s || '')
      return str.length > 8 ? `${str.slice(0, 8)}…` : str
    }

    // 基础主干节点：服务、告警、任务。步骤默认折叠，点击任务节点再展开。
    const baseNodes = (data.nodes || []).filter(n => n.category !== 'step')
    const nodes = baseNodes.map(n => {
      const category = n.category
      const symbolSize = category === 'service' ? 60
        : category === 'task' ? 42
        : category === 'alert' ? 36
        : 16
      return {
        id: n.id,
        name: shortName(n.name),
        fullName: n.name,
        category,
        symbolSize,
        itemStyle: { color: color[category] || '#64748b' },
        value: n.label || n.fullName || n.name,
        nodeType: category,
        steps: n.steps || [],
        expanded: false,
        taskId: category === 'task' ? String(n.id || '').replace(/^task:/, '') : ''
      }
    })
    const idToNode = new Map(nodes.map(n => [n.id, n]))

    // 默认只保留主干边，避免步骤边挤占图。
    const ids = new Set(nodes.map(n => n.id))
    const links = (data.links || [])
      .filter(l => ids.has(l.source) && ids.has(l.target))
      .map(l => ({ source: l.source, target: l.target }))

    const truncateLabel = (s, max = 8) => {
      const str = String(s || '')
      return str.length > max ? `${str.slice(0, max)}…` : str
    }

    const renderOption = (nodeList, linkList) => {
      graphChart.setOption({
        color: [color.service, color.alert, color.task, color.step],
        tooltip: {
          confine: true,
          maxWidth: 300,
          extraCssText: 'white-space: normal; word-break: break-all; box-shadow: 0 0 3px rgba(0, 0, 0, 0.3);',
          formatter: (p) => {
            if (p.dataType === 'edge') return ''
            const full = p.data.fullName || p.data.value || p.name
            return `<b>${p.name}</b><br/>${full}`
          }
        },
        legend: { data: ['service', 'alert', 'task', 'step'], bottom: 0 },
        series: [{
          type: 'graph',
          layout: 'force',
          roam: true,
          draggable: true,
          label: {
            show: true,
            fontSize: 11,
            color: '#1e293b',
            formatter: (p) => {
              if (!p.data || p.dataType === 'edge') return ''
              const d = p.data
              if (d.category === 'alert') return `告警 ${d.name}`
              if (d.category === 'step') return truncateLabel(d.name, 8)
              return d.name
            }
          },
          // 固定 force，步骤节点围绕任务节点呈葡萄状，不切 circular，避免星环。
          force: {
            repulsion: 800,
            edgeLength: 150,
            gravity: 0.08,
            friction: 0.15,
            layoutAnimation: true
          },
          edgeSymbol: ['none', 'arrow'],
          lineStyle: { color: '#cbd5e1', width: 1.2, curveness: 0.12 },
          animationDurationUpdate: 300,
          categories: [
            { name: 'service', itemStyle: { color: color.service } },
            { name: 'alert', itemStyle: { color: color.alert } },
            { name: 'task', itemStyle: { color: color.task } },
            { name: 'step', itemStyle: { color: color.step } }
          ],
          data: nodeList,
          links: linkList
        }]
      })
    }

    const loadSteps = async (taskNode) => {
      if (taskNode.expanded) {
        // 折叠：移除该任务的步骤节点，恢复主干。
        taskNode.expanded = false
        nodes.forEach(n => {
          if (n.id === taskNode.id) n.expanded = false
        })
        const visibleNodes = nodes.filter(n => !n.id.startsWith(`${taskNode.id}:step:`))
        const visibleIds = new Set(visibleNodes.map(n => n.id))
        const visibleLinks = (data.links || [])
          .filter(l => visibleIds.has(l.source) && visibleIds.has(l.target))
          .map(l => ({ source: l.source, target: l.target }))
        renderOption(visibleNodes, visibleLinks)
        return
      }

      // 未展开：先请求后端任务详情，拿完整 agentSteps，再截取关键步骤挂到图上。
      taskNode.loading = true
      try {
        const res = await getTaskDetail(taskNode.taskId)
        const task = res.data || {}
        const rawSteps = task.agentSteps || task.steps || []
        // 合并相同/连续同类步骤，只展示前 8 个关键步骤，避免星环。
        const merged = []
        rawSteps.forEach((s) => {
          const text = String(s || '').replace(/^\(STEP\)\s*/, '').trim()
          if (!text) return
          const last = merged[merged.length - 1]
          if (last && last.name === text) return
          if (last && /^(诊断|执行|报告) Agent 调用/.test(last.name) && /^(诊断|执行|报告) Agent 调用/.test(text)) {
            last.name = `${last.name} / ${text}`
            return
          }
          merged.push({ name: text })
        })
        const maxSteps = 8
        taskNode.steps = (merged.length > maxSteps ? merged.slice(0, maxSteps) : merged).map((s, i) => ({
          id: `${taskNode.id}:step:${i}`,
          name: s.name
        }))
        taskNode.expanded = true

        const stepNodeList = taskNode.steps.map((s, i) => ({
          id: s.id,
          name: truncateLabel(s.name, 8),
          fullName: s.name,
          category: 'step',
          symbolSize: 16,
          itemStyle: { color: color.step },
          value: s.name,
          nodeType: 'step'
        }))
        const visibleNodes = [...nodes.filter(n => n.id !== taskNode.id), ...stepNodeList, taskNode]
        // 每个步骤只连到任务节点，形成围绕式葡萄簇，而不是一条长链。
        const stepLinks = taskNode.steps.map(s => ({ source: taskNode.id, target: s.id }))
        const visibleIds = new Set(visibleNodes.map(n => n.id))
        const baseLinks = (data.links || [])
          .filter(l => visibleIds.has(l.source) && visibleIds.has(l.target))
          .map(l => ({ source: l.source, target: l.target }))
        renderOption(visibleNodes, [...baseLinks, ...stepLinks])
      } catch (e) {
        // 拿不到步骤时保持主干，不能把整个图搞坏
      } finally {
        taskNode.loading = false
      }
    }

    renderOption(nodes, links)
    graphChart.off('click')
    graphChart.on('click', (params) => {
      const node = params?.data
      if (!node || node.nodeType !== 'task') return
      loadSteps(node)
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

onUnmounted(() => {
  if (graphChart) {
    graphChart.dispose()
    graphChart = null
  }
})
</script>

<style scoped>
.toolbar { display: flex; gap: 8px; align-items: center; }
.similar-form { display: flex; gap: 10px; margin-top: 12px; flex-wrap: wrap; }
.graph-wrap { position: relative; border: 1px solid var(--c-border); border-radius: var(--radius-md); overflow: hidden; height: 600px; }
.graph { height: 600px; }
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