<template>
  <div class="approvals page">
    <!-- ============ 概览统计 ============ -->
    <el-row :gutter="16" class="stats-row">
      <el-col :xs="12" :sm="6">
        <div class="stat-card stat-card--warning">
          <div class="stat-card__body">
            <div class="stat-card__label">待审批</div>
            <div class="stat-card__value">{{ counts.PENDING }}</div>
          </div>
          <div class="stat-card__icon">
            <el-icon :size="26"><Clock /></el-icon>
          </div>
        </div>
      </el-col>
      <el-col :xs="12" :sm="6">
        <div class="stat-card stat-card--success">
          <div class="stat-card__body">
            <div class="stat-card__label">已批准</div>
            <div class="stat-card__value">{{ counts.APPROVED }}</div>
          </div>
          <div class="stat-card__icon">
            <el-icon :size="26"><CircleCheck /></el-icon>
          </div>
        </div>
      </el-col>
      <el-col :xs="12" :sm="6">
        <div class="stat-card stat-card--danger">
          <div class="stat-card__body">
            <div class="stat-card__label">已拒绝</div>
            <div class="stat-card__value">{{ counts.REJECTED }}</div>
          </div>
          <div class="stat-card__icon">
            <el-icon :size="26"><CircleClose /></el-icon>
          </div>
        </div>
      </el-col>
      <el-col :xs="12" :sm="6">
        <div class="stat-card stat-card--info">
          <div class="stat-card__body">
            <div class="stat-card__label">超时拒绝</div>
            <div class="stat-card__value">{{ counts.TIMEOUT }}</div>
          </div>
          <div class="stat-card__icon">
            <el-icon :size="26"><Timer /></el-icon>
          </div>
        </div>
      </el-col>
    </el-row>

    <!-- ============ 审批列表卡片 ============ -->
    <div class="glass-card">
      <div class="page-header" style="margin-bottom: var(--space-lg);">
        <div>
          <h3 class="page-title">
            <el-icon><Stamp /></el-icon>人工审批
          </h3>
          <p class="page-subtitle">
            高危操作（回滚、扩容超阈值）在执行前必须人工确认；
            <strong>等待上限 55 秒，超时视为拒绝并进入 15 分钟静默冷却期</strong>
            （fail-closed，宁可不修也不误改线上；冷却期内同源告警只累计次数、不再弹窗）。
          </p>
        </div>
        <div class="toolbar">
          <el-switch
            v-model="autoRefresh"
            active-text="自动刷新"
            inactive-text="手动刷新"
            inline-prompt
            style="--el-switch-on-color: var(--c-primary);"
          />
          <el-button :icon="Refresh" @click="loadApprovals(1)" :loading="loading">刷新</el-button>
        </div>
      </div>

      <!-- 有待审批时给出醒目提示 -->
      <el-alert
        v-if="counts.PENDING > 0 && filters.status === 'PENDING'"
        type="warning"
        :closable="false"
        show-icon
        style="margin-bottom: var(--space-lg);"
      >
        <template #title>
          当前有 {{ counts.PENDING }} 个高危操作正在等待决策，请在 55 秒内处理，否则将自动拒绝并进入 15 分钟静默冷却期
        </template>
      </el-alert>

      <div class="filters">
        <el-radio-group v-model="filters.status" @change="loadApprovals(1)">
          <el-radio-button value="PENDING">待审批</el-radio-button>
          <el-radio-button value="APPROVED">已批准</el-radio-button>
          <el-radio-button value="REJECTED">已拒绝</el-radio-button>
          <el-radio-button value="TIMEOUT">超时</el-radio-button>
          <el-radio-button value="">全部</el-radio-button>
        </el-radio-group>
      </div>

      <el-table :data="approvals" v-loading="loading" style="width: 100%; margin-top: var(--space-lg);">
        <el-table-column label="审批单号" width="160">
          <template #default="{ row }">
            <CopyId :id="row.requestId" />
          </template>
        </el-table-column>

        <el-table-column label="操作内容" min-width="200">
          <template #default="{ row }">
            <span class="mono op-text">{{ row.operation || '—' }}</span>
          </template>
        </el-table-column>

        <el-table-column label="申请理由" min-width="280">
          <template #default="{ row }">
            <el-tooltip :content="row.reason" placement="top-left" :show-after="400" :disabled="!row.reason">
              <span class="cell-desc">{{ row.reason || '—' }}</span>
            </el-tooltip>
          </template>
        </el-table-column>

        <el-table-column label="状态" width="120">
          <template #default="{ row }">
            <el-tag :type="statusTagType(row.status)" size="small">
              {{ statusText(row.status) }}
            </el-tag>
          </template>
        </el-table-column>

        <el-table-column label="发起时间" width="170">
          <template #default="{ row }">
            <el-tooltip :content="formatTime(row.createdAt)" placement="top">
              <span class="cell-time">{{ fromNow(row.createdAt) }}</span>
            </el-tooltip>
          </template>
        </el-table-column>

        <el-table-column label="决策人" width="110">
          <template #default="{ row }">
            <span v-if="row.decidedBy" class="text-muted">{{ row.decidedBy }}</span>
            <span v-else class="text-muted">—</span>
          </template>
        </el-table-column>

        <el-table-column label="决策备注" min-width="180">
          <template #default="{ row }">
            <span v-if="row.decisionNote" class="cell-desc">{{ row.decisionNote }}</span>
            <span v-else class="text-muted">—</span>
          </template>
        </el-table-column>

        <el-table-column label="操作" width="180" fixed="right">
          <template #default="{ row }">
            <template v-if="row.status === 'PENDING'">
              <el-button type="success" size="small" @click="approve(row)">批准</el-button>
              <el-button type="danger" size="small" @click="reject(row)">拒绝</el-button>
            </template>
            <el-button v-else-if="row.taskId" link type="primary" size="small"
                       @click="goTask(row.taskId)">
              查看任务
            </el-button>
          </template>
        </el-table-column>

        <template #empty>
          <div class="empty-state" style="padding: 32px 0;">
            <el-icon size="56" style="color: var(--c-border-soft);"><Stamp /></el-icon>
            <p class="empty-state__title">
              {{ filters.status === 'PENDING' ? '当前没有待审批的高危操作' : '暂无审批记录' }}
            </p>
            <p class="empty-state__desc">
              高危操作在执行前会进入人工审批流程，此处会列出待决策的单据。
            </p>
          </div>
        </template>
      </el-table>

      <div class="pager">
        <el-pagination
          v-model:current-page="page"
          v-model:page-size="size"
          :page-sizes="[10, 20, 50]"
          :total="total"
          layout="total, sizes, prev, pager, next"
          background
          @current-change="loadApprovals"
          @size-change="loadApprovals(1)"
        />
      </div>
    </div>
  </div>
</template>

<script setup>
import { ref, onMounted, onUnmounted } from 'vue'
import { useRouter } from 'vue-router'
import { getApprovals, submitDecision } from '@/api/approval'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Stamp, Refresh, Clock, CircleCheck, CircleClose, Timer } from '@element-plus/icons-vue'
import CopyId from '@/components/CopyId.vue'
import { formatTime, fromNow } from '@/utils/format'

const router = useRouter()

const approvals = ref([])
const total = ref(0)
const page = ref(1)
const size = ref(10)
const filters = ref({ status: 'PENDING' })
const loading = ref(false)
const autoRefresh = ref(true)

const counts = ref({ PENDING: 0, APPROVED: 0, REJECTED: 0, TIMEOUT: 0 })

const loadCounts = async () => {
  const statuses = ['PENDING', 'APPROVED', 'REJECTED', 'TIMEOUT']
  const results = await Promise.all(
    statuses.map(s =>
      getApprovals({ status: s, page: 0, size: 1 })
        .then(r => r.data?.total || 0)
        .catch(() => 0)
    )
  )
  counts.value = statuses.reduce((acc, s, i) => {
    acc[s] = results[i]
    return acc
  }, {})
}

const statusTagType = (s) => {
  if (s === 'PENDING') return 'warning'
  if (s === 'APPROVED') return 'success'
  if (s === 'REJECTED') return 'danger'
  if (s === 'TIMEOUT') return 'info'
  return 'info'
}

const statusText = (s) => {
  const map = { PENDING: '待审批', APPROVED: '已批准', REJECTED: '已拒绝', TIMEOUT: '超时拒绝' }
  return map[s] || s || '未知'
}

const loadApprovals = async (p = page.value) => {
  try {
    loading.value = true
    const res = await getApprovals({ status: filters.value.status || undefined, page: p, size: size.value })
    approvals.value = res.data?.records || []
    total.value = res.data?.total || 0
    page.value = p
  } catch {
    approvals.value = []
    total.value = 0
  } finally {
    loading.value = false
  }
}

const refreshAll = () => {
  loadApprovals(page.value)
  loadCounts()
}

// 批准：确认弹窗后直接提交
const approve = async (row) => {
  try {
    await ElMessageBox.confirm(
      `确定批准该操作吗？<br/><br/>操作内容：<b>${row.operation || '—'}</b><br/>申请理由：${row.reason || '—'}`,
      '批准确认',
      {
        confirmButtonText: '确定批准',
        cancelButtonText: '取消',
        type: 'warning',
        dangerouslyUseHTMLString: true
      }
    )
  } catch {
    return // 用户点取消
  }
  try {
    await submitDecision(row.requestId, { approved: true, note: '' })
    ElMessage.success('已批准，agent 将继续执行，执行后进入验证期（最多 3 轮）')
    refreshAll()
  } catch {
    // 提交失败的提示由 request.js 统一处理
  }
}

// 拒绝：弹输入框填备注，备注为空不允许提交
const reject = async (row) => {
  let value
  try {
    const res = await ElMessageBox.prompt('请填写拒绝原因（必填，会记入审计记录）', `拒绝 - ${row.requestId}`, {
      confirmButtonText: '确定拒绝',
      cancelButtonText: '取消',
      inputPlaceholder: '例如：高风险操作，请先评估影响面',
      inputValidator: (v) => (v && v.trim() ? true : '备注不能为空')
    })
    value = res.value
  } catch {
    return // 取消输入
  }
  try {
    await submitDecision(row.requestId, { approved: false, note: value.trim() })
    ElMessage.success('已拒绝，进入 15 分钟静默冷却期（期间同源告警只累计次数，不再弹窗）')
    refreshAll()
  } catch {
    // 提交失败的提示由 request.js 统一处理
  }
}

const goTask = (taskId) => {
  router.push({ path: '/tasks', query: { id: taskId } })
}

// 自动刷新：审批有时效（55s 超时），挂在页面上时定期拉取
let autoTimer = null
const startAutoRefresh = () => {
  stopAutoRefresh()
  autoTimer = setInterval(() => {
    if (autoRefresh.value && !loading.value) refreshAll()
  }, 5000)
}
const stopAutoRefresh = () => {
  if (autoTimer) {
    clearInterval(autoTimer)
    autoTimer = null
  }
}

onMounted(() => {
  loadApprovals(1)
  loadCounts()
  startAutoRefresh()
})

onUnmounted(() => stopAutoRefresh())
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
  gap: 14px;
}

.filters {
  padding: 12px 16px;
  background: var(--c-surface-hover);
  border: 1px solid var(--c-border);
  border-radius: var(--radius-md);
}

.op-text {
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

.pager {
  display: flex;
  justify-content: flex-end;
  margin-top: var(--space-lg);
}

@media (max-width: 768px) {
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