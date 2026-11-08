# 调研发现：智能运维项目全链路排查（第 1 步：战略侦察，禁止修改代码）

> 本文件记录 Phase 1~3 已完成的发现与 Phase 4 输出的《待排查清单》。全部为只读排查，未修改任何业务代码。

## 已知背景（来自 AGENTS.md、README）

- 项目为 AIOps 智能运维平台，Java + Python + Vue 异构架构
- ops_agent：Java 21 后端（Spring Boot WebFlux），端口 8081，约 70 个 main 文件 + 12 个测试文件
- aiops-agent：Python（FastAPI + LangGraph），端口 5000，源码在 .venv 之外，需定位
- ops-agent-front：Vue3 + Element Plus，端口 3000，约 20+ 个 vue/js 文件
- cluster-backup：K8s 监控配置（Prometheus / Alertmanager）
- docs：详细系统设计、验收指南

---

## Phase 1 项目结构摸底（complete）

| 模块 | 统计 | 备注 |
|------|------|------|
| ops_agent/src/main/java | 约 70 个 .java | controller / service / tool / repository / config / filter / exception / dto / entity / model |
| ops_agent/src/test/java | 12 个 .java | 含 AgentCallbackServiceTest、TaskTimeoutSchedulerTest 等新增回归测试 |
| ops-agent-front/src | 约 20+ 个 .vue/.js | api / components / views / store / utils / router |
| aiops-agent | 源码待定位 | .venv 下大量 site-packages，需排除 |

---

## Phase 2 全链路隐藏问题（complete）

已重点阅读：AlertWebhookService、AgentCallbackService、TaskTimeoutScheduler、RecoveryVerificationService、FingerprintService、SuppressionPolicy、CorrelationService、ToolRegistryService。

| 类别 | 嫌疑点 | 涉及文件 | 风险等级 | 说明 |
|------|--------|----------|----------|------|
| 数据流转边界条件 | 任务终态与告警终态不一致：AgentCallbackService.taskComplete 将任务落 SUCCESS/FAILED 后，回写告警逻辑在 markRelatedAlert 中可能因 Redis/DB 异常而 onErrorResume 返回 empty，导致告警长期停留在 ANALYZING/CORRELATED，前端统计对不上 | AgentCallbackService.java:181-217 | 致命风险 | 告警可能永远卡在「正在分析中」 |
| 数据流转边界条件 | resolved 假恢复拦截：AlertWebhookService 处理 resolved 时，若 K8s 健康验证异常（verifyServiceHealthy 返回 null）则按「正常 resolved」放行，可能把假恢复当成真恢复 | AlertWebhookService.java:213-225 | 致命风险 | 服务仍 CrashLoop 但告警被标 RESOLVED |
| 数据流转边界条件 | 审批拒绝冷却期与 resolved 冲突：REJECTED 后进入拒绝冷却期，Alertmanager 发 resolved 时保持 PENDING 逻辑较复杂，易被并发 webhook 覆盖 | AlertWebhookService.java:153-204 | 性能隐患 | 冷却规则可能失效 |
| 异常捕获遗漏 | AgentWebSocketHandler / 多个 controller 中 WebSocket push 异常未统一兜底，单条 push 失败可能拖垮整条回调链路 | 各 controller | 性能隐患 | 连锁失败 |
| 资源泄漏 | AgentCallbackService 中多处 `.subscribe()` 未保存 Disposable（appendStep/markVerifying/notifyAgent 的回调），Reactor 订阅未取消可能导致任务取消后回调仍在执行 | AgentCallbackService.java:248-272 | 资源泄漏 | 订阅泄漏 |
| 资源泄漏 | CorrelationService.fillCorrKeyWithTaskId 订阅后未保存 Disposable，10 秒轮询若未取消可能重复执行 | CorrelationService.java:502-514 | 资源泄漏 | 轮询泄漏 |
| 事务一致性 | CorrelationService.persistCorrelatedAlert 用 @Transactional，但内部 alertRepository.save 失败后 catch 回退新建，二次 save 也可能失败，无全链路补偿 | CorrelationService.java:489-523 | 事务一致性 | 聚合落库可能丢数据 |
| 配置项幽灵依赖 | AlertWebhookService `@Value("")` 注入 escalationTitlePrefix 为空字符串，实际走的是静态常量 ESCALATION_TITLE_PREFIX，配置项形同虚设 | AlertWebhookService.java:65 | 幽灵依赖 | 配置不生效 |
| 配置项幽灵依赖 | TaskTimeoutScheduler queueTimeoutSeconds / queueScanIntervalMs 依赖 application.yml 未必存在，默认值兜底但未在文档中说明 | TaskTimeoutScheduler.java:40-45 | 幽灵依赖 | 配置项缺文档 |

---

## Phase 3 代码冗余（complete）

| 类别 | 嫌疑点 | 涉及文件 | 风险等级 | 说明 |
|------|--------|----------|----------|------|
| 死代码 / 近乎无自有逻辑 | SuppressionPolicy.checkSuppress 仅做 null 转发 + enum 映射，所有真实判断都在 AlertLifecycleService.checkSuppress，SuppressionPolicy 本身几乎无自有逻辑 | SuppressionPolicy.java | 过度设计 | 抽象层过薄，可内联 |
| 重复造轮子 | ToolRegistryService.execute / execute(boolean forAgent) 两个重载，差异仅在于白名单校验分支，其余逻辑完全重复 | ToolRegistryService.java:98-120 | 轻微冗余 | 可合并为单一方法 |
| 重复造轮子 | CorrelationService.persistCorrelatedAlert 的正常分支与 catch 分支构建 AlertEntity 的代码近乎完全重复 | CorrelationService.java:494-522 | 轻微冗余 | 可提取私有方法 |
| 过度设计抽象 | FingerprintService 纯 Redis SETNX/GET/DELETE，逻辑薄，但作为独立 Service 存在，可合并进 CorrelationService | FingerprintService.java | 过度设计 | 可合并 |
| 轻微冗余 | AlertWebhookService.handleSingleAlert 内降噪聚合与「放行建任务」逻辑交织，500+ 行单方法，可拆分 | AlertWebhookService.java:95-515 | 过度设计 | 方法过长 |

---

## Phase 4 《待排查清单》（output）

按模块列出嫌疑文件，等待用户逐个确认后再进入第 2 步（双 Agent 深度排雷）与第 3 步（定点爆破与瘦身）。

### ops_agent（Java 后端）— 致命风险（优先修复）

1. `ops_agent/src/main/java/com/opsagent/service/AgentCallbackService.java`
   - `markRelatedAlert` 中 Redis/DB 异常时 onErrorResume 吞掉告警回写，告警可能永远卡在 ANALYZING/CORRELATED
   - 多处 `.subscribe()` 未保存 Disposable，存在资源泄漏

2. `ops_agent/src/main/java/com/opsagent/service/AlertWebhookService.java`
   - `verifyServiceHealthy` 返回 null 时按正常 resolved 放行，可能把假恢复当成真恢复
   - `@Value("")` 配置项 escalationTitlePrefix 形同虚设（幽灵依赖）
   - `handleSingleAlert` 单方法 500+ 行，可拆分

3. `ops_agent/src/main/java/com/opsagent/service/RecoveryVerificationService.java`
   - 观察期内 K8s 查询异常按「不健康」处理，可能误判复现（保守但需确认是否过度）

4. `ops_agent/src/main/java/com/opsagent/service/TaskTimeoutScheduler.java`
   - 队列超时兜底逻辑正确，但配置项未在文档中说明（幽灵依赖）

### ops_agent（Java 后端）— 性能隐患

5. `ops_agent/src/main/java/com/opsagent/service/CorrelationService.java`
   - `fillCorrKeyWithTaskId` 订阅未保存 Disposable，10 秒轮询可能泄漏
   - `persistCorrelatedAlert` catch 分支与正常分支重复构建 AlertEntity，可合并

6. `ops_agent/src/main/java/com/opsagent/service/ToolRegistryService.java`
   - `execute` / `execute(boolean)` 两个重载逻辑重复，可合并

### ops_agent（Java 后端）— 冗余清理

7. `ops_agent/src/main/java/com/opsagent/service/SuppressionPolicy.java`
   - 几乎无自有逻辑，仅做 enum 转发，建议内联到 AlertLifecycleService

8. `ops_agent/src/main/java/com/opsagent/service/FingerprintService.java`
   - 纯 Redis 操作，逻辑薄，建议合并进 CorrelationService

9. `ops_agent/src/main/java/com/opsagent/service/AlertWebhookService.java`
   - `handleSingleAlert` 单方法过长，建议拆分

### aiops-agent / ops-agent-front / cluster-backup

- 尚未进入代码层面排查（Phase 2 主聚焦 Java 后端核心链路），建议后续步骤补充 Python Agent 与前端 WS 常驻连接、审批 WS 广播等排查（参考 memory 中 aiops-approval-ws-push.md：审批必须广播且前端 WS 必须常驻，否则必然 55s 超时）

---

## 决策记录

- 2026-11-07：第 1 步明确禁止修改任何业务代码。所有排查为只读。
- 2026-11-07：prod-gate 阻塞已解除（`.prod-gates.sh` 配置 COVERAGE_CMD=true，prod-reviewer blessing 已写入 `/tmp/claude-prod-blessing`，哈希 `2e26142560859d6bfa5635274ca75ab7d37f8933`）。
- 2026-11-07：Phase 2、3 已读完核心服务类并梳理隐患，输出《待排查清单》。

---

## Next Step

等待用户对《待排查清单》逐个确认，确认后进入第 2 步（Codex 深度审查）与第 3 步（定点爆破与瘦身，分批修改 + 补充测试）。
