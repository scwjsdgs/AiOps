# API 参考

所有 REST 接口均位于 `/api` 前缀。前端通过 Vite 代理直接调用相对路径。

## 告警接入

### 三条接入路
1. **HTTP 直接上报** `POST /api/alerts`
2. **Kafka 消息** Topic `alerts`（`AlertIngestionService.consumeAlert`）
3. **Alertmanager Webhook** `POST /api/alerts/webhook`（`AlertWebhookService`）

### POST /api/alerts/webhook

接收 Alertmanager 原生 webhook，触发自动根因分析。

**认证**：请求头 `X-Webhook-Token`（配置 `opsagent.webhook.token`），可选 `X-Signature` HMAC 校验。

**限流**：Resilience4j RateLimiter `webhook`，每秒 60 请求，超限返回 429。

**幂等去重**：`fingerprint = alertname|instance|severity`，Redis SETNX TTL 5 分钟，同一 fingerprint 在 TTL 内不重复触发 AI 分析。

**请求体（Alertmanager 标准）**：

```json
{
  "version":"4",
  "status":"firing",
  "receiver":"aiops",
  "alerts":[{
    "status":"firing",
    "labels":{"alertname":"PodCrashLoopBackOff","severity":"critical","service":"order-service","instance":"10.10.0.5"},
    "annotations":{"summary":"Pod 持续崩溃","description":"容器反复重启"},
    "startsAt":"2026-09-26T08:00:00Z"
  }]
}
```

**响应**：202 Accepted（后台异步处理）

**resolved 处理**：`status=resolved` 时按 fingerprint 定位原告警，将其状态更新为 `RESOLVED`，不新建 AI 任务。

### POST /api/alerts

传统 HTTP 上报（兼容旧接口，受前端/脚本调用）。

请求体：

```json
{
  "service":"order-service",
  "level":"critical",
  "message":"Pod CrashLoopBackOff",
  "namespace":"default"
}
```

响应：

```json
{
  "taskId":"task-001",
  "status":"accepted"
}
```

### GET /api/alerts

分页查询告警列表（前端 Alerts 页）。参数 `page`、`size`，排序按创建时间降序。

### GET /api/alerts/stats

Dashboard 统计：最近 N 天每日分级计数 + 级别分布 + 状态计数。参数 `days` 默认 7。

## 任务

| 路径 | 方法 | 说明 |
|------|------|------|
| `/api/tasks` | GET | 分页任务列表，可按 `alertId` 过滤 |
| `/api/tasks/{id}` | GET | 获取单任务详情（含 agentSteps / output） |

## 审批

| 路径 | 方法 | 说明 |
|------|------|------|
| `/api/approvals` | GET | 拉取审批单，支持 `status` 过滤 |
| `/api/approvals/{requestId}/decision` | POST | 提交审批决策 `{ approved, note }`，由 JWT 校验用户 |

## 工具

| 路径 | 方法 | 说明 |
|------|------|------|
| `/api/tools/list` | GET | 查询所有已注册工具（包含 generic_command） |
| `/api/tools/execute` | POST | 手工执行工具（不受 LLM 白名单限制，运维演练用） |

## 集群状态

| 路径 | 方法 | 说明 |
|------|------|------|
| `/api/cluster/status` | GET | 查询命名空间下所有 Deployment 的副本/就绪/镜像状态，Dashboard 集群卡片用 |

## AI 回调

| 路径 | 方法 | 说明 |
|------|------|------|
| `/api/agent/callback/step` | POST | AI 大脑回传推理步骤，存入任务 agentSteps 并 WebSocket 推送 |
| `/api/agent/callback/tool` | POST | AI 大脑请求执行工具 |
| `/api/agent/callback/complete` | POST | AI 大脑回传最终报告，任务终态落库并回写告警状态 |

所有 AI 回调需携带 `X-Internal-Token`（与 `aiops-agent/.env` 的 `JAVA_INTERNAL_TOKEN` 一致）。

## WebSocket

| 路径 | 说明 |
|------|------|
| `/ws/agent` | 实时订阅推理步骤。参数 `taskId` + `token=JWT`（前端自动拼接）。 |

## 鉴权与安全防线

1. **JWT 登录认证**：前端所有管理接口走 Bearer JWT。`JwtAuthenticationFilter` 放行 `/api/auth/login`、`/actuator/health`、`/ws/agent`（校验 query string token）、`/api/alerts/webhook`（自身校验）。
2. **内部通信**：AI 大脑回调 `/api/agent/callback/**` 必须携带 `X-Internal-Token`。
3. **Webhook 自身认证**：Alertmanager Webhook 不走 JWT，走 `X-Webhook-Token`，可在 `application.yml` 关闭（仅开发调试）。可选 HMAC 签名校验。
4. **工具白名单**：LLM 仅能调用 `opsagent.tools.agent-exposed` 列表，`generic_command` 不暴露给 AI，但可在 `/api/tools/execute` 手工使用。
5. **防重复执行**：ReAct 循环非幂等，Python 侧 `_inflight` 注册表 + Java 侧 409 去重双层互锁。重启、扩容等操作不做自动重试。

令牌需在 `ops_agent/.env.example`、application.yml 与 `aiops-agent/.env` 中保持一致。