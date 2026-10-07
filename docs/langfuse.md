# LangFuse 可观测性

本项目通过 LangFuse 追踪：
- LLM 调用（含 token 消耗、耗时）
- 工具执行
- Agent 慢 Trace 瀑布图

## 1. Docker 启动 LangFuse

仓库提供 `aiops-agent/docker-compose.langfuse.yml`（含 PostgreSQL 依赖），映射到宿主机 **3030** 端口，避免与前端 3000 冲突。

```bash
cd aiops-agent
docker compose -f docker-compose.langfuse.yml up -d
docker ps  # 看到 langfuse、langfuse-worker、langfuse-minio、langfuse-minio-init 等容器
```

`langfuse-worker` 是 v3 必须的队列消费者：web 只负责接收/上传 JSON，worker 负责消费 `otel-ingestion-queue` / `ingestion-queue` 并写入 ClickHouse。缺少 worker 时 HTTP 返回 200/207，但 Trace 不会出现在 UI 或 ClickHouse。

首次启动时 `minio-init` 会自动创建 S3 bucket（默认 `langfuse`）。LangFuse v3 不会自动建 bucket，若日志出现 `NoSuchBucket: The specified bucket does not exist`，可手动修复：

```bash
docker exec langfuse-minio mc alias set local http://localhost:9000 \
  "$MINIO_ROOT_USER" "$MINIO_ROOT_PASSWORD"
docker exec langfuse-minio mc mb --ignore-existing local/langfuse
```

首次打开 `http://localhost:3030`，注册账号并创建 Project，拿到：

- Public Key
- Secret Key

## 2. Python 环境变量

在 `aiops-agent/.env` 增加：

```ini
LANGFUSE_ENABLED=true
LANGFUSE_HOST=http://localhost:3030
LANGFUSE_PUBLIC_KEY=pk-xxxx
LANGFUSE_SECRET_KEY=sk-xxxx
```

## 3. 代码接入

初始化与 CallbackHandler 在 `aiops-agent/observability/langfuse_client.py`：

- `get_langfuse()`：按配置初始化 LangFuse 客户端
- `get_langfuse_callback_handler()`：返回 LangChain `CallbackHandler`
- `finalize()`：进程结束前 flush，避免丢 trace

`agents/react_agent.py` 在创建 `ChatOpenAI` 时挂上 callback：

```python
langfuse_cb = get_langfuse_callback_handler()
llm = ChatOpenAI(..., callbacks=[langfuse_cb] if langfuse_cb else None)
```

`run_agent_for_alert` 在报告回传后调用 `langfuse_finalize()`。

## 4. 查看 Trace

- 打开 `http://localhost:3030` → Traces
- 每个任务（task_id）一个 Trace，展开可见：
  - `ChatOpenAI` LLM 调用
  - tool 执行（工具名、参数、结果）
  - 每一步耗时与 token
- “慢 Trace”在 Trace 列表按 Duration 降序筛选即可得到瀑布图。

## 5. 注意事项

- `LANGFUSE_ENABLED=false` 时完全降级，不影响 Agent 主链路。
- 若 LangFuse 服务不可用，`get_langfuse_callback_handler` 返回 `None`，LLM 照常运行。
- `langfuse-worker` 通过 `REDIS_CONNECTION_STRING=redis://host.docker.internal:6379` 连接现有宿主机 Redis；项目 Redis 未设置密码。
- 可用下面的方法快速验证链路：投递一条 ingestion 事件后，队列 `llen bull:ingestion-queue:wait` 应归 0，ClickHouse `traces` 表应出现对应行。
- 生产环境请设置强 `NEXTAUTH_SECRET`、`SALT`、`ENCRYPTION_KEY`（至少 32 字节），并限制数据库端口暴露。
