# -*- coding: utf-8 -*-
"""
Query 润色：检索前把口语化提问改写为包含标准运维术语的检索查询。

调用 DeepSeek 改写，Prompt 明确要求只返回改写后的句子。
改写失败时降级返回原句，保证检索主链路永不中断。
"""
import logging

from langchain_openai import ChatOpenAI
from config import config

logger = logging.getLogger(__name__)

_REWRITE_PROMPT = (
    "你是一个运维搜索专家，请把用户的口语问题改写为包含标准术语的检索查询语句，"
    "只返回改写后的句子。"
)


def rewrite_query(raw_query: str) -> str:
    """把口语化 Query 改写为标准检索 Query；失败时原样返回。"""
    raw = (raw_query or "").strip()
    if not raw:
        return raw

    try:
        # 统一走 DeepSeek：单独 key 优先，否则跟随主 LLM。
        api_key = config.QUERY_REWRITE_API_KEY or config.LLM_API_KEY
        base_url = config.QUERY_REWRITE_BASE_URL or config.LLM_BASE_URL

        llm = ChatOpenAI(
            model=config.QUERY_REWRITE_MODEL,
            openai_api_key=api_key,
            openai_api_base=base_url,
            temperature=0,
            timeout=15,
            max_retries=1,
            extra_body={"thinking": {"type": "disabled"}},
        )
        result = llm.invoke(
            [
                {"role": "system", "content": _REWRITE_PROMPT},
                {"role": "user", "content": raw},
            ]
        )
        rewritten = (result.content or "").strip()
        if rewritten and rewritten.lower() != "pass":
            return rewritten
    except Exception as e:
        logger.warning(f"Query 润色失败，降级返回原句: {e}")

    return raw