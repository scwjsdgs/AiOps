# -*- coding: utf-8 -*-
"""
执行子图（独立 StateGraph）：专精 K8s 写操作，强制人工审批。

关键设计：
1. 执行子图的 LLM 只能绑定执行类工具（确认状态 + 重启/清缓存/扩缩容/回滚）。
2. 审批闸门在子图内部 `approval_gate` 节点处理：manual 模式先 interrupt()，
   人工批准后才进入执行循环；拒绝/异常 fail-closed 不执行任何写操作。
3. 审批通过后仍保留高危工具的 Java 侧白名单/审批审计，双保险。
4. 执行完成后把结果写入 execution_results，交回 Supervisor 路由到诊断验证。
"""
import json

from langchain_core.messages import SystemMessage, HumanMessage, ToolMessage
from langchain_openai import ChatOpenAI
from langgraph.graph import StateGraph, START, END
from langgraph.types import interrupt

from config import config
from agents.graph.state import AgentState
from agents.graph.tool_sets import create_execution_tools
from agents.graph.worker import trim_worker_messages, sanitize_messages
from observability.langfuse_client import get_langfuse_callback_handler
from services.callback import callback_service

EXECUTOR_SYSTEM_PROMPT = """你是 AIOps 执行 Agent，只负责执行已经过人工审批的 K8s 变更。

可用工具：
1. get_service_status：执行前确认当前状态、执行后验证
2. execute_repair_action：restart / clear_cache
3. scale_service：扩缩容
4. rollback_version：回滚（高危）

规则：
- 本 Agent 只会收到 approval_status=approved 的状态；如果状态不是 approved，
  立即拒绝执行所有写工具。
- 所有写操作必须把不含幻觉的真实结果写回。
- 不要发明"已恢复"：工具返回失败就如实说明失败。
- 不要调用诊断 Agent 的只读工具（日志/指标/影响面不在你的工具集里）。
- 执行完成后只输出 EXECUTION_RESULT=... 一行摘要。
""".strip()


async def _executor_inner(state: AgentState) -> dict:
    """执行子图内部核心节点：在审批已通过后执行变更。"""
    if state.get("approval_status") != "approved":
        return {
            "approval_status": "rejected",
            "execution_results": [{"tool": "approval", "result": "审批未通过，未执行任何写操作。"}],
            "completed_agents": ["executor"],
            "next_agent": "reporter",
        }

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
    tools = create_execution_tools(task_id)
    llm_with_tools = llm.bind_tools(tools)
    tools_dict = {t.name: t for t in tools}

    user_content = _execution_input(state)
    messages = [
        SystemMessage(content=EXECUTOR_SYSTEM_PROMPT),
        HumanMessage(content=user_content),
    ]
    results = list(state.get("execution_results", []))
    max_steps = min(config.AGENT_MAX_ITERATIONS, 4)
    used_signatures = set()

    for _ in range(max_steps):
        # 滑动窗口 + 兜底清洗：保留最近 window 条消息，并剔除被切断的孤儿
        # ToolMessage / 未配对 tool_calls。
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
                sig = f"{name}|{json.dumps(args, sort_keys=True, ensure_ascii=False)}"
            except Exception:
                sig = f"{name}|{args}"
            if sig in used_signatures:
                observation = "重复调用被拒绝：同一写操作不要重复执行，请直接给出结果。"
            else:
                # 写工具执行前的二次校验：审批已通过且工具属于执行白名单
                if not _is_safe_write(name, args, state):
                    observation = "该操作未获得人工审批，已拒绝执行。"
                else:
                    used_signatures.add(sig)
                    func = tools_dict.get(name)
                    if func is None:
                        observation = f"未知工具：{name}"
                    else:
                        await callback_service.send_step(task_id, f"执行 Agent 调用 {name}", f"executor_{name}")
                        try:
                            observation = await func.ainvoke(args)
                        except Exception as e:
                            observation = f"工具执行失败：{e}"
                    results.append({"tool": name, "args": args, "result": str(observation)})
            messages.append(ToolMessage(content=str(observation), tool_call_id=tc["id"]))
        if len(results) >= config.AGENT_MAX_ITERATIONS:
            break

    return {
        "execution_results": results,
        "completed_agents": ["executor"],
        "approval_status": "approved",
        "next_agent": "diagnostic",
        "iteration_count": (state.get("iteration_count") or 0) + 1,
    }


async def _approval_gate(state: AgentState) -> dict:
    """执行子图内部审批闸门：interrupt 挂起等人工决定（保留 Java 双保险）。

    返回值与旧 graph.executor_graph_node 一致：approved 继续执行，
    拒绝/不可用时 fail-closed 直接给 reporter 状态。
    """
    if state.get("approval_status") == "approved":
        return {"approval_status": "approved"}

    request = dict(state.get("approval_request") or {
        "operation": state.get("current_subtask") or "执行 Agent 请求的写操作",
        "reason": (state.get("root_cause") or "")[:500],
        "taskId": state.get("task_id", ""),
    })
    request.setdefault("taskId", state.get("task_id", ""))

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
        import logging
        logging.getLogger(__name__).warning(
            f"审批请求推送失败（继续挂起等待） task={state.get('task_id')}: {e}"
        )

    if approval_result is not None and (approval_result.data or {}).get("approved"):
        request["requestId"] = (approval_result.data or {}).get("requestId", "")
        return {
            "approval_status": "approved",
            "approval_request": request,
            "next_agent": "executor",
        }

    if approval_result is not None and (approval_result.data or {}).get("pending"):
        request["requestId"] = (approval_result.data or {}).get("requestId", request.get("requestId", ""))
        decision = interrupt({"type": "human_approval", "request": request, "requestId": request.get("requestId", "")})
    else:
        return {
            "approval_status": "rejected",
            "execution_results": [{
                "tool": "approval",
                "result": f"审批未能获得批准，未执行任何写操作。{getattr(approval_result, 'message', '') or ''}".strip(),
                "args": request,
            }],
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
            "next_agent": "reporter",
        }

    request["requestId"] = decision.get("requestId", request.get("requestId", ""))
    request["note"] = decision.get("note", "")
    return {
        "approval_status": "approved",
        "approval_request": request,
        "next_agent": "executor",
    }


def build_executor_subgraph():
    """构建独立执行子图（CompiledStateGraph）。

    子图内部：approval_gate（人工审批闸门）-> entry（执行循环）。
    主图把该编译子图作为 "executor" 节点；子图内部私有消息不暴露给主图。
    """
    builder = StateGraph(AgentState)
    builder.add_node("approval_gate", _approval_gate)
    builder.add_node("entry", _executor_inner)
    builder.add_edge(START, "approval_gate")
    builder.add_edge("approval_gate", "entry")
    builder.add_edge("entry", END)
    return builder.compile()


# 兼容旧函数：直接调用内部核心
async def executor_subgraph_node(state: AgentState) -> dict:
    return await _executor_inner(state)


def _is_safe_write(name: str, args: dict, state: AgentState) -> bool:
    if state.get("approval_status") != "approved":
        return False
    # 写工具白名单
    return name in {"execute_repair_action", "scale_service", "rollback_version"}


def _execution_input(state: AgentState) -> str:
    parts = [f"任务：{state.get('core_intent', '')}"]
    if state.get("root_cause"):
        parts.append(f"根因：{state['root_cause']}")
    if state.get("impact_summary"):
        parts.append(f"影响面：{state['impact_summary']}")
    approval = state.get("approval_request") or {}
    parts.append(f"审批：已通过（requestId={approval.get('requestId', '-')}）")
    return "\n".join(parts)


def executor_route(state: AgentState) -> str:
    return "diagnostic"


# 兼容别名：老调用方 import executor_node 不会断
async def executor_node(state: AgentState) -> dict:
    return await _executor_inner(state)