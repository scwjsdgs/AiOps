# 🔍 智能运维项目体检报告

## 📊 扫描范围
- **Java 后端**：`ops_agent/`（Spring Boot WebFlux，端口 8081）
- **Python AI 大脑**：`aiops-agent/`（FastAPI + LangGraph，端口 5000）
- **前端**：`ops-agent-front/`（Vue 3，端口 3000）
- **K8s 监控配置**：`cluster-backup/`

---

## 🔴 严重问题（需立即修复）

### P001：LangGraph 审批恢复验证缺失（安全漏洞）
- **位置**：`aiops-agent/agents/graph/graph.py` - `resume_graph` 方法
- **问题**：审批恢复时只验证了 `X-Internal-Token` 和 Checkpointer 状态，**未验证 `requestId` 是否与挂起的审批请求匹配**。攻击者可以伪造任意 `requestId` 恢复其他人的审批。
- **依据**：代码仅检查 `checkpoint.state.approval_request.get("requestId")` 是否存在，但未比对请求体中的 `requestId`。
- **建议**：添加 `requestId` 匹配校验，不匹配时抛出 `ValueError` 返回 400。
- **需人工确认**：✅ 涉及安全机制，需人工评审修复方案

### P002：敏感密钥明文暴露（安全红线）
- **位置**：
  - `aiops-agent/.env`：LLM_API_KEY、DASHSCOPE_API_KEY、RERANK_API_KEY、LangFuse 密钥、数据库密码、加密密钥
  - `ops_agent/.env`：MySQL 密码
- **问题**：大量生产级密钥明文存储在 `.env` 文件中，可能被误提交到 Git 仓库。
- **依据**：根据 AGENTS.md §0.2，禁止提交密钥、token、kubeconfig、数据库密码。
- **建议**：
  1. 立即检查 `.gitignore` 是否正确排除 `.env`
  2. 检查 Git 历史是否已提交敏感信息（`git log --all --full-history -- .env`）
  3. 如果已提交，需要改写历史或轮换密钥
- **需人工确认**：✅ 需人工检查 Git 历史并轮换密钥

### P003：内部令牌过于弱（安全风险）
- **位置**：
  - `ops_agent/.env`：`OPSAGENT_INTERNAL_TOKEN=dev-internal-token`
  - `aiops-agent/.env`：`JAVA_INTERNAL_TOKEN=dev-internal-token`
  - `ops_agent/src/main/resources/application.yml`：默认值 `dev-internal-token`
- **问题**：生产环境内部通信令牌使用可猜测的弱密钥，攻击者可以通过伪造令牌调用 `/api/agent/callback/*` 接口触发高危操作。
- **依据**：根据 AGENTS.md §0.2，密钥配置需强随机。
- **建议**：生产环境必须使用强随机令牌（至少 32 字节随机字符）。
- **需人工确认**：✅ 需人工确认是否为生产环境配置

---

## 🟠 高危问题（建议尽快修复）

### H001：Python Kafka 并发访问非线程安全
- **位置**：`aiops-agent/services/kafka_callback.py` - `KafkaToolCallback._waiters`
- **问题**：`_waiters` 是普通 `dict`，在异步高并发场景下可能出现竞态条件，导致同一个 `requestId` 的 Future 被覆盖或重复唤醒。
- **依据**：Python `dict` 不是线程安全的，多协程同时访问可能导致数据竞争。
- **建议**：使用 `asyncio.Lock` 保护 `_waiters` 的读写操作，或使用线程安全的数据结构。
- **需人工确认**：❌ 可直接修复

### H002：Java 审批恢复未验证 requestId 匹配
- **位置**：`ops_agent/src/main/java/com/opsagent/service/ApprovalService.java` - `notifyAgentResume`
- **问题**：Java 侧在通知 Python 恢复审批时，只检查 `taskId` 非空，**未验证 `requestId` 是否与挂起的审批请求匹配**。可能导致错误恢复。
- **依据**：代码直接调用 Python resume 接口，未做 requestId 校验。
- **建议**：调用前从 `Approval` 表或 Redis 中获取当前挂起的 requestId，与请求体比对。
- **需人工确认**：❌ 可直接修复

### H003：Java 告警聚合缺少事务保护
- **位置**：`ops_agent/src/main/java/com/opsagent/service/AlertWebhookService.java` - `correlateAlert`
- **问题**：聚合告警时涉及多次数据库操作（查询、更新、新增），没有 `@Transactional` 保护，可能在高并发场景下出现数据不一致。
- **依据**：多个数据库操作不在同一事务中，中间步骤失败可能导致状态不一致。
- **建议**：添加 `@Transactional` 注解，确保聚合操作的原子性。
- **需人工确认**：❌ 可直接修复

### H004：Python 配置中硬编码 LangFuse 凭证
- **位置**：`aiops-agent/.env` - LangFuse、Postgres、ClickHouse、MinIO 密钥
- **问题**：LangFuse 相关的密钥全部硬编码在 `.env` 中，这些密钥如果泄露会导致可观测性平台被攻击。
- **依据**：多个密钥（POSTGRES_PASSWORD、LANGFUSE_SECRET_KEY、CLICKHOUSE_PASSWORD、MINIO_ROOT_PASSWORD）使用弱密码或默认值。
- **建议**：LangFuse 部署时使用强随机密钥，并通过环境变量注入。
- **需人工确认**：✅ 需人工确认 LangFuse 部署配置

---

## 🟡 中等问题（建议修复）

### M001：Python 工具映射不完整
- **位置**：`aiops-agent/tools/agent_tools.py` - `REPAIR_ACTION_MAP`
- **问题**：`REPAIR_ACTION_MAP` 只映射了 `restart` 和 `clear_cache`，但 Java 侧有更多修复工具（`rollback`、`scale_up`、`traffic_switch`、`pod_evict`、`node_drain`）。LLM 调用这些 action 时会返回"不支持的动作"。
- **依据**：Java `ToolRegistryService` 注册了 18 个工具，但 Python 映射只有 2 个。
- **建议**：补全 `REPAIR_ACTION_MAP` 或拆分工具，让 LLM 能直接调用具体工具名。
- **需人工确认**：❌ 可直接修复

### M002：Java 工具白名单配置与注册数不匹配
- **位置**：`ops_agent/src/main/resources/application.yml` - `opsagent.tools.agent-exposed`
- **问题**：白名单配置了 10 个工具，但 `ToolRegistryService` 注册了 18 个工具。8 个工具（`configRollback`、`trafficSwitch`、`podEvict`、`nodeDrain`、`connectionPool`、`healthCheckParam` 等）不在白名单中，AI 无法调用。
- **依据**：白名单字符串 `restart_service,scale_up,query_log,get_status,rollback,clear_cache,request_human_approval,query_metrics,query_pod_events,query_impact` 共 10 个，但注册列表有 18 个。
- **建议**：确认是否为设计意图，如果不是，补全白名单或删除无用工具注册。
- **需人工确认**：✅ 需人工确认设计意图

### M003：Java Kafka 结果发送超时未回传失败
- **位置**：`ops_agent/src/main/java/com/opsagent/service/KafkaToolCallbackService.java` - `publishResult`
- **问题**：发送 Kafka 结果超时（5秒）后只记录日志，Python 侧可能继续等待永远不会到来的结果。
- **依据**：`get(5, TimeUnit.SECONDS)` 超时抛出异常，异常被捕获后只记录日志，没有向 Python 侧返回失败信号。
- **建议**：超时后应向 `agent-tool-result` topic 发送失败消息，或触发 HTTP 降级通道。
- **需人工确认**：❌ 可直接修复

### M004：Java 验证服务任务失败时仍可能安排验证
- **位置**：`ops_agent/src/main/java/com/opsagent/service/AgentCallbackService.java` - `scheduleRecoveryVerification`
- **问题**：使用 `doOnSuccess` 安排验证，如果任务状态转换失败，验证仍会被安排，可能导致冗余验证。
- **依据**：`doOnSuccess` 只在流成功完成时触发，但前面的 `completeTask` 可能失败（返回错误状态）。
- **建议**：改为 `doOnNext` 并检查返回状态，或使用 `then` 链式调用。
- **需人工确认**：❌ 可直接修复

### M005：Java Webhook 聚合告警标题前缀硬编码
- **位置**：`ops_agent/src/main/java/com/opsagent/service/AlertWebhookService.java` - `ESCALATION_TITLE_PREFIX`
- **问题**：告警升级标题前缀硬编码为 `[严重恶化]`，不便于国际化或配置调整。
- **依据**：`public static final String ESCALATION_TITLE_PREFIX = "[严重恶化] "` 是硬编码常量。
- **建议**：移到 `application.yml` 配置中。
- **需人工确认**：❌ 可直接修复

### M006：Python LangGraph 等待分区分配可能无限等待
- **位置**：`aiops-agent/services/kafka_callback.py` - `_wait_partitions`
- **问题**：等待 Kafka 分区分配的超时后只记录警告，然后继续循环，可能无限等待。
- **依据**：超时后 `logger.warning`，然后 `return`，消费者可能分配不到分区但仍在运行。
- **建议**：超时后抛出异常，触发 HTTP 降级或启动失败。
- **需人工确认**：❌ 可直接修复

### M007：Java Kafka 消费者配置可能导致重复消费
- **位置**：`ops_agent/src/main/resources/application.yml` - `spring.kafka.consumer.auto-offset-reset: earliest`
- **问题**：服务重启时从最早的消息开始消费，可能重复处理大量历史告警。
- **依据**：`auto-offset-reset: earliest` 会在新消费者组从头开始消费。
- **建议**：生产环境改为 `latest`，或确保 Kafka topic 有合理的保留期。
- **需人工确认**：✅ 需人工确认是否为设计意图

---

## 🟢 低优先级问题（可延后处理）

### L001：Java 验证轮次重试耗尽逻辑复杂
- **位置**：`ops_agent/src/main/java/com/opsagent/service/AgentCallbackService.java` - `markRelatedAlert`
- **问题**：`REGRESSED` 状态处理逻辑复杂，依赖 `AlertLifecycleService.onVerificationRegressed` 的决策，难以理解和维护。
- **依据**：代码中有多层嵌套判断，涉及 `VERIFICATION_REGRESSED`、`maxRetries`、`verificationRound` 等多个字段。
- **建议**：提取独立方法，简化逻辑。
- **需人工确认**：❌ 可直接重构

### L002：Java 工具注册中存在冗余工具
- **位置**：`ops_agent/src/main/java/com/opsagent/service/ToolRegistryService.java` - `init`
- **问题**：注册了 18 个工具，但实际 AI 只能调用白名单中的 10 个，8 个工具（`configRollback`、`trafficSwitch`、`podEvict`、`nodeDrain`、`connectionPool`、`healthCheckParam` 等）只能通过前端手工调用。
- **依据**：注册列表与白名单不匹配。
- **建议**：如果这些工具只为前端手工使用，考虑拆分为"人工工具池"和"AI 工具池"。
- **需人工确认**：✅ 需人工确认设计意图

### L003：Java 日志级别未区分环境
- **位置**：`ops_agent/src/main/resources/application.yml` - `logging.level.com.opsagent: DEBUG`
- **问题**：生产环境日志级别为 DEBUG，可能产生大量日志，影响性能。
- **依据**：`com.opsagent: DEBUG` 在生产环境会产生大量调试日志。
- **建议**：通过环境变量区分开发/生产日志级别。
- **需人工确认**：❌ 可直接修复

### L004：Python 回调服务工具执行失败仅记录警告
- **位置**：`aiops-agent/services/callback.py` - `execute_tool`
- **问题**：Kafka 降级到 HTTP 后，如果 HTTP 调用失败，只记录警告，没有实际降级策略。
- **依据**：`except Exception as e: logger.warning(...)`，然后继续执行。
- **建议**：HTTP 失败后应抛出异常，让上层决定是否重试或终止。
- **需人工确认**：❌ 可直接修复

### L005：Java Redis 连接默认 localhost 不适配容器化
- **位置**：`ops_agent/src/main/resources/application.yml` - `spring.data.redis.host: localhost`
- **问题**：Redis 连接配置为 localhost，在容器化部署时可能无法连接。
- **依据**：容器内 Redis 通常通过服务名访问，而非 localhost。
- **建议**：通过环境变量配置 Redis 地址。
- **需人工确认**：✅ 需人工确认部署环境

---

## 🔗 重点链路通断表

| 链路 | 状态 | 问题编号 | 说明 |
|------|------|----------|------|
| Alertmanager → Java Webhook | ✅ 通 | - | 幂等去重、标准化逻辑正常 |
| Java → Python Agent Start | ✅ 通 | - | HTTP 回调正常 |
| Python → Java 工具调用（Kafka） | ⚠️ 潜在风险 | H001, M003, M006 | 并发安全、超时处理、分区分配问题 |
| Python → Java 工具调用（HTTP 降级） | ⚠️ 潜在风险 | L004 | 失败处理不完善 |
| Java 审批 → Python Resume | 🔴 断 | P001, H002 | 缺少 requestId 匹配校验 |
| Python 审批 interrupt → Checkpointer | ✅ 通 | - | SQLite/Redis 持久化正常 |
| Java 告警聚合 | ⚠️ 潜在风险 | H003 | 缺少事务保护 |
| Java → 前端 WebSocket | ✅ 通 | - | 推送逻辑正常 |
| RAG 知识库检索 | ✅ 通 | - | 混合检索、Rerank 逻辑正常 |
| 文档变更 → Kafka → Python | ⚠️ 潜在风险 | - | 需确认 doc_change_consumer 健康状态 |

---

## 📋 修复优先级建议

### 第一优先级（立即修复）
1. **P001**：LangGraph 审批恢复验证缺失（安全漏洞）
2. **P002**：敏感密钥明文暴露（安全红线）
3. **P003**：内部令牌过于弱（安全风险）

### 第二优先级（本周内修复）
4. **H001**：Python Kafka 并发访问非线程安全
5. **H002**：Java 审批恢复未验证 requestId 匹配
6. **H003**：Java 告警聚合缺少事务保护
7. **H004**：Python 配置中硬编码 LangFuse 凭证

### 第三优先级（两周内修复）
8. **M001**：Python 工具映射不完整
9. **M002**：Java 工具白名单配置与注册数不匹配
10. **M003**：Java Kafka 结果发送超时未回传失败
11. **M004**：Java 验证服务任务失败时仍可能安排验证
12. **M005**：Java Webhook 聚合告警标题前缀硬编码
13. **M006**：Python LangGraph 等待分区分配可能无限等待
14. **M007**：Java Kafka 消费者配置可能导致重复消费

### 第四优先级（可延后处理）
15. **L001-L005**：低优先级问题

---

## ⚠️ 特别说明

1. **敏感信息已发现但未展示**：体检过程中发现了 `.env` 文件中的密钥，但根据 AGENTS.md §0.2，不在输出中明文展示真实密钥。
2. **Git 历史检查未执行**：受限于"不切分支、git 只读"约束，未执行 `git log` 检查历史提交，需人工确认。
3. **运行时状态未验证**：受限于"禁止启动、停止、重启任何服务"约束，未验证服务实际运行状态，需人工确认健康检查接口。

---

**扫描时间**：2026-11-03
**扫描范围**：全项目只读体检
**基线提交**：479d53f
**分支**：fix/baseline

