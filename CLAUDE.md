# 项目全局规则
- 请用中文回答我的所有问题。
- 请全部用中文进行思考和推理。
- 总结代码时，风格要流畅、易读。

## 前端现代化改造规范（2026-11-08）

### 设计系统（src/styles/theme.css）
所有页面收敛为 CSS 变量，不再写死色值：
- 品牌色：--c-primary / --c-primary-dark / --c-primary-light / --c-primary-soft（深空蓝）
- 语义色：--c-success / --c-warning / --c-danger / --c-info 及其 -soft 变体
- 中性色：--c-text / --c-text-secondary / --c-text-muted / --c-text-inverse
- 背景层：--c-bg / --c-surface / --c-surface-hover / --c-border / --c-border-soft
- 玻璃拟态：--glass-bg / --glass-border / --glass-shadow / --glass-blur（glass-card / glass-card__body）
- 圆角：--radius-lg(16px) / --radius-md(12px) / --radius-sm(8px) / --radius-xs(6px) / --radius-full
- 阴影：--shadow-xs / --sm / --md / --lg / --glow
- 间距：--space-xs / --sm / --md / --lg / --xl / --page / --card
- 断点：--bp-sm(640px) / --md(768px) / --lg(1024px) / --xl(1280px)

### 页面级通用类
- `.page`：页面根容器；`.page > * + *` 自动间距 var(--space-page)
- `.page-header`：左标题 + 右操作栏；`.page-title` / `.page-subtitle`
- `.section-title`：区块标题 + 分割线
- `.stat-card`：关键指标卡（label / value / hint / icon，tone 区分颜色）
- `.chart-card` / `.chart-container`：图表卡（ECharts，配色与设计系统一致）
- `.glass-card`：玻璃拟态卡片（backdrop-filter + 半透明边框）
- `.empty-state`：空状态（icon / title / desc）

### Element Plus 外观对齐
- el-card：圆角 var(--radius-lg)、浅边框、极轻阴影；header 背景 var(--c-surface-hover)
- el-table：头部 var(--c-surface-hover)、行 hover var(--c-primary-soft)、单元格 padding 12px 0
- el-button：圆角 var(--radius-md)、hover 带 glow；primary/danger/success/warning/info 均统一
- el-tag：圆角 var(--radius-sm)、font-weight 500；tone 对应语义色 -soft 背景
- el-input / el-select / el-textarea：focus 时边框 var(--c-primary) + 3px glass 阴影

### 响应式
- 768px 以下：page-header 改为纵向、toolbar 宽度 100%、charts 全宽、stat-card 改为纵向、登录页隐藏品牌区
- 900px 以下：ChatOps 侧边栏改为上下堆叠，高度固定 300px

### XSS 防护
- marked 渲染用户内容前，必须通过 stripDangerous 去掉 <script>、on* 事件属性、javascript: 链接（Alerts.vue、Task.vue、ChatOps.vue）
- 因环境不可安装 dompurify，轻量级正则防护作为兜底；后续可替换为 DOMPurify

### 截图目录
- ops-agent-front/screenshots/：Playwright 全量截图，命名 01-login.png … 11-tools.png
- ops-agent-front/PREVIEW.md：截图预览索引，包含每张图的 URL 与相对路径

### 启动端口
- Java 后端 ops_agent：8081（本机无 JVM，未能启动）
- Python 后端 aiops-agent：5000（venv python main.py，已启动）
- 前端 ops-agent-front：3000（3000–3004 被占用，实际运行在 3005）
- 前端代理：/api → localhost:8081，/ws → ws://localhost:8081