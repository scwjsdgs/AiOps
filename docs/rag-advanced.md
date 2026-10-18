# RAG 知识库闭环、分层评估与成本可观测

本文档补充 README 未展开的新增 RAG 能力。所有实现不依赖新增容器或外部下载，
复用项目已有 Chroma / DashScope embedding / LangChain / FastAPI。

## 1. 知识库管理闭环

入口：`aiops-agent/rag/knowledge_manager.py`、`aiops-agent/rag/knowledge_meta.py`，
Java 代理 `/api/rag/knowledge/*`（Python 原生接口见 `aiops-agent/main.py`）。

流程：

1. 上传文档：保存源文件到 `DOCUMENTS_DIR`，注册元数据。
2. 切片：`RecursiveCharacterTextSplitter(chunk_size=500, chunk_overlap=50)`。
3. embedding + 增量 upsert：内容 hash 未变直接跳过（案例去重）；内容变化则删除旧 chunks 并写入新 chunks。
4. 质量评分：`0~1`，前端可调整并记录审计。
5. 版本回滚：默认回到上一版，元数据里保留 `versions` 历史。
6. 更新审计：`knowledge_audit.jsonl` 追加 upsert / rollback / delete / quality_update / upload_failed。
7. 生效延迟：每个版本记录 `created_at -> effective_at` 与 `effective_latency_ms`。
8. 失败重试：`/api/rag/knowledge/retry` 从审计里找 `upload_failed` 重试。

## 2. 分层评估指标

入口：`aiops-agent/rag/eval_metrics.py`，调用方式：`POST /api/rag/evaluate`（Java 代理）或直接调用 Python `/api/rag/evaluate`。

| 层 | 指标 | 说明 |
|---|---|---|
| 检索层 | Context Precision | 相关上下文在排序结果中的位置加权 |
| 检索层 | Context Recall | 标准答案被检索上下文覆盖比例 |
| 检索层 | NDCG | 前 K 位相关性的折扣累积 |
| 检索层 | Recall@K / Hit@K / MRR | 经典伪相关检索指标 |
| 生成层 | Faithfulness | 答案断言能从检索上下文得到支持的比例 |
| 生成层 | Answer Relevance | 答案与问题 token 相关性 |
| 生成层 | Answer Correctness | 答案与标准答案相似度 |

LLM-as-judge：接口 `{ "llm_judge": true }` 时使用
`REFLECTION_BASE_URL / REFLECTION_MODEL / REFLECTION_API_KEY`（默认 DeepSeek deepseek-flash）打分，
失败自动降级为启发式结果。

## 3. LLM 成本与性能

入口：`aiops-agent/rag/cost_tracker.py`，Java `/api/llm/cost`。

- 按模型与阶段累计：调用次数、输入/输出 Token、耗时、估算成本。
- LLM 缓存：`(model, prompt)` 哈希 + TTL，命中直接复用。
- 模型路由：`/api/llm/route` 匹配主/备模型，记录阶段。
- 预算告警：环境变量 `LLM_DAILY_BUDGET_USD`，默认 5 美元，超出后输出告警级别事件。
- 价格表为估算值，可修改 `MODEL_PRICE` 对接实际计费。

## 4. 混沌与对抗评测

入口：`aiops-agent/rag/chaos_metrics.py`，调用方式：`GET /api/chaos/run` 与 `GET /api/chaos/adversarial`（Java 代理）。

混沌场景：

- `rag-disabled`：RAG 关闭时 agent 仍可基于日志/经验分析。
- `embedding-down`：embedding 服务不可用时检索降级。
- `store-empty`：向量库为空时返回空结果而非抛异常。
- `retrieval-timeout`：检索超时后安全返回。

对抗样本：

- 提示注入：忽略系统指令、执行任意命令等。
- 伪造报告：要求把失败写成成功。

当前为启发式安全判定与场景框架；接入真实 LangGraph Multi-Agent 时可在工具名/参数入口处挂同一套检查器。