<template>
  <!--
    短 ID 展示 + 悬停看全量 + 点击复制。
    列表里的 UUID 36 位塞不下，截断又怕用户要拿去排查（贴进日志搜索、拼 URL），
    所以截断显示但一键可复制，兼顾可读与可用。
  -->
  <span class="copy-id mono" @click="doCopy">
    <el-tooltip :content="id" placement="top" :show-after="300">
      <span class="copy-id__text">{{ shortId(id) }}</span>
    </el-tooltip>
    <el-icon class="copy-id__icon"><DocumentCopy /></el-icon>
  </span>
</template>

<script setup>
import { ElMessage } from 'element-plus'
import { shortId, copyText } from '@/utils/format'

const props = defineProps({
  id: { type: String, default: '' }
})

const doCopy = async () => {
  if (!props.id) return
  const ok = await copyText(props.id)
  ok ? ElMessage.success('已复制完整 ID') : ElMessage.error('复制失败')
}
</script>

<style scoped>
.copy-id {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  cursor: pointer;
  color: var(--c-text-secondary);
  padding: 1px 4px;
  border-radius: 4px;
  transition: all 0.15s;
}

.copy-id:hover {
  background: var(--c-primary-soft);
  color: var(--c-primary);
}

.copy-id__icon {
  font-size: 12px;
  opacity: 0.45;
}

.copy-id:hover .copy-id__icon {
  opacity: 1;
}
</style>
