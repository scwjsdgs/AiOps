# -*- coding: utf-8 -*-
"""
工具集隔离：按 Agent 职责拆分，而不是把 10 个工具全绑给一个 LLM。

- 诊断 Agent：只读工具（知识库、状态、日志、Pod 事件、指标、影响面）
- 执行 Agent：只读确认 + 写操作（状态确认、重启/清缓存、扩缩容、回滚）
- 报告 Agent：不再绑定真实工具，防止在总结阶段再次触发操作或查询

人工审批在 LangGraph 中不是由诊断/执行模型的工具调用触发的，而是执行 Agent
在写操作前的审批闸门：闸门调用 Java 的 request_human_approval 工具创建审批单，
再用 interrupt() 把图挂起。这样 LLM 自己不能绕过审批，审批与否由状态图保证。
"""
from langchain.tools import tool

from services.callback import callback_service
from rag.vector_store import get_vector_store
from config import config
import asyncio
import logging

logger = logging.getLogger(__name__)

REPAIR_ACTION_MAP = {
    "restart": "restart_service",
    "restart_service": "restart_service",
    "clear_cache": "clear_cache",
}


def _svc_params(service: str, **extra) -> dict:
    params = {"service": service, "deployment": service}
    params.update(extra)
    return params


def _get_vector_store():
    if not config.RAG_ENABLED:
        return None
    try:
        return get_vector_store()
    except Exception as e:
        logger.warning(f"向量库不可用，知识库检索降级为占位实现：{e}")
        return None


async def _search_knowledge_base(query: str) -> str:
    vector_store = _get_vector_store()
    if vector_store is None:
        from rag.case_store import search_cases
        cases = await asyncio.to_thread(search_cases, query, 3)
        return _format_cases(cases) or "知识库当前不可用（向量库未初始化），请基于日志与经验给出稳妥建议。"
    from rag.case_store import search_cases
    from rag.vector_store import hybrid_search
    cases = await asyncio.to_thread(search_cases, query, 3)
    docs = await asyncio.to_thread(hybrid_search, query, 3)
    parts = []
    if cases:
        parts.append(_format_cases(cases))
    if docs:
        parts.append("知识库参考：\n" + "\n\n".join(d[0] for d in docs))
    return "\n\n".join(parts) if parts else "未找到相关历史记录。"


def _format_cases(cases) -> str:
    if not cases:
        return ""
    lines = [
        f"【历史案例 {i+1}】服务={c.get('service')} 告警={c.get('alert_name')}\n{c.get('content')}"
        for i, c in enumerate(cases)
    ]
    return "历史相似案例：\n\n" + "\n\n".join(lines)


def _format_status(data: dict) -> str:
    status = data.get("status", "unknown")
    detail = (
        f"副本 {data.get('readyReplicas', '?')}/{data.get('replicas', '?')} 就绪"
        f"，镜像 {data.get('image', 'unknown')}"
    )
    return f"服务当前状态：{status}（{detail}）"


def _format_metrics(data: dict) -> str:
    rows = data.get("result") or []
    if not rows:
        return f"指标查询无数据：{data.get('query', '')}"
    lines = []
    for row in rows[:20]:
        metric = row.get("metric") or {}
        value = row.get("value", "?")
        label = ", ".join(f"{k}={v}" for k, v in list(metric.items())[:5])
        lines.append(f"{label} -> {value}")
    return f"指标查询（{data.get('resultCount', len(rows))} 条）：\n" + "\n".join(lines)


def _format_logs(data: dict) -> str:
    logs = data.get("logs") or "（无日志输出）"
    return f"日志分析结果（共 {data.get('lineCount', '?')} 行，来源 {data.get('source', 'unknown')}）：\n{logs[:2000]}"


def _format_events(data: dict) -> str:
    events = data.get("events") or []
    if not events:
        return "服务当前无异常事件，Pod 状态正常"
    lines = []
    for ev in events[:20]:
        if ev.get("reason"):
            lines.append(
                f"[{ev.get('type', '?')}] {ev.get('reason', '?')}: {ev.get('message', '')} "
                f"(x{ev.get('count', '?')}, {ev.get('lastSeen', '?')})"
            )
    pod_line = "\n".join(
        f"- Pod {ev.get('pod')} phase={ev.get('phase', '?')}" for ev in events if ev.get("pod") and ev.get("phase")
    )
    summary = f"Pod 事件（{data.get('podCount', '?')} 个 Pod、{data.get('eventCount', '?')} 条记录）：\n"
    if pod_line:
        summary += pod_line + "\n"
    summary += "\n".join(lines[:15])
    return summary


def _format_impact(data: dict) -> str:
    lines = [data.get("summary") or ""]
    services = data.get("affectedServices") or []
    for svc in services[:10]:
        lines.append(
            f"- 流量入口 {svc.get('service')}：就绪端点 {svc.get('readyEndpoints', '?')}，"
            f"未就绪 {svc.get('notReadyEndpoints', '?')}"
            + ("（已断流）" if svc.get("outage") else "")
        )
    pods = data.get("affectedPods") or []
    bad = [p for p in pods if not p.get("ready")]
    if bad:
        lines.append(f"- 异常实例 {len(bad)}/{len(pods)} 个：")
        for p in bad[:10]:
            problems = "、".join(p.get("problems") or [])
            lines.append(f"  · {p.get('pod')}（{p.get('phase', '?')}{'：' + problems if problems else ''}）")
    if data.get("outage"):
        lines.append("⚠️ 已确认断流：业务流量当前中断，应优先恢复入口可用性。")
    return "\n".join(line for line in lines if line)


def create_diagnostic_tools(task_id: str):
    @tool
    async def search_knowledge_base(query: str) -> str:
        """检索运维知识库（历史故障处理手册 + 平台沉淀的历史诊断案例）"""
        return await _search_knowledge_base(query)

    @tool
    async def get_service_status(service: str) -> str:
        """查询服务当前运行状态（副本数、就绪情况、镜像版本）"""
        return await _read_tool(task_id, "get_status", _svc_params(service), "status_check")

    @tool
    async def analyze_logs(service: str, lines: int = 100, keyword: str = "ERROR") -> str:
        """查询服务最近 N 行日志，并按关键字过滤"""
        return await _read_tool(
            task_id, "query_log", _svc_params(service, lines=lines, keyword=keyword), "log_analysis"
        )

    @tool
    async def query_pod_events(service: str) -> str:
        """查询 Deployment 下 Pod 的 Kubernetes 事件（ImagePullBackOff、CrashLoopBackOff、OOMKilled、探针失败等），根因定位首选。"""
        return await _read_tool(task_id, "query_pod_events", _svc_params(service), "pod_events")

    @tool
    async def query_metrics(query: str) -> str:
        """查询 Prometheus 监控指标（PromQL）。可查容器 CPU/内存、副本数、节点资源等，用于根因分析时获取真实数据。"""
        return await _read_tool(task_id, "query_metrics", {"query": query, "promql": query}, "metrics_query")

    @tool
    async def query_impact(service: str) -> str:
        """分析故障影响面：该服务的流量入口（Service）有哪些、是否已断流、哪些实例异常。"""
        return await _read_tool(task_id, "query_impact", _svc_params(service), "impact_analysis")

    return [
        search_knowledge_base,
        get_service_status,
        analyze_logs,
        query_pod_events,
        query_metrics,
        query_impact,
    ]


def create_execution_tools(task_id: str):
    @tool
    async def get_service_status(service: str) -> str:
        """查询服务当前运行状态（执行前确认、执行后验证）"""
        return await _read_tool(task_id, "get_status", _svc_params(service), "status_check")

    @tool
    async def execute_repair_action(service: str, action: str) -> str:
        """执行常规运维动作。action 取值：restart（重启服务）、clear_cache（清理缓存）"""
        tool_name = REPAIR_ACTION_MAP.get((action or "").strip().lower())
        if tool_name is None:
            return f"不支持的动作：{action}。可用动作只有 restart、clear_cache，请改用其他工具，或直接说明无法执行。"
        await callback_service.send_step(task_id, f"准备执行 {action} 于 {service}", "action_execution")
        return await _write_tool(task_id, tool_name, _svc_params(service, action=action), action)

    @tool
    async def scale_service(service: str, replicas: int) -> str:
        """调整服务实例数量（扩缩容）"""
        await callback_service.send_step(task_id, f"正在将 {service} 扩缩容至 {replicas} 个实例", "scaling")
        return await _write_tool(task_id, "scale_up", _svc_params(service, replicas=replicas), "scale")

    @tool
    async def rollback_version(service: str, target_version: str = "previous") -> str:
        """将服务回滚到指定版本（默认回滚到前一个版本）"""
        await callback_service.send_step(task_id, f"正在将 {service} 回滚至 {target_version}", "rollback")
        return await _write_tool(task_id, "rollback", _svc_params(service, version=target_version), "rollback")

    return [get_service_status, execute_repair_action, scale_service, rollback_version]


async def _read_tool(task_id: str, tool_name: str, params: dict, step_name: str) -> str:
    try:
        result = await callback_service.execute_tool(task_id, tool_name, params)
        if result.success:
            data = result.data or {}
            if tool_name == "get_status":
                return _format_status(data) if data else result.message
            if tool_name == "query_metrics":
                return _format_metrics(data) if data else result.message
            if tool_name == "query_log":
                return _format_logs(data) if data else result.message
            if tool_name == "query_pod_events":
                return _format_events(data) if data else result.message
            if tool_name == "query_impact":
                return _format_impact(data) if data else result.message
            return result.message
        return f"读取 {tool_name} 失败：{result.message}"
    except Exception as e:
        return f"读取 {tool_name} 失败：{e}"


async def _write_tool(task_id: str, tool_name: str, params: dict, action: str) -> str:
    try:
        result = await callback_service.execute_tool(task_id, tool_name, params)
        await callback_service.send_step(task_id, f"{tool_name} 执行结果：{result.message}", f"{action}_result")
        if result.success:
            return f"{tool_name} 执行成功：{result.message}"
        return f"{tool_name} 执行失败：{result.message}。请如实说明未能完成，不要声称已执行。"
    except Exception as e:
        return f"调用 Java 执行 {tool_name} 失败：{e}"