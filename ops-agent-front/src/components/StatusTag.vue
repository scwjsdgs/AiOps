<template>
  <!--
    状态/级别统一标签。kind 决定用哪套枚举映射：
      alert  —— AlertEntity.status（FIRING/PENDING/ANALYZING/RESOLVED/CORRELATED/FAILED/TIMEOUT）
      task   —— Task.status（PENDING/RUNNING/SUCCESS/FAILED/TIMEOUT/REGRESSED）
      severity —— CRITICAL/WARNING/INFO
    带色点：纯文字标签在密集表格里扫读时区分度不够，加个圆点一眼能定位。
  -->
  <el-tag :type="meta.type" :size="size" :effect="effect" class="status-tag">
    <span class="status-tag__dot" :style="{ background: dotColor }"></span>
    {{ meta.text }}
  </el-tag>
</template>

<script setup>
import { computed } from 'vue'
import { alertStatusMeta, taskStatusMeta, severityMeta } from '@/utils/format'

const props = defineProps({
  status: { type: String, default: '' },
  kind: { type: String, default: 'alert' }, // alert | task | severity
  size: { type: String, default: 'default' },
  effect: { type: String, default: 'light' }
})

const meta = computed(() => {
  if (props.kind === 'task') return taskStatusMeta(props.status)
  if (props.kind === 'severity') return severityMeta(props.status)
  return alertStatusMeta(props.status)
})

// 色点颜色跟随 el-tag 的 type
const DOT_COLORS = {
  success: '#10b981',
  warning: '#f59e0b',
  danger: '#ef4444',
  primary: '#2563eb',
  info: '#94a3b8'
}

const dotColor = computed(() => DOT_COLORS[meta.value.type] || '#94a3b8')
</script>

<style scoped>
.status-tag {
  display: inline-flex;
  align-items: center;
  gap: 5px;
}

.status-tag__dot {
  width: 6px;
  height: 6px;
  border-radius: 50%;
  flex-shrink: 0;
}
</style>
