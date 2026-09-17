<template>
  <div>
    <el-card>
      <div style="margin-bottom: 20px;">
        <el-button type="primary" @click="sendTestAlert">发送测试告警</el-button>
      </div>
      <el-table :data="alerts" style="width: 100%">
        <el-table-column prop="id" label="ID" width="80" />
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
      <el-pagination layout="prev, pager, next" :total="total" @current-change="loadAlerts" />
    </el-card>

    <el-dialog title="Agent 分析报告" v-model="dialogVisible" width="800px">
      <div v-if="currentTask">
        <el-descriptions title="任务概览" :column="2" border>
          <el-descriptions-item label="任务ID">{{ currentTask.id }}</el-descriptions-item>
          <el-descriptions-item label="状态">
            <el-tag :type="currentTask.status === 'SUCCESS' ? 'success' : currentTask.status === 'FAILED' ? 'danger' : 'warning'">{{ currentTask.status }}</el-tag>
          </el-descriptions-item>
          <el-descriptions-item label="任务类型">{{ currentTask.type }}</el-descriptions-item>
          <el-descriptions-item label="创建时间">{{ currentTask.createdAt }}</el-descriptions-item>
        </el-descriptions>

        <div style="margin-top: 20px;">
          <h4>推理步骤</h4>
          <el-timeline>
            <el-timeline-item v-for="(step, idx) in currentTask.agentSteps" :key="idx" :timestamp="'步骤 ' + (idx + 1)" :type="step.startsWith('[status_check]') ? 'primary' : 'success'">
              {{ step }}
            </el-timeline-item>
          </el-timeline>
        </div>

        <div style="margin-top: 20px;">
          <h4>分析报告</h4>
          <div class="markdown-body" v-html="renderedReport"></div>
        </div>
      </div>
      <div v-else>未找到关联任务</div>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, computed, onMounted } from 'vue'
import { getAlerts, sendAlert } from '@/api/alert'
import { getTasks } from '@/api/task'
import { ElMessage } from 'element-plus'
import { marked } from 'marked'

const alerts = ref([])
const total = ref(0)
const page = ref(1)
const size = 10

// 弹窗状态
const dialogVisible = ref(false)
const currentTask = ref(null)

// Agent 输出的是 Markdown（表格、标题、加粗），原始文本塞给用户没法看，
// 渲染成 HTML 再展示。marked 默认转义危险标签，注入风险可控。
const renderedReport = computed(() => {
  const report = currentTask.value?.output
  if (!report) return ''
  return marked.parse(report)
})

const loadAlerts = async (p = page.value) => {
  try {
    const res = await getAlerts({ page: p, size })
    alerts.value = res.data?.records || []
    total.value = res.data?.total || 0
  } catch (e) {
    alerts.value = []
    total.value = 0
  }
}

// 告警与任务靠 alertId 关联。列表接口按 alertId 过滤后取最新一条：
// 同一告警可能被分析多次（重放），最新的才是当前结论。
const viewAnalysis = async (row) => {
  try {
    const res = await getTasks({ alertId: row.id, page: 1, size: 1 })
    const records = res.data?.records || []
    if (records.length === 0) {
      currentTask.value = null
      ElMessage.info('该告警还没有关联的分析任务')
    } else {
      currentTask.value = records[0]
    }
    dialogVisible.value = true
  } catch (e) {
    ElMessage.error('查询分析任务失败')
  }
}

const sendTestAlert = async () => {
  try {
    await sendAlert({
      source: '前端测试',
      severity: 'WARNING',
      title: '测试告警',
      description: '这是一个来自前端的测试告警',
      serviceName: 'frontend'
    })
    ElMessage.success('告警发送成功')
    loadAlerts()
  } catch (e) {
    ElMessage.error('发送失败')
  }
}

onMounted(() => loadAlerts())
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