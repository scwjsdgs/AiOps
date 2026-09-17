import request from '@/utils/request'

export const sendAlert = (data) => {
  return request.post('/alerts', data)
}

export const getAlerts = (params) => {
  return request.get('/alerts', { params })
}

// Dashboard 统计：最近 N 天每日分级计数 + 级别分布，供 ECharts 渲染
export const getAlertStats = (days = 7) => {
  return request.get('/alerts/stats', { params: { days } })
}