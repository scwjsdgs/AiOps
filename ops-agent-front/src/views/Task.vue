<template>
  <div>
    <el-card>
      <h3>任务查询</h3>
      <el-form inline>
        <el-form-item label="任务ID">
          <el-input v-model="taskId" placeholder="请输入任务ID" clearable />
        </el-form-item>
        <el-form-item>
          <el-button type="primary" @click="loadTask">查询</el-button>
        </el-form-item>
      </el-form>

      <div v-if="task" style="margin-top: 20px;">
        <el-descriptions title="任务详情" :column="2" border>
          <el-descriptions-item label="任务ID">{{ task.id }}</el-descriptions-item>
          <el-descriptions-item label="状态">
            <el-tag :type="task.status === 'SUCCESS' ? 'success' : task.status === 'FAILED' ? 'danger' : 'warning'">{{ task.status }}</el-tag>
          </el-descriptions-item>
          <el-descriptions-item label="类型">{{ task.type }}</el-descriptions-item>
          <el-descriptions-item label="创建时间">{{ task.createdAt }}</el-descriptions-item>
          <el-descriptions-item label="输入">{{ task.input || '无' }}</el-descriptions-item>
          <el-descriptions-item label="耗时">{{ duration }}</el-descriptions-item>
        </el-descriptions>

        <div v-if="task.agentSteps && task.agentSteps.length" style="margin-top: 20px;">
          <h4>Agent 推理过程（{{ task.agentSteps.length }} 步）</h4>
          <el-timeline>
            <el-timeline-item
              v-for="(step, idx) in task.agentSteps"
              :key="idx"
              :timestamp="'步骤 ' + (idx + 1)"
              :type="stepType(step)"
              :icon="stepIcon(step)"
            >
              {{ step }}
            </el-timeline-item>
          </el-timeline>
        </div>

        <div v-if="task.output" style="margin-top: 20px;">
          <h4>分析报告</h4>
          <div class="markdown-body" v-html="renderedReport"></div>
        </div>
      </div>
      <el-empty v-else-if="searched" description="未找到任务" />
    </el-card>
  </div>
</template>

<script setup>
import { ref, computed, onMounted, onUnmounted, watch } from 'vue'
import { useRoute } from 'vue-router'
import { getTask } from '@/api/task'
import { ElMessage } from 'element-plus'
import { marked } from 'marked'
import { Search, Connection, Cpu, Document, Clock } from '@element-plus/icons-vue'

const route = useRoute()
const taskId = ref('')
const task = ref(null)
const searched = ref(false)

// 轮询定时器：任务未到终态时自动刷新，报告出来不用手动重查
let pollTimer = null
const isFinalStatus = (t) => t && ['SUCCESS', 'FAILED', 'TIMEOUT'].includes(t.status)

const stopPolling = () => {
  if (pollTimer) {
    clearInterval(pollTimer)
    pollTimer = null
  }
}

const startPolling = () => {
  stopPolling()
  // PENDING/RUNNING 时每 3 秒重查一次；终态停止。
  // agent 分析要几十秒到几分钟，不轮询的话用户要不停手动点查询。
  pollTimer = setInterval(async () => {
    if (!taskId.value.trim()) {
      stopPolling()
      return
    }
    try {
      const res = await getTask(taskId.value)
      task.value = res.data
      if (isFinalStatus(task.value)) stopPolling()
    } catch (e) {
      // 单次轮询失败不打断，下一轮继续
    }
  }, 3000)
}

// Agent 输出的是 Markdown，渲染成 HTML 展示（marked 默认转义危险标签）
const renderedReport = computed(() => {
  const report = task.value?.output
  if (!report) return ''
  return marked.parse(report)
})

// 任务耗时：createdAt → updatedAt。分析要几十秒到几分钟，
// 耗时是"Agent 真在干活"的直观证据。
const duration = computed(() => {
  const t = task.value
  if (!t?.createdAt || !t?.updatedAt) return '无'
  const ms = new Date(t.updatedAt) - new Date(t.createdAt)
  if (isNaN(ms) || ms < 0) return '无'
  return ms >= 60000 ? Math.floor(ms / 60000) + ' 分 ' + Math.round((ms % 60000) / 1000) + ' 秒'
    : Math.round(ms / 1000) + ' 秒'
})

// 推理步骤按内容分型：不同类型的步骤给不同颜色/图标，
// 一排小 tag 挤成一坨变成可读的时间线。
const stepType = (step) => {
  if (step.includes('TOOL_CALL') || step.includes('(tool)')) return 'warning'
  if (step.includes('ERROR') || step.includes('失败')) return 'danger'
  if (step.includes('status_check')) return 'primary'
  return 'success'
}

const stepIcon = (step) => {
  if (step.includes('TOOL_CALL') || step.includes('(tool)')) return Connection
  if (step.includes('ERROR') || step.includes('失败')) return Clock
  if (step.includes('status_check')) return Search
  return Document
}

const loadTask = async () => {
  if (!taskId.value.trim()) {
    ElMessage.warning('请输入任务ID')
    return
  }
  try {
    const res = await getTask(taskId.value)
    task.value = res.data
    searched.value = true
    // 未到终态就开始轮询，终态停止
    if (!isFinalStatus(task.value)) startPolling()
    else stopPolling()
  } catch (e) {
    task.value = null
    searched.value = true
    stopPolling()
    ElMessage.error('任务不存在或查询失败')
  }
}

// 支持 Dashboard 一键诊断等外部页面跳转过来时带 ?id=xxx 直接查询
onMounted(() => {
  const id = route.query.id
  if (id) {
    taskId.value = id
    loadTask()
  }
})

watch(() => route.query.id, (id) => {
  if (id && id !== taskId.value) {
    taskId.value = id
    loadTask()
  }
})

// 离开页面清理轮询，防止定时器泄漏
onUnmounted(() => stopPolling())
</script>

<style scoped>
.markdown-body {
  background: #f8f9fa;
  padding: 16px;
  border-radius: 4px;
  line-height: 1.7;
}
.markdown-body :deep(table) {
  border-collapse: collapse;
  width: 100%;
  margin: 12px 0;
}
.markdown-body :deep(th),
.markdown-body :deep(td) {
  border: 1px solid #dcdfe6;
  padding: 8px 12px;
  text-align: left;
}
.markdown-body :deep(th) {
  background: #f0f2f5;
}
.markdown-body :deep(h1),
.markdown-body :deep(h2),
.markdown-body :deep(h3),
.markdown-body :deep(h4) {
  margin: 16px 0 8px;
}
</style>