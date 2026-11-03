# -*- coding: utf-8 -*-
"""
LangGraph Multi-Agent 状态图：Supervisor -> diagnostic -> executor -> reporter。

这是替代原手写 ReAct while 循环的核心：
- 用 StateGraph 的节点/边表达编排，不再手写 max_iterations while；
- 用 Redis Checkpointer 持久化全局状态，不再自己维护 Redis _inflight；
- 用 interrupt() + GraphResume 替代 Java 55 秒阻塞审批；
- 条件边 + retry_count 控制收敛，不再依赖"最多 8 轮"单一计数器。
"""
from __future__ import annotations

import asyncio
import json
import logging
import time
from typing import Any, AsyncGenerator, Optional

from langchain_core.messages import HumanMessage, AIMessage
from agents.graph.worker import sanitize_messages
from langgraph.config import get_stream_writer
from langgraph.graph import StateGraph, START, END
from langgraph.types import interrupt, Command

from config import config
from agents.graph.state import AgentState
from agents.graph.checkpointer import get_checkpointer, clear_checkpoint
from agents.graph.supervisor import supervisor_node, supervisor_route
from agents.graph.diagnostic import build_diagnostic_subgraph
from agents.graph.executor import build_executor_subgraph
from agents.graph.reporter import build_reporter_subgraph
from agents.graph.pod_diagnosis import fan_out_to_pods, diagnose_single_pod
from observability.langfuse_client import finalize as langfuse_finalize
from services.callback import callback_service
from models import Alert

logger = logging.getLogger(__name__)

AGENT_NAMES = {
    "supervisor": "Supervisor Agent",
    "diagnostic": "诊断 Agent",
    "executor": "执行 Agent",
    "reporter": "报告 Agent",
}


# ---------- 图节点 ----------

async def diagnostic_graph_node(state: AgentState) -> dict:
    """兼容入口：直接调用诊断子图内部核心。"""
    from agents.graph.diagnostic import diagnostic_subgraph_node
    return await diagnostic_subgraph_node(state)


async def executor_graph_node(state: AgentState) -> dict:
    """执行子图入口，兼容旧调用方。

    审批闸门仍在图节点层：manual 模式 interrupt() 挂起等待 Java 回调，
    auto/approved 模式进入执行子图；拒绝时 fail-closed 不执行写操作。
    """
    from agents.graph.executor import executor_subgraph_node
    if state.get("approval_status") == "approved":
        # 已经批准：正常进入执行子图
        return await executor_subgraph_node(state)

    # 未批准：进入审批 interrupt。
    request = dict(state.get("approval_request") or {
        "operation": state.get("current_subtask") or "执行 Agent 请求的写操作",
        "reason": (state.get("root_cause") or "")[:500],
        "taskId": state.get("task_id", ""),
    })
    request.setdefault("taskId", state.get("task_id", ""))

    # 先把审批请求发给 Java：auto 模式会立即返回 approved，manual 模式返回 pending。
    # LangGraph interrupt 恢复时不会重跑这段代码，因此不会重复推送。
    approval_result = None
    try:
        approval_result = await callback_service.execute_tool(
            state.get("task_id", ""),
            "request_human_approval",
            {
                "operation": request.get("operation", ""),
                "reason": request.get("reason", ""),
                "contextId": state.get("task_id", ""),
            },
        )
    except Exception as e:
        logger.warning(f"审批请求推送失败（继续挂起等待） task={state.get('task_id')}: {e}")

    # auto 模式：Java 已批准并落审计，不需要 interrupt 等待人工。
    if approval_result is not None and (approval_result.data or {}).get("approved"):
        request["requestId"] = (approval_result.data or {}).get("requestId", "")
        state = dict(state)
        state["approval_status"] = "approved"
        state["approval_request"] = request
        return await executor_subgraph_node(state)

    # Java 明确返回 pending=true（manual 模式）：interrupt 挂起等待人工决定。
    if approval_result is not None and (approval_result.data or {}).get("pending"):
        java_request_id = (approval_result.data or {}).get("requestId") or request.get("requestId", "")
        java_request_id = str(java_request_id).strip()
        request["requestId"] = java_request_id
        decision = interrupt({"type": "human_approval", "request": request, "requestId": java_request_id})
    else:
        # 审批服务不可用 / auto 未批准：fail-closed，不挂起也不执行写操作。
        return {
            "approval_status": "rejected",
            "execution_results": [{
                "tool": "approval",
                "result": f"审批未能获得批准，未执行任何写操作。{getattr(approval_result, 'message', '') or ''}".strip(),
                "args": request,
            }],
            "completed_agents": ["executor"],
            "next_agent": "reporter",
        }

    if not decision or not decision.get("approved"):
        return {
            "approval_status": "rejected",
            "execution_results": [{
                "tool": "approval",
                "result": "审批未通过，未执行任何写操作。",
                "args": request,
            }],
            "completed_agents": ["executor"],
            "next_agent": "reporter",
        }

    # 批准后把 requestId/note 写回状态，并在同一节点继续执行
    request["requestId"] = decision.get("requestId", request.get("requestId", ""))
    request["note"] = decision.get("note", "")
    state = dict(state)
    state["approval_status"] = "approved"
    state["approval_request"] = request
    return await executor_subgraph_node(state)


async def reporter_graph_node(state: AgentState) -> dict:
    """兼容入口：直接调用报告子图内部核心。"""
    from agents.graph.reporter import reporter_subgraph_node
    return await reporter_subgraph_node(state)


# ---------- 条件边 ----------

def _should_force_report(state: AgentState) -> bool:
    """iteration_count 防死循环条件边：达到上限后无论 Supervisor 想做什么都强制收束。

    报告 Agent 已完成后直接放行，避免把终态又拉回 loop。
    Supervisor 每轮也会推进 supervisor_round，两个指标任一达到上限都强制收束，
    防止诊断/执行子图里 iteration_count 增长慢、但主图轮次已经绕圈的情况。
    """
    if state.get("report"):
        return False
    iteration_rounds = (state.get("iteration_count") or 0)
    supervisor_rounds = (state.get("supervisor_round") or 0)
    limit = min(config.MAX_ITERATION_ROUNDS, config.AGENT_MAX_ITERATIONS)
    return iteration_rounds >= limit or supervisor_rounds >= limit


def route_after_diagnostic(state: AgentState) -> str:
    # 根因定位后：迭代次数超限时不再回 Supervisor 继续绕圈，直接强制报告
    if _should_force_report(state):
        return "report"
    return "supervisor"


def route_after_executor(state: AgentState) -> str:
    # 执行完成后必须回诊断验证（回归验证）；防死循环时同样强制收束
    if _should_force_report(state):
        return "report"
    return "fan_out"


def route_after_reporter(state: AgentState) -> str:
    return "end"


async def supervisor_round_node(state: AgentState) -> dict:
    """Supervisor 包装：执行原逻辑并递增路由轮次，供 iteration_count 条件边使用。"""
    out = await supervisor_node(state)
    out["supervisor_round"] = (state.get("supervisor_round") or 0) + 1
    return out


def build_graph():
    """构建 LangGraph 状态图（Supervisor 主图 + 三个独立子图 + 并行诊断）。

    结构：
      START -> supervisor
      supervisor -> fan_out（diagnostic 路径）
      fan_out.conditional:
        - list[Send] -> diagnose_single_pod 并发诊断
        - "single" -> diagnostic 子图
      诊断/并行分支完成后 -> supervisor
      executor / reporter 为独立 StateGraph 编译子图
    """
    checkpointer = get_checkpointer()
    builder = StateGraph(AgentState)

    # Supervisor 主图节点
    builder.add_node("supervisor", supervisor_round_node)

    # fan_out 汇接节点：作为「诊断子图前面的条件路由」挂载点
    # 它本身不做业务，真正的 Send 逻辑在条件边 fan_out_to_pods
    async def _fan_out_placeholder(state: AgentState) -> dict:
        return {}

    builder.add_node("fan_out", _fan_out_placeholder)
    builder.add_node("diagnose_single_pod", diagnose_single_pod)

    # 真正的独立子图：三个 Agent 各自是编译后的 StateGraph
    builder.add_node("diagnostic", build_diagnostic_subgraph())
    builder.add_node("executor", build_executor_subgraph())
    builder.add_node("reporter", build_reporter_subgraph())

    builder.add_edge(START, "supervisor")
    builder.add_conditional_edges(
        "supervisor",
        supervisor_route,
        {
            "diagnostic": "fan_out",
            "executor": "executor",
            "reporter": "reporter",
            "end": END,
        },
    )

    # 诊断子图前面：fan_out 条件路由。
    # fan_out_to_pods 返回 list[Send] 时 LangGraph 动态并发到 diagnose_single_pod；
    # 返回 "single" 时走常规诊断子图。
    builder.add_conditional_edges(
        "fan_out",
        fan_out_to_pods,
        {
            "single": "diagnostic",
            # Send 目标由 Send() 的 node 字段指定，无需静态映射
        },
    )
    # Send 并行分支完成与单诊断子图完成都回 Supervisor 汇总
    builder.add_edge("diagnose_single_pod", "supervisor")
    builder.add_edge("diagnostic", "supervisor")

    builder.add_conditional_edges(
        "executor",
        route_after_executor,
        {
            "fan_out": "fan_out",
            "report": "reporter",
        },
    )
    builder.add_conditional_edges(
        "reporter",
        route_after_reporter,
        {
            "end": END,
        },
    )

    graph = builder.compile(checkpointer=checkpointer)
    return graph


_graph: Optional[Any] = None


def get_graph():
    global _graph
    if _graph is None:
        _graph = build_graph()
    return _graph


def _build_initial_state(task_id: str, user_input: str, alert: Optional[Alert] = None,
                         history: dict = None, chatops: bool = False) -> dict:
    """构造新任务状态；history 用于 ChatOps 多轮会话恢复已有 checkpoint。"""
    base = {
        "task_id": task_id,
        "core_intent": user_input[:200],
        "chatops": chatops,
        "messages": [HumanMessage(content=user_input)],
        "evidence": [],
        "execution_results": [],
        "completed_agents": [],
        "plan": [],
        "retry_count": 0,
        "max_retries": config.AGENT_MAX_ITERATIONS,
        "iteration_count": 0,
        "approval_status": "none",
        "approval_request": None,
        "alert": {
            "service_name": alert.serviceName if alert else "",
            "alert_name": alert.title if alert else "",
            "task_id": task_id,
        } if alert else None,
        "next_agent": "diagnostic",
        "was_interrupted": False,
        "final_status": None,
        "started_at": time.strftime("%Y-%m-%d %H:%M:%S"),
    }
    if not history:
        return base

    # 多轮会话：保留旧 evidence/执行结果等上下文，并把历史 AI 报告作为记忆；
    # 新提问仍以独立 HumanMessage 开头，避免把旧轮次工具消息重复注入。
    ai_summaries = []
    for msg in history.get("messages") or []:
        mtype = getattr(msg, "type", "")
        content = getattr(msg, "content", "") or ""
        if not content or mtype not in ("ai", "human"):
            continue
        ai_summaries.append(f"{'用户' if mtype == 'human' else '助手'}: {str(content)[:600]}")
    if history.get("report"):
        ai_summaries.append(f"助手最终报告: {str(history['report'])[:1000]}")

    summary = "\n".join(ai_summaries[-12:]) if ai_summaries else ""
    base["messages"] = [HumanMessage(content=user_input)]
    base["previous_summary"] = summary
    base["core_intent"] = (summary + "\n\n用户本次问题：" + user_input)[:1200]
    base["evidence"] = list(history.get("evidence") or [])
    base["execution_results"] = list(history.get("execution_results") or [])
    base["plan"] = list(history.get("plan") or [])
    base["completed_agents"] = list(history.get("completed_agents") or [])
    base["retry_count"] = 0
    base["iteration_count"] = history.get("iteration_count") or 0
    base["approval_status"] = "none"
    base["started_at"] = history.get("started_at") or time.strftime("%Y-%m-%d %H:%M:%S")
    return base


def _config_for(task_id: str) -> dict:
    """图运行配置：recursion_limit 硬闸门 + Send 并行诊断 max_concurrency。

    LangGraph 会把 configurable 与顶层并发/递归配置一起传给 Pregel。
    """
    return {
        "recursion_limit": config.GRAPH_RECURSION_LIMIT,
        "max_concurrency": config.PARALLEL_POD_DIAGNOSIS_MAX_CONCURRENCY,
        "configurable": {"thread_id": task_id, "checkpoint_id": None},
    }


async def run_graph_stream(task_id: str, user_input: str, max_iterations: int = None,
                           alert: Optional[Alert] = None,
                           thread_id: str = None,
                           history_override: Optional[dict] = None,
                           chatops: bool = False) -> AsyncGenerator[str, None]:
    """流式执行 LangGraph 状态图，产出与原 ReAct 相同的 SSE 事件。

    原 ReAct 的 while/iteration 由状态图的条件边 + retry_count 替代。
    interrupt() 会挂起任务，不会阻塞在 Java 55 秒审批上。

    thread_id 是 Checkpointer 的会话维度：ChatOps 传入 session_id，
    让同一会话的 langgraph 图状态跨页面/刷新持久化；告警任务仍用 task_id。

    history_override 是 Java 网关从 Redis Memory 读出的最近 5 轮上下文；
    优先级高于从 Checkpointer 恢复的旧状态，避免 Redis 30 分钟 TTL 与
    LangGraph Checkpointer 双源不一致。
    """
    if max_iterations is None:
        max_iterations = config.AGENT_MAX_ITERATIONS
    thread_id = thread_id or task_id

    history = history_override
    if history is None:
        history = await _load_graph_state(thread_id) or {}
    # ChatOps 只读入口不恢复历史挂起的审批/执行状态，避免旧僵尸 checkpoint
    # 让新提问继续卡在“等待人工审批”。只保留已完成会话中的消息/报告摘要。
    if chatops and history and _state_is_chatops_stuck(history):
        logger.warning("ChatOps checkpoint 存在审批/执行挂起，已丢弃旧状态 thread=%s", thread_id)
        history = {}
    # state.task_id 必须保持 Java 的真实任务 ID（chatops-xxx / 告警 taskId），
    # thread_id 只用于 Checkpointer 的会话维度。此前 ChatOps 误把 sessionId
    # 注入 state.task_id，导致 Python 工具回调以 sessionId 写 Java Redis task，
    # 出现 “task:chat-xxx 不存在” 并被忽略。
    initial = _build_initial_state(task_id, user_input, alert, history=history, chatops=chatops)
    graph = get_graph()
    graph_config = _config_for(thread_id)

    # 用 astream 而不是 ainvoke：主图推进过程中同步吐出 values/updates/custom，
    # custom 通道承载报告 token，前端因此能获得打字机式增量，而不是等整轮 ainvoke
    # 跑完后才收到一个 final。告警入口仍然只消费 final/awaiting_approval，行为不变。
    result: dict = {}
    pending_report_tokens: list[str] = []
    async for event in graph.astream_events(initial, config=graph_config, version="v2"):
        event_name = event.get("event") or ""
        data = event.get("data") or {}

        # 最终全局状态：name=LangGraph 且没有子图 namespace 时输出完整 AgentState。
        if event_name == "on_chain_end" and str(event.get("name") or "") == "LangGraph":
            namespace = event.get("metadata", {}).get("langgraph_node")
            if namespace is None and isinstance(data.get("output"), dict):
                result = data["output"]
            continue

        if event_name == "on_chat_model_stream":
            chunk = data.get("chunk")
            token = str(getattr(chunk, "content", "") or "") if chunk is not None else ""
            if token:
                pending_report_tokens.append(token)
                yield json.dumps({
                    "type": "text",
                    "content": token,
                }, ensure_ascii=False)
            continue

        if event_name == "on_tool_start":
            tool = str(event.get("name") or data.get("name") or "tool")
            yield json.dumps({
                "type": "tool_start",
                "tool": tool,
            }, ensure_ascii=False)
            continue

        if event_name == "on_tool_end":
            tool = str(event.get("name") or data.get("name") or "tool")
            output = data.get("output")
            content = str(output)[:500] if output is not None else ""
            yield json.dumps({
                "type": "tool_end",
                "tool": tool,
                "content": content,
            }, ensure_ascii=False)
            continue

    # 图可能被 interrupt 挂起（result 包含 __interrupt__）。
    # 流式接口保持与旧版一致：有 report 则输出 final，否则输出挂起状态。
    interrupted = bool(result.get("__interrupt__"))
    report = result.get("report") or "".join(pending_report_tokens).strip()
    if report:
        yield json.dumps({"type": "final", "data": report}, ensure_ascii=False)
        # 把轮次报告写回消息通道：ChatOps 刷新/切换会话时能恢复完整多轮历史。
        try:
            sanitized = sanitize_messages(result.get("messages", []))
            await graph.aupdate_state(
                graph_config,
                {"messages": sanitized + [AIMessage(content=str(report))]},
            )
        except Exception as e:
            logger.warning(f"报告写回 Checkpointer 失败 thread={thread_id}: {e}")
    elif interrupted or (result.get("approval_status") in ("pending", "none")
                         and result.get("next_agent") == "executor"):
        # __interrupt__ 可能是 None（图未真正挂起但状态停留在审批前/后）；
        # 直接取下标会抛 'NoneType' object is not subscriptable，把整个任务打 FAILED。
        # 安全取值：只有 interrupt 列表非空才尝试取第一个的 request。
        interrupts = result.get("__interrupt__") or []
        request = result.get("approval_request") or (
            interrupts[0].get("value", {}).get("request", {}) if interrupts else {}
        ) or {}
        yield json.dumps({
            "type": "awaiting_approval",
            "data": {
                "operation": request.get("operation", ""),
                "reason": request.get("reason", ""),
                "task_id": task_id,
            },
        }, ensure_ascii=False)
    else:
        # 未报告也未挂起：说明异常中止，仍输出可审计的兜底。
        yield json.dumps({
            "type": "final",
            "data": _build_fallback_report(max_iterations, result),
        }, ensure_ascii=False)


def _extract_pending_approval_request_id(snapshot) -> Optional[str]:
    """从 LangGraph StateSnapshot 的当前挂起 interrupt 中提取审批 requestId。

    安全约束：只有当前仍挂起的 human_approval interrupt 才证明任务在等待审批。
    approval_request.requestId 只是历史/审计字段，不能单独作为恢复凭证，
    否则已完成的审批单可能被再次用来恢复任意高危任务。多个 interrupt 时取第一个。
    """
    try:
        for task in getattr(snapshot, "tasks", []) or []:
            for interrupt_obj in getattr(task, "interrupts", []) or []:
                value = getattr(interrupt_obj, "value", None)
                if not isinstance(value, dict):
                    continue
                if value.get("type") != "human_approval":
                    continue
                request = value.get("request") or {}
                for candidate in (
                        value.get("requestId"),
                        request.get("requestId"),
                        request.get("approvalId"),
                        request.get("contextId"),
                ):
                    candidate = str(candidate or "").strip()
                    if candidate:
                        return candidate
    except Exception:
        pass
    return None


def _build_fallback_report(max_iterations: int, state: dict) -> str:
    observations = state.get("evidence", [])
    lines = [
        "## 分析未收敛（状态图未完成）",
        "",
        f"状态图执行未产生最终报告，已中止。",
        "",
        "**这不是一个诊断结论**，仅汇总已收集到的原始证据，供人工判断：",
        "",
    ]
    if observations:
        for obs in observations[-8:]:
            lines.append(f"- {obs.get('tool')}: {str(obs.get('observation', ''))[:300]}")
    else:
        lines.append("- （未收集到有效观测数据）")
    lines += ["", "**建议**：需要人工介入排查，或检查 LangGraph 条件边/审批状态配置。"]
    return "\n".join(lines)


async def resume_graph(task_id: str, approval_decision: dict = None) -> dict:
    """审批决定后恢复被 interrupt() 挂起的图。

    Java 审批决定接口在更新审批单后调用 Python /api/agent/resume，
    携带 requestId / approved / note；这里把决定写回状态并续跑。

    安全校验：只有确实存在挂起的审批 interrupt，且 requestId 与挂起单一致，
    才允许恢复。拒绝时抛 ValueError，调用方按 401/400 返回。
    """
    decision = approval_decision or {}
    approved = bool(decision.get("approved"))
    graph = get_graph()
    graph_config = _config_for(task_id)

    snapshot = await graph.aget_state(graph_config)
    pending_request_id = _extract_pending_approval_request_id(snapshot)
    if not pending_request_id:
        raise ValueError("task 未处于人工审批挂起状态，不允许 resume（taskId 与 Checkpoint 线程 ID 可能不一致）")
    request_id = str(decision.get("requestId") or "").strip()
    if not request_id:
        raise ValueError("resume 缺少 requestId")
    if request_id != (pending_request_id or "").strip():
        # 宽松匹配之前必须留日志，便于排查 Java 回调与 Checkpoint 的 requestId 来源。
        logger.warning(
            "审批恢复 requestId 不一致 task=%s incoming=%s checkpoint=%s",
            task_id, request_id, pending_request_id,
        )
        raise ValueError("requestId 与当前挂起审批单不匹配")

    resume_payload = {
        "approved": approved,
        "requestId": request_id,
        "note": decision.get("note", ""),
    }
    result = await graph.ainvoke(
        Command(resume=resume_payload),
        config=graph_config,
    )
    # 恢复成功后清理当前挂起凭证，并单独保留最近完成 ID 供审计。
    # approval_request.requestId 不能继续留成“当前挂起单”，否则历史值可能被
    # 误当作有效审批凭证再次通过校验。
    try:
        await graph.aupdate_state(graph_config, {
            "approval_request": {
                **(result.get("approval_request") or {}),
                "requestId": None,
                "note": decision.get("note", ""),
            },
            "last_completed_request_id": request_id,
        })
    except Exception as e:
        logger.warning(f"恢复后清理 approval_request.requestId 失败 task={task_id}: {e}")
    # 恢复完成后走与 run_agent_for_alert 相同的收尾：Reflection + 回传 Java。
    report = result.get("report")
    if report:
        state = await _load_graph_state(task_id) or {}
        observations_log = [str(x.get("observation", "")) for x in state.get("evidence", [])]
        evidence = "\n".join(observations_log[-10:]) if observations_log else ""
        from agents.reflection import reflect_on_report
        review = reflect_on_report(report, evidence)
        final_report = report
        if not review["passed"]:
            from agents.react_agent import _revise_report_with_feedback
            final_report = await _revise_report_with_feedback(report, review["feedback"], evidence)
        alert_meta = state.get("alert") or {}
        final_status = "SUCCESS"
        if not approved:
            # 人工拒绝审批：任务语义是“已拒绝”，不是系统失败，也不是成功。
            final_status = "REJECTED"
            final_report = (final_report or "") + (
                "\n\n---\n任务已终止：用户拒绝了高危修复操作，未对集群做任何变更。"
            )
        await callback_service.complete_task(
            task_id,
            final_report,
            status=final_status,
            service_name=alert_meta.get("service_name", ""),
            alert_name=alert_meta.get("alert_name", ""),
        )
        clear_checkpoint(task_id)
        langfuse_finalize()
    return result


async def run_agent_for_alert(task_id: str, alert: Alert) -> str:
    """非流式入口：跑完整个 LangGraph 状态图并把最终报告回传给 Java。

    保留原有业务闭环：告警输入 -> 图诊断/执行/报告 -> Reflection -> 回传。
    审批挂起时本方法不会长时间等待；图状态保存在 Redis Checkpointer，
    后续 Java 审批回调通过 resume_graph 恢复。
    """
    user_input = (
        f"服务 {alert.serviceName} 发生告警：{alert.title} - {alert.description}"
        f"，详情：{alert.detail or '无'}"
    )

    # 防重复：同一 taskId 若已有未完成的 LangGraph checkpoint（例如审批挂起中），
    # 不再创建新图，避免重发导致同一高危操作被执行两次。
    existing = await _load_graph_state(task_id)
    if existing and not existing.get("report") and existing.get("completed_agents"):
        return "PENDING_APPROVAL"

    final = None
    pending = False
    # 只读告警（预测性预警等）：不触发 executor/审批，只输出诊断报告。
    readonly = bool(getattr(alert, "readonly", False))
    try:
        async with asyncio.timeout(config.AGENT_TIMEOUT_SECONDS):
            async for event in run_graph_stream(task_id, user_input, alert=alert, chatops=readonly):
                data = json.loads(event)
                if data.get("type") == "final":
                    final = data.get("data")
                    break
                if data.get("type") == "awaiting_approval":
                    pending = True
    except TimeoutError:
        final = f"处理超时（超过 {config.AGENT_TIMEOUT_SECONDS} 秒），未能完成分析。"
        await callback_service.complete_task(task_id, final, status="FAILED")
        return final

    if pending:
        # 图已挂起等人工审批：不回调完成，等待 Java 审批回调恢复。
        return "PENDING_APPROVAL"

    if final is None:
        final = "处理失败，未获得最终报告。"

    from agents.reflection import reflect_on_report
    state = await _load_graph_state(task_id) or {}
    observations_log = [str(x.get("observation", "")) for x in state.get("evidence", [])]
    evidence = "\n".join(observations_log[-10:]) if observations_log else ""
    review = reflect_on_report(final, evidence)
    if not review["passed"]:
        from agents.react_agent import _revise_report_with_feedback
        logger.warning(f"Reflection 未通过，尝试修订: {review['feedback']}")
        final = await _revise_report_with_feedback(final, review["feedback"], evidence)
        review2 = reflect_on_report(final, evidence)
        if not review2["passed"]:
            logger.warning("Reflection 二次仍未通过，按 FAILED 回传")
            await callback_service.complete_task(
                task_id, final, status="FAILED",
                service_name=alert.serviceName or "",
                alert_name=alert.title or "",
            )
            clear_checkpoint(task_id)
            langfuse_finalize()
            return final

    await callback_service.complete_task(
        task_id,
        final,
        service_name=alert.serviceName or "",
        alert_name=alert.title or "",
    )
    clear_checkpoint(task_id)
    langfuse_finalize()
    return final


async def _load_graph_state(task_id: str) -> Optional[dict]:
    try:
        graph = get_graph()
        result = await graph.aget_state(_config_for(task_id))
        if not result or not result.values:
            return None
        state = dict(result.values)
        # checkpoint 里可能残留被裁剪切断的孤儿 ToolMessage；
        # 读取状态时就清洗一遍，避免后续会话/报告被脏数据污染。
        if state.get("messages"):
            state["messages"] = sanitize_messages(state["messages"])
        return state
    except Exception:
        return None


def _state_is_chatops_stuck(state: dict) -> bool:
    """判断 ChatOps 旧 checkpoint 是否仍挂起人工审批、需要丢弃重来。"""
    if not state:
        return False
    if state.get("report"):
        return False
    # 只有明确停留在审批中断，才可能让下一次提问继续卡住。
    if state.get("approval_status") == "pending":
        return True
    # 旧版 ChatOps 曾进入 executor 且还没完成报告，同样按挂起处理。
    committed_executor = "executor" in (state.get("completed_agents") or [])
    if committed_executor and state.get("approval_status") in ("pending", "none"):
        return True
    return False


# 兼容旧入口，避免其它调用方因 import 报错
async def run_react_loop(task_id: str, user_input: str, max_iterations: int = None,
                         thread_id: str = None, history_override: dict = None):
    async for event in run_graph_stream(task_id, user_input, max_iterations,
                                        thread_id=thread_id,
                                        history_override=history_override,
                                        chatops=True):
        yield event