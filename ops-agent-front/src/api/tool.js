import request from '@/utils/request'

// 工具清单。每项含 name / description / agentExposed / dangerous / idempotent，
// 标记由后端权威计算，前端不硬编码。
export const getTools = () => {
  return request.get('/tools/list')
}

// 当前生效的 agent 白名单（排查"为什么 agent 说某工具不存在"）
export const getAgentExposed = () => {
  return request.get('/tools/agent-exposed')
}
