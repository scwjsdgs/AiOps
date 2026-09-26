/**
 * 统一格式化与枚举映射
 * ====================
 * 改造前的问题：时间直接渲染后端返回的 ISO 串（2026-09-22T13:50:10 带个 T，很别扭），
 * 36 位 UUID 塞在 80px 宽的列里显示不全，状态/级别的颜色判断在每个页面各写一份且不全
 * （比如告警状态后端有 6 种，Alerts.vue 只区分了 RESOLVED 和"其他"）。
 * 这里收敛成一份，所有页面引用同一套。
 */

// ---------------------------- 时间 ----------------------------

/**
 * 后端 LocalDateTime 序列化成 "2026-09-22T13:50:10"（无时区后缀）。
 * 直接 new Date() 在部分浏览器会按 UTC 解析导致差 8 小时，所以手动拆字段，
 * 保证展示的就是后端存的时间。
 */
const parseDateTime = (v) => {
  if (!v) return null
  if (v instanceof Date) return v
  const s = String(v)
  const m = s.match(/^(\d{4})-(\d{2})-(\d{2})[T ](\d{2}):(\d{2}):(\d{2})/)
  if (m) {
    return new Date(+m[1], +m[2] - 1, +m[3], +m[4], +m[5], +m[6])
  }
  const d = new Date(s)
  return isNaN(d.getTime()) ? null : d
}

const pad = (n) => String(n).padStart(2, '0')

/** "2026-09-22 13:50:10"；空值返回 "—"，不留空白格 */
export const formatTime = (v) => {
  const d = parseDateTime(v)
  if (!d) return '—'
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())} ` +
    `${pad(d.getHours())}:${pad(d.getMinutes())}:${pad(d.getSeconds())}`
}

/** 只要日期 "09-22"，表格窄列用 */
export const formatDate = (v) => {
  const d = parseDateTime(v)
  if (!d) return '—'
  return `${pad(d.getMonth() + 1)}-${pad(d.getDate())} ${pad(d.getHours())}:${pad(d.getMinutes())}`
}
  const d = parseDateTime(v)
  if (!d) return '—'
  return `${pad(d.getMonth() + 1)}-${pad(d.getDate())} ${pad(d.getHours())}:${pad(d.getMinutes())}`
}

/**
 * 相对时间："刚刚 / 3 分钟前 / 2 小时前 / 3 天前"。
 * 告警列表里"多久之前"比绝对时间更能反映新鲜度，超过 7 天退回绝对时间。
 */
export const fromNow = (v) => {
  const d = parseDateTime(v)
  if (!d) return '—'
  const diff = Date.now() - d.getTime()
  if (diff < 0) return formatTime(v)
  const min = Math.floor(diff / 60000)
  if (min < 1) return '刚刚'
  if (min < 60) return `${min} 分钟前`
  const hour = Math.floor(min / 60)
  if (hour < 24) return `${hour} 小时前`
  const day = Math.floor(hour / 24)
  if (day <= 7) return `${day} 天前`
  return formatTime(v)
}

/** 两个时间点之间的耗时，中文可读："1 分 23 秒" */
export const durationBetween = (start, end) => {
  const a = parseDateTime(start)
  const b = parseDateTime(end)
  if (!a || !b) return '—'
  const ms = b - a
  if (isNaN(ms) || ms < 0) return '—'
  if (ms < 1000) return '不到 1 秒'
  if (ms < 60000) return `${(ms / 1000).toFixed(1)} 秒`
  const min = Math.floor(ms / 60000)
  const sec = Math.round((ms % 60000) / 1000)
  return `${min} 分 ${sec} 秒`
}

// ---------------------------- ID ----------------------------

/**
 * UUID 缩短显示：前 8 位 + 省略号。列表里靠它保持可读，
 * 完整值放进 tooltip，点击可复制（见 CopyId 组件）。
 */
export const shortId = (id) => {
  if (!id) return '—'
  const s = String(id)
  return s.length > 12 ? s.slice(0, 8) : s
}

// 复制到剪贴板：优先用 Clipboard API，非 https 环境降级到 execCommand
export const copyText = async (text) => {
  if (!text) return false
  try {
    if (navigator.clipboard && window.isSecureContext) {
      await navigator.clipboard.writeText(text)
      return true
    }
  } catch (e) {
    // 落到下面的降级分支
  }
  try {
    const ta = document.createElement('textarea')
    ta.value = text
    ta.style.position = 'fixed'
    ta.style.opacity = '0'
    document.body.appendChild(ta)
    ta.select()
    const ok = document.execCommand('copy')
    document.body.removeChild(ta)
    return ok
  } catch (e) {
    return false
  }
}

// ---------------------------- 告警级别 ----------------------------

/**
 * 后端实际出现的级别：CRITICAL / WARNING / INFO（预测性预警也是 WARNING）。
 * 统一映射成 el-tag 的 type + 中文 + 色点色值。
 */
const SEVERITY_MAP = {
  CRITICAL: { type: 'danger', text: '严重', color: '#ef4444' },
  WARNING: { type: 'warning', text: '警告', color: '#f59e0b' },
  INFO: { type: 'info', text: '提示', color: '#64748b' }
}

export const severityMeta = (s) =>
  SEVERITY_MAP[String(s || '').toUpperCase()] || { type: 'info', text: s || '未知', color: '#94a3b8' }

// ---------------------------- 告警状态 ----------------------------

/**
 * 后端 AlertEntity.status 的实际取值（grep 出来的一手结论）：
 *   FIRING      —— 告警触发中（未处理）
 *   PENDING     —— 已接入待分析
 *   ANALYZING   —— agent 分析中
 *   RESOLVED    —— 已解决
 *   CORRELATED  —— 被降噪聚合进已有任务的分析（醒目区分，不是"丢失"）
 *   FAILED      —— 分析失败
 *   TIMEOUT     —— 处理超时
 */
const ALERT_STATUS_MAP = {
  FIRING: { type: 'danger', text: '触发中' },
  PENDING: { type: 'warning', text: '待分析' },
  ANALYZING: { type: 'primary', text: '分析中' },
  RESOLVED: { type: 'success', text: '已解决' },
  CORRELATED: { type: 'info', text: '已聚合' },
  FAILED: { type: 'danger', text: '失败' },
  TIMEOUT: { type: 'warning', text: '超时' }
}

export const alertStatusMeta = (s) =>
  ALERT_STATUS_MAP[String(s || '').toUpperCase()] || { type: 'info', text: s || '未知' }

// ---------------------------- 任务状态 ----------------------------

/**
 * Task.status 取值：PENDING / RUNNING / SUCCESS / FAILED / TIMEOUT / REGRESSED。
 * REGRESSED 是独立语义终态：修复执行成功但观察期内故障复现 —— 不是 SUCCESS 也不是 FAILED，
 * 单独着色并在文案里说清楚，否则会被误读成"修好了"。
 */
const TASK_STATUS_MAP = {
  PENDING: { type: 'info', text: '排队中' },
  RUNNING: { type: 'warning', text: '分析中' },
  SUCCESS: { type: 'success', text: '已完成' },
  FAILED: { type: 'danger', text: '失败' },
  TIMEOUT: { type: 'warning', text: '超时' },
  REGRESSED: { type: 'danger', text: '修复后复现' }
}

export const taskStatusMeta = (s) =>
  TASK_STATUS_MAP[String(s || '').toUpperCase()] || { type: 'info', text: s || '未知' }

/** 任务是否已到终态：终态停止轮询，未终态每 3 秒续查 */
export const isFinalTaskStatus = (s) =>
  ['SUCCESS', 'FAILED', 'TIMEOUT', 'REGRESSED'].includes(String(s || '').toUpperCase())

// ---------------------------- 推理步骤分型 ----------------------------

/**
 * agent 步骤的 stepName 枚举，与 aiops-agent/tools/agent_tools.py 里的
 * send_step(task_id, content, "<name>") 逐一对齐。
 *
 * 这些名字必须显式列出，不能靠猜测文本：后端 describe() 把步骤写成
 * "[status_check] (STEP) 正在查询 nginx 状态" —— 括号里恒为事件类型 STEP，
 * 工具名只在方括号/stepName 字段里，靠 "(tool)" 之类的字样永远匹配不上。
 */
const TOOL_STEPS = new Set([
  'status_check', 'log_analysis', 'metrics_query', 'pod_events', 'impact_analysis'
])
const REPAIR_STEPS = new Set([
  'action_execution', 'action_result', 'scaling', 'rollback'
])
const ERROR_STEPS = new Set(['action_error'])
const APPROVAL_STEPS = new Set(['awaiting_approval'])

/** 从步骤文本里抠出 stepName，时间线上直接显示成标签 */
export const stepToolName = (step) => {
  const m = String(step || '').match(/^\[([A-Za-z0-9_]+)\]/)
  return m ? m[1] : ''
}

/**
 * 推理步骤分型，供时间线着色与配图。
 *
 * 判型优先用 stepName（结构化、无歧义）：实时监控页把 stepName 与 content
 * 拼成一个串再调这里，任务/告警页传的是后端拼好的 "[stepName] (type) content"，
 * 两种情况都能从串首/方括号里取到同一个 stepName。
 *
 * 失败态优先于成功态：stepName 为 action_error，或内容里出现「失败/超时/异常」时，
 * 一律判 error 标红 —— 否则一个失败的工具调用会被当成正常调用显示成蓝色，
 * 恰恰把最该被看见的信息盖掉了。
 */
export const stepKind = (step) => {
  const s = String(step || '')
  const name = stepToolName(s) || s.trim().split(/\s+/)[0]

  if (ERROR_STEPS.has(name) || /失败|超时|异常|ERROR/i.test(s)) return 'error'
  if (APPROVAL_STEPS.has(name) || /审批|approval/i.test(s)) return 'approval'
  if (TOOL_STEPS.has(name)) return 'tool'
  if (REPAIR_STEPS.has(name)) return 'repair'

  // 兜底：stepName 缺失或格式变动时，退回文本特征判断
  if (/TOOL_CALL|\(tool\)/i.test(s)) return 'tool'
  if (/thought|观察|推理/i.test(s)) return 'thought'
  return 'step'
}

// ---------------------------- 数值 ----------------------------

/** 字节转 MB，保留 1 位 */
export const toMB = (bytes) => {
  if (bytes === undefined || bytes === null || bytes === '') return '—'
  return (Number(bytes) / 1024 / 1024).toFixed(1)
}

/** 大数字千分位，统计卡片用 */
export const formatNumber = (n) => {
  if (n === undefined || n === null || n === '') return '0'
  const v = Number(n)
  return isNaN(v) ? '0' : v.toLocaleString('zh-CN')
}
