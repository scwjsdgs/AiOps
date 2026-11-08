<template>
  <div class="audit page">
    <!-- ============ 概览统计 ============ -->
    <el-row :gutter="16" class="stats-row" v-if="Object.keys(stats).length">
      <el-col :xs="12" :sm="6" v-for="(count, key) in stats" :key="key">
        <div class="stat-card stat-card--primary">
          <div class="stat-card__body">
            <div class="stat-card__label">{{ categoryLabel(key) }}（近 7 天）</div>
            <div class="stat-card__value">{{ count }}</div>
          </div>
          <div class="stat-card__icon">
            <el-icon :size="26"><Stamp /></el-icon>
          </div>
        </div>
      </el-col>
    </el-row>

    <!-- ============ 审计列表卡片 ============ -->
    <div class="glass-card">
      <div class="page-header" style="margin-bottom: var(--space-lg);">
        <div>
          <h3 class="page-title">
            <el-icon><Stamp /></el-icon>安全审计
          </h3>
          <p class="page-subtitle">
            登录、告警注入、工具执行、审批决策、配置变更、RAG 管理均记录操作者、来源通道、目标与结果。
          </p>
        </div>
        <div class="toolbar">
          <el-button :icon="Refresh" @click="loadAudit(1)" :loading="loading">刷新</el-button>
          <el-button type="primary" :icon="VideoPlay" @click="replayVisible = true">记录操作回放</el-button>
        </div>
      </div>

      <div class="filters">
        <el-select v-model="filters.category" clearable placeholder="操作类型" style="width: 150px"
                   @change="loadAudit(1)">
          <el-option label="登录" value="LOGIN" />
          <el-option label="告警" value="ALERT" />
          <el-option label="工具" value="TOOL" />
          <el-option label="审批" value="APPROVAL" />
          <el-option label="RAG" value="RAG" />
          <el-option label="混沌" value="CHAOS" />
        </el-select>
        <el-input v-model="filters.keyword" placeholder="搜索操作者 / 动作 / 对象" clearable
                  style="flex: 1; min-width: 240px;" @keyup.enter="loadAudit(1)" @clear="loadAudit(1)" />
        <el-button type="primary" :icon="Search" @click="loadAudit(1)">查询</el-button>
      </div>

      <el-table :data="records" v-loading="loading" style="width: 100%; margin-top: var(--space-lg);">
        <el-table-column label="时间" width="170">
          <template #default="{ row }">
            <span class="text-muted">{{ formatTime(row.timestamp) }}</span>
          </template>
        </el-table-column>
        <el-table-column label="类型" width="100">
          <template #default="{ row }">
            <el-tag size="small" effect="plain">{{ row.category }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="action" label="动作" min-width="160" />
        <el-table-column prop="operator" label="操作者" width="130" />
        <el-table-column prop="channel" label="通道" width="100" />
        <el-table-column prop="target" label="目标" min-width="220" />
        <el-table-column label="结果" width="100">
          <template #default="{ row }">
            <el-tag :type="row.success ? 'success' : 'danger'" size="small">
              {{ row.success ? '成功' : '失败' }}
            </el-tag>
          </template>
        </el-table-column>
      </el-table>

      <div class="pager">
        <el-pagination
          v-model:current-page="page"
          v-model:page-size="size"
          :total="total"
          layout="total, sizes, prev, pager, next"
          background
          @current-change="loadAudit"
          @size-change="loadAudit(1)"
        />
      </div>
    </div>

    <!-- ============ 记录操作回放 ============ -->
    <el-dialog v-model="replayVisible" title="记录操作回放" width="560px">
      <el-form label-position="top">
        <el-form-item label="动作名称">
          <el-input v-model="replayForm.action" placeholder="如：manual_drain_drill" />
        </el-form-item>
        <el-form-item label="目标">
          <el-input v-model="replayForm.target" placeholder="如：node-a / deployment-nginx" />
        </el-form-item>
        <el-form-item label="说明">
          <el-input v-model="replayForm.detail" type="textarea" :rows="3" placeholder="回放步骤说明" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="replayVisible = false">取消</el-button>
        <el-button type="primary" @click="saveReplay">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import { getAuditLogs, recordReplay } from '@/api/audit'
import { ElMessage } from 'element-plus'
import { Stamp, Refresh, VideoPlay, Search } from '@element-plus/icons-vue'

const records = ref([])
const total = ref(0)
const page = ref(1)
const size = ref(20)
const loading = ref(false)
const stats = ref({})
const filters = ref({ category: '', keyword: '' })

const formatTime = (t) => {
  if (!t) return ''
  return String(t).replace('T', ' ').slice(0, 19)
}

const categoryLabel = (key) => ({
  LOGIN: '登录', ALERT: '告警', TOOL: '工具',
  APPROVAL: '审批', RAG: '知识库', CHAOS: '混沌', REPLAY: '回放'
}[key] || key)

const loadAudit = async (p = page.value) => {
  loading.value = true
  try {
    const res = await getAuditLogs({
      page: p,
      size: size.value,
      category: filters.value.category || undefined,
      keyword: filters.value.keyword || undefined
    })
    const data = res.data || {}
    records.value = data.records || []
    total.value = data.total || 0
    stats.value = data.stats || {}
    page.value = p
  } catch {
    records.value = []
  } finally {
    loading.value = false
  }
}

const replayVisible = ref(false)
const replayForm = ref({ action: '', target: '', detail: '' })
const saveReplay = async () => {
  if (!replayForm.value.action?.trim()) {
    ElMessage.warning('请填写动作名称')
    return
  }
  try {
    await recordReplay(replayForm.value)
    ElMessage.success('回放记录已保存')
    replayVisible.value = false
    replayForm.value = { action: '', target: '', detail: '' }
    loadAudit(1)
  } catch {
    ElMessage.error('保存失败')
  }
}

onMounted(() => loadAudit(1))
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
  display: flex;
  align-items: center;
  gap: 12px;
  flex-wrap: wrap;
}

.filters > * {
  flex-shrink: 0;
}

.pager {
  display: flex;
  justify-content: flex-end;
  margin-top: var(--space-lg);
}

.text-muted {
  color: var(--c-text-muted);
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

  .filters {
    flex-direction: column;
    align-items: stretch;
  }

  .filters > * {
    width: 100%;
  }

  .pager {
    justify-content: center;
  }
}
</style>