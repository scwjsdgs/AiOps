# 开发环境搭建

## 环境要求

| 工具 | 推荐版本 |
|------|----------|
| JDK | 21 |
| Python | 3.12+ |
| Node.js | 20 |
| Maven | 3.9+ |
| Docker | 20+ |
| kind | 0.20+ |

## 启动步骤

### 1. 启动依赖服务（MySQL、Redis、Kafka）

```bash
docker compose up -d
```

> 若无 root 权限，可用 `docker compose`（V2）指令。

### 2. 创建本地 Kubernetes 集群

```bash
kind create cluster --name aiops
kubectl cluster-info --context kind-aiops
```

### 3. 配置环境变量

```bash
cp ops_agent/.env.example ops_agent/.env
cp aiops-agent/.env.example aiops-agent/.env
```

修改 `ops_agent/.env`（`MYSQL_URL`、`MYSQL_USERNAME`、`MYSQL_PASSWORD`、`OPSAGENT_INTERNAL_TOKEN`、`OPSAGENT_WEBHOOK_TOKEN`）。
修改 `aiops-agent/.env`（`LLM_API_KEY`、`LLM_BASE_URL`、`LLM_MODEL`、`JAVA_INTERNAL_TOKEN`）。

前端 `ops-agent-front/.env` 预置 `VITE_API_BASE_URL=/api`、`VITE_WS_BASE_URL=ws://localhost:8081`。

### 4. 启动 Java 后端

```bash
cd ops_agent
./mvnw spring-boot:run
```

端口：8081

### 5. 启动 Python AI 大脑

```bash
cd aiops-agent
python -m venv .venv
source .venv/bin/activate   # Windows: .venv\Scripts\activate
pip install -r requirements.txt
uvicorn main:app --reload --port 5000
```

端口：5000

### 6. 启动前端

```bash
cd ops-agent-front
npm install
npm run dev
```

访问：http://localhost:3000

## 目录结构

```
AiOps/
├── aiops-agent/       # Python AI 大脑 (FastAPI + LangChain ReAct + RAG)
├── ops_agent/         # Java 后端 (Spring Boot WebFlux + Webhook + WebSocket)
├── ops-agent-front/   # Vue 3 前端 (Pinia + Element Plus)
├── docs/              # 项目文档
├── .github/workflows/ # CI 配置
├── CONTRIBUTING.md
├── LICENSE
└── README.md
```

## 调试说明

- Java 后端日志级别：`com.opsagent` 为 DEBUG，可在 `application.yml` 调整。
- Webhook Token 测试：默认 `dev-webhook-token`，如需关闭校验可将 `opsagent.webhook.token` 置空（仅开发调试）。
- AI 推理控制：`AGENT_MAX_ITERATIONS=8`、`AGENT_TIMEOUT_SECONDS=300` 于 `aiops-agent/.env` 调整。
- 审批模式：`opsagent.approval.mode=manual|auto` 于 `application.yml` 控制。
