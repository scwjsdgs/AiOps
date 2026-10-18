# -*- coding: utf-8 -*-
"""
诊断子图（独立 StateGraph）：专精只读排查。

工具集隔离（写入 whitelist）：
- search_knowledge_base（RAG 历史案例/SOP）
- get_service_status（K8s 状态）
- analyze_logs（日志）
- query_pod_events（Pod 事件）
- query_metrics（Prometheus）
- query_impact（影响面）

诊断子图不拥有任何写工具，因此即使提示词被注入，也无法直接变更集群。

内部结构：entry -> loop -> 若工具调用未收敛继续 loop，否则完成（async 工具调用
在单节点内顺序执行，回合内循环由 LLM 决定，这里用条件边控制最大回合数）。

作为独立 Subgraph：
- build_diagnostic_subgraph() 返回编译后的 StateGraph；
- 子图与父图共享 AgentState 通道，但内部循环/消息只在子图入口内维护，
  不会把子图私有迭代暴露到主图 Checkpoint。
"""
from langchain_core.messages import SystemMessage, HumanMessage, ToolMessage
from langchain_openai import ChatOpenAI
from langgraph.graph import StateGraph, START, END

from config import config
from agents.graph.state import AgentState
from agents.graph.worker import trim_worker_messages, sanitize_messages
from agents.graph.tool_sets import create_diagnostic_tools
from observability.langfuse_client import get_langfuse_callback_handler
from services.callback import callback_service

DIAGNOSTIC_SYSTEM_PROMPT = """你是 AIOps 诊断 Agent，只负责只读排查和根因定位。

可用工具（全部只读）：
1. search_knowledge_base：历史案例/SOP
2. get_service_status：当前副本数、就绪情况、镜像
3. analyze_logs：最近错误日志
4. query_pod_events：Pod 异常事件（ImagePullBackOff、CrashLoopBackOff、OOMKilled、探针失败）
5. query_metrics：Prometheus PromQL
6. query_impact：流量入口、是否断流、异常实例

规则：
- 先看影响面与 Pod 事件，再决定是否查日志/指标，不要无意义地重复查询。
- 同一工具同一参数只能调用一次。
- 拿到明确根因后立即输出 ROOT_CAUSE=... 和 IMPACT=...，不要继续加查指标"佐证"。
- 影响面必须包含：受影响的 Service、是否断流、异常实例数量。
- 禁止调用任何修复/写操作工具。""".strip()


async def _diagnostic_inner(state: AgentState) -> dict:
    """诊断子图内部核心节点：执行一次循环并产出 evidence/root_cause/impact_summary。"""
    task_id = state.get("task_id", "")
    langfuse_cb = get_langfuse_callback_handler()
    llm = ChatOpenAI(
        model=config.LLM_MODEL,
        openai_api_key=config.LLM_API_KEY,
        openai_api_base=config.LLM_BASE_URL,
        temperature=0,
        streaming=False,
        timeout=config.LLM_TIMEOUT_SECONDS,
        max_retries=config.LLM_MAX_RETRIES,
        callbacks=[langfuse_cb] if langfuse_cb else None,
        extra_body={"thinking": {"type": "disabled"}},
    )
    tools = create_diagnostic_tools(task_id)
    llm_with_tools = llm.bind_tools(tools)
    tools_dict = {t.name: t for t in tools}

    # 只注入当前需要的信息，避免把整段历史重复带给诊断模型
    user_content = _diagnostic_input(state)
    messages = [
        SystemMessage(content=DIAGNOSTIC_SYSTEM_PROMPT),
        HumanMessage(content=user_content),
    ]

    evidence = list(state.get("evidence", []))
    # 子图内部最大工具回合数（收敛控制）；超了直接走条件边收束
    max_rounds = min(config.AGENT_MAX_ITERATIONS, 6)
    used_signatures = set()

    for _ in range(max_rounds):
        # 滑动窗口 + 兜底清洗：裁剪超出窗口的旧消息，并剔除被切断的孤儿
        # ToolMessage / 未配对 tool_calls，避免脏数据污染 LLM 上下文。
        messages = sanitize_messages(trim_worker_messages(messages, config.MESSAGES_WINDOW_SIZE))
        response = await llm_with_tools.ainvoke(messages)
        messages.append(response)
        tool_calls = getattr(response, "tool_calls", None) or []
        if not tool_calls:
            break
        for tc in tool_calls:
            name = tc["name"]
            args = tc["args"] or {}
            try:
                import json as _json
                sig = f"{name}|{_json.dumps(args, sort_keys=True, ensure_ascii=False)}"
            except Exception:
                sig = f"{name}|{args}"
            if sig in used_signatures:
                observation = "重复调用被拒绝：同一工具同一参数只能执行一次，请换思路或直接给出结论。"
            else:
                used_signatures.add(sig)
                func = tools_dict.get(name)
                if func is None:
                    observation = f"未知工具：{name}"
                else:
                    await callback_service.send_step(task_id, f"诊断 Agent 调用 {name}", f"diagnostic_{name}")
                    try:
                        observation = await func.ainvoke(args)
                    except Exception as e:
                        observation = f"工具执行失败：{e}"
                evidence.append({"tool": name, "args": args, "observation": observation})
            messages.append(ToolMessage(
                    content=str(observation), tool_call_id=tc["id"]
                ))
        # 全局证据上限兜底
        if len(evidence) >= config.AGENT_MAX_ITERATIONS:
            break

    # 从最后一段文本提取 ROOT_CAUSE / IMPACT
    final_text = ""
    for m in reversed(messages):
        if getattr(m, "type", "") == "ai" and getattr(m, "content", ""):
            final_text = m.content or ""
            break
    root_cause = _extract_field(final_text, "ROOT_CAUSE")
    impact = _extract_field(final_text, "IMPACT")

    return {
        # 默认不扩 messages：证据已结构化写入 evidence，避免全局消息无限膨胀。
        "evidence": evidence,
        "root_cause": root_cause or final_text[:500] or "未明确根因，需人工介入。",
        "impact_summary": impact or _fallback_impact(evidence),
        "completed_agents": ["diagnostic"],
        "next_agent": "reporter",
        "iteration_count": (state.get("iteration_count") or 0) + 1,
    }


def build_diagnostic_subgraph():
    """构建独立诊断子图（CompiledStateGraph）。

    主图把该编译子图作为 "diagnostic" 节点；子图内部维护自己的 LLM 循环，
    不向主图暴露私有工具调用消息。
    """
    builder = StateGraph(AgentState)
    builder.add_node("entry", _diagnostic_inner)
    builder.add_edge(START, "entry")
    builder.add_edge("entry", END)
    return builder.compile()


# 兼容旧函数：直接调用内部核心（仍可作普通节点函数使用）
async def diagnostic_subgraph_node(state: AgentState) -> dict:
    return await _diagnostic_inner(state)


def _diagnostic_input(state: AgentState) -> str:
    core = state.get("core_intent", "")
    parts = [f"本次任务：{core}"]
    if state.get("root_cause"):
        parts.append(f"已知根因：{state['root_cause']}")
    if state.get("impact_summary"):
        parts.append(f"影响面：{state['impact_summary']}")
    if state.get("execution_results"):
        parts.append("最近执行结果：\n" + "\n".join(
            f"- {r.get('tool')}: {r.get('result', '')}" for r in state["execution_results"][-5:]
        ))
    # 修复后回归验证时，明确告诉诊断 Agent 要验证而不是重新下结论
    if "executor" in state.get("completed_agents", []):
        parts.append("目标：验证刚才的修复是否真正恢复，不要重复给出与现状不符的旧根因。")
    return "\n".join(parts)


def _extract_field(text: str, field: str) -> str:
    for line in (text or "").splitlines():
        if line.strip().upper().startswith(field + "="):
            return line.split("=", 1)[1].strip()
    return ""


def _fallback_impact(evidence) -> str:
    for item in reversed(evidence):
        obs = item.get("observation", "")
        if item.get("tool") == "query_impact" and obs:
            return obs[:500]
    return "影响面未确认（证据不足）。"


def diagnostic_route(state: AgentState) -> str:
    """诊断完成后由 Supervisor 决定下一步。"""
    if state.get("approval_status") == "pending":
        return "executor"
    return "supervisor"


# 兼容别名：老调用方 import diagnostic_node 不会断
async def diagnostic_node(state: AgentState) -> dict:
    return await _diagnostic_inner(state)