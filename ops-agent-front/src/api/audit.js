import request from '@/utils/request'

export const getAuditLogs = (params) => request.get('/audit', { params })
export const getAuditStats = () => request.get('/audit/stats')
export const recordReplay = (data) => request.post('/audit/replay', data)
