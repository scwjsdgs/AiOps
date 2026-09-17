import request from '@/utils/request'

// 分页查询审批单，params: { status, page, size }
export const getApprovals = (params) => {
  return request.get('/approvals', { params })
}

// 提交审批决定，requestId 为审批单号，data: { approved, note }
export const submitDecision = (requestId, data) => {
  return request.post(`/approvals/${requestId}/decision`, data)
}
