# 安全审计、运维工具与根因图谱

## 1. 安全审计

新增 `audit_logs` 表（JPA 自动建表），所有重要入口记录：

| 场景 | 分类 | 动作 |
|---|---|---|
| 登录成功/失败 | LOGIN | login_success / login_failed |
| 前端注入告警 | ALERT | alert_received |
| Alertmanager Webhook | ALERT | webhook_received / webhook_auth_failed / webhook_processing_failed |
| 工具执行 | TOOL | tool_execute（含 contextId、成功/失败、通过/校验失败） |
| 审批决策 | APPROVAL | decision（批准/拒绝） |
| 混沌评测 | CHAOS | chaos_run |
| 人工操作回放 | REPLAY | 前端 `POST /api/audit/replay` |

审计页 `/audit` 支持分类/关键词筛选、近 7 天分类统计。

## 2. 新增运维工具

Java 侧新增 6 个工具，全部注册进 `ToolRegistryService` 并出现在工具管理页：

| 工具 | 参数 | 说明 | 风险 |
|---|---|---|---|
| config_rollback | deployment/service | Deployment `rollout undo` | 高危 |
| traffic_switch | service, activeDeployment, standbyDeployment | Service selector 切换后端 | 高危 |
| pod_evict | pod/podName | 删除 Pod 由控制器重建 | 高危 |
| node_drain | node/nodeName, action=drain\|uncordon | 标记节点不可调度/恢复 | 高危 |
| adjust_connection_pool | deployment, poolKey, poolValue | 环境变量调整连接池并滚动 | 高危 |
| adjust_health_check | deployment, probe, initialDelaySeconds, failureThreshold | 调整 liveness/readiness 探针 | 高危 |

这些工具默认不加入 `agent-exposed` 白名单，工具页会显示“仅人工执行”；
只有当需要 agent 自主执行时才显式加入 `opsagent.tools.agent-exposed`，请保持保守。

## 3. 单集群 namespace 隔离

`/api/environment` 返回当前 namespace 与模式说明。
所有工具、状态、拓扑、影响面、基线均基于 `opsagent.kubernetes.namespace`，
不再维护多套 kind 集群。若要切换环境，修改 application.yml 后重启 Java 即可。

## 4. 根因图谱与相似故障推荐

- `/api/intelligence/root-cause-graph`：基于 Redis 任务、推理步骤、输入服务名，
  自动生成 Service / 告警 / 任务 / 步骤 四类节点与告警触发、分析、影响关系边。
- `/api/intelligence/similar-tasks`：按服务名/关键词检索历史任务，
  返回状态、关键词与“复看历史报告”建议。

前端页面 `/root-cause` 用 ECharts 展示图谱与推荐列表。