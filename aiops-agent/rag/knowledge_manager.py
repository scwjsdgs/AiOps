# -*- coding: utf-8 -*-
"""
RAG 知识库管理 API：上传、切片、增量 upsert、版本回滚、更新审计、质量评分。

设计约束：
  - 不引入新第三方依赖，复用 langchain_text_splitters / DashScopeEmbeddings / Chroma；
  - 上传文件落在 DOCUMENTS_DIR，向量库按 source 元数据实现增量；
  - 全部操作失败降级，不阻断告警主链路。
"""
import hashlib
import os
import re
import tempfile
import time

from config import config

from rag.dashscope_embeddings import DashScopeEmbeddings
from rag.knowledge_meta import (
    ACTIVE,
    append_audit,
    get_document,
    list_audit,
    list_documents,
    load_meta,
    mark_effective,
    register_document,
    rollback,
    stats,
    update_chunk_count,
    update_quality,
)
from langchain_text_splitters import RecursiveCharacterTextSplitter

SUPPORTED_SUFFIX = {".txt", ".md"}

_SPLITTER = RecursiveCharacterTextSplitter(chunk_size=500, chunk_overlap=50)


def _safe_filename(name: str) -> str:
    name = os.path.basename((name or "").strip())
    name = re.sub(r"[\\/:*?\"<>|]", "_", name)
    return name or "untitled.txt"


def _simple_hash(text: str) -> str:
    return hashlib.sha256(text.encode("utf-8")).hexdigest()[:16]


def _read_file(filepath: str, size_limit: int = 5 * 1024 * 1024) -> str:
    size = os.path.getsize(filepath)
    if size > size_limit:
        raise ValueError(f"文件过大：{size} 字节，上限 {size_limit}")
    with open(filepath, "r", encoding="utf-8") as f:
        return f.read()


def _embed(texts: list) -> list:
    """调用 DashScope embedding；失败抛出，由上层决定是否降级/重试。"""
    if not texts:
        return []
    return DashScopeEmbeddings().embed_documents(texts)


def _build_store():
    from langchain_chroma import Chroma
    persist_dir = config.VECTOR_STORE_DIR
    store = Chroma(
        persist_directory=persist_dir,
        embedding_function=DashScopeEmbeddings(),
    )
    return store


def _current_chunks(store, source: str) -> list:
    """当前 Chroma 中该 source 的 chunks 与 ids。"""
    try:
        collection = getattr(store, "_collection", None)
        if collection is None:
            return [], []
        data = collection.get(where={"source": source}, include=["documents", "metadatas", "ids"])
        docs = data.get("documents") or []
        ids = data.get("ids") or []
        return docs, ids
    except Exception:
        return [], []


def upload_document(content: str, filename: str, operator: str = "system",
                    version: str = "", quality_score: float = 0.0,
                    summary: str = "") -> dict:
    """上传一份文档：保存文件 -> 注册元数据 -> 切片 -> upsert 向量。

    增量设计：内容 hash 没变直接返回 duplicate=True；
    内容变了则删除旧 chunks 再写入新 chunks（等价于 upsert）。
    """
    filename = _safe_filename(filename)
    content = (content or "").strip()
    if not content:
        return {"ok": False, "reason": "内容为空"}

    # 1. 注册元数据，先做内容级去重
    registered = register_document(filename, content, version=version,
                                   quality_score=quality_score, summary=summary,
                                   operator=operator)
    if not registered.get("ok"):
        return registered
    if registered.get("duplicate"):
        return {"ok": True, "duplicate": True, "source": filename,
                "version": registered.get("version", ""), "chunks": 0}

    # 2. 保存源文件
    doc_path = os.path.join(config.DOCUMENTS_DIR, filename)
    os.makedirs(config.DOCUMENTS_DIR, exist_ok=True)
    with open(doc_path, "w", encoding="utf-8") as f:
        f.write(content)

    # 3. 切片
    chunks = _SPLITTER.split_text(content)
    try:
        store = _build_store()
        old_docs, old_ids = _current_chunks(store, filename)
        if old_ids:
            store._collection.delete(ids=old_ids)
        if chunks:
            # 每个 chunk 带 source 元数据，便于检索/审计/版本筛选
            metadatas = [{
                "source": filename,
                "version": registered.get("version", ""),
                "doc_id": registered.get("source", filename),
                "updated_at": time.strftime("%Y-%m-%dT%H:%M:%S%z"),
            } for _ in chunks]
            ids = [f"{filename}-{_simple_hash(chunk)}-{i}" for i, chunk in enumerate(chunks)]
            store.add_texts(texts=chunks, metadatas=metadatas, ids=ids)
            if hasattr(store, "persist"):
                store.persist()
        update_chunk_count(filename, len(chunks))
        mark_effective(filename)
        append_audit("upload", filename, {
            "chunks": len(chunks), "operator": operator,
            "old_chunks": len(old_ids),
        })
        return {"ok": True, "source": filename, "version": registered.get("version", ""),
                "chunks": len(chunks), "removed_chunks": len(old_ids), "duplicate": False}
    except Exception as e:
        append_audit("upload_failed", filename, {"error": str(e), "operator": operator})
        return {"ok": False, "reason": f"向量写入失败，源文件与元数据已保留：{e}"}


def rebuild_vector_store(operator: str = "system") -> dict:
    """全量重建：读取当前 documents 目录，逐份上传并更新 chunk 数。"""
    os.makedirs(config.DOCUMENTS_DIR, exist_ok=True)
    files = sorted(f for f in os.listdir(config.DOCUMENTS_DIR)
                   if os.path.splitext(f)[1].lower() in SUPPORTED_SUFFIX)
    result = {"ok": True, "files": [], "chunks": 0}
    for name in files:
        try:
            content = _read_file(os.path.join(config.DOCUMENTS_DIR, name))
            # 先重置元数据再上传，保证 rebuild 后旧 chunks 不会残留
            meta = load_meta()
            doc = meta.get("documents", {}).get(name)
            if doc:
                doc["content_hash"] = ""
            import json
            with open(os.path.join(config.VECTOR_STORE_DIR, "knowledge_meta.json"), "w", encoding="utf-8") as f:
                json.dump(meta, f, ensure_ascii=False, indent=2)
            res = upload_document(content, name, operator=operator, version=doc.get("version", "") if doc else "")
            result["files"].append(res)
            result["chunks"] += int(res.get("chunks", 0) or 0)
        except Exception as e:
            result["files"].append({"ok": False, "source": name, "reason": str(e)})
    append_audit("rebuild", "*", {"operator": operator, "files": len(files)})
    return result


def delete_document(source: str, operator: str = "system") -> dict:
    """删除文档：从向量库与元数据中移除。保留审计记录。"""
    source = _safe_filename(source)
    try:
        store = _build_store()
        old_docs, old_ids = _current_chunks(store, source)
        if old_ids:
            store._collection.delete(ids=old_ids)
            if hasattr(store, "persist"):
                store.persist()
        meta = load_meta()
        meta.get("documents", {}).pop(source, None)
        _save_meta(meta)
        append_audit("delete", source, {"removed_chunks": len(old_ids), "operator": operator})
        return {"ok": True, "removed_chunks": len(old_ids)}
    except Exception as e:
        append_audit("delete_failed", source, {"error": str(e), "operator": operator})
        return {"ok": False, "reason": str(e)}


def _save_meta(meta: dict) -> bool:
    import json
    path = os.path.join(config.VECTOR_STORE_DIR, "knowledge_meta.json")
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "w", encoding="utf-8") as f:
        json.dump(meta, f, ensure_ascii=False, indent=2)
    return True


# ---- 对外 API 聚合 ----

def overview() -> dict:
    """供前端知识库管理页：文档清单 + 概况 + 最近审计。"""
    docs = list_documents()
    meta = load_meta()
    return {
        "stats": stats(),
        "documents": docs,
        "audit": list_audit(20),
        "documents_dir": config.DOCUMENTS_DIR,
        "vector_store_dir": config.VECTOR_STORE_DIR,
        "active_versions": sum(1 for d in docs if d.get("status") == ACTIVE),
    }


def document_detail(source: str) -> dict:
    doc = get_document(source)
    return {"ok": bool(doc), "document": doc}


def retry_failed(operator: str = "system") -> dict:
    """重试失败的上传：从审计里找 upload_failed，重新尝试。

    这里返回已记录失败项与重试结果；真实重试依赖底层向量库可用性。
    """
    failed = [a for a in list_audit(200) if a.get("action") == "upload_failed"]
    result = []
    for item in failed[-10:]:
        source = item.get("source", "")
        content_path = os.path.join(config.DOCUMENTS_DIR, source)
        if not os.path.exists(content_path):
            continue
        try:
            content = _read_file(content_path)
            res = upload_document(content, source, operator=operator)
            result.append({"source": source, "retried": True, "result": res})
        except Exception as e:
            result.append({"source": source, "retried": False, "error": str(e)})
    return {"failed_count": len(failed), "retries": result}
