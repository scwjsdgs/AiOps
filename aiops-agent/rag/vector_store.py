import os
import time
import logging

from langchain_community.document_loaders import DirectoryLoader, TextLoader
from langchain_community.vectorstores import Chroma
from rag.dashscope_embeddings import DashScopeEmbeddings
from langchain_text_splitters import RecursiveCharacterTextSplitter
from config import config

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
