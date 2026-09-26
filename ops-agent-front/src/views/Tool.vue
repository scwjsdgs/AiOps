<template>
  <div class="page">
    <el-card>
      <div class="page-header" style="margin-bottom: 16px;">
        <div>
          <h3 class="page-title">
            <el-icon><Tools /></el-icon>工具管理
          </h3>
          <p class="page-subtitle">
            共 {{ tools.length }} 个工具，其中 {{ exposedCount }} 个可被 AI 自主调用。
            标记由后端实时下发（白名单来自 <code class="mono">opsagent.tools.agent-exposed</code> 配置），
            调整配置后刷新即生效。
          </p>
        </div>
        <el-button :icon="Refresh" @click="loadTools" :loading="loading">刷新</el-button>
      </div>

      <!-- 安全说明：这是本项目四层防线之一，值得在界面上讲清楚 -->
      <el-alert type="info" :closable="false" show-icon style="margin-bottom: 16px;">
        <template #title>
          安全边界：白名单外的工具（如 <code class="mono">generic_command</code>，可执行任意 shell）
          不暴露给大模型，只保留在人工执行通道。即使提示词被注入，模型也只能调用白名单内的运维工具。
        </template>
      </el-alert>

      <div v-loading="loading" class="tool-grid">
        <div
          v-for="tool in tools"
          :key="tool.name"
          class="tool-card"
          :class="{ 'tool-card--blocked': !isAgentExposed(tool) }"
        >
          <div class="tool-card__head">
            <div class="tool-card__icon">
              <el-icon><component :is="toolIcon(tool.name)" /></el-icon>
            </div>
            <div class="tool-card__title">
              <div class="tool-card__name mono">{{ tool.name }}</div>
              <div class="tool-card__badges">
                <el-tag v-if="isAgentExposed(tool)" type="success" size="small" effect="light">
                  AI 可调用
                </el-tag>
                <el-tag v-else type="danger" size="small" effect="light">
                  仅人工执行
                </el-tag>
                <el-tag v-if="isDangerous(tool)" type="warning" size="small" effect="plain">
                  改变线上状态
                </el-tag>
                <el-tag v-if="!tool.idempotent" type="info" size="small" effect="plain">
                  不可重试
                </el-tag>
              </div>
            </div>
          </div>

          <p class="tool-card__desc">{{ tool.description }}</p>

          <div class="tool-card__footer">
            <el-button size="small" type="primary" plain :icon="VideoPlay" @click="openRun(tool)">
              手动执行
            </el-button>
            <el-button size="small" link type="primary" @click="fillExample(tool)">
              填入示例
            </el-button>
          </div>
        </div>

        <el-empty v-if="!loading && !tools.length" description="未获取到工具列表" />
      </div>
    </el-card>

    <!-- ============ 手动执行弹窗 ============ -->
    <el-dialog v-model="runVisible" title="手动执行工具" width="640px" destroy-on-close>
      <el-alert
        v-if="currentTool && !isAgentExposed(currentTool)"
        type="warning"
        :closable="false"
        show-icon
        style="margin-bottom: 16px;"
        title="该工具不在 agent 白名单内，手工执行不受白名单限制，请谨慎操作"
      />
      <el-alert
        v-else-if="isDangerous(currentTool)"
        type="error"
        :closable="false"
        show-icon
        style="margin-bottom: 16px;"
        title="该操作会改变线上状态。经 agent 调用时需人工审批；你正在手工执行，请自行确认影响面"
      />

      <el-descriptions :column="1" border size="small" style="margin-bottom: 16px;">
        <el-descriptions-item label="工具名">
          <span class="mono">{{ currentTool?.name }}</span>
        </el-descriptions-item>
        <el-descriptions-item label="说明">{{ currentTool?.description }}</el-descriptions-item>
      </el-descriptions>

      <el-form label-position="top">
        <el-form-item label="参数（JSON）">
          <el-input
            v-model="paramsText"
            type="textarea"
            :rows="6"
            class="mono"
            placeholder='例如：{"deployment": "nginx", "namespace": "default"}'
          />
        </el-form-item>
      </el-form>

      <div v-if="runResult" class="run-result">
        <div class="section__head" style="margin-bottom: 8px;">
          <h4 class="section__title">
            执行结果
            <el-tag :type="runResult.success ? 'success' : 'danger'" size="small">
              {{ runResult.success ? '成功' : '失败' }}
            </el-tag>
            <el-tag type="info" size="small" effect="plain">{{ runResult.durationMs }} ms</el-tag>
          </h4>
        </div>
        <div v-if="runResult.message" class="run-result__msg">{{ runResult.message }}</div>
        <pre v-if="runResult.data" class="run-result__data">{{ prettyData }}</pre>
      </div>

      <template #footer>
        <el-button @click="runVisible = false">关闭</el-button>
        <el-button type="primary" :loading="running" @click="executeTool">执行</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, computed, onMounted } from 'vue'
import { getTools } from '@/api/tool'
import request from '@/utils/request'
import { ElMessage } from 'element-plus'
import {
  Tools, Refresh, VideoPlay, Search, Connection, Document, Cpu,
  RefreshRight, Upload, Delete, Stamp, DataLine, Bell, Share
} from '@element-plus/icons-vue'

const tools = ref([])
const loading = ref(false)

// agentExposed / dangerous / idempotent 三个标记全部来自后端 /tools/list，
// 前端不做任何硬编码判断：改 application.yml 的白名单后刷新即生效，
// 不会出现"后端已放开、前端还标着仅人工执行"的漂移。
const isAgentExposed = (tool) => !!tool?.agentExposed
const isDangerous = (tool) => !!tool?.dangerous

// AI 可调用工具数：直接数后端下发的标记，不再靠前端猜
const exposedCount = computed(() => tools.value.filter(isAgentExposed).length)

// 工具图标：按用途给个直观图形，纯文字列表太单调
const toolIcon = (name) => {
  const map = {
    get_status: Cpu,
    query_log: Document,
    query_metrics: DataLine,
    query_pod_events: Bell,
    query_impact: Share,
    restart_service: RefreshRight,
    scale_up: Upload,
    rollback: Refresh,
    clear_cache: Delete,
    request_human_approval: Stamp,
    generic_command: Connection
  }
  return map[name] || Tools
}

// 各工具的示例参数：让"手动执行"页开箱可用，不用查文档
const EXAMPLES = {
  get_status: { deployment: 'nginx', namespace: 'default' },
  query_log: { deployment: 'nginx', namespace: 'default', lines: 50 },
  query_metrics: { query: 'kube_deployment_status_replicas_available' },
  query_pod_events: { deployment: 'nginx', namespace: 'default' },
  query_impact: { deployment: 'nginx' },
  restart_service: { deployment: 'nginx', namespace: 'default' },
  scale_up: { deployment: 'nginx', namespace: 'default', replicas: 2 },
  rollback: { deployment: 'nginx', namespace: 'default' },
  clear_cache: { deployment: 'nginx', namespace: 'default' },
  request_human_approval: { operation: 'rollback nginx', reason: '可疑版本，需回滚验证' },
  generic_command: { command: 'kubectl get pods -n default' }
}

const loadTools = async () => {
  loading.value = true
  try {
    const res = await getTools()
    tools.value = res.data || []
  } catch {
    tools.value = []
  } finally {
    loading.value = false
  }
}

// ---------------------------- 执行 ----------------------------
const runVisible = ref(false)
const currentTool = ref(null)
const paramsText = ref('{}')
const running = ref(false)
const runResult = ref(null)

const prettyData = computed(() => {
  const d = runResult.value?.data
  if (!d) return ''
  try {
    return typeof d === 'string' ? d : JSON.stringify(d, null, 2)
  } catch {
    return String(d)
  }
})

const openRun = (tool) => {
  currentTool.value = tool
  runResult.value = null
  paramsText.value = JSON.stringify(EXAMPLES[tool.name] || {}, null, 2)
  runVisible.value = true
}

// 「填入示例」直接展开执行面板并带上参数，省去用户手敲 JSON
const fillExample = (tool) => {
  openRun(tool)
}

const executeTool = async () => {
  let params
  try {
    params = paramsText.value.trim() ? JSON.parse(paramsText.value) : {}
  } catch {
    ElMessage.error('参数不是合法 JSON，请检查格式')
    return
  }

  running.value = true
  runResult.value = null
  try {
    const res = await request.post('/tools/execute', {
      toolName: currentTool.value.name,
      parameters: params
    })
    runResult.value = res.data
    if (res.data?.success) {
      ElMessage.success('执行成功')
    } else {
      ElMessage.warning('执行完成，但工具返回失败')
    }
  } catch {
    // request.js 已提示
  } finally {
    running.value = false
  }
}

onMounted(loadTools)
</script>

<style scoped>
.tool-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(300px, 1fr));
  gap: 16px;
  min-height: 120px;
}

.tool-card {
  background: var(--c-surface);
  border: 1px solid var(--c-border);
  border-radius: var(--radius-lg);
  padding: 16px;
  display: flex;
  flex-direction: column;
  transition: all 0.18s;
}

.tool-card:hover {
  box-shadow: var(--shadow-md);
  border-color: var(--c-primary-light);
  transform: translateY(-2px);
}

/* 未暴露给 agent 的工具：左侧红边 + 微灰底，视觉上和"AI 可用"区分开 */
.tool-card--blocked {
  border-left: 3px solid var(--c-danger);
  background: linear-gradient(180deg, var(--c-danger-soft) 0%, var(--c-surface) 40%);
}

.tool-card__head {
  display: flex;
  gap: 12px;
  align-items: flex-start;
  margin-bottom: 12px;
}

.tool-card__icon {
  width: 38px;
  height: 38px;
  border-radius: 10px;
  background: var(--c-primary-soft);
  color: var(--c-primary);
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 18px;
  flex-shrink: 0;
}

.tool-card--blocked .tool-card__icon {
  background: var(--c-danger-soft);
  color: var(--c-danger);
}

.tool-card__title {
  min-width: 0;
  flex: 1;
}

.tool-card__name {
  font-size: 14px;
  font-weight: 600;
  color: var(--c-text);
  margin-bottom: 6px;
  word-break: break-all;
}

.tool-card__badges {
  display: flex;
  gap: 6px;
  flex-wrap: wrap;
}

.tool-card__desc {
  font-size: 12.5px;
  color: var(--c-text-muted);
  line-height: 1.65;
  margin: 0 0 14px;
  flex: 1;
}

.tool-card__footer {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding-top: 12px;
  border-top: 1px dashed var(--c-border);
}

/* ---------- 执行结果 ---------- */
.run-result {
  margin-top: 16px;
  padding: 14px;
  background: var(--c-surface-hover);
  border: 1px solid var(--c-border);
  border-radius: var(--radius-md);
}

.section__head {
  display: flex;
  align-items: center;
  justify-content: space-between;
}

.section__title {
  margin: 0;
  font-size: 14px;
  font-weight: 600;
  display: flex;
  align-items: center;
  gap: 8px;
}

.run-result__msg {
  font-size: 13px;
  color: var(--c-text-secondary);
  margin-bottom: 10px;
  line-height: 1.6;
}

.run-result__data {
  background: var(--c-sidebar-from);
  color: #e2e8f0;
  padding: 12px 14px;
  border-radius: var(--radius-sm);
  font-size: 12px;
  line-height: 1.6;
  max-height: 260px;
  overflow: auto;
  margin: 0;
  font-family: 'JetBrains Mono', Consolas, monospace;
}
</style>
