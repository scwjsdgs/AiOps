import { defineStore } from 'pinia'

// WebSocket 单例连接。
//
// 改造前 connect(taskId) 每换一个任务就断开重连，且只有任务页会连——
// 用户停在仪表板时，agent 发起的审批请求完全收不到，55 秒后自动超时。
// 现在改为：登录后建立一条常驻连接（taskId 留空 = 订阅全部），
// 所有页面共享，审批等全局事件都能收到。
export const useWebSocketStore = defineStore('websocket', {
  state: () => ({
    ws: null,
    connected: false,
    messages: [],
    // 审批事件单独存放：审批弹窗要的是"最新的待处理项"，不是全部消息流
    pendingApproval: null,
    reconnectTimer: null,
    manualClose: false,
    retryCount: 0,
    // 当前订阅的 taskId。空串 = 只收广播（审批）；有值 = 额外收该任务的步骤推送。
    // 两条流都走同一条连接，靠这个字段决定是否需要在切换时重连。
    currentTaskId: ''
  }),

  actions: {
    /**
     * 建立连接。taskId 为空表示只订阅广播事件（审批）。
     *
     * 幂等规则：已连着且 taskId 没变则直接返回；taskId 变了则重连，
     * 因为服务端按 taskId 决定推哪些任务步骤 —— 不重连就收不到新任务的消息。
     * 无论连接带上哪个 taskId，审批事件都会推送（服务端所有连接都加入广播集合）。
     */
    connect(taskId = '') {
      const want = taskId || ''
      const connecting = this.ws && this.ws.readyState === WebSocket.CONNECTING

      if (this.ws && (this.connected || connecting) && this.currentTaskId === want) {
        return
      }
      if (this.ws && (this.connected || connecting)) {
        // taskId 变了：静默关闭旧连接后重连（走 reconnectNow 避免触发退避）
        this.currentTaskId = want
        this.manualClose = true
        try { this.ws.close() } catch { /* 忽略关闭异常 */ }
        this.ws = null
        this.connected = false
      }

      this.currentTaskId = want
      this.manualClose = false

      const token = localStorage.getItem('token') || ''
      const base = import.meta.env.VITE_WS_BASE_URL || `ws://${location.host}`
      // taskId 为空时带上空值即可 —— 后端 resolveTaskId 会落到 "default" 频道，
      // 该连接依然在广播集合里，审批事件照收不误。
      const qs = [`taskId=${encodeURIComponent(want)}`]
      if (token) qs.push(`token=${encodeURIComponent(token)}`)
      const url = `${base}/ws/agent?${qs.join('&')}`

      try {
        this.ws = new WebSocket(url)
      } catch (e) {
        console.error('WebSocket 建立失败', e)
        this.scheduleReconnect()
        return
      }

      this.ws.onopen = () => {
        this.connected = true
        this.retryCount = 0
      }

      this.ws.onmessage = (event) => {
        let data
        try {
          data = JSON.parse(event.data)
        } catch {
          return
        }
        this.messages.push(data)
        // 只留最近 500 条，长时间开着页面不会无限增长
        if (this.messages.length > 500) {
          this.messages = this.messages.slice(-500)
        }
        this.handleApprovalEvent(data)
      }

      this.ws.onclose = () => {
        this.connected = false
        if (!this.manualClose) this.scheduleReconnect()
      }

      this.ws.onerror = () => {
        // onerror 之后必然触发 onclose，重连逻辑统一放在 onclose
        this.connected = false
      }
    },

    /**
     * 解析审批事件。
     *
     * 后端把结构化字段拼在 content 里（requestId/operation/reason/timeout），
     * 因为 AgentMessage 只有 type/content/stepName 三个自由字段可用。
     */
    handleApprovalEvent(data) {
      const type = data?.type
      if (!type || !type.startsWith('APPROVAL_')) return

      const content = data.content || ''
      const field = (key) => {
        const m = content.match(new RegExp(`\\|${key}=([^|]*)`))
        return m ? m[1] : ''
      }
      const text = content.split('|')[0]

      if (type === 'APPROVAL_PENDING') {
        this.pendingApproval = {
          requestId: field('requestId'),
          operation: field('operation'),
          reason: field('reason'),
          timeout: parseInt(field('timeout') || '55', 10),
          text,
          receivedAt: Date.now()
        }
      } else if (type === 'APPROVAL_DECIDED' || type === 'APPROVAL_TIMEOUT') {
        // 已决定/已超时：关掉弹窗，避免用户对着失效的框点批准
        this.pendingApproval = null
      }
    },

    scheduleReconnect() {
      if (this.reconnectTimer) return
      // 退避重连：1s、2s、4s… 上限 30s，避免后端重启时把连接打满
      const delay = Math.min(1000 * Math.pow(2, this.retryCount), 30000)
      this.retryCount += 1
      this.reconnectTimer = setTimeout(() => {
        this.reconnectTimer = null
        this.connect()
      }, delay)
    },

    disconnect() {
      this.manualClose = true
      if (this.reconnectTimer) {
        clearTimeout(this.reconnectTimer)
        this.reconnectTimer = null
      }
      if (this.ws) {
        this.ws.close()
        this.ws = null
      }
      this.connected = false
      this.pendingApproval = null
    },

    clearMessages() {
      this.messages = []
    }
  }
})
