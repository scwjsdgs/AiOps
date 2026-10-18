# -*- coding: utf-8 -*-
"""
RAG 分层评估指标（Ragas 风格，纯 Python / 可选 LLM-judge）。

分层：
  - 检索层：Context Precision / Context Recall / NDCG / Recall@K / MRR / Hit@K
  - 生成层：Faithfulness / Answer Relevance / Answer Correctness

兼容两种输入：
  1. 已有 retrieval_eval.py 的测试集 [{question, standard_doc_id, standard_answer}]
  2. 分层评测集 [{question, reference, contexts, answer, reference_answer}]

LLM-judge 默认关闭；开启后通过 OpenAI 兼容接口做可解释打分，失败自动降级为启发式。
"""
from __future__ import annotations

import math
import re
from dataclasses import dataclass, field
from typing import List, Optional


@dataclass
class EvalSample:
    question: str
    reference: str                 # 标准答案/标准上下文
    contexts: List[str] = field(default_factory=list)   # 实际检索到的上下文
    answer: str = ""               # 实际生成答案
    reference_answer: str = ""     # 标准答案（Answer Correctness 用）


# ---------------- 基础工具 ----------------

def _tokenize(text: str) -> List[str]:
    """轻量分词：中文按字符与英文单词切分，足以支撑指标计算。"""
    if not text:
        return []
    # 保留中文/英文/数字，去掉标点
    parts = re.findall(r"[一-鿿]|[A-Za-z0-9]+", text.lower())
    return parts


def _set_overlap(a: set, b: set):
    if not b:
        return 0.0
    return len(a & b) / len(b)


def _jaccard(a: set, b: set) -> float:
    if not a and not b:
        return 1.0
    if not a or not b:
        return 0.0
    return len(a & b) / len(a | b)


def _is_relevant(context: str, reference: str) -> bool:
    """启发式相关性：上下文与标准答案的 token 重叠度。"""
    ctx = set(_tokenize(context))
    ref = set(_tokenize(reference))
    if not ctx or not ref:
        return False
    return _jaccard(ctx, ref) >= 0.18 or _set_overlap(ctx, ref) >= 0.5


# ---------------- 检索层 ----------------

def context_precision(sample: EvalSample) -> float:
    """Context Precision：相关上下文在排序结果中的位置加权。"""
    contexts = sample.contexts or []
    if not contexts:
        return 0.0
    relevant = [_is_relevant(c, sample.reference) for c in contexts]
    total = sum(1 for r in relevant if r)
    if total == 0:
        return 0.0
    score = 0.0
    for idx, r in enumerate(relevant, start=1):
        if r:
            score += sum(relevant[:idx]) / idx
    return round(score / total, 4)


def context_recall(sample: EvalSample) -> float:
    """Context Recall：标准答案中被检索上下文覆盖的比例。"""
    ref = set(_tokenize(sample.reference))
    if not ref:
        return 0.0
    covered = set()
    for ctx in sample.contexts:
        covered |= set(_tokenize(ctx))
    return round(_set_overlap(covered, ref), 4)


def ndcg(sample: EvalSample, k: int = 5) -> float:
    """NDCG@K：按相关性折扣累积。"""
    contexts = (sample.contexts or [])[:k]
    if not contexts:
        return 0.0
    relevant = [_is_relevant(c, sample.reference) for c in contexts]
    dcg = 0.0
    for i, r in enumerate(relevant, start=1):
        if r:
            dcg += 1.0 / math.log2(i + 1)
    ideal = sum(1.0 / math.log2(i + 1) for i in range(1, sum(relevant) + 1)) if sum(relevant) else 0.0
    if ideal <= 0:
        return 0.0
    return round(dcg / ideal, 4)


def recall_at_k(sample: EvalSample, k: int = 3) -> float:
    ref = set(_tokenize(sample.reference))
    if not ref:
        return 0.0
    covered = set()
    for ctx in (sample.contexts or [])[:k]:
        covered |= set(_tokenize(ctx))
    return round(_set_overlap(covered, ref), 4)


def hit_at_k(sample: EvalSample, k: int = 3) -> int:
    return 1 if any(_is_relevant(c, sample.reference) for c in (sample.contexts or [])[:k]) else 0


def mrr(sample: EvalSample) -> float:
    for i, ctx in enumerate(sample.contexts or [], start=1):
        if _is_relevant(ctx, sample.reference):
            return round(1.0 / i, 4)
    return 0.0


# ---------------- 生成层（启发式） ----------------

def _claim_atoms(text: str) -> List[str]:
    """把答案拆成可校验的原子断言：句子级。"""
    text = (text or "").strip()
    if not text:
        return []
    # 按句末标点/换行拆分，忽略空段与纯 Markdown 符号
    raw = re.split(r"[。！？!?\n]+", text)
    out = []
    for part in raw:
        part = part.strip().strip("-#*")
        if len(part) >= 4 and not part.startswith("```"):
            out.append(part)
    return out


def _contains_evidence(claim: str, contexts: List[str]) -> bool:
    """断言是否能在上下文中找到依据（token 级重叠）。"""
    claim_tokens = set(_tokenize(claim))
    if not claim_tokens:
        return False
    best = 0.0
    for ctx in contexts:
        ctx_tokens = set(_tokenize(ctx))
        best = max(best, _set_overlap(claim_tokens, ctx_tokens))
    return best >= 0.45


def faithfulness(sample: EvalSample) -> float:
    """Faithfulness：答案断言中有多少能从检索上下文得到支持。"""
    claims = _claim_atoms(sample.answer)
    if not claims:
        return 0.0
    supported = sum(1 for c in claims if _contains_evidence(c, sample.contexts))
    return round(supported / len(claims), 4)


def answer_relevance(sample: EvalSample) -> float:
    """Answer Relevance：答案 token 是否与问题相关。"""
    q = set(_tokenize(sample.question))
    a = set(_tokenize(sample.answer))
    if not q or not a:
        return 0.0
    return round((_jaccard(q, a) + _set_overlap(q, a)) / 2, 4)


def answer_correctness(sample: EvalSample) -> float:
    """Answer Correctness：答案与标准答案的相似度（精确/语义混合启发式）。"""
    if not sample.reference_answer and not sample.reference:
        return 0.0
    ref = sample.reference_answer or sample.reference
    a = set(_tokenize(sample.answer))
    r = set(_tokenize(ref))
    return round(_jaccard(a, r), 4)


# ---------------- LLM-judge 可选增强 ----------------

def _ask_judge(sample: EvalSample, metric: str) -> Optional[float]:
    """通过 OpenAI 兼容模型打分（0-1）；失败返回 None 由启发式兜底。"""
    try:
        from langchain_openai import ChatOpenAI
        from config import config
        llm = ChatOpenAI(
            model=config.REFLECTION_MODEL,
            openai_api_key=config.REFLECTION_API_KEY or config.LLM_API_KEY,
            openai_api_base=config.REFLECTION_BASE_URL or config.LLM_BASE_URL,
            temperature=0,
            timeout=30,
            extra_body={"thinking": {"type": "disabled"}},
        )
        prompt = (
            "你是 RAG 评估专家。请只输出 0 到 1 之间的一个数字，不要解释。\n"
            f"指标：{metric}\n问题：{sample.question}\n"
            f"标准答案：{sample.reference or sample.reference_answer}\n"
            f"检索上下文：{sample.contexts}\n答案：{sample.answer}"
        )
        resp = llm.invoke(prompt)
        text = (resp.content or "").strip()
        m = re.search(r"0(?:\.\d+)?|1(?:\.0+)?", text)
        if m:
            return min(1.0, max(0.0, float(m.group(0))))
    except Exception:
        return None
    return None


def _with_llm(sample: EvalSample, metric: str, heuristic: float, enabled: bool) -> float:
    if not enabled:
        return heuristic
    try:
        val = _ask_judge(sample, metric)
        return round(val, 4) if val is not None else heuristic
    except Exception:
        return heuristic


# ---------------- 聚合 ----------------

def evaluate_samples(samples: List[EvalSample], llm_judge: bool = False, k: int = 3) -> dict:
    if not samples:
        return {"total": 0, "retrieval": {}, "generation": {}, "overall": 0.0}

    metrics = {
        "context_precision": [],
        "context_recall": [],
        "ndcg": [],
        "recall_at_k": [],
        "hit_at_k": [],
        "mrr": [],
        "faithfulness": [],
        "answer_relevance": [],
        "answer_correctness": [],
    }
    for s in samples:
        cp = context_precision(s)
        cr = context_recall(s)
        metrics["context_precision"].append(cp)
        metrics["context_recall"].append(cr)
        metrics["ndcg"].append(ndcg(s, k))
        metrics["recall_at_k"].append(recall_at_k(s, k))
        metrics["hit_at_k"].append(hit_at_k(s, k))
        metrics["mrr"].append(mrr(s))
        metrics["faithfulness"].append(_with_llm(s, "faithfulness", faithfulness(s), llm_judge))
        metrics["answer_relevance"].append(_with_llm(s, "answer_relevance", answer_relevance(s), llm_judge))
        metrics["answer_correctness"].append(_with_llm(s, "answer_correctness", answer_correctness(s), llm_judge))

    retrieval_keys = ["context_precision", "context_recall", "ndcg", "recall_at_k", "hit_at_k", "mrr"]
    generation_keys = ["faithfulness", "answer_relevance", "answer_correctness"]
    retrieval_avg = {k: round(sum(metrics[k]) / len(metrics[k]), 4) for k in retrieval_keys}
    generation_avg = {k: round(sum(metrics[k]) / len(metrics[k]), 4) for k in generation_keys}
    overall = round(
        (sum(retrieval_avg.values()) / len(retrieval_avg)
         + sum(generation_avg.values()) / len(generation_avg)) / 2, 4)

    return {
        "total": len(samples),
        "k": k,
        "retrieval": retrieval_avg,
        "generation": generation_avg,
        "overall": overall,
        "per_sample": [
            {
                "question": s.question,
                "metrics": {key: round(metrics[key][i], 4) for key in metrics},
            }
            for i, s in enumerate(samples)
        ],
    }


def run_eval_from_dataset(dataset: list, llm_judge: bool = False, k: int = 3) -> dict:
    """从现有 JSON 测试集构建 EvalSample。

    标准 ID 形如 k8s_ops_manual.txt#CrashLoopBackOff，评估时用标准答案作为 reference。
    """
    samples = []
    for item in dataset or []:
        q = (item.get("question") or "").strip()
        ref = (item.get("standard_answer") or item.get("reference") or "").strip()
        if not q or not ref:
            continue
        samples.append(EvalSample(question=q, reference=ref))
    return evaluate_samples(samples, llm_judge=llm_judge, k=k)
