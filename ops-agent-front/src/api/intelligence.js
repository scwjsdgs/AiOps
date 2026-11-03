import request from '@/utils/request'

// 告警根因图谱
export const getRootCauseGraph = (deployment, limit = 100) => {
  return request.get('/intelligence/root-cause-graph', { params: { deployment, limit } })
}

// 根因图谱下钻：拉取任务详情中的完整 agentSteps
export const getTaskDetail = (taskId) => {
  return request.get(`/tasks/${taskId}`)
}

// 历史相似故障推荐
export const getSimilarTasks = (params) => {
  return request.get('/intelligence/similar-tasks', { params })
}
