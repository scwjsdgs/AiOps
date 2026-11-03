import request from '@/utils/request'

// ChatOps 多会话管理：后端 Java 按 JWT username 维护会话索引，
// 实际对话历史由 Python LangGraph Checkpointer 持久化。
export const listSessions = () => request.get('/chatops/sessions')

export const createSession = () => request.post('/chatops/sessions')

export const getSessionMessages = (sessionId) => request.get(`/chatops/sessions/${sessionId}`)

export const deleteSession = (sessionId) => request.delete(`/chatops/sessions/${sessionId}`)

// ChatOps 流式请求的任务快照：SSE 断开或刷新后，用它回捞最新状态，避免永久卡死
export const getChatTask = (taskId) => request.get(`/tasks/${taskId}`)

// 前端主动离开/SSE 超时取消时，通知 Python 立即停止当前 LangGraph 任务
export const cancelChatTask = (taskId) => request.post(`/chatops/tasks/${taskId}/cancel`)
