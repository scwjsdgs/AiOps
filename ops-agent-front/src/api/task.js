import request from '@/utils/request'

export const getTask = (id) => {
  return request.get(`/tasks/${id}`)
}

// 分页任务列表，alertId 可选 —— 告警页"查看分析"靠它打通
export const getTasks = (params) => {
  return request.get('/tasks', { params })
}