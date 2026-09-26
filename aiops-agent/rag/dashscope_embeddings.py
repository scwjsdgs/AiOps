"""DashScope 原生 text-embedding 接口的 Embeddings 实现。

为什么要自己写而不直接用 OpenAIEmbeddings：
    OpenAIEmbeddings 走的是 OpenAI 兼容的 /v1/embeddings（input 是字符串数组），
    而这里用的是 DashScope 原生接口
    /api/v1/services/embeddings/text-embedding/text-embedding
    （input 是 {"texts": [...]}，响应体是 {"output": {"embeddings": [...]}}），
    两边的请求/响应结构完全不同，只改 base_url 会导致 404/解析失败。

接口格式（DashScope 文档）：
    请求：POST /api/v1/services/embeddings/text-embedding/text-embedding
          Authorization: Bearer <key>
          {"model": "...", "input": {"texts": ["...", ...]}}
    响应：{"output": {"embeddings": [{"embedding": [...], "text_index": 0}, ...]},
           "usage": {...}, "request_id": "..."}
"""

import logging

import httpx
from langchain_core.embeddings import Embeddings

from config import config

logger = logging.getLogger(__name__)

# DashScope 单次请求最多 10 条文本，超了会报错，这里按 10 条一批自动分批
_BATCH_SIZE = 10


class DashScopeEmbeddings(Embeddings):
    """实现 langchain 的 embed_documents / embed_query 两个标准方法，
    这样 Chroma.from_documents / similarity_search 可以无感使用。"""

    def __init__(self, api_key: str = None, model: str = None):
        self.api_key = api_key or config.DASHSCOPE_API_KEY
        self.model = model or config.EMBEDDING_MODEL
        self._timeout = httpx.Timeout(
            connect=config.HTTP_CONNECT_TIMEOUT,
            read=30.0,
            write=10.0,
            pool=config.HTTP_CONNECT_TIMEOUT,
        )

    def _embed(self, texts: list) -> list:
        if not texts:
            return []
        headers = {
            "Authorization": f"Bearer {self.api_key}",
            "Content-Type": "application/json",
        }
        url = (
            "https://dashscope.aliyuncs.com/api/v1/services/embeddings"
            "/text-embedding/text-embedding"
        )

        all_embeddings = [None] * len(texts)
        # 按 10 条一批分批请求，text_index 是批次内的下标，要映射回全局下标
        for batch_start in range(0, len(texts), _BATCH_SIZE):
            batch = texts[batch_start:batch_start + _BATCH_SIZE]
            payload = {
                "model": self.model,
                "input": {"texts": batch},
            }
            try:
                resp = httpx.post(url, json=payload, headers=headers, timeout=self._timeout)
                resp.raise_for_status()
                body = resp.json()
            except httpx.HTTPStatusError as e:
                # 把 DashScope 的错误体带出来，密钥不对/模型名错时能直接看到原因
                detail = ""
                try:
                    detail = str(e.response.json())
                except Exception:
                    detail = e.response.text[:200]
                raise RuntimeError(f"DashScope embedding 调用失败（HTTP {e.response.status_code}）：{detail}") from e
            except Exception as e:
                raise RuntimeError(f"DashScope embedding 调用失败：{e}") from e

            output = (body.get("output") or {}).get("embeddings") or []
            if len(output) != len(batch):
                raise RuntimeError(
                    f"DashScope 返回向量数 {len(output)} 与输入 {len(batch)} 不一致"
                )

            # 按返回顺序填充，不依赖 text_index（实测 DashScope 原生接口
            # 不保证返回 text_index，靠它定位会把多条向量全部错位到第一格，
            # 剩下的全是 None，chromadb 写入时即刻报 nan 错）。
            for offset, item in enumerate(output):
                idx = batch_start + offset
                embedding = item.get("embedding")
                if not embedding:
                    raise RuntimeError(f"DashScope 返回的第 {idx} 条向量为空")
                all_embeddings[idx] = embedding

        # 兜底：任何一条没填充到（None），说明响应与输入不对齐，宁可不回也不拿错向量
        if any(e is None for e in all_embeddings):
            raise RuntimeError(f"部分文本未得到向量：{sum(1 for e in all_embeddings if e is None)}/{len(texts)}")
        return all_embeddings

    def embed_documents(self, texts: list) -> list:
        """建库时用：把所有文档块编码成向量。"""
        return self._embed(texts)

    def embed_query(self, text: str) -> list:
        """检索时用：把查询语句编码成向量。"""
        return self._embed([text])[0]
