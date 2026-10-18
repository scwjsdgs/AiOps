# -*- coding: utf-8 -*-
"""
LLM 成本与性能可观测（轻量本地实现，不依赖新组件）。

设计：
  - 按模型/阶段累计 token、耗时、调用次数与成本；
  - 模型路由：同一请求可指定 model，路由层选择配置好的模型并记录；
  - LLM 缓存：相同 (model, prompt) 且未过期的结果直接复用，省钱省延迟；
  - 预算告警：超过日预算阈值时输出告警级别计数，前端可读取展示。

不替换 LangFuse：LangFuse 负责深度 trace，这里负责平台内的实时统计与预算控制。
"""
import hashlib
import json
import os
import threading
import time
from typing import Dict, Optional

# 与 config 解耦的默认价格表（每百万 token 美元），可按环境覆盖
# 仅为演示/本地估算，不表示实时市场价。
MODEL_PRICE = {
    "deepseek-flash": {"input": 0.27, "output": 1.10},
    "deepseek-v4-flash": {"input": 0.27, "output": 1.10},
    "qwen-plus": {"input": 0.80, "output": 2.00},
    "qwen-turbo": {"input": 0.30, "output": 0.60},
    "gpt-4o-mini": {"input": 0.15, "output": 0.60},
}
DEFAULT_PRICE = {"input": 1.0, "output": 2.0}

DAILY_BUDGET_USD = float(os.getenv("LLM_DAILY_BUDGET_USD", "5.0"))
CACHE_TTL_SECONDS = int(os.getenv("LLM_CACHE_TTL_SECONDS", "3600"))


class CostTracker:
    def __init__(self):
        self._lock = threading.Lock()
        self._usage = {}            # model -> {calls, input_tokens, output_tokens, latency_ms, cost}
        self._by_stage = {}         # stage -> {calls, latency_ms, cost}
        self._cache = {}            # key -> (expire_at, content)
        self._cache_hits = 0
        self._alerts = []           # [(time, level, message)]

    def _record_model(self, model: str, input_tokens: int, output_tokens: int,
                      latency_ms: int, cost: float):
        key = model or "unknown"
        with self._lock:
            item = self._usage.setdefault(key, {
                "calls": 0, "input_tokens": 0, "output_tokens": 0,
                "latency_ms": 0, "cost": 0.0,
            })
            item["calls"] += 1
            item["input_tokens"] += max(0, input_tokens)
            item["output_tokens"] += max(0, output_tokens)
            item["latency_ms"] += max(0, latency_ms)
            item["cost"] += max(0.0, cost)
            self._check_budget()

    def _record_stage(self, stage: str, latency_ms: int, cost: float):
        key = stage or "unknown"
        with self._lock:
            item = self._by_stage.setdefault(key, {
                "calls": 0, "latency_ms": 0, "cost": 0.0,
            })
            item["calls"] += 1
            item["latency_ms"] += max(0, latency_ms)
            item["cost"] += max(0.0, cost)

    def _price(self, model: str) -> dict:
        price = MODEL_PRICE.get(model or "", DEFAULT_PRICE)
        return price or DEFAULT_PRICE

    def _check_budget(self):
        total = sum(v["cost"] for v in self._usage.values())
        if total > 0 and total >= DAILY_BUDGET_USD:
            if not self._alerts or self._alerts[-1][2].split("|")[0] != "DAILY_BUDGET":
                self._alerts.append((time.strftime("%Y-%m-%dT%H:%M:%S%z"), "warning",
                                     f"DAILY_BUDGET|LLM 日预算已超限：${total:.2f} / ${DAILY_BUDGET_USD:.2f}"))

    def usage(self) -> dict:
        with self._lock:
            return {
                "models": dict(self._usage),
                "stages": dict(self._by_stage),
                "total_cost": round(sum(v["cost"] for v in self._usage.values()), 6),
                "total_input_tokens": sum(v["input_tokens"] for v in self._usage.values()),
                "total_output_tokens": sum(v["output_tokens"] for v in self._usage.values()),
                "total_calls": sum(v["calls"] for v in self._usage.values()),
                "daily_budget": DAILY_BUDGET_USD,
                "cache_enabled": True,
                "cache_ttl_seconds": CACHE_TTL_SECONDS,
                "cache_hits": self.cache_hit_count,
                "alerts": list(self._alerts[-20:][::-1]),
            }

    @property
    def cache_hit_count(self) -> int:
        return self._cache_hits

    def cache_get(self, model: str, prompt: str) -> Optional[str]:
        key = self._cache_key(model, prompt)
        with self._lock:
            item = self._cache.get(key)
            if item and item[0] >= time.time():
                self._cache_hits += 1
                return item[1]
            return None

    def cache_put(self, model: str, prompt: str, content: str):
        key = self._cache_key(model, prompt)
        with self._lock:
            self._cache[key] = (time.time() + CACHE_TTL_SECONDS, content)
            # 简单 LRU 上限，避免长期无界增长
            if len(self._cache) > 2000:
                oldest_key = min(self._cache, key=lambda k: self._cache[k][0])
                self._cache.pop(oldest_key, None)

    @staticmethod
    def _cache_key(model: str, prompt: str) -> str:
        return hashlib.sha256(f"{model}|{prompt}".encode("utf-8")).hexdigest()

    def record_call(self, model: str, prompt: str, response_text: str,
                    input_tokens: int = 0, output_tokens: int = 0,
                    latency_ms: int = 0, stage: str = "unknown") -> dict:
        """记录一次 LLM 调用并更新统计，返回使用信息。"""
        price = self._price(model)
        cost = input_tokens / 1_000_000 * price["input"] + output_tokens / 1_000_000 * price["output"]
        self._record_model(model, input_tokens, output_tokens, latency_ms, cost)
        self._record_stage(stage, latency_ms, cost)
        return {
            "model": model,
            "stage": stage,
            "input_tokens": input_tokens,
            "output_tokens": output_tokens,
            "latency_ms": latency_ms,
            "cost": round(cost, 8),
        }


def json_serializer(obj):
    if isinstance(obj, (int, float, str, bool)) or obj is None:
        return obj
    return str(obj)


cost_tracker = CostTracker()