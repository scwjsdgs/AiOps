# -*- coding: utf-8 -*-
"""
报告子图（独立 StateGraph）：只负责汇总证据，防幻觉。

核心：报告子图不绑定任何工具，只能读到 diagnostics/evidence、
execution_results、impact_summary 和原任务输入；所有事实必须能在
这些证据里找到，找不到就明确写「证据不足」，绝不允许编造。
"""
from langchain_core.messages import SystemMessage, HumanMessage
from langchain_openai import ChatOpenAI
from langgraph.graph import StateGraph, START, END

from config import config
from agents.graph.state import AgentState
from agents.graph.worker import trim_worker_messages, sanitize_messages
from observability.langfuse_client import get_langfuse_callback_handler

REPORTER_SYSTEM_PROMPT = """你是 AIOps 报告 Agent，只负责汇总证据生成最终运维报告。

你没有任何工具，也不能访问真实集群；你唯一的材料来源是下方【证据】。

硬性要求：
1. 报告必须包含：根因、已执行操作（或为何未执行修复）、影响面、验证结果、后续建议。
2. 每条影响面/状态/执行结果都必须能从【证据】中找到出处。
3. 证据里没有的事实（例如"已恢复""已扩容"）禁止写进报告。
4. 若证据不足，明确写「证据不足」，并说明还需要查什么，不得猜。
5. 输出完报告后，在末尾单独一行：REPORT_READY=true""".strip()


async def _reporter_inner(state: AgentState) -> dict:
    """报告子图内部核心节点：只读状态里的证据并生成报告。"""
    evidence = state.get("evidence", [])
    execution = state.get("execution_results", [])
    langfuse_cb = get_langfuse_callback_handler()
    llm = ChatOpenAI(
        model=config.LLM_MODEL,
        openai_api_key=config.LLM_API_KEY,
        openai_api_base=config.LLM_BASE_URL,
        temperature=0,
        timeout=config.LLM_TIMEOUT_SECONDS,
        max_retries=config.LLM_MAX_RETRIES,
        callbacks=[langfuse_cb] if langfuse_cb else None,
        extra_body={"thinking": {"type": "disabled"}},
    )

    user = _reporter_input(state, evidence, execution)
    messages = [
        SystemMessage(content=REPORTER_SYSTEM_PROMPT),
        HumanMessage(content=user),
    ]
    # 报告场景同样受 trim 保护：若输入证据非常长，先按消息窗口压缩
    messages = sanitize_messages(trim_worker_messages(messages, config.MESSAGES_WINDOW_SIZE))
    result = await llm.ainvoke(messages)
    report = (result.content or "").strip()
    return {
        "report": report,
        "next_agent": "end",
        "completed_agents": ["reporter"],
        "iteration_count": (state.get("iteration_count") or 0) + 1,
    }


def build_reporter_subgraph():
    """构建独立报告子图（CompiledStateGraph）。"""
    builder = StateGraph(AgentState)
    builder.add_node("entry", _reporter_inner)
    builder.add_edge(START, "entry")
    builder.add_edge("entry", END)
    return builder.compile()


# 兼容旧函数：直接调用内部核心
async def reporter_subgraph_node(state: AgentState) -> dict:
    return await _reporter_inner(state)


async def reporter_node(state: AgentState) -> dict:
    """兼容别名：旧调用方 import 本符号不会断。"""
    return await _reporter_inner(state)


def _reporter_input(state: AgentState, evidence: list, execution: list) -> str:
    parts = [f"任务：{state.get('core_intent', '')}"]
    if state.get("root_cause"):
        parts.append(f"\n【根因】{state['root_cause']}")
    if state.get("impact_summary"):
        parts.append(f"\n【影响面】{state['impact_summary']}")

    parts.append("\n【诊断证据】")
    if evidence:
        for item in evidence[-20:]:
            tool = item.get("tool", "")
            obs = item.get("observation", "")
            parts.append(f"- {tool}: {str(obs)[:800]}")
    else:
        parts.append("- （无）")

    parts.append("\n【执行结果】")
    if execution:
        for item in execution[-10:]:
            parts.append(f"- {item.get('tool')}: {str(item.get('result', ''))[:800]}")
    else:
        parts.append("- （未执行任何变更）")

    return "\n".join(parts)


def route_after_reporter(state: AgentState) -> str:
    return "end"