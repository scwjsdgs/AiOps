# -*- coding: utf-8 -*-
"""
ReAct 兼容层。

本项目编排已迁移到 LangGraph Multi-Agent（agents/graph/），原手写
ReAct while 循环不再作为运行入口。保留本文件仅用于：
1. 兼容旧调用方 / 旧文档中的 import；
2. 提供 _revise_report_with_feedback 给新图收尾阶段复用。

不要再在这里增加新的 ReAct 循环逻辑。
"""
import logging
from typing import AsyncGenerator, Optional

from langchain_openai import ChatOpenAI
from langchain_core.messages import SystemMessage, HumanMessage

from config import config
from models import Alert
from agents.graph.graph import run_graph_stream, run_agent_for_alert as _run_agent_for_alert

logger = logging.getLogger(__name__)


async def run_react_loop(
    task_id: str,
    user_input: str,
    max_iterations: int = None,
) -> AsyncGenerator[str, None]:
    """兼容旧流式入口：转发到 LangGraph run_graph_stream。"""
    async for event in run_graph_stream(task_id, user_input, max_iterations):
        yield event


async def run_agent_for_alert(task_id: str, alert: Alert) -> str:
    """兼容旧非流式入口：转发到 LangGraph Multi-Agent。"""
    return await _run_agent_for_alert(task_id, alert)


async def _revise_report_with_feedback(report: str, feedback: str, evidence: str = "") -> str:
    """根据 Reflection 审阅意见，让模型修订报告。

    修订仍要求基于原文，不能凭空补内容；失败时原样返回，避免丢报告。
    """
    try:
        llm = ChatOpenAI(
            model=config.LLM_MODEL,
            openai_api_key=config.LLM_API_KEY,
            openai_api_base=config.LLM_BASE_URL,
            temperature=0,
            extra_body={"thinking": {"type": "disabled"}},
        )
        messages = [
            SystemMessage(content=(
                "你是资深 SRE 报告修订专家。请根据审阅意见修订以下运维诊断报告。"
                "必须严格基于给定原文材料与工具观测，不得编造事实、数据或操作结果。"
                "只输出修订后的完整报告。"
            )),
            HumanMessage(content=(
                f"【原文材料/观测】\n{evidence or '（无）'}\n\n"
                f"【审阅意见】\n{feedback}\n\n"
                f"【原报告】\n{report}"
            )),
        ]
        result = await llm.ainvoke(messages)
        revised = (result.content or "").strip()
        return revised or report
    except Exception as e:
        logger.warning(f"报告修订失败，保留原报告: {e}")
        return report