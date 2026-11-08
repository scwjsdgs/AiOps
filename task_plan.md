# 任务计划：智能运维前端界面现代化改造

**目标：** 将当前 Vue3 + Element Plus 前端页面改造成具有现代感、高级感的响应式大屏/仪表盘，并按“架构设计 -> 编码 -> 测试”流水线交付。

**项目模块：**
- ops-agent-front：Vue3 + Vite + Element Plus + ECharts，端口 3000

---

## 阶段 1：超级头脑风暴与文件规划

| 子任务 | 内容 | 状态 |
|--------|------|------|
| superpowers:brainstorm | 头脑风暴：现代化响应式大屏/仪表盘方案 | complete |
| planning-with-files | 将 UI 改造计划写入 task_plan.md | complete |

---

## 阶段 2：查新与核心重构

| 子任务 | 内容 | 状态 |
|--------|------|------|
| context7 | 查 Vue3/Vite + Element Plus + ECharts 最新规范 | complete |
| nxtg-forge | 按“架构设计 -> 编码 -> 测试”流水线重构前端 | complete |

**已重构页面：** Dashboard、Alerts、Task、RealTime、Approvals、Login、AuditLogs、RootCause、ChatOps。Tool.vue 已有现代化样式，保留。

---

## 阶段 3：前端美化与代码瘦身

| 子任务 | 内容 | 状态 |
|--------|------|------|
| frontend-design | 深度美化 Vue 组件（统一设计系统、玻璃拟态、ECharts 可视化、响应式断点） | complete |
| code-simplifier | 消除冗余 CSS、深层嵌套、重复样式 | complete |

**设计系统：** theme.css 已收敛为 CSS 变量（主色/语义色/中性色/背景层/圆角/阴影/间距/玻璃拟态/响应式断点/动画）。所有页面引用变量，不再写死色值。

---

## 阶段 4：启动服务、全量截图、扫描与审查

| 子任务 | 内容 | 状态 |
|--------|------|------|
| Bash | 启动 Java 后端 (8081)、Python 后端 (5000)、前端 (3000)，curl 验证接口 | in_progress |
| playwright | 遍历登录页/仪表盘/告警列表，全尺寸截图存到 screenshots/，生成 PREVIEW.md | pending |
| semgrep | 前端项目安全扫描 | pending |
| codex | 对抗性审查 | pending |
| touchstone | CRAP 复杂度检查 | pending |

---

## 阶段 5：文档更新与 GitHub 推送

| 子任务 | 内容 | 状态 |
|--------|------|------|
| claude-md-management | 更新 CLAUDE.md：写入新前端规范、screenshots/ 目录说明 | pending |
| Git | git add / commit / push 到 scwjsdgs/aiops | pending |

---

## 决策记录

- 2026-11-08：前端现有页面为 Login/Dashboard/Alerts/Approvals/Task/RealTime/Tool/ChatOps/AuditLogs/RootCause，均已读完。Layout 已有深色侧边栏 + 顶栏 + 响应式折叠，可作为现代化基座。
- UI 改造方向：深色品牌色 + 玻璃拟态卡片 + ECharts 可视化 + 响应式断点 + 统一设计 token，不改动业务逻辑与 API 接口。
- 环境限制：严禁全局安装依赖，项目内安装必须记入 progress.md，缺依赖必须用 which/where 查本地绝对路径。
