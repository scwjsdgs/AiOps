# -*- coding: utf-8 -*-
"""
历史案例库（Agent 自学习闭环）。

每次诊断完成后，把最终报告写进这个向量库（metadata: kind=case）；
下次相似告警进来，search_knowledge_base 会先检索历史案例再诊断——
「越用越聪明」的能力就是这两步。

与知识库（rag/vector_store.py 的 SOP 文档）共用 embedding 通道与
persist 目录结构，但 collection 独立，互不污染：
  - 知识库: collection "aiops_knowledge"
  - 案例库: collection "aiops_cases"

失败自动降级：入库/检索失败只记日志，绝不影响主链路。
"""
import logging
import os
import re
import time
import uuid

from rag.dashscope_embeddings import DashScopeEmbeddings
from langchain_text_splitters import RecursiveCharacterTextSplitter
from config import config

logger = logging.getLogger(__name__)

_CASE_COLLECTION = "aiops_cases"
_SPLITTER = RecursiveCharacterTextSplitter(chunk_size=500, chunk_overlap=50)

# 懒加载缓存（与 vector_store 同样的失败冷却思路）
_store = None
_last_failure_at = 0.0
_FAILURE_COOLDOWN_SECONDS = 60


def _get_store():
    """懒加载案例库。失败返回 None（降级，不抛异常）。"""
    global _store, _last_failure_at
    if _store is not None:
        return _store
    now = time.time()
    if _last_failure_at and (now - _last_failure_at) < _FAILURE_COOLDOWN_SECONDS:
        return None
    try:
        from langchain_community.vectorstores import Chroma
        persist_dir = os.path.join(config.VECTOR_STORE_DIR, "cases")
        _store = Chroma(
            collection_name=_CASE_COLLECTION,
            persist_directory=persist_dir,
            embedding_function=DashScopeEmbeddings(),
        )
        return _store
    except Exception as e:
        _last_failure_at = now
        logger.warning(f"案例库初始化失败（{_FAILURE_COOLDOWN_SECONDS}s 内不再重试）：{e}")
        return None


def _extract_service(report: str) -> str:
    """从报告文本提取服务名（complete_task 调用时拿不到 service，只能从报告提取）。

    支持常见格式：
      - 服务：leaky-app / 服务名: leaky-app
      - service=leaky-app / serviceName=leaky-app / deployment=leaky-app
      - 反引号包裹的 deployment 名
      - 「对 deployment/服务 leaky-app」等句中描述
    提不到返回 unknown —— metadata 缺失不影响检索，只影响展示。
    """
    text = (report or "")
    patterns = [
        r"(?:服务名|服务|deployment|Deployment|应用)\s*[:：]\s*`?([A-Za-z0-9_.-]+)`?",
        r"(?:serviceName|service|deployment|svc)\s*=\s*`?([A-Za-z0-9_.-]+)`?",
        r"(?:对|针对)\s*(?:deployment|服务|应用)\s*`?([A-Za-z0-9_.-]+)`?",
        r"`([A-Za-z0-9_.-]+)`",
        r"\b(?:deployment|服务|应用)[/:：]\s*([A-Za-z0-9_.-]+)",
    ]
    for pattern in patterns:
        m = re.search(pattern, text)
        if m:
            return m.group(1)
    return "unknown"


def save_case(
    report: str,
    service: str = "",
    alert_name: str = "",
    status: str = "SUCCESS",
    task_id: str = "",
) -> bool:
    """把一次诊断的最终报告写进案例库。

    分块后每块都带同一 metadata（service/alert_name/status/kind=case/taskId），
    检索时按相似度召回，元数据让 agent 知道这是哪次故障的哪部分。
    失败只记日志——自学习是增强，不是主链路。

    service 显式传入优先（完整链路现在由 callback 带 service_name）；
    仍为空的旧调用方再回退到从报告文本提取，尽量消除 service=unknown。
    """
    text = (report or "").strip()
    if not text or len(text) < 50:
        # 过短的报告（比如纯"执行失败：xxx"）没有沉淀价值
        return False
    store = _get_store()
    if store is None:
        return False
    svc = (service or "").strip() or _extract_service(text)
    try:
        chunks = _SPLITTER.split_text(text)
        ids = [str(uuid.uuid4()) for _ in chunks]
        metadatas = [{
            "kind": "case",
            "service": svc,
            "alert_name": alert_name or "unknown",
            "status": status,
            "taskId": task_id or "",
        } for _ in chunks]
        store.add_texts(texts=chunks, metadatas=metadatas, ids=ids)
        logger.info(f"案例已入库：service={svc} alert={alert_name or 'unknown'} "
                    f"task={task_id or '-'} chunks={len(chunks)}")
        return True
    except Exception as e:
        logger.warning(f"案例入库失败（可忽略）: {e}")
        return False


def search_cases(query: str, k: int = 3) -> list:
    """检索相似历史案例。返回 [{content, service, alert_name, status}, ...]。

    失败返回空列表（调用方降级为只查知识库）。
    """
    text = (query or "").strip()
    if not text:
        return []
    store = _get_store()
    if store is None:
        return []
    try:
        # 历史案例检索同样先润色 Query（口语 -> 标准术语），提升召回
        if config.QUERY_REWRITE_ENABLED:
            from rag.query_rewriter import rewrite_query
            search_text = rewrite_query(text)
            if search_text and search_text != text:
                logger.info(f"案例检索 Query 润色: {text} -> {search_text}")
                text = search_text
        docs = store.similarity_search_with_score(text, k=k)
        results = []
        for doc, score in docs:
            meta = doc.metadata or {}
            results.append({
                "content": doc.page_content,
                "service": meta.get("service", "unknown"),
                "alert_name": meta.get("alert_name", "unknown"),
                "status": meta.get("status", ""),
                "score": float(score),
            })
        return results
    except Exception as e:
        logger.warning(f"案例检索失败（可忽略）: {e}")
        return []


def case_count() -> int:
    """当前案例数（诊断/展示用）。读不到返回 0。"""
    store = _get_store()
    if store is None:
        return 0
    try:
        return int(store._collection.count())
    except Exception:
        return 0
