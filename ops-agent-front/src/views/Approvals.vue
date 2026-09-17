<template>
  <div>
    <el-card>
      <h3>人工审批</h3>
      <!-- 状态筛选 + 查询 -->
      <el-form inline style="margin-bottom: 10px;">
        <el-form-item label="状态">
          <el-select v-model="status" style="width: 160px;" @change="() => loadApprovals(1)">
            <el-option label="待审批" value="PENDING" />
            <el-option label="已批准" value="APPROVED" />
            <el-option label="已拒绝" value="REJECTED" />
          </el-select>
        </el-form-item>
        <el-form-item>
          <el-button type="primary" @click="loadApprovals(1)">查询</el-button>
        </el-form-item>
      </el-form>

      <!-- 审批单列表 -->
      <el-table :data="approvals" v-loading="loading" style="width: 100%">
        <el-table-column prop="requestId" label="审批单号" width="220" />
        <el-table-column prop="operation" label="操作内容" />
        <el-table-column prop="reason" label="原因" />
        <el-table-column prop="status" label="状态" width="110">
          <template #default="{ row }">
            <el-tag :type="statusTagType(row.status)">{{ statusText(row.status) }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="decidedBy" label="审批人" width="110" />
        <el-table-column prop="createdAt" label="创建时间" width="180" />
        <el-table-column prop="decidedAt" label="审批时间" width="180" />
        <el-table-column label="操作" width="180" fixed="right">
          <template #default="{ row }">
            <template v-if="row.status === 'PENDING'">
              <el-button type="success" size="small" @click="approve(row)">批准</el-button>
              <el-button type="danger" size="small" @click="reject(row)">拒绝</el-button>
            </template>
          </template>
        </el-table-column>
      </el-table>

      <el-pagination
        layout="prev, pager, next"
        :total="total"
        :page-size="size"
        :current-page="page"
        @current-change="loadApprovals"
        style="margin-top: 20px; display: flex; justify-content: flex-end;"
      />
    </el-card>
  </div>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import { getApprovals, submitDecision } from '@/api/approval'
import { ElMessage, ElMessageBox } from 'element-plus'

const approvals = ref([])
const total = ref(0)
const page = ref(1)
const size = 10
const status = ref('PENDING')
const loading = ref(false)

// 状态 -> 标签颜色
const statusTagType = (s) => {
  if (s === 'PENDING') return 'warning'
  if (s === 'APPROVED') return 'success'
  if (s === 'REJECTED') return 'danger'
  return 'info'
}

// 状态 -> 中文文本
const statusText = (s) => {
  if (s === 'PENDING') return '待审批'
  if (s === 'APPROVED') return '已批准'
  if (s === 'REJECTED') return '已拒绝'
  return s || '未知'
}

const loadApprovals = async (p = page.value) => {
  try {
    loading.value = true
    const res = await getApprovals({ status: status.value, page: p, size })
    approvals.value = res.data?.records || []
    total.value = res.data?.total || 0
    page.value = p
  } catch (e) {
    approvals.value = []
    total.value = 0
    // request.js 拦截器已经弹过错误提示，这里不再重复弹
  } finally {
    // 必须复位 loading，否则表格会一直转圈
    loading.value = false
  }
}

// 批准：确认弹窗后直接提交
const approve = async (row) => {
  try {
    await ElMessageBox.confirm(
      `确定批准该操作吗？操作内容：${row.operation}`,
      '批准确认',
      { confirmButtonText: '确定', cancelButtonText: '取消', type: 'warning' }
    )
    await submitDecision(row.requestId, { approved: true, note: '' })
    ElMessage.success('已批准')
    loadApprovals(page.value)
  } catch (e) {
    // 用户点取消时 ElMessageBox 会 reject，这里静默处理
  }
}

// 拒绝：弹输入框填备注，备注为空不允许提交
const reject = async (row) => {
  try {
    const { value } = await ElMessageBox.prompt('请填写拒绝原因（必填）', `拒绝 - ${row.requestId}`, {
      confirmButtonText: '确定',
      cancelButtonText: '取消',
      inputPlaceholder: '例如：高风险操作，请先评估影响',
      inputValidator: (v) => (v && v.trim() ? true : '备注不能为空')
    })
    await submitDecision(row.requestId, { approved: false, note: value.trim() })
    ElMessage.success('已拒绝')
    loadApprovals(page.value)
  } catch (e) {
    // 取消输入时静默，提交失败的提示由 request.js 统一处理
  }
}

onMounted(() => loadApprovals(1))
</script>
