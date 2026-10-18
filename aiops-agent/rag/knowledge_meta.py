# -*- coding: utf-8 -*-
"""
RAG 知识库元数据与闭环管理（不依赖外部新组件）。

职责：
  1. 文档级 metadata 落盘（JSON），记录每份 SOP 文档的 source 文件名、
     版本、状态、质量分、上传时间、内容摘要与生效状态。
  2. 更新审计：每次上传/切片/embedding/回滚/删除都追加一条审计记录。
  3. 版本回滚：不重算 embedding，而是把“当前生效版本”指回上一版。
  4. 增量 upsert：以 source 文件名为键，只重新处理变化的文档。
  5. 去重：相同文件名 + 相同内容 hash 视为重复，不重复入库。
  6. 生效延迟监控：记录每个版本“创建时间 -> 生效时间”，超时由调用方告警。

所有读写都做失败降级：知识库管理是增强能力，任何异常都不应影响主链路。
"""
import json
import os
import time
import uuid

from config import config

META_PATH = os.path.join(config.VECTOR_STORE_DIR, "knowledge_meta.json")
AUDIT_PATH = os.path.join(config.VECTOR_STORE_DIR, "knowledge_audit.jsonl")

ACTIVE = "ACTIVE"
DISABLED = "DISABLED"


def _ensure_dir(path: str):
    os.makedirs(os.path.dirname(path), exist_ok=True)


def _now():
    return time.strftime("%Y-%m-%dT%H:%M:%S%z")


def load_meta() -> dict:
    """读取文档元数据；不存在或损坏返回空结构。"""
    if not os.path.exists(META_PATH):
        return {"version": 1, "documents": {}}
    try:
        with open(META_PATH, "r", encoding="utf-8") as f:
            data = json.load(f)
        return data if isinstance(data, dict) else {"version": 1, "documents": {}}
    except Exception as e:
        return {"version": 1, "documents": {}, "error": str(e)}


def _save_meta(data: dict) -> bool:
    try:
        _ensure_dir(META_PATH)
        with open(META_PATH, "w", encoding="utf-8") as f:
            json.dump(data, f, ensure_ascii=False, indent=2)
        return True
    except Exception:
        return False


def append_audit(action: str, source: str, detail: dict):
    """追加一条审计记录（追加写 JSONL，简单且失败不影响主链路）。"""
    try:
        _ensure_dir(AUDIT_PATH)
        record = {
            "time": _now(),
            "action": action,
            "source": source,
            "detail": detail,
        }
        with open(AUDIT_PATH, "a", encoding="utf-8") as f:
            f.write(json.dumps(record, ensure_ascii=False) + "\n")
    except Exception as e:
        print(f"知识库审计写入失败（可忽略）: {e}")


def list_audit(limit: int = 200) -> list:
    """读取最近审计记录，倒序返回。"""
    if not os.path.exists(AUDIT_PATH):
        return []
    rows = []
    try:
        with open(AUDIT_PATH, "r", encoding="utf-8") as f:
            for line in f:
                line = line.strip()
                if not line:
                    continue
                try:
                    rows.append(json.loads(line))
                except json.JSONDecodeError:
                    continue
    except Exception:
        return []
    return rows[-limit:][::-1]


def _doc_id(source: str) -> str:
    return str(uuid.uuid5(uuid.NAMESPACE_DNS, source or "unknown"))


def register_document(source: str, content: str, version: str = "v1",
                      quality_score: float = 0.0, status: str = ACTIVE,
                      summary: str = "", operator: str = "system",
                      changed: bool = True) -> dict:
    """注册或更新一份 SOP 文档的元数据。

    幂等规则：source 相同且 content_hash 相同 -> 不新增版本（返回重复标记）。
    content_hash 变化 -> 创建新版本，旧版本保留可回滚。
    """
    source = (source or "").strip()
    if not source:
        return {"ok": False, "reason": "source 不能为空"}

    content_hash = _simple_hash(content or "")
    meta = load_meta()
    docs = meta.setdefault("documents", {})

    existing = docs.get(source)
    if existing and existing.get("content_hash") == content_hash:
        append_audit("duplicate_skip", source, {
            "version": existing.get("version", ""),
            "reason": "内容未变化，跳过重复 upsert",
            "operator": operator,
        })
        return {"ok": True, "duplicate": True, "source": source, "version": existing.get("version", "")}

    now = _now()
    if existing:
        # 旧版本放入 versions 列表，保留可回滚
        versions = existing.setdefault("versions", [])
        versions.append({
            "version": existing.get("version", ""),
            "content_hash": existing.get("content_hash", ""),
            "status": existing.get("status", ACTIVE),
            "quality_score": existing.get("quality_score", 0.0),
            "updated_at": existing.get("updated_at", now),
        })
        existing["previous_version"] = existing.get("version", "")
        existing["version"] = version if version else _next_version(existing.get("version", ""))
    else:
        existing = {
            "doc_id": _doc_id(source),
            "source": source,
            "version": version if version else "v1",
            "versions": [],
        }
        docs[source] = existing

    existing["content_hash"] = content_hash
    existing["status"] = status
    existing["quality_score"] = quality_score
    existing["summary"] = summary or ""
    existing["chunk_count"] = 0
    existing["created_at"] = existing.get("created_at", now)
    existing["updated_at"] = now
    existing["operator"] = operator
    existing["effective_at"] = now
    existing["effective_latency_ms"] = 0

    if changed:
        append_audit("upsert", source, {
            "version": existing.get("version", ""),
            "content_hash": content_hash,
            "operator": operator,
        })
    _save_meta(meta)
    return {"ok": True, "source": source, "version": existing.get("version", ""),
            "duplicate": False}


def _simple_hash(text: str) -> str:
    import hashlib
    return hashlib.sha256(text.encode("utf-8")).hexdigest()[:16]


def _next_version(current: str) -> str:
    try:
        num = int((current or "v0")[1:])
        return f"v{num + 1}"
    except Exception:
        return "v2"


def list_documents(active_only: bool = False) -> list:
    """文档清单（倒序按更新时间）。"""
    meta = load_meta()
    docs = list(meta.get("documents", {}).values())
    docs.sort(key=lambda d: d.get("updated_at", ""), reverse=True)
    return [d for d in docs if not active_only or d.get("status") == ACTIVE]


def get_document(source: str) -> dict:
    return load_meta().get("documents", {}).get(source, {})


def update_chunk_count(source: str, count: int):
    meta = load_meta()
    doc = meta.get("documents", {}).get(source)
    if not doc:
        return
    doc["chunk_count"] = int(count or 0)
    _save_meta(meta)


def update_quality(source: str, score: float, note: str = "", operator: str = "system") -> bool:
    """更新文档质量评分并审计。score 建议 0-1。"""
    meta = load_meta()
    doc = meta.get("documents", {}).get(source)
    if not doc:
        return False
    old = doc.get("quality_score", 0.0)
    doc["quality_score"] = float(score)
    doc["quality_note"] = note or ""
    doc["quality_updated_at"] = _now()
    _save_meta(meta)
    append_audit("quality_update", source, {
        "from": old, "to": float(score), "note": note, "operator": operator,
    })
    return True


def rollback(source: str, target_version: str = "", operator: str = "system") -> dict:
    """回滚到指定版本（默认上一版）。

    版本本身不做物理重建，而是把元数据里的当前版本改回目标版本，
    配合 vector_store 的 source 元数据/内容筛选实现“生效版本回退”。
    """
    meta = load_meta()
    doc = meta.get("documents", {}).get(source)
    if not doc:
        return {"ok": False, "reason": "文档不存在"}
    versions = doc.get("versions") or []
    if not versions:
        return {"ok": False, "reason": "没有可回滚的历史版本"}

    target = target_version
    if not target:
        # 默认回滚到 versions 中最近的一个版本（上一版）
        target = versions[-1].get("version", "")
    target_ver = next((v for v in versions if v.get("version") == target), None)
    if not target_ver:
        return {"ok": False, "reason": f"目标版本不存在: {target}"}

    now = _now()
    doc["previous_version"] = doc.get("version", "")
    doc["version"] = target_ver["version"]
    doc["content_hash"] = target_ver["content_hash"]
    doc["status"] = ACTIVE
    doc["updated_at"] = now
    doc["effective_at"] = now
    doc["operator"] = operator
    doc["rollback_from"] = target_ver.get("version", "")
    # 保留版本列表供再次回滚；目标版本不删除
    _save_meta(meta)
    append_audit("rollback", source, {
        "target_version": target, "operator": operator,
    })
    return {"ok": True, "source": source, "version": target}


def mark_effective(source: str):
    """生效确认：记录生效时间与延迟，供“生效延迟监控”统计。"""
    meta = load_meta()
    doc = meta.get("documents", {}).get(source)
    if not doc:
        return
    try:
        created = doc.get("created_at", "")
        created_ts = time.mktime(time.strptime(created, "%Y-%m-%dT%H:%M:%S%z"))
        latency_ms = max(0, int((time.time() - created_ts) * 1000))
        doc["effective_at"] = _now()
        doc["effective_latency_ms"] = latency_ms
        _save_meta(meta)
    except Exception:
        doc["effective_at"] = _now()
        _save_meta(meta)


def stats() -> dict:
    """知识库概况：文档数、活跃数、总 chunk、平均分、失效/重试计数。"""
    docs = list_documents()
    total_chunks = sum(int(d.get("chunk_count", 0)) for d in docs)
    scores = [float(d.get("quality_score", 0)) for d in docs if d.get("quality_score") is not None]
    return {
        "documents": len(docs),
        "active": sum(1 for d in docs if d.get("status") == ACTIVE),
        "chunks": total_chunks,
        "avg_quality": round(sum(scores) / len(scores), 4) if scores else 0.0,
        "latest_updated": max((d.get("updated_at", "") for d in docs), default=""),
    }
