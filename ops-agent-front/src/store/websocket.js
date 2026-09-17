import { defineStore } from 'pinia'

export const useWebSocketStore = defineStore('websocket', {
  state: () => ({
    ws: null,
    connected: false,
    messages: [],
    lastTaskId: ''
  }),
  actions: {
    connect(taskId) {
      if (this.ws && this.lastTaskId === taskId) return
      if (this.ws) this.disconnect()
      this.lastTaskId = taskId
      const token = localStorage.getItem('token') || ''
      const url = `${import.meta.env.VITE_WS_BASE_URL}/ws/agent?taskId=${taskId}${token ? `&token=${encodeURIComponent(token)}` : ''}`
      this.ws = new WebSocket(url)
      this.ws.onopen = () => {
        this.connected = true
        console.log('WebSocket connected')
      }
      this.ws.onmessage = (event) => {
        const data = JSON.parse(event.data)
        this.messages.push(data)
      }
      this.ws.onclose = () => {
        this.connected = false
        console.log('WebSocket disconnected')
      }
      this.ws.onerror = (err) => {
        console.error('WebSocket error', err)
      }
    },
    disconnect() {
      if (this.ws) {
        this.ws.close()
        this.ws = null
        this.connected = false
      }
    },
    clearMessages() {
      this.messages = []
    }
  }
})