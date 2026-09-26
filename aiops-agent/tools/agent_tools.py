import asyncio
import logging

from langchain.tools import tool

from services.callback import callback_service
from rag.vector_store import get_vector_store
from config import config

logger = logging.getLogger(__name__)

# execute_repair_action 的 action -> Java 工具名 白名单映射。
# 直接把 action 当工具名发给 Java 会命中不存在的工具（Tool not found），
# 而且等于把 LLM 的自由文本拼进工具名，必须显式收敛。
REPAIR_ACTION_MAP = {
    "restart": "restart_service",
    "restart_service": "restart_service",
    "clear_cache": "clear_cache",
}


def _svc_params(service: str, **extra) -> dict:
    """service 与 deployment 双发，Java 侧读到哪个 key 都能工作。"""
    params = {"service": service, "deployment": service}
    params.update(extra)
    return params


def create_tools(task_id: str):
    vector_store = None
    if config.RAG_ENABLED:
        try:
            vector_store = get_vector_store()
        except Exception as e:
            # 知识库不可用不应该让整个 agent 起不来
            logger.warning(f"向量库不可用，知识库检索降级为占位实现：{e}")

    # ----- 1. 知识库检索 -----
    if vector_store is None:
        @tool
        async def search_knowledge_base(query: str) -> str:
            """检索运维知识库（历史故障处理手册 + 平台沉淀的历史诊断案例）"""
            # 向量库整体不可用时，仍尝试只查案例库（两条独立通道）
            from rag.case_store import search_cases
            cases = await asyncio.to_thread(search_cases, query, 3)
            if cases:
                lines = [f"【历史案例 {i+1}】服务={c['service']} 告警={c['alert_name']}\n{c['content']}"
                         for i, c in enumerate(cases)]
                return "历史相似案例：\n\n" + "\n\n".join(lines)
            return "知识库当前不可用（向量库未初始化），请基于日志与经验给出稳妥建议。"
    else:
        @tool
        async def search_knowledge_base(query: str) -> str:
            """检索运维知识库（历史故障处理手册 + 平台沉淀的历史诊断案例）"""
            # 先查历史案例（agent 自己沉淀的诊断报告），再查 SOP 知识库。
            # 案例在前——它是「上次遇到同样的问题是怎么修好的」，对本次决策最直接有用。
            from rag.case_store import search_cases
            cases = await asyncio.to_thread(search_cases, query, 3)
            docs = await asyncio.to_thread(vector_store.similarity_search, query, k=3)

            parts = []
            if cases:
                lines = [f"【历史案例 {i+1}】服务={c['service']} 告警={c['alert_name']}\n{c['content']}"
                         for i, c in enumerate(cases)]
                parts.append("历史相似案例：\n\n" + "\n\n".join(lines))
            if docs:
                parts.append("知识库参考：\n" + "\n\n".join([doc.page_content for doc in docs]))
            if not parts:
                return "未找到相关历史记录。"
            return "\n\n".join(parts)

    # ----- 2. 常规修复（重启 / 清理缓存） -----
    @tool
    async def execute_repair_action(service: str, action: str) -> str:
        """执行常规运维动作。action 取值：restart（重启服务）、clear_cache（清理缓存）"""
        tool_name = REPAIR_ACTION_MAP.get((action or "").strip().lower())
        if tool_name is None:
            return (
                f"不支持的动作：{action}。可用动作只有 restart、clear_cache，"
                "请改用其他工具，或直接说明无法执行。"
            )

        await callback_service.send_step(task_id, f"准备执行 {action} 于 {service}", "action_execution")
        try:
            result = await callback_service.execute_tool(
                task_id, tool_name, _svc_params(service, action=action)
            )
        except Exception as e:
            error_msg = f"调用 Java 执行 {tool_name} 失败：{e}"
            await callback_service.send_step(task_id, error_msg, "action_error")
            return error_msg

        await callback_service.send_step(
            task_id, f"{tool_name} 执行结果：{result.message}", "action_result"
        )
        if not result.success:
            return f"{tool_name} 执行失败：{result.message}。请如实说明未能完成，不要声称已修复。"
        return f"{tool_name} 执行成功：{result.message}"

    # ----- 3. 查询服务状态 -----
    @tool
    async def get_service_status(service: str) -> str:
        """查询服务当前运行状态（副本数、就绪情况、镜像版本）"""
        await callback_service.send_step(task_id, f"正在查询 {service} 状态", "status_check")
        try:
            result = await callback_service.execute_tool(task_id, "get_status", _svc_params(service))
        except Exception as e:
            return f"查询 {service} 状态失败：{e}"

        if not result.success:
            return f"查询 {service} 状态失败：{result.message}"

        data = result.data or {}
        status = data.get("status", "unknown")
        detail = (
            f"副本 {data.get('readyReplicas', '?')}/{data.get('replicas', '?')} 就绪"
            f"，镜像 {data.get('image', 'unknown')}"
        )
        return f"服务 {service} 当前状态：{status}（{detail}）"

    # ----- 4. 扩缩容 -----
    @tool
    async def scale_service(service: str, replicas: int) -> str:
        """调整服务实例数量（扩缩容）"""
        await callback_service.send_step(
            task_id, f"正在将 {service} 扩缩容至 {replicas} 个实例", "scaling"
        )
        try:
            result = await callback_service.execute_tool(
                task_id, "scale_up", _svc_params(service, replicas=replicas)
            )
        except Exception as e:
            return f"扩缩容 {service} 失败：{e}"

        if not result.success:
            return f"扩缩容 {service} 失败：{result.message}。请如实说明，不要声称已调整。"

        data = result.data or {}
        return (
            f"扩缩容成功：{service} 实例数由 {data.get('previousReplicas', '?')} "
            f"调整为 {data.get('replicas', replicas)}"
        )

    # ----- 5. 版本回滚 -----
    @tool
    async def rollback_version(service: str, target_version: str = "previous") -> str:
        """将服务回滚到指定版本（默认回滚到前一个版本）"""
        await callback_service.send_step(
            task_id, f"正在将 {service} 回滚至 {target_version}", "rollback"
        )
        try:
            result = await callback_service.execute_tool(
                task_id, "rollback", _svc_params(service, version=target_version)
            )
        except Exception as e:
            return f"回滚 {service} 失败：{e}"

        if not result.success:
            return f"回滚 {service} 失败：{result.message}。请如实说明，不要声称已回滚。"

        data = result.data or {}
        return (
            f"回滚成功：{service} 镜像由 {data.get('fromImage', '?')} "
            f"回退至 {data.get('toImage', '?')}"
        )

    # ----- 6. 日志分析 -----
    @tool
    async def analyze_logs(service: str, lines: int = 100, keyword: str = "ERROR") -> str:
        """查询服务最近 N 行日志，并按关键字过滤"""
        await callback_service.send_step(
            task_id, f"正在拉取 {service} 最近 {lines} 行日志", "log_analysis"
        )
        try:
            result = await callback_service.execute_tool(
                task_id, "query_log", _svc_params(service, lines=lines, keyword=keyword)
            )
        except Exception as e:
            return f"拉取 {service} 日志失败：{e}"

        if not result.success:
            return f"拉取 {service} 日志失败：{result.message}"

        data = result.data or {}
        logs = data.get("logs") or "（无日志输出）"
        # lineCount 才是真实行数；logs 是字符串，len() 得到的是字符数
        line_count = data.get("lineCount", "?")
        source = data.get("source", "unknown")
        return f"日志分析结果（共 {line_count} 行，来源 {source}）：\n{logs[:2000]}"

    # ----- 7. Prometheus 指标查询 -----
    @tool
    async def query_metrics(query: str) -> str:
        """查询 Prometheus 监控指标（PromQL）。可查容器 CPU/内存、副本数、节点资源等，用于根因分析时获取真实数据。"""
        await callback_service.send_step(
            task_id, f"正在查询监控指标：{query}", "metrics_query"
        )
        try:
            result = await callback_service.execute_tool(
                task_id, "query_metrics", {"query": query, "promql": query}
            )
        except Exception as e:
            return f"查询指标失败：{e}"

        if not result.success:
            return f"查询指标失败：{result.message}"

        data = result.data or {}
        rows = data.get("result") or []
        if not rows:
            return f"指标查询无数据：{query}"
        # 每行是 {metric: {...}, value: "...", timestamp: ...}
        lines = []
        for row in rows[:20]:
            metric = row.get("metric") or {}
            value = row.get("value", "?")
            label = ", ".join(f"{k}={v}" for k, v in list(metric.items())[:5])
            lines.append(f"{label} -> {value}")
        return f"指标查询（{data.get('resultCount', len(rows))} 条）：\n" + "\n".join(lines)

    # ----- 8. Pod 事件查询（根因定位首选） -----
    @tool
    async def query_pod_events(service: str) -> str:
        """查询 Deployment 下 Pod 的 Kubernetes 事件（ImagePullBackOff、CrashLoopBackOff、OOMKilled、探针失败等），根因定位首选。"""
        await callback_service.send_step(
            task_id, f"正在查询 {service} 的 Pod 事件", "pod_events"
        )
        try:
            result = await callback_service.execute_tool(
                task_id, "query_pod_events", _svc_params(service)
            )
        except Exception as e:
            return f"查询 Pod 事件失败：{e}"

        if not result.success:
            return f"查询 Pod 事件失败：{result.message}"

        data = result.data or {}
        events = data.get("events") or []
        if not events:
            return f"服务 {service} 当前无异常事件，Pod 状态正常"
        # events 数组同时含 pod 摘要和真正的事件；事件行有 reason/message，pod 摘要有 phase/containers
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

    # ----- 9. 人工审批 -----
    @tool
    async def request_human_approval(operation: str, reason: str) -> str:
        """执行高风险操作前，请求人工确认"""
        await callback_service.send_step(
            task_id,
            f"请求人工确认：{operation}，原因：{reason}",
            "awaiting_approval",
        )
        try:
            result = await callback_service.execute_tool(
                task_id,
                "request_human_approval",
                {"operation": operation, "reason": reason, "contextId": task_id},
            )
        except Exception as e:
            return f"审批请求发送失败：{e}。请勿执行该高危操作。"

        if not result.success:
            return f"审批未通过或请求失败：{result.message}。请勿执行该高危操作。"

        data = result.data or {}
        if data.get("approved"):
            return (
                f"人工已批准（{data.get('mode', 'auto')} 模式，"
                f"请求号 {data.get('requestId', '-')}）：{operation}"
            )
        return f"人工未批准：{operation}。请勿执行该操作。"

    # ----- 10. 影响面分析（拓扑维度） -----
    @tool
    async def query_impact(service: str) -> str:
        """分析故障影响面：该服务的流量入口（Service）有哪些、是否已断流、哪些实例异常。用于判断故障影响范围与紧急程度，根因报告应包含这段结论。"""
        await callback_service.send_step(
            task_id, f"正在分析 {service} 的故障影响面", "impact_analysis"
        )
        try:
            result = await callback_service.execute_tool(
                task_id, "query_impact", _svc_params(service)
            )
        except Exception as e:
            return f"影响面分析失败：{e}"

        if not result.success:
            return f"影响面分析不可用：{result.message}"

        data = result.data or {}
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

    return [
        search_knowledge_base,
        execute_repair_action,
        get_service_status,
        scale_service,
        rollback_version,
        analyze_logs,
        query_metrics,
        query_pod_events,
        request_human_approval,
        query_impact,
    ]
