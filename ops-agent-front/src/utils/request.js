import axios from 'axios'
import { ElMessage } from 'element-plus'
import { useUserStore } from '@/store/user'

const request = axios.create({
  baseURL: import.meta.env.VITE_API_BASE_URL,
  timeout: 10000
})

request.interceptors.request.use(
  config => {
    const userStore = useUserStore()
    if (userStore.token) {
      config.headers['Authorization'] = `Bearer ${userStore.token}`
    }
    return config
  },
  error => Promise.reject(error)
)

request.interceptors.response.use(
  response => {
    const res = response.data
    if (res.code !== 200) {
      ElMessage.error(res.message || '请求失败')
      if (res.code === 401) {
        const userStore = useUserStore()
        userStore.logout()
        window.location.href = '/login'
      }
      return Promise.reject(new Error(res.message))
    }
    return res
  },
  error => {
    // 后端 JWT 过滤器返回的是 HTTP 401 状态码（非 2xx），axios 走的是这里
    // 而不是上面的成功回调 —— 必须在这里做登出跳转，否则 token 失效后
    // 用户会被卡在页面上，所有请求反复 401 也回不到登录页。
    //
    // 登录接口的 401 是"账号密码错"，不是"登录态失效"，不能触发登出跳转，
    // 否则用户在登录页输错密码会被强行刷新页面、错误提示一闪而过。
    const status = error.response?.status
    const isLoginRequest = error.config?.url?.includes('/auth/login')
    if (status === 401 && !isLoginRequest) {
      const userStore = useUserStore()
      userStore.logout()
      window.location.href = '/login'
      return Promise.reject(error)
    }
    // 非 2xx 的响应体里同样有后端的 { code, message }，优先展示它，
    // 否则用户只能看到 axios 的 "Request failed with status code 400"，
    // 后端精心写的中文提示（如"审批单不存在"）全被丢掉。
    const backendMsg = error.response?.data?.message
    ElMessage.error(backendMsg || error.message || '网络错误')
    return Promise.reject(error)
  }
)

export default request