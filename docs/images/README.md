# 演示截图 / GIF

此目录存放 README 第 10 节引用的演示截图与 GIF。当前已放入以下实测截图：

| 文件 | 内容 | 用途 |
|---|---|---|
| `pod容器异常发出告警.png` | Prometheus/Alertmanager 真实告警进入平台 | 自动告警闭环 |
| `未接受告警前.png` | 告警接入前前端待处理状态 | 告警接入断面 |
| `告警大屏-上半部分.png` | 告警大屏总览与趋势 | 告警大屏 |
| `告警大屏-中间部分.png` | 告警列表与影响面 | 告警大屏 |
| `告警大屏-底部.png` | 告警大屏底部明细 | 告警大屏 |
| `chatops-高可用降级演示.png` | ChatOps 高可用降级演示 | ChatOps 降级展示 |
| `基线预警.png` | 指标基线偏离预警 | 前瞻式运维 |
| `metrics-底层重启波峰.png` | 底层指标重启波峰 | 指标/根因分析 |
| `approval-AI识别误报.png` | 审批页 AI 误报识别 | 人工审批 |
| `approval-告警风暴与超时安全兜底.png` | 告警风暴与超时安全兜底 | 人工审批 |
| `approval-拒绝后安全降级.png` | 审批拒绝后的 fail-closed | 人工审批 |
| `approval-清理现场后遗留审批.png` | 清理现场后遗留审批场景 | 人工审批 |
| `approval-真实故障待审批.png` | 真实故障进入待审批 | 人工审批 |
| `alertmanager-触发-告警推送.png` | Alertmanager 触发与告警推送 | 自动告警闭环 |

## 后续可补充素材

- `chatops-demo.gif`：ChatOps 交互式诊断完整流式过程。
- `alert-loop.png`：告警从 PENDING → ANALYZING → RESOLVED 状态流转。
- `dashboard.png`：仪表板告警趋势 + 级别分布 + 任务统计。

## 录制工具建议

- GIF：ScreenToGif（Windows，免费）或 `ffmpeg -i in.mp4 -vf "fps=10,scale=800:-1" out.gif`
- 截图：Win+Shift+S 或 Snipaste
