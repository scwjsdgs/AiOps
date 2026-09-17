# Alertmanager Webhook 接入使用说明

## 接入流程

Alertmanager → `POST /api/alerts/webhook` → Java 解析（`AlertWebhookService`）→ 幂等去重 →
firing 写入 MySQL 并触发 AI 分析；resolved 更新对应告警终态为 RESOLVED，不重复建任务。

## 配置

### Java 侧 `application.yml`

```yaml
opsagent:
  # Webhook 相关配置
  webhook:
    token: ${OPSAGENT_WEBHOOK_TOKEN:dev-webhook-token}   # 请求必须携带 X-Webhook-Token 头
    hmac:
      secret: ${OPSAGENT_WEBHOOK_HMAC_SECRET:}           # 可选：配置后要求 X-Signature 头
    idempotency:
      ttlMinutes: 5                                       # Redis 幂等去重 TTL
    fingerprint-keys: alertname,instance,severity         # fingerprint 组成键
```

注意：`token` 置空表示不校验（仅本地调试）；生产环境务必从环境变量注入真实令牌。

### 限流配置

`WebhookRateLimiterConfig` 定义了 Resilience4j `RateLimiter` bean（默认每秒 60 次），
`AlertWebhookController` 通过 `@RateLimiter(name = "webhook")` 启用。超限自动返回 429，
用于抵御告警风暴时瞬时冲垮 AI 推理链路。可在配置类中调整 `limitForPeriod` / `limitRefreshPeriod`。

### Alertmanager `alertmanager.yml`

```yaml
route:
  receiver: aiops
receivers:
  - name: aiops
    webhook_configs:
      - url: http://ops_agent:8081/api/alerts/webhook
        http_config:
          headers:
            X-Webhook-Token: dev-webhook-token
```

## 安全防线

1. `JwtAuthenticationFilter` 放行 `/api/alerts/webhook` 路由，不校验 JWT；Controller 内部校验 `X-Webhook-Token`。
2. 可选 HMAC 签名校验（配置了 `hmac.secret` 后要求 `X-Signature` 头）。
3. 入口限流：Resilience4j RateLimiter，每秒 60 次。
4. 幂等去重：Redis `webhook:dup:{fingerprint}`，TTL 5 分钟。

## 去重与限流说明

- 指纹键：`alertname|instance|severity`（可配置 `fingerprint-keys`）。
- **去重仅对 firing 生效**：resolved 是终态事件，若也复用同名 key，5 分钟内先 firing 后 resolved 会被去重挡掉，告警状态会永远停在 ANALYZING。
- firing 重复请求直接丢弃并记 metric `agent.webhook.duplicate`；resolved 重复会重复更新状态（幂等）。
- Metric：`agent.webhook.received / duplicate / resolved`。

## 接口清单

- `POST /api/alerts/webhook` 接收 Alertmanager webhook，返回 202。
- `POST /api/alerts` 传统 HTTP 上报。
- Kafka `alerts` topic 消息消费。

## 验证

```bash
# 1. firing 测试（预期 202，MySQL 新增 PENDING 告警，AI 开始分析）
curl -i -X POST http://localhost:8081/api/alerts/webhook \
  -H "X-Webhook-Token: dev-webhook-token" \
  -H "Content-Type: application/json" \
  -d '{
    "version":"4","status":"firing","receiver":"aiops",
    "alerts":[{
      "status":"firing",
      "labels":{"alertname":"PodCrashLoopBackOff","severity":"critical","service":"order-service","instance":"10.10.0.5"},
      "annotations":{"summary":"Pod 崩溃","description":"容器反复重启"},
      "startsAt":"2026-09-26T08:00:00Z"
    }]
  }'

# 2. 重复请求（5 分钟内应被去重：202 但告警/任务不新增）
curl -i -X POST http://localhost:8081/api/alerts/webhook \
  -H "X-Webhook-Token: dev-webhook-token" -H "Content-Type: application/json" \
  -d '{"version":"4","status":"firing","receiver":"aiops","alerts":[{...同上...}]}'

# 3. resolved 测试（应更新告警为 RESOLVED，不新建任务）
curl -i -X POST http://localhost:8081/api/alerts/webhook \
  -H "X-Webhook-Token: dev-webhook-token" -H "Content-Type: application/json" \
  -d '{
    "version":"4","status":"resolved","receiver":"aiops",
    "alerts":[{
      "status":"resolved",
      "labels":{"alertname":"PodCrashLoopBackOff","severity":"critical","service":"order-service","instance":"10.10.0.5"},
      "annotations":{"summary":"Pod 恢复"},
      "startsAt":"2026-09-26T08:00:00Z","endsAt":"2026-09-26T08:10:00Z"
    }]
  }'
```

验证要点：

- firing 后 202 返回，MySQL `alerts` 表新增一条 PENDING 且带 `fingerprint` 字段，Redis `task:*` 新建，AI 侧开始 ReAct 推理。
- 重复 firing 不新建任务，metric `agent.webhook.duplicate` 增加。
- resolved 后告警状态变为 `RESOLVED`，任务不受影响。
- 前端 Dashboard 一键 AI 诊断会实时推送推理步骤；高危操作时审批链路出现待审批单。

完整 API 见 [api.md](./api.md)。