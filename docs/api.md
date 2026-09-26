# API 参考

所有 REST 接口均位于 `/api` 前缀。前端通过 Vite 代理直接调用相对路径。

## 响应约定：HTTP 状态码与 body.code

**鉴权与请求类错误同时体现在两处**：HTTP 状态码与 body 里的 `code` 字段保持一致，
调用方读哪个都行。

| 场景 | HTTP | body.code | 说明 |
|------|------|-----------|------|
| 登录失败（用户名/密码错） | **401** | 401 | 用户不存在与密码错误返回同一 message，避免账号枚举 |
| 缺 JWT / token 失效 | **401** | 401 | `JwtAuthenticationFilter` 直接返回 |
| 请求体格式错误 | **400** | 400 | JSON 语法错误、字段类型不匹配 |
| 审批单号不存在 / 重复审批 | **400** | 400 | `BusinessException` 按 code 映射 |
| 任务不存在 | **404** | 404 | |
| 上游服务异常 | **502** | 502 | Python agent / K8s 不可达 |
| 服务端未知异常 | **500** | 500 | |

**工具执行结果例外**：`/api/tools/execute` 与 `/api/agent/callback/tool` 对「工具执行失败」
**始终返回 HTTP 200**，成败由 body 里的 `data.success` 表达。

这是刻意的设计而非疏漏：Python agent 侧用 `resp.raise_for_status()` 调工具，
若把「工具执行失败」映射成非 2xx，Agent 会在异常分支里丢掉结构化的失败信息
（`message` / `data`），无法如实告诉 LLM「重启失败了，原因是什么」。
HTTP 语义上「请求成功送达」与「被请求的操作成功执行」本就是两件事。

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

传统 HTTP 上报（兼容旧接口，受前端/脚本调用）。字段名支持别名兼容：`level`/`severity`、`message`/`title`/`summary`、`service`/`serviceName`/`svc`/`namespace`、`detail`/`msg`。

请求体（推荐使用实体字段）：

```json
{
  "source": "order-service",
  "severity": "critical",
  "title": "Pod CrashLoopBackOff",
  "description": "容器反复重启",
  "serviceName": "order-service",
  "host": "10.0.0.1"
}
```

兼容别名示例：

```json
{
  "service": "order-service",
  "level": "critical",
  "message": "Pod CrashLoopBackOff",
  "namespace": "default"
}
```

响应：

```json
{
  "code": 200,
  "message": "success",
  "data": null
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

### 工具返回值的 `success` 语义（重要）

工具执行结果**始终是 HTTP 200**，成败由 body 里的 `data.success` 表达。
但不同工具对「目标不存在」的处理**刻意不同**，调用方不能只看 `success`：

| 工具类型 | 场景 | `success` | 判读方式 |
|----------|------|-----------|----------|
| 只读工具（`get_status`） | Deployment 不存在 | **`true`** | 看 `data.status == "not_found"`。不存在是一个**事实**而非异常，让 LLM 判断「服务根本没部署」，而不是看到"查询失败"后去重试别的工具 |
| 写操作工具（`scale_up` / `restart_service` / `rollback`） | Deployment 不存在 | **`false`** | 直接看 `success`，message 为 `Deployment not found` |
| 参数校验失败（任何工具） | replicas 越界、缺必填参数 | `false` | 校验在访问 apiserver **之前**完成，绝不触碰集群 |

```bash
# 只读：不存在 → success=true，需看 status 字段
curl -s -X POST localhost:8081/api/tools/execute -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"toolName":"get_status","parameters":{"deployment":"no-such-svc"}}'
# → {"success":true,"data":{"status":"not_found","replicas":0,...}}

# 写操作：不存在 → success=false
curl -s -X POST localhost:8081/api/tools/execute -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"toolName":"scale_up","parameters":{"deployment":"no-such-svc","replicas":2}}'
# → {"success":false,"message":"Deployment not found: no-such-svc (namespace=default)"}
```

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