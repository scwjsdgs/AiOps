# AIOps LangGraph Multi-Agent 重构设计

> 本文档描述 Python AI 大脑从“单 Agent 手写 ReAct while 循环”迁移到
> “LangGraph Multi-Agent 状态图”后的编排、状态、审批与恢复机制。

## 1. 为什么重构

原实现是单 Agent 手写 while 循环（最多 8 轮），工具全绑在一个 LLM 上，
审批靠 Java 工具内阻塞等待 55 秒，状态用 Redis/MySQL 旁路维护。问题：

- 工具权限没有物理隔离：一个模型同时拥有只读工具和写工具，提示词注入后可能“先斩后奏”。
- 手写 while 的轮次、去重、收敛逻辑散落各层，难以观察、重放、恢复。
- 审批同步阻塞在 Java 线程上，超时 55 秒后 fail-closed，无法支持长时间人工决策。
- Redis 状态与图执行是两套体系，断点恢复只能靠快照，不能从“下一步执行到哪”精确续跑。

## 2. LangGraph 状态图

```mermaid
flowchart LR
    START([Prometheus 告警 / ChatOps / 定时巡检]) --> SUP[Supervisor Agent<br>任务拆解与路由]
    SUP -->|diagnostic| DIA[诊断 Agent<br>只读排查]
    DIA --> SUP
    SUP -->|executor| EXE[执行 Agent<br>K8s 写操作]
    EXE -->|interrupt| HITL[人工审批<br>/api/approvals/{requestId}/decision]
    HITL -->|resume / rejected| EXE
    EXE --> DIA
    DIA --> SUP
    SUP -->|reporter| REP[报告 Agent<br>只汇总证据防幻觉]
    REP --> END([最终报告 -> Java 任务 / 案例库])
```

- 节点：`supervisor`、`diagnostic`、`executor`、`reporter`。
- 边：由条件边根据 `AgentState.next_agent` 路由；执行 Agent 完成后必须回到
  diagnostic 做回归验证，避免“执行完”直接当成“修好了”。
- Worker（diagnostic / executor / reporter）各自封装为独立 LangGraph 子图，
  Supervisor 主图只引用子图入口节点。子图内可独立维护内部循环与消息窗口。
- 不再使用 Python 手写 while 循环执行图：图运行交给 `langgraph`，
  `AGENT_MAX_ITERATIONS` 仅作为 Worker 内部工具步数兜底。

## 3. 全局 AgentState

`aiops-agent/agents/graph/state.py` 定义跨节点共享的全局状态：

| 字段 | 类型/默认 | 说明 |
|------|-----------|------|
| `task_id` | str | 告警 task / thread_id |
| `core_intent` | str | 本次告警/用户问题 |
| `current_subtask` | str | 当前节点子任务 |
| `messages` | list[BaseMessage] | LangChain 消息历史，reducer 追加 |
| `evidence` | list[dict] | 诊断 Agent 的只读证据 |
| `root_cause` | str | 根因结论 |
| `impact_summary` | str | 影响面摘要 |
| `approval_status` | str | `none / pending / approved / rejected / timeout` |
| `approval_request` | dict | 审批操作/理由/requestId |
| `execution_results` | list[dict] | 执行 Agent 真实写操作结果 |
| `plan` | list[str] | Supervisor 计划 |
| `next_agent` | str | `diagnostic / executor / reporter / end` |
| `retry_count` | int | 收敛控制 |
| `max_retries` | int | 允许的最大节点重试次数 |
| `completed_agents` | list[str] | 已完成 Worker |
| `iteration_count` | int | 主流程总迭代轮数，条件边用它强制收束 |
| `report` | str | 报告 Agent 最终输出 |
| `was_interrupted` | bool | 是否因人工审批挂起 |
| `final_status` | str | `SUCCESS / FAILED` |
| `started_at` / `completed_at` | str | 审计时间戳 |
| `error` | str | 错误信息 |

## 4. Multi-Agent 角色

> 从本版本起，diagnostic / executor / reporter 三个 Worker 都是独立子图
> （Subgraph），入口函数统一为 `*_subgraph_node`；每个子图拥有自己的内部
> 循环与 trim 窗口，主图只负责路由和调度，职责更清晰。

### Supervisor Agent（`agents/graph/supervisor.py`）

- 负责任务拆解与路由。
- 不绑定工具，避免“路由决策”与“真实执行”混在一起。
- 根据状态图进度把任务交给 diagnostic / executor / reporter / end。

### 诊断子图（`agents/graph/diagnostic.py` + `tool_sets.py`）

- 只读工具集：知识库、服务状态、日志、Pod 事件、Prometheus 指标、影响面。
- 没有任何写工具，即使提示词注入也无法直接变更集群。
- 产出 `evidence`、`root_cause`、`impact_summary`。

### 执行子图（`agents/graph/executor.py` + `tool_sets.py`）

- 写工具集：状态确认（只读）、重启/清缓存、扩缩容、回滚。
- 进入执行前先经 `interrupt()` 人工审批闸门；批准后才执行 K8s 写操作。
- 拒绝/异常时不执行写操作（fail-closed）。
- 执行结果写入 `execution_results`，交回 Supervisor 路由到诊断验证。

### 报告子图（`agents/graph/reporter.py`）

- 不绑定任何工具，只读全局状态中的证据/执行结果/影响面。
- 硬性要求：报告里的每条事实必须能从证据中找到；证据不足必须明说。
- 配合 Reflection 做二次审阅，PASS 才回调 Java。

## 5. 解决原有痛点

### 5.1 用 `interrupt()` 替代阻塞 55 秒审批

- 移除 Java `HumanApprovalTool.createAndAwait` 的线程轮询。
- Java 侧只负责提交审批单、WebSocket 广播、落库，审批决定后回调 Python
  `/api/agent/resume/{taskId}`。
- Python 执行子图在写操作前调用 `interrupt()`，图挂起在 Checkpointer
  （本地 SqliteSaver / 生产 Redis，接口一致）；决定恢复后同一节点继续执行，
  无需把 HTTP 连接挂住 55 秒。

### 5.2 用 SQLite Checkpointer 替代手写 Redis `_inflight`

- 本机 Redis 是普通 redis:7，没有 RedisSearch 模块，早期 `RedisSaver` 会报
  `FT.INFO` 未知命令。实际使用官方 `SqliteSaver`（`agents/graph/checkpointer.py`）
  以 `thread_id=taskId` 保存每步 checkpoint。
- 进程重启后仍可从同一 taskId 恢复挂起图。
- `clear_checkpoint(taskId)` 在任务终态后清理，避免 SQLite 积压。
- 原 `services/task_runner.py _inflight` 仍用于运行时互斥，但状态持久化
  与恢复完全交给 Checkpointer。

### 5.3 用条件边替代手写 while 8 轮限制

- 图结构天然是“状态机 + 条件边”，不再用 Python `while iteration < max` 驱动。
- `AGENT_MAX_ITERATIONS` 保留为 Worker 内部步数兜底。
- 重复工具调用通过 Worker 内 `used_signatures` 去重；全局收敛由图条件边控制。

## 5.4 子图封装

- diagnostic / executor / reporter 三个 Worker 各自拥有独立子图文件
  （`diagnostic.py` / `executor.py` / `reporter.py`），入口函数命名为
  `*_subgraph_node`。
- 主图 `graph.py` 只注册四个节点：`supervisor` 加三个子图入口；
  子图内部循环/工具调度不再占用主图节点数。
- 保留 `diagnostic_node` / `executor_node` / `reporter_node` 作为兼容别名，
  避免旧调用方 import 断开。

## 5.5 `trim_messages` 滑动窗口（Token 优化）

- 每个 Worker 子图在进入 LLM 前调用 `agents/graph/worker.py` 的
  `trim_worker_messages`。
- 规则：保护首条 System 系统提示，对非 System 消息只保留最近的
  `MESSAGES_WINDOW_SIZE` 条（默认 12），超出窗口的旧轮次直接裁掉。
- 全局 `messages` 通道在 `state.py` 里使用 bounded reducer，Checkpoint 也不会
  无限膨胀。
- `MESSAGES_WINDOW_SIZE` 可在 `.env` 调整。

## 5.6 `iteration_count` 防死循环条件边

- 初始状态写入 `iteration_count: 0`；每个 Worker 子图完成时递增 1。
- Supervisor 路由后的下一跳到达 Worker 前，条件边检查
  `iteration_count >= MAX_ITERATION_ROUNDS`（默认 6）。
- 达到上限时，无论原本要进 diagnostic / executor，都会强制改走 reporter
  子图收束并结束，避免 Supervisor<->Worker 无限交换。
- `GRAPH_RECURSION_LIMIT`（默认 40）是 LangGraph 的 `recursion_limit` 硬闸门，
  作为条件边漏判时的最终防线。

## 6. 部署与兼容

依赖（`aiops-agent/requirements.txt`）：

```txt
langgraph
langgraph-checkpoint-sqlite
langchain
langchain-openai
langchain-community
```

环境变量（`aiops-agent/.env.example`）：

```dotenv
GRAPH_CHECKPOINT_TTL_SECONDS=86400
REDIS_HOST=localhost
REDIS_PORT=6379
REDIS_DB=0
```

Java 侧审批模式仍在 `application.yml`：

```yaml
opsagent:
  approval:
    mode: manual   # auto 直接批准并落审计；manual 走 interrupt 挂起
```

接口变化：

- 新增 Python `POST /api/agent/resume/{taskId}`，body `{ requestId, approved, note }`。
  该接口是继续高危操作的入口，强制校验：
  1. `X-Internal-Token` 与 Java 内部令牌一致（常量时间比较）；
  2. checkpoint 确实处于人工审批 interrupt 挂起；
  3. `requestId` 与挂起审批单匹配。
  任一项失败返回 401/400，绝不回退到已批准状态。
- Java 审批决定后自动调用上述 resume 接口恢复图。
- 原有 `/api/agent/start`、`/api/agent/callback/*`、WS 推送协议不变。