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

项目使用 kind 做本地 K8s 集群验证。

```bash
# 创建集群
kind create cluster --name aiops

# 应用配置（需自行准备 deploy/k8s/ 下的 YAML，或按需编写）
kubectl apply -f deploy/k8s/
```

K8s 操作通过 fabric8 客户端执行，namespace 默认为 `default`，可在 `application.yml` 的 `opsagent.kubernetes.namespace` 调整。

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
| `LLM_MODEL` | 模型名（如 deepseek-v4-flash / qwen-plus） |
| `JAVA_INTERNAL_TOKEN` | 内部通信令牌（调 Java 回调接口时携带） |
| `RAG_ENABLED` | 是否启用知识库检索 |
| `AGENT_MAX_ITERATIONS` | ReAct 推理最大轮数（默认 8） |
| `AGENT_TIMEOUT_SECONDS` | 单任务整体超时（默认 300） |

### 前端（ops-agent-front）

| 变量 | 说明 |
|------|------|
| `VITE_API_BASE_URL` | REST 请求前缀（默认 `/api`，走 Vite 代理） |
| `VITE_WS_BASE_URL` | WebSocket 直连地址（默认 ws://localhost:8081） |