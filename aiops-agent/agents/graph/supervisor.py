# -*- coding: utf-8 -*-
"""
Supervisor Agent：负责任务拆解与路由。

职责：
1. 读入告警/用户输入，输出结构化 plan；
2. 决定下一个 Worker：diagnostic / executor / reporter / end；
3. 在报告完成后决定是否需要修订或直接结束。

它不绑定工具，避免把"路由"和"实际执行"混在一个 LLM 上下文里。
"""
from typing import Literal

from langchain_core.messages import SystemMessage, HumanMessage
from langchain_openai import ChatOpenAI

from config import config
from agents.graph.state import AgentState
from observability.langfuse_client import get_langfuse_callback_handler

SUPERVISOR_SYSTEM_PROMPT = """你是 AIOps Multi-Agent 编排主管（Supervisor）。

当前系统由三个 Worker 组成：
- diagnostic：只读排查（状态、日志、Pod 事件、指标、影响面、知识库）
- executor：K8s 变更操作（重启、清缓存、扩缩容、回滚），必须经过人工审批闸门
- reporter：只汇总已收集证据并生成最终报告

规则：
1. 首次进入必须路由到 diagnostic，让诊断 Agent 先收集证据。
2. 只有拿到根因/影响面，并且确实需要修复时，才路由 executor；否则直接到 reporter。
3. executor 完成后必须回到 diagnostic 做修复后验证；验证通过再进 reporter。
4. reporter 输出最终报告后，由本 Supervisor 判断是否结束。
5. 不要在你的输出里编造集群事实，也不要直接执行工具。

输出格式：
NEXT_AGENT=<diagnostic|executor|reporter|end>
PLAN=<当前子任务一句话>
REPAIR_OPERATION=<建议执行的具体操作，如 restart nginx>，没有则省略"""


async def supervisor_node(state: AgentState) -> dict:
    """Supervisor 节点：拆解任务并设置 next_agent。"""
    task_id = state.get("task_id", "")
    messages = state.get("messages", [])
    completed = state.get("completed_agents", [])
    root_cause = state.get("root_cause")
    evidence = state.get("evidence", [])
    execution_results = state.get("execution_results", [])
    report = state.get("report")

    # 根据图进度决定下一步，不需要每次都让 LLM 重读全部工具输出。
    if report:
        return {"next_agent": "end", "plan": "报告已完成，结束任务"}
    if not root_cause and "diagnostic" not in completed:
        return {"next_agent": "diagnostic", "plan": "诊断 Agent 收集证据并定位根因"}
    if evidence and "executor" not in completed and _needs_repair(state):
        plan = "执行 Agent 执行修复并先通过审批闸门"
        current_subtask = state.get("current_subtask") or _repair_operation(root_cause, plan)
        return {
            "next_agent": "executor",
            "plan": plan,
            # 审批闸门靠 current_subtask 生成可读审批单，不能留空，
            # 否则 Java 审批列表只会看到“未知高危操作”。
            "current_subtask": current_subtask,
        }
    if "executor" in completed and execution_results:
        # 修复后必须回到 diagnostic 做回归验证，不能直接总结
        return {"next_agent": "diagnostic", "plan": "诊断 Agent 验证修复结果"}
    return {"next_agent": "reporter", "plan": "报告 Agent 汇总证据"}


def _repair_operation(root_cause: str, fallback_plan: str) -> str:
    """根据根因关键词生成可读的审批操作名，防止审批单变成未知高危操作。"""
    text = (root_cause or "").lower()
    if "crash" in text or "crashloop" in text or "重启" in text:
        return "重启故障服务"
    if "oom" in text or "内存" in text:
        return "处理内存/资源问题"
    if "回滚" in text or "镜像" in text or "image" in text:
        return "回滚服务版本"
    if "扩容" in text or "缩容" in text:
        return "调整服务副本数"
    if "断流" in text or "not ready" in text or "notready" in text:
        return "恢复服务可用性"
    return fallback_plan[:120] or "执行修复操作"


def _needs_repair(state: AgentState) -> bool:
    # 不能只看根因文本里的关键词：健康服务在提示词里出现“重启/异常”等词时
    # 也会被误判。必须同时有只读证据证明服务实际不健康，才进入执行/审批。
    if not _evidence_indicates_failure(state):
        return False
    root_cause = (state.get("root_cause") or "").lower()
    keywords = (
        "crash", "imagepull", "imagepullbackoff", "crashloopbackoff", "oom",
        "oomkilled", "失败", "异常", "重启", "回滚", "扩容", "缩容",
        "断流", "unhealthy", "not ready", "notready",
    )
    return any(k in root_cause for k in keywords)


def _evidence_indicates_failure(state: AgentState) -> bool:
    """从只读证据里判断服务是否真实异常，防止健康服务被误路由到审批执行。"""
    evidence = state.get("evidence") or []
    for item in evidence:
        obs = str(item.get("observation") or "").lower()
        tool = item.get("tool", "")
        if tool == "get_service_status":
            # 状态里带 degraded / not_found / 副本就绪不足 / pending 都算异常
            if any(marker in obs for marker in (
                    "degraded", "not_found", "stopped", "pending",
                    "0/", "unavailable", "unhealthy", "not ready", "notready",
            )):
                return True
        elif tool == "query_impact":
            # “未就绪 0”不算异常；只有明确出现断流/outage/异常实例，
            # 或“未就绪 N”的 N > 0 时才判定受影响。
            if any(marker in obs for marker in (
                    "已断流", "断流", "outage", "异常实例",
                    "业务流量当前中断", "未就绪 1", "未就绪 2", "未就绪 3",
                    "未就绪 4", "未就绪 5", "未就绪 6", "未就绪 7",
                    "未就绪 8", "未就绪 9",
            )):
                return True
        elif tool == "query_pod_events":
            if any(marker in obs for marker in (
                    "crashloopbackoff", "imagepullbackoff", "oomkilled",
                    "back-off", "backoff", "error", "failed", "failure",
                    "unhealthy", "not ready", "notready",
            )):
                return True
    # 没有明确证据时保守处理：不进入执行/审批，由报告 Agent 说明证据不足
    return False


def supervisor_route(state: AgentState) -> Literal["diagnostic", "executor", "reporter", "end"]:
    return state.get("next_agent", "diagnostic")