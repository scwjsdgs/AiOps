import os
from pydantic_settings import BaseSettings, SettingsConfigDict

class Settings(BaseSettings):
    # LLM 配置（DeepSeek，OpenAI 兼容接口）
    LLM_API_KEY: str = os.getenv("LLM_API_KEY", "your-api-key-here")
    LLM_BASE_URL: str = os.getenv("LLM_BASE_URL", "https://api.deepseek.com/v1")
    LLM_MODEL: str = os.getenv("LLM_MODEL", "deepseek-flash")

    # 单次 LLM 调用的超时（秒）与失败重试次数
    LLM_TIMEOUT_SECONDS: int = int(os.getenv("LLM_TIMEOUT_SECONDS", "60"))
    LLM_MAX_RETRIES: int = int(os.getenv("LLM_MAX_RETRIES", "2"))

    # 单个告警任务（整个 LangGraph 状态图）的整体超时（秒）
    AGENT_TIMEOUT_SECONDS: int = int(os.getenv("AGENT_TIMEOUT_SECONDS", "300"))

    # 原 ReAct 最大迭代轮数。LangGraph 状态图已用条件边/retry_count 替代 while，
    # 这里作为单节点内部工具调用步数上限与整体兜底，保留向后兼容。
    AGENT_MAX_ITERATIONS: int = int(os.getenv("AGENT_MAX_ITERATIONS", "8"))

    # 单次分析中 query_metrics 的调用上限。LLM 会陷入「反复查指标佐证已有结论」的循环，
    # 查出越来越细的 PromQL 却永远不下结论，导致修复步骤根本走不到、报告也产出不了。
    # 到上限后在代码层直接拒绝执行并回注提示，逼它收敛——纯 Prompt 约束不可靠。
    #
    # 取值 5：一次完整排查通常要查「副本数 + 重启次数 + CPU + 内存 + 一条补充」，
    # 5 次足够覆盖而不误伤。设 3 会打断 CPU/内存多维劣化类的正常分析。
    AGENT_MAX_METRICS_QUERIES: int = int(os.getenv("AGENT_MAX_METRICS_QUERIES", "5"))

    # 同一工具 + 同一参数的重复调用上限（集群状态未变化时）。
    # 防止「换个写法再查一遍」式的原地打转。
    AGENT_MAX_SAME_CALLS: int = int(os.getenv("AGENT_MAX_SAME_CALLS", "2"))

    # 向量库配置
    RAG_ENABLED: bool = os.getenv("RAG_ENABLED", "true").lower() in ("1", "true", "yes", "on")
    VECTOR_STORE_DIR: str = os.getenv("VECTOR_STORE_DIR", "./rag/chroma_db")
    DOCUMENTS_DIR: str = os.getenv("DOCUMENTS_DIR", "./rag/documents")

    # embedding 用阿里云百炼（DashScope）原生 text-embedding 接口，
    # 与 LLM 走的 DeepSeek 是两条独立通道：embedding 只影响知识库建库/检索，
    # 不影响对话模型。密钥从环境变量 DASHSCOPE_API_KEY 或 .env 读取。
    # 字段名必须与 .env 键名一致，否则 pydantic-settings 会把 .env 里的键
    # 当作多余字段报 extra_forbidden。DASHSCOPE_API_KEY 是密钥，别提交进 git。
    DASHSCOPE_API_KEY: str = os.getenv("DASHSCOPE_API_KEY", "")
    EMBEDDING_MODEL: str = os.getenv("EMBEDDING_MODEL", "qwen3.7-text-embedding-flash")

    # Query 润色：默认与主模型同通道，统一走 DeepSeek，降低多供应商限流与成本。
    QUERY_REWRITE_ENABLED: bool = os.getenv("QUERY_REWRITE_ENABLED", "true").lower() in ("1", "true", "yes", "on")
    QUERY_REWRITE_MODEL: str = os.getenv("QUERY_REWRITE_MODEL", "deepseek-flash")
    QUERY_REWRITE_API_KEY: str = os.getenv("QUERY_REWRITE_API_KEY", "")
    QUERY_REWRITE_BASE_URL: str = os.getenv("QUERY_REWRITE_BASE_URL", "https://api.deepseek.com/v1")

    # 混合检索：BM25 / 向量检索 / RRF
    RAG_BM25_ENABLED: bool = os.getenv("RAG_BM25_ENABLED", "true").lower() in ("1", "true", "yes", "on")
    RAG_RRF_K: int = int(os.getenv("RAG_RRF_K", "60"))

    # Rerank：RRF 粗排 Top20 后精排取 Top3
    RERANK_ENABLED: bool = os.getenv("RERANK_ENABLED", "true").lower() in ("1", "true", "yes", "on")
    RERANK_MODEL: str = os.getenv("RERANK_MODEL", "gte-rerank")
    RERANK_TOP_K: int = int(os.getenv("RERANK_TOP_K", "3"))

    # Java 后端地址（ops_agent 监听 8081）
    JAVA_BASE_URL: str = os.getenv("JAVA_BASE_URL", "http://localhost:8081")

    # 与 Java 约定的内部共享密钥。回调接口必须携带 X-Internal-Token，
    # 因为这些接口能触发 restart/scale 等真实运维操作，不能裸奔放行。
    JAVA_INTERNAL_TOKEN: str = os.getenv("JAVA_INTERNAL_TOKEN", "dev-internal-token")

    # LangFuse 可观测性（默认关闭，配置齐了自动启用）
    LANGFUSE_ENABLED: bool = os.getenv("LANGFUSE_ENABLED", "false").lower() in ("1", "true", "yes", "on")
    LANGFUSE_HOST: str = os.getenv("LANGFUSE_HOST", "http://localhost:3030")
    LANGFUSE_PUBLIC_KEY: str = os.getenv("LANGFUSE_PUBLIC_KEY", "")
    LANGFUSE_SECRET_KEY: str = os.getenv("LANGFUSE_SECRET_KEY", "")

    # Redis（Java 侧任务/审批/幂等去重）
    REDIS_HOST: str = os.getenv("REDIS_HOST", "localhost")
    REDIS_PORT: int = int(os.getenv("REDIS_PORT", "6379"))
    REDIS_DB: int = int(os.getenv("REDIS_DB", "0"))

    # LangGraph 审批断点使用 SQLite 持久化（不需要 Redis Search）
    GRAPH_CHECKPOINT_DB_PATH: str = os.getenv("GRAPH_CHECKPOINT_DB_PATH", "./rag/checkpoints.sqlite")

    # Reflection（报告事后审阅，防幻觉；写回 Java 前必须 PASS）
    REFLECTION_ENABLED: bool = os.getenv("REFLECTION_ENABLED", "true").lower() in ("1", "true", "yes", "on")
    REFLECTION_MODEL: str = os.getenv("REFLECTION_MODEL", "deepseek-flash")
    REFLECTION_BASE_URL: str = os.getenv("REFLECTION_BASE_URL", "https://api.deepseek.com/v1")
    REFLECTION_API_KEY: str = os.getenv("REFLECTION_API_KEY", "")

    # 主动巡检配置
    # HEALTH_CHECK_ENABLED=false 关闭定时巡检；HEALTH_CHECK_INTERVAL_HOURS>0 时按该间隔周期性巡检，
    # 否则维持默认「每天 8:00」整点巡检。
    HEALTH_CHECK_ENABLED: bool = os.getenv("HEALTH_CHECK_ENABLED", "true").lower() in ("1", "true", "yes", "on")
    HEALTH_CHECK_INTERVAL_HOURS: float = float(os.getenv("HEALTH_CHECK_INTERVAL_HOURS", "-1"))

    # 调用 Java 的 HTTP 超时（秒）。read 必须足够长：
    # restart_service 在 Java 侧本身就要跑好几秒（缩容、等待、扩容）。
    # 人工审批已不再占用这个 read timeout：审批走 interrupt() 挂起后异步恢复。
    HTTP_CONNECT_TIMEOUT: float = float(os.getenv("HTTP_CONNECT_TIMEOUT", "5"))
    HTTP_READ_TIMEOUT: float = float(os.getenv("HTTP_READ_TIMEOUT", "60"))
    HTTP_WRITE_TIMEOUT: float = float(os.getenv("HTTP_WRITE_TIMEOUT", "10"))

    # ---------- 防死循环 ----------
    # LangGraph 图总步数上限（recursion_limit，硬闸门，条件边之外的兜底）
    GRAPH_RECURSION_LIMIT: int = int(os.getenv("GRAPH_RECURSION_LIMIT", "40"))
    # 主流程条件边允许的最大迭代轮数（iteration_count 达到后强制进 reporter 收束）
    MAX_ITERATION_ROUNDS: int = int(os.getenv("MAX_ITERATION_ROUNDS", "6"))

    # ---------- Token 优化：消息滑动窗口 ----------
    # 每个 Agent 子图内，LLM 循环保留的最近消息条数。LangGraph 的
    # trim_messages 会在每次进入队列式 Worker 前压缩超出窗口的旧消息。
    MESSAGES_WINDOW_SIZE: int = int(os.getenv("MESSAGES_WINDOW_SIZE", "12"))
    # trim_messages 的 token 上限（长日志/多轮对话超过后压缩）
    TRIM_MESSAGES_MAX_TOKENS: int = int(os.getenv("TRIM_MESSAGES_MAX_TOKENS", "4000"))

    # ---------- Send API 多 Pod 并发诊断 ----------
    # 并行诊断最大并发数，编译图/运行时通过 config={"max_concurrency": N} 生效
    PARALLEL_POD_DIAGNOSIS_MAX_CONCURRENCY: int = int(os.getenv("PARALLEL_POD_DIAGNOSIS_MAX_CONCURRENCY", "5"))

    model_config = SettingsConfigDict(
        env_file=".env",
        extra="ignore",  # 忽略 .env 中未定义的变量（如 LangFuse/Postgres 等辅助键）
    )

config = Settings()
