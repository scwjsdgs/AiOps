import request from '@/utils/request'

// 告警根因图谱
export const getRootCauseGraph = (deployment, limit = 100) => {
  return request.get('/intelligence/root-cause-graph', { params: { deployment, limit } })
}

// 历史相似故障推荐
export const getSimilarTasks = (params) => {
  return request.get('/intelligence/similar-tasks', { params })
}
