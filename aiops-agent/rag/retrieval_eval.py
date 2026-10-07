# -*- coding: utf-8 -*-
"""
RAG 检索评估：Hit@K 与 MRR。

测试集格式（JSON）：
[
  {
    "question": "Nginx 频繁重启怎么排查？",
    "standard_doc_id": "k8s_pod_crashloop.txt#OOMKilled",
    "standard_answer": "查看 Pod 事件、重启次数、日志，定位 OOMKilled 或配置错误"
  }
]

用法：
  .venv/Scripts/python.exe rag/retrieval_eval.py
  .venv/Scripts/python.exe rag/retrieval_eval.py --topk 5
"""
import argparse
import json
import logging
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

from rag.vector_store import hybrid_search  # noqa: E402

logging.basicConfig(level=logging.INFO)
logger = logging.getLogger(__name__)

DEFAULT_DATASET = os.path.join(os.path.dirname(__file__), "eval_dataset.json")


def _load_dataset(path: str) -> list:
    if not os.path.exists(path):
        logger.warning(f"测试集不存在：{path}，返回空")
        return []
    with open(path, "r", encoding="utf-8") as f:
        return json.load(f)


def _is_hit(result: tuple, standard_id: str) -> bool:
    """判断检索到的 result 是否命中标准文档 ID。

    标准 ID 形如 "k8s_pod_crashloop.txt#OOMKilled"；
    result 为 (content, metadata, score)。
    优先用 metadata["source"]（建库时写入的文件名）精确匹配；
    旧库没有 source 时退化到 chunk 文本是否包含章节关键词。
    """
    if not standard_id:
        return False
    # 去掉路径，取文件名
    basename = standard_id.replace("\\", "/").split("/")[-1]
    doc_file = basename.split("#")[0].lower()
    section = basename.split("#")[1] if "#" in basename else ""
    content, metadata = result[0], result[1] or {}
    source = str(metadata.get("source") or "").lower()

    # 精确匹配 source
    if source and doc_file and doc_file == source.split("/")[-1].lower():
        if section:
            # 有章节时仍要求正文包含关键段，避免同一文件不同章节误判
            return section.lower() in (content or "").lower()
        return True

    # 旧库无 source：退化到文件名/章节关键词出现在正文
    text = (content or "").lower()
    if doc_file and doc_file not in text:
        return False
    if section and section.lower() not in text:
        return False
    return True


def evaluate(dataset: list, topk: int = 3) -> dict:
    """返回 hit@K 与 MRR 指标。"""
    hit_counts = {k: 0 for k in [1, 3, 5]}
    reciprocal_ranks = []
    total = 0

    for item in dataset:
        question = (item.get("question") or "").strip()
        standard_id = item.get("standard_doc_id") or ""
        if not question or not standard_id:
            continue
        total += 1
        results = hybrid_search(question, k=max(topk, 5))

        rank = None
        for i, result in enumerate(results[:5], start=1):
            if _is_hit(result, standard_id):
                rank = i
                break
        if rank is not None:
            reciprocal_ranks.append(1.0 / rank)
            for k in hit_counts:
                if rank <= k:
                    hit_counts[k] += 1

    if total == 0:
        return {"total": 0, "hit@1": 0.0, "hit@3": 0.0, "hit@5": 0.0, "mrr": 0.0}

    return {
        "total": total,
        "hit@1": round(hit_counts[1] / total, 4),
        "hit@3": round(hit_counts[3] / total, 4),
        "hit@5": round(hit_counts[5] / total, 4),
        "mrr": round(sum(reciprocal_ranks) / total, 4),
    }


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--dataset", default=DEFAULT_DATASET)
    parser.add_argument("--topk", type=int, default=3)
    args = parser.parse_args()

    dataset = _load_dataset(args.dataset)
    logger.info(f"测试集条数：{len(dataset)}")
    result = evaluate(dataset, topk=args.topk)
    print(json.dumps(result, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
