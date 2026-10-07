import os
import time
import logging

from langchain_community.document_loaders import DirectoryLoader, TextLoader
from langchain_community.vectorstores import Chroma
from langchain_text_splitters import RecursiveCharacterTextSplitter

from config import config
from rag.dashscope_embeddings import DashScopeEmbeddings
from rag.query_rewriter import rewrite_query

logger = logging.getLogger(__name__)

# 构建失败后的冷却时间（秒）。
# 没有它的话，每次 agent 启动都会重新尝试构建一次向量库，
# 密钥不对时会反复打 embedding API（每次都 401 且拖慢启动）。
_FAILURE_COOLDOWN_SECONDS = 60


def _count_documents(store) -> int:
    """数一数这个向量库里到底有多少条。读不到就当作 0。"""
    try:
        collection = getattr(store, "_collection", None)
        if collection is not None:
            return int(collection.count())
    except Exception as e:
        logger.debug(f"读取向量库文档数失败：{e}")
    return 0


def build_vector_store(force_rebuild: bool = False):
    # embedding 走 DashScope 原生接口（见 rag/dashscope_embeddings.py），
    # 不再复用 LLM 的 DeepSeek key —— DeepSeek 没有 /embeddings，之前打开 RAG
    # 会在启动时反复 404。两条通道独立：LLM 管对话，embedding 只管知识库。
    embeddings = DashScopeEmbeddings()
    persist_dir = config.VECTOR_STORE_DIR
    if not force_rebuild and os.path.exists(persist_dir) and os.listdir(persist_dir):
        store = Chroma(
            persist_directory=persist_dir,
            embedding_function=embeddings,
        )
        # 目录非空 ≠ 有数据。上一次构建如果在 embedding 阶段失败（比如密钥无效），
        # chromadb 已经建好了目录和一个空 collection。只看 os.listdir 的话，
        # 这个空库会被永远加载下去，检索恒为空且不报任何错 —— 知识库静默失效。
        if _count_documents(store) > 0:
            return store
        logger.warning("向量库目录存在但文档数为 0，重新构建")
    elif force_rebuild and os.path.exists(persist_dir):
        # 强制重建：先清空旧 collection，避免旧 chunks 的旧 metadata 残留，
        # 否则 source 会混着 basename 和旧路径。
        try:
            old_store = Chroma(
                persist_directory=persist_dir,
                embedding_function=embeddings,
            )
            old_store.delete_collection()
        except Exception as e:
            logger.warning(f"清空旧向量库失败（继续重建）: {e}")

    loader = DirectoryLoader(
        config.DOCUMENTS_DIR,
        glob="**/*.txt",
        loader_cls=TextLoader,
        loader_kwargs={"encoding": "utf-8"},
    )
    documents = loader.load()
    if not documents:
        logger.warning("未找到任何文档，请将 .txt 文件放入 rag/documents/")
        return None

    text_splitter = RecursiveCharacterTextSplitter(chunk_size=500, chunk_overlap=50)
    docs = text_splitter.split_documents(documents)
    # 给每个 chunk 补上 source metadata（文件名），
    # 供检索评估、审计和精确判断“这条文档来自哪个 SOP/手册”。
    # 注意 LangChain 的 Document.metadata 原有 source 可能是绝对路径，这里统一为 basename。
    for d in docs:
        # 强制只保留文件名：DirectoryLoader 会给绝对/相对路径，
        # 统一成 basename 后检索评估、审计都能稳定对照“哪个文档”。
        d.metadata["source"] = os.path.basename(str(d.metadata.get("source") or "")) or "unknown"
    vectorstore = Chroma.from_documents(
        documents=docs,
        embedding=embeddings,
        persist_directory=persist_dir,
    )
    # 新版 chromadb 在 from_documents 时已持久化，persist() 可能已不存在
    if hasattr(vectorstore, "persist"):
        vectorstore.persist()
    return vectorstore


_vector_store = None
_last_failure_at = 0.0
_last_failure_msg = ""


def get_vector_store():
    """懒加载向量库。

    与之前的区别：失败时返回 None 而不是把异常抛出去。
    知识库不可用只应该让知识库工具降级，不应该让整个 agent 起不来。
    """
    global _vector_store, _last_failure_at, _last_failure_msg

    if _vector_store is not None:
        return _vector_store

    now = time.time()
    if _last_failure_at and (now - _last_failure_at) < _FAILURE_COOLDOWN_SECONDS:
        logger.debug(f"向量库处于失败冷却期，跳过重建：{_last_failure_msg}")
        return None

    try:
        _vector_store = build_vector_store()
        if _vector_store is None:
            _last_failure_at = now
            _last_failure_msg = "知识库为空（rag/documents 下没有文档）"
        return _vector_store
    except Exception as e:
        _last_failure_at = now
        _last_failure_msg = str(e)
        logger.warning(f"构建向量库失败（{_FAILURE_COOLDOWN_SECONDS}s 内不再重试）：{e}")
        return None


# ============================================================
# 混合检索：BM25 关键词检索 + Chroma 向量检索 + RRF 融合 + Rerank 精排
# ============================================================

# 延迟导入，避免建库失败时把 rank_bm25 的缺失也牵扯进来
def _get_bm25_index():
    """为知识库文档构建 BM25 倒排索引（按 chunk 维度）。

    分词用 jieba（中文运维文档），停用词过滤常见虚词。
    返回 (bm25_index, corpus_chunks) 或 None。
    """
    try:
        from rank_bm25 import BM25Okapi
        import jieba
        jieba.setLogLevel(logging.INFO)
    except ImportError:
        logger.warning("未安装 rank_bm25 / jieba，BM25 通道降级关闭")
        return None

    store = get_vector_store()
    if store is None:
        return None
    try:
        collection = getattr(store, "_collection", None)
        if collection is None:
            return None
        # Chroma 拿全部文档与 metadata
        data = collection.get(include=["documents", "metadatas"])
        docs = data.get("documents") or []
        metas = data.get("metadatas") or []
        if not docs:
            return None
        corpus = [list(jieba.cut(d)) for d in docs]
        bm25 = BM25Okapi(corpus)
        return (bm25, docs, metas)
    except Exception as e:
        logger.warning(f"构建 BM25 索引失败：{e}")
        return None


_bm25_cache = None


def _tokenize(text: str) -> list:
    try:
        import jieba
        jieba.setLogLevel(logging.INFO)
        return list(jieba.cut(text))
    except ImportError:
        return text.split()


def _bm25_search(query: str, k: int = 20):
    """BM25 关键词召回，返回 [(content, metadata), ...] 按分数降序。"""
    global _bm25_cache
    if _bm25_cache is None:
        _bm25_cache = _get_bm25_index()
    if _bm25_cache is None:
        return []
    bm25, docs, metas = _bm25_cache
    try:
        scores = bm25.get_scores(_tokenize(query))
        ranked = sorted(zip(docs, metas, scores), key=lambda x: x[2], reverse=True)[:k]
        return [(d, m or {}, s) for d, m, s in ranked if s > 0]
    except Exception as e:
        logger.warning(f"BM25 检索失败：{e}")
        return []


def _vector_search(query: str, k: int = 20):
    """Chroma 向量召回，返回 [(content, metadata), ...]（已按相似度排序）。"""
    store = get_vector_store()
    if store is None:
        return []
    try:
        docs = store.similarity_search_with_score(query, k=k)
        # docs 元素是 (Document, score)，score 越小越相似
        return [(d.page_content, d.metadata or {}, float(s)) for d, s in docs]
    except Exception as e:
        logger.warning(f"向量检索失败：{e}")
        return []


def _rrf_fuse(vector_results: list, bm25_results: list, k: int = 60, top_n: int = 20) -> list:
    """RRF（倒数排名融合）：score = Σ 1/(k + rank_i)，rank 从 1 开始。

    两路结果都是 (content, metadata, score) 三元组。
    """
    rrf = {}
    # 向量路
    for rank, (content, _meta, _score) in enumerate(vector_results, start=1):
        rrf[content] = rrf.get(content, 0.0) + 1.0 / (k + rank)
    # BM25 路
    for rank, (content, _meta, _score) in enumerate(bm25_results, start=1):
        rrf[content] = rrf.get(content, 0.0) + 1.0 / (k + rank)

    # 融合后保留 metadata（优先向量路的 metadata，因为含真实来源信息）
    meta_map = {content: meta for content, meta, _s in vector_results}
    for content, meta, _s in bm25_results:
        meta_map.setdefault(content, meta)

    ranked = sorted(rrf.items(), key=lambda x: x[1], reverse=True)[:top_n]
    return [(content, meta_map.get(content, {}), score) for content, score in ranked]


_last_rerank_failure_at = 0.0
_RERANK_COOLDOWN_SECONDS = 60


def _rerank(query: str, candidates: list, top_n: int = 3) -> list:
    """Rerank 精排：调 DashScope gte-rerank API，对 (query, doc) 打分排序。

    candidates: [(content, metadata, score), ...]
    失败时降级返回原候选（保持已 RRF 粗排结果）；失败后进入冷却，
    避免每次检索都因无权限/网络问题反复打外呼拖慢主链路。
    """
    global _last_rerank_failure_at
    if not candidates or not config.RERANK_ENABLED:
        return candidates[:top_n]

    api_key = config.DASHSCOPE_API_KEY
    if not api_key:
        logger.warning("未配置 DASHSCOPE_API_KEY，Rerank 降级")
        return candidates[:top_n]

    now = time.time()
    if _last_rerank_failure_at and (now - _last_rerank_failure_at) < _RERANK_COOLDOWN_SECONDS:
        return candidates[:top_n]

    try:
        import httpx
        docs = [c[0] for c in candidates]
        payload = {
            "model": config.RERANK_MODEL,
            "query": query,
            "documents": docs,
            "top_n": top_n,
            "return_documents": True,
        }
        resp = httpx.post(
            "https://dashscope.aliyuncs.com/api/v1/services/rerank/text-rerank/text-rerank",
            json=payload,
            headers={"Authorization": f"Bearer {api_key}"},
            timeout=httpx.Timeout(30.0),
        )
        resp.raise_for_status()
        body = resp.json()
        results = (body.get("output") or {}).get("results") or []
        # 结果按 relevance 降序，每项含 index（对应原 candidates 下标）
        reranked = []
        for item in results:
            idx = item.get("index")
            if idx is not None and 0 <= idx < len(candidates):
                reranked.append(candidates[idx])
        return reranked[:top_n] if reranked else candidates[:top_n]
    except Exception as e:
        _last_rerank_failure_at = time.time()
        logger.warning(f"Rerank 调用失败，降级返回 RRF 结果: {e}")
        return candidates[:top_n]


def hybrid_search(query: str, k: int = 3):
    """检索入口：润色 Query -> BM25 + 向量双路召回 -> RRF 粗排 -> Rerank 精排。

    返回 [(content, metadata, rrf_score), ...]，已按最终排序截取 k 条。
    任何一步失败都降级，绝不让主链路中断。
    """
    raw = (query or "").strip()
    if not raw:
        return []

    # 1. Query 润色（失败则用原句）
    search_query = rewrite_query(raw) if config.QUERY_REWRITE_ENABLED else raw
    if search_query != raw:
        logger.info(f"Query 润色: {raw} -> {search_query}")

    # 2. 双路召回（各取粗排上限，默认 20）
    bm25_results = _bm25_search(search_query, k=20) if config.RAG_BM25_ENABLED else []
    vector_results = _vector_search(search_query, k=20)
    logger.info(f"双路召回：向量 {len(vector_results)} 条 / BM25 {len(bm25_results)} 条")

    # 3. RRF 融合粗排，取 Top20
    fused = _rrf_fuse(vector_results, bm25_results, k=config.RAG_RRF_K, top_n=20)
    if not fused:
        return []

    # 4. Rerank 精排，取 Top k（默认 3）
    return _rerank(search_query, fused, top_n=k)