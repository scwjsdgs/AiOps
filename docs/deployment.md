# 部署说明

## 本地开发部署

参考 [development.md](./development.md)。

## Docker Compose 部署

项目依赖 MySQL、Redis、Kafka 三个中间件。通过 `docker compose up -d` 启动：

```bash
docker compose up -d
```

> 注意：当前仓库中**未包含** `docker-compose.yml` 文件。若新 clone 后遇到 `.env` 缺失，请先参考
> `ops_agent/.env.example`、`aiops-agent/.env.example` 创建对应 `.env`。后续版本会补充完整的
> Compose 编排文件（Java 后端、Python AI 大脑、前端的服务定义）。

## Kubernetes 部署

项目使用 kind 做本地 K8s 集群验证。`kind-config.yaml` 已固定 API Server 端口 16443，
并为 Prometheus / Alertmanager / Grafana 配置 NodePort 到宿主机端口 9090 / 9093 / 30300，
避免每次重建集群端口漂移。

```bash
# 创建集群（读取 kind-config.yaml 的端口映射）
kind create cluster --config kind-config.yaml

# 部署监控栈：Prometheus + Alertmanager + Grafana + kube-state-metrics + node-exporter
bash apply-monitoring.sh

# 如果当前集群不是从该配置创建，开发时保持这两个后台会话：
kubectl port-forward -n monitoring svc/prometheus 9090:9090
kubectl port-forward -n monitoring svc/alertmanager 9093:9093
```

监控栈说明：

- `cluster-backup/monitoring-recreated/prometheus-config` 内含 `rules.yml`，
  其中 `DemoAppCrashLoop` 规则会在 `leaky-app` 5 分钟重启超过 1 次且持续 2 分钟后告警。
- Alertmanager 的 webhook 使用 `host.docker.internal:8081`（kind 容器访问 Windows 宿主 Java 的有效地址）
  并通过 `http_config.basic_auth` 携带 `webhook / dev-webhook-token`，与 Java 默认配置一致。
- 若修改了 `OPSAGENT_WEBHOOK_TOKEN`，请同步修改 `04-alertmanager.yaml` 中的密码。

K8s 操作通过 fabric8 客户端执行，namespace 默认为 `default`，可在 `application.yml` 的 `opsagent.kubernetes.namespace` 调整。
`query_log` 在 SSH 和本地目录都不可用时会降级读取 K8s Pod 日志，数据源标记为 `k8s-pod-logs`。

## 环境变量

### Java 后端（ops_agent）

| 变量 | 说明 |
|------|------|
| `MYSQL_URL` | MySQL JDBC 连接串（含库名、时区） |
| `MYSQL_USERNAME` | MySQL 用户 |
| `MYSQL_PASSWORD` | MySQL 密码 |
| `OPSAGENT_INTERNAL_TOKEN` | 内部通信令牌（与 Python 的 JAVA_INTERNAL_TOKEN 一致） |
| `OPSAGENT_WEBHOOK_TOKEN` | Alertmanager Webhook 自身认证令牌 |
| `OPSAGENT_WEBHOOK_HMAC_SECRET` | 可选 Webhook HMAC 签名密钥 |
| `OPSAGENT_AGENT_URL` | AI 大脑地址（默认 http://localhost:5000） |

### Python AI 大脑（aiops-agent）

| 变量 | 说明 |
|------|------|
| `LLM_API_KEY` | LLM API 密钥 |
| `LLM_BASE_URL` | LLM 接口地址（OpenAI 兼容） |
| `LLM_MODEL` | 模型名（默认 deepseek-flash） |
| `JAVA_INTERNAL_TOKEN` | 内部通信令牌（调 Java 回调接口时携带） |
| `RAG_ENABLED` | 是否启用知识库检索（true 启用 RAG） |
| `DASHSCOPE_API_KEY` | RAG embedding 专用密钥（阿里云百炼 DashScope 原生接口，与 LLM 无关；DeepSeek 无 `/v1/embeddings`） |
| `EMBEDDING_MODEL` | embedding 模型名（如 qwen3.7-text-embedding-flash） |
| `AGENT_MAX_ITERATIONS` | LangGraph Worker 内部工具步数兜底（默认 8） |
| `AGENT_TIMEOUT_SECONDS` | 单任务整体超时（默认 300） |
| `GRAPH_CHECKPOINT_TTL_SECONDS` | LangGraph Checkpointer TTL（默认 86400，审批挂起/恢复用；本地 SqliteSaver / 生产 Redis 统一口径） |
| `QUERY_REWRITE_ENABLED` | 检索前是否用 DeepSeek 润色口语 Query（默认 true） |
| `QUERY_REWRITE_MODEL` | Query 润色模型名（默认 deepseek-flash） |
| `RAG_BM25_ENABLED` | 是否启用 BM25 关键词检索通道（默认 true） |
| `RERANK_ENABLED` | RRF 粗排后是否用 gte-rerank 精排 Top3（默认 true，失败自动降级） |
| `REFLECTION_ENABLED` | 报告生成后是否审阅防幻觉，PASS 才回调 Java（默认 true） |
| `REDIS_HOST` / `REDIS_PORT` / `REDIS_DB` | 生产环境 LangGraph Checkpointer 断点续传 / 审批恢复用的 Redis；本地因缺 RedisSearch 用 SqliteSaver，接口一致 |
| `LANGFUSE_ENABLED` | 是否启用 LangFuse 可观测性（默认 false；启动见 docs/langfuse.md） |
| `MINIO_ROOT_USER` / `MINIO_ROOT_PASSWORD` / `S3_BUCKET` / `S3_REGION` | LangFuse v3 使用的 MinIO/S3 存储；compose 首次启动会通过 `minio-init` 自动建 bucket |
| `langfuse-worker` | LangFuse v3 队列消费者，负责把 web 入队的 OTEL/ingestion 事件写入 ClickHouse；缺少它会表现为 HTTP 正常但无 Trace |

### 前端（ops-agent-front）

| 变量 | 说明 |
|------|------|
| `VITE_API_BASE_URL` | REST 请求前缀（默认 `/api`，走 Vite 代理） |
| `VITE_WS_BASE_URL` | WebSocket 直连地址（默认 ws://localhost:8081） |