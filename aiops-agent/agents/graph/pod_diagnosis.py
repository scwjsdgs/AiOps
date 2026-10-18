# -*- coding: utf-8 -*-
"""
Send API 多 Pod 并发诊断：fan_out_to_pods -> diagnose_single_pod。

由主图在「诊断子图」前调用条件路由。若发现多个异常 Pod，则用 LangGraph Send
把每个 Pod 作为独立分支并发诊断；否则走单个诊断子图。

Send 局部状态刻意保持极简：只传 pod_name + task_id，严禁把 messages / evidence
等大对象塞进去，避免 Checkpoint 膨胀和并行分支间互相污染。
"""
from __future__ import annotations

import json
import logging
from typing import List

from langchain_core.messages import SystemMessage, HumanMessage, ToolMessage, AIMessage
from langchain_core.tools import BaseTool
from langchain_openai import ChatOpenAI
from langgraph.types import Send

from config import config
from agents.graph.state import AgentState
from agents.graph.tool_sets import create_diagnostic_tools
from agents.graph.worker import trim_worker_messages, sanitize_messages
from observability.langfuse_client import get_langfuse_callback_handler
from services.callback import callback_service

logger = logging.getLogger(__name__)

SINGLE_POD_PROMPT = """你是 AIOps 单 Pod 诊断 Worker，只负责对一个具体 Pod 做只读排查。

可用只读工具：search_knowledge_base、get_service_status、analyze_logs、
query_pod_events、query_metrics、query_impact。

规则：
- 所有查询必须以 pod_name 为过滤依据，不要扩大到其它服务/Pod。
- 同一工具同一参数只调用一次，尽快输出结论。
- 输出格式：
  POD_ROOT_CAUSE=<该 Pod 异常根因>
  POD_IMPACT=<该 Pod 影响>
- 禁止任何写操作工具。""".strip()


def fan_out_to_pods(state: AgentState):
    """诊断入口前的条件路由：异常 Pod 数 > 1 时 Send 并行，否则走单诊断。

    返回 list[Send] 时 LangGraph 会并发执行 diagnose_single_pod；
    返回字符串 "single" 时走常规诊断子图。
    """
    pods = _extract_abnormal_pods(state)
    if not pods:
        # 没有候选 Pod，仍可能从 evidence/test 场景拿到，保守回退单诊断
        candidate = state.get("abnormal_pods") or []
        pods = [p for p in candidate if isinstance(p, str) and p]
    if len(pods) <= 1:
        return "single"

    sends = []
    for pod in pods:
        # 极简局部状态：只传 pod_name + task_id
        sends.append(Send("diagnose_single_pod", {
            "pod_name": pod,
            "task_id": state.get("task_id", ""),
        }))
    logger.info(f"Send API 并行诊断 {len(pods)} 个异常 Pod: {pods[: config.PARALLEL_POD_DIAGNOSIS_MAX_CONCURRENCY]}")
    return sends


def _extract_abnormal_pods(state: AgentState) -> List[str]:
    """从 evidence 中提取异常 Pod 名单（优先取 query_pod_events/query_impact 返回）。"""
    explicit = state.get("abnormal_pods") or []
    if explicit:
        return [p for p in explicit if isinstance(p, str) and p]

    pods: List[str] = []
    for item in reversed(state.get("evidence", [])):
        obs = item.get("observation", "")
        if not isinstance(obs, str):
            continue
        # 兼容 query_pod_events / query_impact 的文本格式
        for line in obs.splitlines():
            line = line.strip()
            if line.startswith("- Pod "):
                name = line.split(" ", 2)[2] if len(line.split(" ", 2)) > 2 else ""
                name = name.split(" phase=")[0].strip()
                if name and name not in pods:
                    pods.append(name)
    return pods


async def diagnose_single_pod(state: AgentState) -> dict:
    """Send 分支 Worker：只诊断单 Pod，返回 pod_diagnoses 增量。

    Send 分支收到的是极简局部状态（pod_name/task_id），langgraph 会把
    pod_diagnoses 用 operator.add 合并回主状态。
    """
    pod = state.get("pod_name", "")
    task_id = state.get("task_id", "")
    if not pod:
        return {"pod_diagnoses": [{"pod_name": "", "root_cause": "缺少 pod_name", "impact": ""}]}

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
    tools: List[BaseTool] = create_diagnostic_tools(task_id)
    llm_with_tools = llm.bind_tools(tools)
    tools_dict = {t.name: t for t in tools}

    # 隔离 Send 分支局部状态：不从主图继承 messages，只带上单 Pod 所需字段。
    # 即使主图 messages 已被工具调用塞满，这里也始终从干净的 System + Human 开始。
    messages = [
        SystemMessage(content=SINGLE_POD_PROMPT),
        HumanMessage(content=f"请诊断 Pod：{pod}\n任务：{state.get('core_intent', '')[:300]}"),
    ]
    evidence = []
    used_signatures = set()
    max_rounds = min(config.AGENT_MAX_ITERATIONS, 4)

    for _ in range(max_rounds):
        # 兜底：每次进入 LLM 前清洗一次，把可能被裁剪/恢复切断的孤儿
        # ToolMessage 与未配对 tool_calls 全部剔除，避免脏数据污染上下文。
        messages = sanitize_messages(trim_worker_messages(messages, config.MESSAGES_WINDOW_SIZE))
        response = await llm_with_tools.ainvoke(messages)
        messages.append(response)
        tool_calls = getattr(response, "tool_calls", None) or []
        if not tool_calls:
            break
        for tc in tool_calls:
            name = tc["name"]
            args = tc["args"] or {}
            sig = _signature(name, args)
            if sig in used_signatures:
                observation = "重复调用被拒绝：同一工具同一参数只能执行一次。"
            else:
                used_signatures.add(sig)
                func = tools_dict.get(name)
                if func is None:
                    observation = f"未知工具：{name}"
                else:
                    await callback_service.send_step(task_id, f"并行诊断 {pod} 调用 {name}", f"parallel_pod_{name}")
                    try:
                        observation = await func.ainvoke(args)
                    except Exception as e:
                        observation = f"工具执行失败：{e}"
                evidence.append({"tool": name, "args": args, "observation": observation, "pod": pod})
            messages.append(ToolMessage(content=str(observation), tool_call_id=tc["id"]))

    final_text = ""
    for m in reversed(messages):
        if getattr(m, "type", "") == "ai" and getattr(m, "content", ""):
            final_text = m.content or ""
            break
    root = _extract_field(final_text, "POD_ROOT_CAUSE") or final_text[:400] or "未定位根因"
    impact = _extract_field(final_text, "POD_IMPACT") or ""

    # 只返回增量，由 operator.add 合并；payload 保持小体积
    return {
        "pod_diagnoses": [{
            "pod_name": pod,
            "root_cause": root[:1000],
            "impact": impact[:800],
            "evidence": evidence[-8:],
        }]
    }


def _signature(name: str, args: dict) -> str:
    try:
        return f"{name}|{json.dumps(args, sort_keys=True, ensure_ascii=False)}"
    except Exception:
        return f"{name}|{args}"


def _extract_field(text: str, field: str) -> str:
    for line in (text or "").splitlines():
        if line.strip().upper().startswith(field + "="):
            return line.split("=", 1)[1].strip()
    return ""