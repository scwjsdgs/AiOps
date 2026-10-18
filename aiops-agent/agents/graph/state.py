# -*- coding: utf-8 -*-
"""
全局 AgentState（LangGraph 跨节点共享状态）。

原 ReAct 的 AgentState 是单 Agent 的本地工作内存；这里把它升级为
LangGraph 状态图里所有节点共享的全局状态。节点之间不直接传对象，
而是通过 StateGraph 的 reducer 合并/写入同一个 checkpoint。
"""
from typing import Any, Dict, List, Optional, TypedDict, Annotated
import operator

from langchain_core.messages import BaseMessage
from langgraph.graph.message import add_messages

from config import config


def _dedup_append(existing: Optional[List[Any]], incoming: Any) -> List[Any]:
    """追加列表字段，避免同一步骤/证据在恢复时被重复写入。"""
    if incoming is None:
        return existing or []
    if isinstance(incoming, list):
        for item in incoming:
            if item not in (existing or []):
                (existing := (existing or [])).append(item)
        return existing or []
    if incoming not in (existing or []):
        (existing := (existing or [])).append(incoming)
    return existing or []


def _bounded_messages(existing: Optional[List[BaseMessage]], incoming: Any) -> List[BaseMessage]:
    """Checkpoint 体积优化：messages 用 add_messages 合并后按上限裁剪。

    全局状态里只保留最近 N 条消息，旧消息的细节已经落进 evidence / summary，
    不需要把整段历史写进 checkpoint。trim_messages 在 Worker 子图内还会再做释放。
    """
    merged = add_messages(existing or [], incoming)
    limit = max(4, config.MESSAGES_WINDOW_SIZE)
    if isinstance(merged, list) and len(merged) > limit:
        merged = merged[-limit:]
    return merged


class AgentState(TypedDict, total=False):
    # 状态图身份与业务上下文
    task_id: str
    core_intent: str                     # 本次告警/用户问题，Supervisor 拆解依据
    current_subtask: str                 # 当前节点正在推进的子任务

    # ChatOps 多轮会话记忆：历史 AI 报告/摘要，不写入 LangGraph 消息通道
    previous_summary: str

    # 跨节点共享的 LangChain 消息历史（各节点可读写）
    messages: Annotated[list[BaseMessage], _bounded_messages]

    # 诊断 Agent 的只读证据与最终根因结论
    evidence: Annotated[list[Dict[str, Any]], _dedup_append]
    root_cause: Optional[str]
    impact_summary: Optional[str]        # 影响面：流量入口/是否断流/异常实例数

    # Send API 多 Pod 并发诊断：fan_out_to_pods 发现的异常 Pod 目标列表
    abnormal_pods: Annotated[list[str], _dedup_append]
    # Send 分支局部状态：只传 pod_name，严禁把 messages/evidence 全量塞进 Send
    pod_name: Optional[str]
    # 各 Pod 诊断子报告结果，operator.add 天然支持并行分支写入合并
    pod_diagnoses: Annotated[list[Dict[str, Any]], operator.add]

    # 执行 Agent 的审批与执行结果
    approval_status: Optional[str]       # none / pending / approved / rejected / timeout
    approval_request: Optional[Dict[str, Any]]
    execution_results: Annotated[list[Dict[str, Any]], _dedup_append]

    # Supervisor 路由控制
    plan: Annotated[list[str], _dedup_append]
    next_agent: str

    # 收敛与防重复执行
    retry_count: int                     # 当前节点/同参数调用重试次数
    max_retries: int                     # 条件边允许的最大重试次数
    completed_agents: Annotated[list[str], _dedup_append]

    # 防死循环：主流程总迭代轮数；条件边达到阈值后强制进 reporter
    iteration_count: int
    # Supervisor 路由轮次（独立于 Worker 完成次数，用于主循环防死循环）
    supervisor_round: int

    # 报告与终态控制
    report: Optional[str]
    was_interrupted: Optional[bool]      # 是否因人工审批 interrupt 挂起
    final_status: Optional[str]          # SUCCESS / FAILED

    # 运行时/审计
    started_at: Optional[str]
    completed_at: Optional[str]
    error: Optional[str]
    alert: Optional[Dict[str, Any]]      # 告警元数据（serviceName/title），供 resume 后案例库入库