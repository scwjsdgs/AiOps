import request from '@/utils/request'

// ChatOps 多会话管理：后端 Java 按 JWT username 维护会话索引，
// 实际对话历史由 Python LangGraph Checkpointer 持久化。
export const listSessions = () => request.get('/chatops/sessions')

export const createSession = () => request.post('/chatops/sessions')

export const getSessionMessages = (sessionId) => request.get(`/chatops/sessions/${sessionId}`)
