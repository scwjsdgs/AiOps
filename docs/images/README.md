# 演示截图 / GIF

此目录存放 README 第 10 节引用的演示截图与 GIF。请人工录制后放入，文件名对应：

## 需要录制的素材

| 文件 | 内容 | 录制建议 |
|---|---|---|
| `chatops-demo.gif` | ChatOps 交互式诊断 | 打开前端 /chatops 页面，输入「leaky-app 为什么频繁重启？」，录制 agent 流式推理到报告输出的完整过程（约 30-60s，建议 8-10fps 压缩体积） |
| `alert-loop.png` | 全自动告警闭环 | 运行 `bash demo.sh` 期间，截取前端 /alerts 页面：告警从 PENDING → ANALYZING → RESOLVED 的状态流转 + CORRELATED 降噪标记 |
| `dashboard.png` | 仪表板 | 前端 /dashboard 页面：告警趋势图 + 级别分布 + 任务统计卡片 |

## 录制工具建议

- GIF：ScreenToGif（Windows，免费）或 `ffmpeg -i in.mp4 -vf "fps=10,scale=800:-1" out.gif`
- 截图：Win+Shift+S 或 Snipaste
