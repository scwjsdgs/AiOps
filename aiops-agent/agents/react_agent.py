import json
import asyncio
from typing import AsyncGenerator
from langchain_openai import ChatOpenAI
from langchain_core.messages import HumanMessage, ToolMessage, SystemMessage
from config import config
from tools.agent_tools import create_tools
from agents.memory_manager import memory_manager
from agents.agent_state import AgentState, save_state, suspend_for_approval, clear_state, load_state
from observability.langfuse_client import get_langfuse_callback_handler, finalize as langfuse_finalize
from services.callback import callback_service
from models import Alert

# 计数上限只针对指标查询：它是这类死循环里最高频的一个（模型会不断换 PromQL 写法），
# 其余只读工具靠下面的「同参数去重」+「剩余轮次收敛指令」兜住，无需逐一计数。
METRICS_TOOL_NAME = "query_metrics"

SYSTEM_PROMPT = """你是一位资深的 SRE 运维专家，拥有丰富的工具可调用。请严格遵循 ReAct 推理模式：

**可用工具清单**：
1. search_knowledge_base：查询内部故障手册/SOP
2. get_service_status：查看服务当前运行状态（副本数、就绪情况、镜像版本）
3. analyze_logs：获取服务最近错误日志
4. query_pod_events：查询 Deployment 下 Pod 的 K8s 事件（ImagePullBackOff、CrashLoopBackOff、OOMKilled、探针失败），根因定位优先
5. query_metrics：查询 Prometheus 监控指标（PromQL），可查 CPU/内存/副本数等真实数据
6. execute_repair_action：执行重启（restart）、清理缓存（clear_cache）等常规动作
7. scale_service：调整实例数量（扩缩容）
8. rollback_version：回滚服务版本（高危！需谨慎）
9. request_human_approval：执行高风险操作前先申请人工确认
10. query_impact：分析故障影响面（流量入口有哪些、是否已断流、哪些实例异常），用于判断影响范围与紧急程度

**Prometheus 查询模板**（调用 query_metrics 时优先套用，避免自编 PromQL 出错）：
- 就绪副本数：kube_deployment_status_replicas_available{deployment="<服务名>"}
- 期望副本数：kube_deployment_spec_replicas{deployment="<服务名>"}
- Pod 重启次数：kube_pod_container_status_restarts_total{pod=~"<服务名>-.*"}
- 容器 CPU：sum(rate(container_cpu_usage_seconds_total{pod=~"<服务名>-.*",container!="POD"}[3m])) by (pod)
- 容器内存：sum(container_memory_working_set_bytes{pod=~"<服务名>-.*",container!="POD"}) by (pod)
把 <服务名> 替换为实际服务名即可。

**推理循环格式**：
Thought: 分析当前告警，决定如何排查。
Action: 选择一个工具，并传入参数。
Observation: 观察工具返回结果，判断是否解决问题。
重复直到根因明确且修复完成，最后输出包含根因、操作步骤、验证结果的总结报告。

**反重复与推进约束（最高优先级，必须严格遵守）**：
- 你在进行分析时，最多执行 {max_metrics} 次指标查询（query_metrics）。如果你已经明确了故障现象，必须立刻给出结论，或者调用 execute_repair_action（restart_service）等工具尝试修复。禁止无限度地查询指标。
- 同一个工具、同一组参数，禁止重复调用第二次。如果一次查询没有返回有用信息，换一个思路或直接下结论，不要用略有差异的 PromQL 反复试探。
- 禁止用「查询来确认上一步的查询结果」的方式推进。工具的返回就是事实，不需要二次验证同一个指标。
- 排查总步数控制在 6 步以内。达到 6 步时，无论是否查全，都必须给出结论并推进到修复或总结。
- 拿到了明确的故障现象（例如副本 0/2、Pod 处于 ImagePullBackOff/CrashLoopBackOff、容器反复重启），就已经足够下结论，不要再继续采集更多指标来"佐证"。

**重要安全规则**：
- 在执行 rollback_version，或执行 scale_service 且实例数变化超过 50% 时，必须先调用 request_human_approval 获取批准。判断比例前，先用 get_service_status 拿到当前副本数。
- 修复完成后，务必调用 get_service_status 验证服务已恢复正常。
- 工具返回失败时，禁止在最终报告里声称操作成功；必须如实说明失败原因和当前状态。
- 如果知识库和日志都查不到线索，请基于常识给出稳妥建议。

**影响面分析要求**：
- 定位到根因后、输出最终报告前，必须调用一次 query_impact 确认影响范围（尤其当故障涉及某个 Deployment 时）。
- 最终报告必须包含「影响面」一节，至少写明：受影响的流量入口（Service）、是否已断流、异常实例数量。判断紧急程度和是否需要立即介入，依据的是流量有没有中断，而不只是 Pod 有没有重启。
- 如果 query_impact 显示已断流（outage=true），应在报告开头显著提示「业务流量已中断，需优先恢复入口可用性」。
"""


async def run_react_loop(
    task_id: str,
    user_input: str,
    max_iterations: int = None,
) -> AsyncGenerator[str, None]:
    """流式执行 ReAct 循环，逐条产出 SSE 事件（JSON 字符串，不含换行）。

    换行由传输层（main.py）统一负责，这里只产出事件本身。
    """
    if max_iterations is None:
        max_iterations = config.AGENT_MAX_ITERATIONS

    memory = memory_manager.get_memory(task_id)
    langfuse_cb = get_langfuse_callback_handler()
    llm = ChatOpenAI(
        model=config.LLM_MODEL,
        openai_api_key=config.LLM_API_KEY,
        openai_api_base=config.LLM_BASE_URL,
        temperature=0,
        # 关掉流式：部分 OpenAI 兼容网关在 streaming 下对 tool_calls 的增量分片
        # 处理不完整，会导致工具调用丢失。这里的"流式"本就是逐事件而非逐 token。
        streaming=False,
        timeout=config.LLM_TIMEOUT_SECONDS,
        max_retries=config.LLM_MAX_RETRIES,
        callbacks=[langfuse_cb] if langfuse_cb else None,
    )
    tools = create_tools(task_id)
    # 显式状态机：核心意图就是本次输入；后续检索/工具/审批/报告都记录在这里。
    state = AgentState(task_id=task_id, core_intent=user_input[:200])

    # 关键：必须把工具声明绑定到 LLM 上。
    # 不绑定的话，请求里没有任何 tools 声明，网关永远不会返回 tool_calls，
    # 下面的循环会退化成「LLM 直接输出一段散文就结束」，所有工具形同虚设。
    llm_with_tools = llm.bind_tools(tools) if tools else llm
    tools_dict = {t.name: t for t in tools if getattr(t, "name", None)}

    history = memory.chat_memory.messages if memory else []
    messages = (
        # 用配置值填入 Prompt 的 {max_metrics}：提示里的数字必须和代码里的硬上限一致，
        # 否则调了配置却只改了拦截、没改提示，模型会一直撞墙。
        [SystemMessage(content=SYSTEM_PROMPT.replace("{max_metrics}", str(config.AGENT_MAX_METRICS_QUERIES)))]
        + list(history)
        + [HumanMessage(content=user_input)]
    )

    iteration = 0
    final_answer = None

    # ---- 死循环防护状态 ----
    # 纯 Prompt 约束靠不住：模型可以完全无视"最多查 3 次"继续查满 8 轮，
    # 最后连报告都产出不了（只剩一句"达到最大迭代次数"）。必须在代码层硬拦。
    metrics_query_count = 0                      # query_metrics 已调用次数
    call_signatures = {}                         # "工具名|参数" -> 次数，用于同调用去重
    observations_log = []                        # 观测记录，兜底报告要用

    while iteration < max_iterations:
        response = await llm_with_tools.ainvoke(messages)
        ai_msg = response
        messages.append(ai_msg)

        content = ai_msg.content
        tool_calls = getattr(ai_msg, "tool_calls", None) or []

        if content:
            yield json.dumps({"type": "thought", "data": content}, ensure_ascii=False)

        if not tool_calls:
            final_answer = content
            yield json.dumps({"type": "final", "data": final_answer}, ensure_ascii=False)
            break

        for tc in tool_calls:
            tool_name = tc["name"]
            tool_args = tc["args"]
            yield json.dumps(
                {"type": "action", "data": {"tool": tool_name, "args": tool_args}},
                ensure_ascii=False,
            )

            # ---- 拦截 1：指标查询次数上限 ----
            # 到顶后不再执行，只回注一条提示，逼模型下结论或转修复。
            if tool_name == METRICS_TOOL_NAME and metrics_query_count >= config.AGENT_MAX_METRICS_QUERIES:
                observation = (
                    f"已达指标查询上限（{config.AGENT_MAX_METRICS_QUERIES} 次），本次调用未执行。"
                    "你已掌握足够信息。现在必须二选一："
                    "① 直接输出最终结论报告；② 若判断需要修复，调用 execute_repair_action 执行 restart。"
                    "禁止再请求任何指标查询。"
                )
                yield json.dumps({"type": "warning", "data": observation}, ensure_ascii=False)
                yield json.dumps({"type": "observation", "data": observation}, ensure_ascii=False)
                messages.append(ToolMessage(content=observation, tool_call_id=tc["id"]))
                continue

            # ---- 拦截 2：完全相同的调用去重 ----
            # 参数一致说明场景没变，重跑一次结果必然相同，纯属原地打转。
            try:
                sig = f"{tool_name}|{json.dumps(tool_args, sort_keys=True, ensure_ascii=False)}"
            except (TypeError, ValueError):
                sig = f"{tool_name}|{tool_args}"

            if sig in call_signatures and call_signatures[sig] >= config.AGENT_MAX_SAME_CALLS:
                observation = (
                    f"重复调用被拒绝：{tool_name} 已用相同参数调用过 "
                    f"{call_signatures[sig]} 次，结果不会改变。请换一个思路，"
                    "或直接给出结论 / 推进到修复动作。"
                )
                yield json.dumps({"type": "warning", "data": observation}, ensure_ascii=False)
                yield json.dumps({"type": "observation", "data": observation}, ensure_ascii=False)
                messages.append(ToolMessage(content=observation, tool_call_id=tc["id"]))
                continue

            if tool_name == METRICS_TOOL_NAME:
                metrics_query_count += 1

            tool_func = tools_dict.get(tool_name)
            if tool_func is None:
                observation = f"未知工具: {tool_name}，请改用可用工具清单中的工具。"
                yield json.dumps({"type": "error", "data": observation}, ensure_ascii=False)
            else:
                call_signatures[sig] = call_signatures.get(sig, 0) + 1
                # ---- 状态机维护：记录工具调用意图（在真正执行前持久化） ----
                state.current_subtask = f"执行 {tool_name}"
                state.start_tool(tool_name, tool_args)
                # 人工审批工具触发前，把 AgentState 挂起保存到 Redis，
                # 供审批结束后的断点续传使用（fail-safe，保存失败不中断主流程）。
                if tool_name == "request_human_approval":
                    suspend_for_approval(
                        state,
                        {"operation": tool_args.get("operation", ""),
                         "reason": tool_args.get("reason", "")},
                    )
                try:
                    observation = await tool_func.ainvoke(tool_args)
                except Exception as e:
                    observation = f"工具执行失败: {str(e)}"
                    yield json.dumps({"type": "error", "data": observation}, ensure_ascii=False)
                state.finish_tool(tool_name)
                state.add_step(f"{tool_name} 完成")

            yield json.dumps(
                {"type": "observation", "data": str(observation)}, ensure_ascii=False
            )
            messages.append(ToolMessage(content=str(observation), tool_call_id=tc["id"]))
            observations_log.append(f"[{tool_name}] {str(observation)[:400]}")
            # 每轮结束持久化状态（含已完成步骤），便于审计/断点恢复
            save_state(state)

        iteration += 1

        # ---- 拦截 3：收尾阶段强制收敛 ----
        # 只剩最后一轮时，明确告诉模型"不能再查了"，把这一轮留给结论。
        remaining = max_iterations - iteration
        if remaining == 1:
            messages.append(SystemMessage(content=(
                "【系统指令】这是最后一轮。禁止再调用任何查询类工具，"
                "必须立刻输出最终结论文档，包含：根因、已执行的操作（或为何未执行修复）、"
                "当前服务状态、以及后续建议。"
            )))

    # ---- 迭代耗尽：用已收集的证据生成保底报告，而不是只丢一句"达到上限" ----
    if final_answer is None:
        final_answer = _build_fallback_report(
            max_iterations, metrics_query_count, observations_log
        )
        yield json.dumps({"type": "final", "data": final_answer}, ensure_ascii=False)

    memory.chat_memory.add_user_message(user_input)
    memory.chat_memory.add_ai_message(final_answer or "")
    memory_manager.save_memory(task_id)
    # 把观测证据写进状态机上下文，供 Reflection / 断点续传使用
    state.context["observations"] = observations_log[-20:]
    state.completed_steps.append("报告生成")
    save_state(state)


def _build_fallback_report(
    max_iterations: int, metrics_query_count: int, observations: list
) -> str:
    """迭代耗尽时的兜底报告。

    改造前这里只返回「达到最大迭代次数（8），未能完成处理。」——运维人员拿到的
    是一句无信息量的失败，前面几步真实查到的东西全丢了，等于白跑。
    这里把已收集的观测整理成报告，至少让任务有产出、可审计。

    注意措辞必须诚实：这是「分析未收敛」的中止，不能包装成诊断结论。
    """
    lines = [
        "## 分析未收敛（已达迭代上限）",
        "",
        f"本次分析在执行 {max_iterations} 轮后仍未得出最终结论，已中止。",
        f"期间共执行 {metrics_query_count} 次指标查询。",
        "",
        "**这不是一个诊断结论**，仅汇总本次已收集到的原始证据，供人工判断：",
        "",
    ]

    if observations:
        # 只保留最近若干条：太多会把报告淹没，且早期的多半是重复查询
        for obs in observations[-8:]:
            lines.append(f"- {obs}")
    else:
        lines.append("- （未收集到有效观测数据）")

    lines += [
        "",
        "**建议**：该服务需要人工介入排查。可能原因包括查询方向反复摇摆、"
        "工具返回信息不足、或告警本身缺乏可定位的异常特征。",
    ]
    return "\n".join(lines)


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


async def run_agent_for_alert(task_id: str, alert: Alert) -> str:
    """非流式入口：跑完整个 ReAct 循环并把最终报告回传给 Java。"""
    user_input = (
        f"服务 {alert.serviceName} 发生告警：{alert.title} - {alert.description}"
        f"，详情：{alert.detail or '无'}"
    )
    final = None

    try:
        async with asyncio.timeout(config.AGENT_TIMEOUT_SECONDS):
            async for event in run_react_loop(task_id, user_input):
                data = json.loads(event)
                if data.get("type") == "final":
                    final = data.get("data")
                    break
    except TimeoutError:
        final = f"处理超时（超过 {config.AGENT_TIMEOUT_SECONDS} 秒），未能完成分析。"
        await callback_service.complete_task(task_id, final, status="FAILED")
        return final

    if final is None:
        final = "处理失败，未获得最终报告。"

    # ---- Reflection 防幻觉：结合检索原文/观测审阅，PASS 才回调 Java ----
    from agents.reflection import reflect_on_report
    state = load_state(task_id) or AgentState(task_id=task_id, core_intent=user_input[:200])
    observations_log = state.context.get("observations") or []
    evidence = "\n".join(observations_log[-10:]) if observations_log else ""
    review = reflect_on_report(final, evidence)
    if not review["passed"]:
        # 审阅不过：把模型指出的错误回注，重跑一轮“修正版”报告
        logger.warning(f"Reflection 未通过，尝试修订: {review['feedback']}")
        final = _revise_report_with_feedback(final, review["feedback"], evidence)
        review2 = reflect_on_report(final, evidence)
        if not review2["passed"]:
            logger.warning("Reflection 二次仍未通过，按 FAILED 回传")
            await callback_service.complete_task(
                task_id, final, status="FAILED",
                service_name=alert.serviceName or "",
                alert_name=alert.title or "",
            )
            clear_state(task_id)
            langfuse_finalize()
            return final

    # 携带 serviceName/title 一并入库，让案例库 metadata 有真实服务名
    await callback_service.complete_task(
        task_id,
        final,
        service_name=alert.serviceName or "",
        alert_name=alert.title or "",
    )
    clear_state(task_id)
    langfuse_finalize()
    return final
