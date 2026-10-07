# -*- coding: utf-8 -*-
"""
Reflection（事后审阅，防幻觉）。

Agent 生成最终报告后，让模型结合检索到的原文再审阅一遍：
  - 报告是否完全基于原文/工具观测，有没有编造（幻觉）？
  - 没问题输出 PASS；有问题指出错误。

只有收到 PASS 才回调 Java；否则把审阅意见回注给 Agent 让它修订一轮。
"""
import logging

from langchain_openai import ChatOpenAI
from config import config

logger = logging.getLogger(__name__)

_REFLECTION_PROMPT = (
    "你是一个严格的运维报告审阅专家。请检查这份诊断报告是否完全基于给定的原文材料"
    "（工具观测、知识库检索结果、集群真实状态），有没有幻觉（编造了原文里没有的事实、"
    "数据或操作结果）？\n"
    "如果报告完全有据可依、无编造，只输出 PASS。\n"
    "如果发现问题，请指出具体的错误点（哪一句与原文不符），不要输出 PASS。"
)


def reflect_on_report(report: str, evidence: str = "") -> dict:
    """审阅报告。返回 {"passed": bool, "feedback": str}。

    审阅失败（模型不可用等）时默认放行（passed=True）——Reflection 是增强，
    不能因为审阅服务故障把整个诊断卡死。
    """
    if not config.REFLECTION_ENABLED:
        return {"passed": True, "feedback": "Reflection 未启用，直接放行"}
    text = (report or "").strip()
    if not text:
        return {"passed": True, "feedback": "报告为空，跳过审阅"}

    try:
        # Reflection 默认走 DashScope（embedding/Rerank 同通道）：
        # 单独 key 优先，否则用 DASHSCOPE_API_KEY；base 同理。
        api_key = config.REFLECTION_API_KEY or config.DASHSCOPE_API_KEY or config.LLM_API_KEY
        base_url = config.REFLECTION_BASE_URL or "https://dashscope.aliyuncs.com/compatible-mode/v1"
        llm = ChatOpenAI(
            model=config.REFLECTION_MODEL,
            openai_api_key=api_key,
            openai_api_base=base_url,
            temperature=0,
        )
        user_content = (
            f"【原文材料】\n{evidence or '（本次未提供检索原文，仅审阅报告自身一致性）'}\n\n"
            f"【待审阅报告】\n{text}"
        )
        result = llm.invoke(
            [
                {"role": "system", "content": _REFLECTION_PROMPT},
                {"role": "user", "content": user_content},
            ]
        )
        content = (result.content or "").strip()
        passed = content.upper().startswith("PASS")
        if not passed and "PASS" == content.upper().strip():
            passed = True
        logger.info(f"Reflection 结果: passed={passed}")
        return {"passed": passed, "feedback": content}
    except Exception as e:
        logger.warning(f"Reflection 审阅失败，默认放行: {e}")
        return {"passed": True, "feedback": f"审阅服务异常，已放行：{e}"}
