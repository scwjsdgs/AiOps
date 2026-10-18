from fastapi import FastAPI, HTTPException, Request, status
from fastapi.responses import StreamingResponse, JSONResponse
from fastapi.middleware.cors import CORSMiddleware
from pydantic import BaseModel
from models import Alert
from config import config
from agents.graph.graph import run_agent_for_alert, run_react_loop, resume_graph
from services.callback import callback_service
from services.kafka_callback import kafka_tool_callback
from services.task_runner import submit
from rag.vector_store import get_vector_store
import logging
import json
import asyncio
import datetime
import time
import uuid
import hmac

# 延迟导入，避免知识库管理模块异常影响主链路
def _rag_modules():
    from rag import knowledge_manager, eval_metrics, chaos_metrics
    from rag.cost_tracker import cost_tracker
    return knowledge_manager, eval_metrics, chaos_metrics, cost_tracker

# ---------- 日志配置 ----------
logging.basicConfig(level=logging.INFO)
logger = logging.getLogger(__name__)

# ---------- FastAPI 实例 ----------
app = FastAPI(title="AIOps ReAct Agent with RAG")

# ============================================================
# 中间件区域（按顺序执行：先添加的先执行）
# ============================================================

# ---------- 1. CORS 跨域（必须放在最前面，让 OPTIONS 预检请求优先通过） ----------
app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],  # 生产环境请换成具体域名，如 ["https://your-frontend.com"]
    allow_credentials=True,
    allow_methods=["*"],
    allow_headers=["*"],
)


# ---------- 2. 请求追踪中间件（注入 X-Request-ID） ----------
@app.middleware("http")
async def add_request_id_middleware(request: Request, call_next):
    """
    为每个请求生成唯一 ID，方便串联 Java -> Python -> 前端的全链路日志。
    响应头会返回 X-Request-ID，便于前端/运维排查问题。
    """
    request_id = request.headers.get("X-Request-ID")
    if not request_id:
        request_id = f"req-{uuid.uuid4().hex[:12]}"
    request.state.request_id = request_id

    response = await call_next(request)
    response.headers["X-Request-ID"] = request_id
    return response


# ---------- 3. 性能监控与日志中间件 ----------
@app.middleware("http")
async def log_requests_middleware(request: Request, call_next):
    """
    记录每个请求的：路径、方法、耗时、状态码。
    这是生产环境排查慢接口的杀手锏。
    """
    start_time = time.perf_counter()

    # 读取请求体大小（不消费流）
    body = await request.body()
    logger.info(f"Request: {request.method} {request.url.path} | BodySize: {len(body)} bytes")

    response = await call_next(request)

    process_time = time.perf_counter() - start_time
    request_id = getattr(request.state, "request_id", "N/A")
    logger.info(
        f"Response: {request.method} {request.url.path} | "
        f"Status: {response.status_code} | "
        f"Duration: {process_time:.3f}s | "
        f"RequestID: {request_id}"
    )

    response.headers["X-Process-Time"] = str(process_time)
    return response


# ---------- 4. 全局异常捕获（放在最后，兜底所有未处理的异常） ----------
@app.exception_handler(Exception)
async def global_exception_handler(request: Request, exc: Exception):
    """
    捕获所有未处理的异常，返回统一的 JSON 格式错误信息。
    避免把 Python 堆栈直接暴露给前端（安全考虑）。
    """
    request_id = getattr(request.state, "request_id", "unknown")
    logger.error(f"Unhandled exception: {exc} | RequestID: {request_id}", exc_info=True)

    return JSONResponse(
        status_code=status.HTTP_500_INTERNAL_SERVER_ERROR,
        content={
            "success": False,
            "error": "Internal Server Error",
            "request_id": request_id,
            "message": "服务内部错误，请联系管理员"
        }
    )


# ============================================================
# 请求/响应模型
# ============================================================

class StartRequest(BaseModel):
    taskId: str
    description: str
    title: str = "告警分析"
    serviceName: str = "unknown"
    severity: str = "CRITICAL"
    host: str = "unknown"
    detail: str = ""


class StreamRequest(BaseModel):
    taskId: str
    message: str
    sessionId: str = ""


# ============================================================
# 业务接口
# ============================================================

@app.post("/api/agent/start")
async def start_agent(request: StartRequest):
    """受理一个告警分析任务。

    立即返回 202，真正的推理在后台跑，最终报告通过
    /api/agent/callback/complete 回传。

    之所以不在这里 await 到推理结束：整个 LangGraph 状态图通常要几十秒到几分钟，
    远超 Java 侧的调用超时，会让 Java 以为"调用失败"而重发，
    导致同一个任务被重复执行（重启/扩容等危险操作被执行多次）。
    """
    task_id = request.taskId
    alert = Alert(
        id=task_id,
        severity=request.severity,
        title=request.title,
        description=request.description,
        serviceName=request.serviceName,
        host=request.host,
        detail=request.detail,
    )
    logger.info(f"收到 agent 启动请求，task={task_id}")

    if submit(task_id, lambda: run_agent_for_alert(task_id, alert)) == "duplicate":
        logger.warning(f"任务 {task_id} 正在执行中，拒绝重复受理")
        raise HTTPException(status_code=409, detail=f"任务 {task_id} 正在执行中")

    return JSONResponse(
        status_code=status.HTTP_202_ACCEPTED,
        content={"status": "accepted", "task_id": task_id},
    )


@app.post("/api/agent/stream")
async def stream_agent(request: StreamRequest):
    task_id = request.taskId
    user_msg = request.message
    session_id = (request.sessionId or "").strip() or task_id
    await callback_service.send_step(task_id, "开始流式推理", "stream_start")

    async def event_generator():
        try:
            # ChatOps 直接把 session_id 作为 LangGraph thread_id：同一会话
            # 的历史状态从 Checkpointer 恢复，跨页面/刷新也可继续追问。
            async for event in run_react_loop(task_id, user_msg, thread_id=session_id):
                # event 本身不带换行，这里统一拼 SSE 帧
                yield f"data: {event}\n\n"
                data = json.loads(event)
                # LangGraph interrupt 挂起后，额外推一个兼容旧前端的 action 事件，
                # 避免 ChatOps 页面只认识 action/final 而把挂起状态忽略。
                if data.get("type") == "awaiting_approval":
                    await callback_service.send_step(task_id, "等待人工审批（LangGraph 已挂起）", "awaiting_approval")
                    yield f"data: {json.dumps({'type': 'action', 'data': {'tool': 'request_human_approval', 'args': data.get('data', {})}}, ensure_ascii=False)}\n\n"
            yield f"data: {json.dumps({'type': 'end', 'data': '流式处理完成'}, ensure_ascii=False)}\n\n"
        except Exception as e:
            from langgraph.errors import GraphRecursionError
            message = str(e) or "未知错误"
            if isinstance(e, GraphRecursionError):
                message = "大模型推理进入死循环，已自动终止。"
            elif "429" in message or "rate limit" in message.lower():
                message = "大模型服务暂不可用（限流），请稍后重试。"
            logger.exception(f"ChatOps 流式处理失败 task={task_id}: {e}")
            error_json = json.dumps({"type": "error", "content": message}, ensure_ascii=False)
            yield f"data: {error_json}\n\n"
            end_json = json.dumps({"type": "end", "content": "处理已结束"}, ensure_ascii=False)
            yield f"data: {end_json}\n\n"

    return StreamingResponse(event_generator(), media_type="text/event-stream")


# ============================================================
# ChatOps 多会话
# ============================================================

@app.get("/api/chatops/sessions/{session_id}")
async def chatops_get_session(session_id: str, username: str = "unknown"):
    """拉取指定会话的历史消息，供前端切换会话后渲染。

    历史消息从 LangGraph checkpoint 恢复：包括 messages、evidence 与最终报告。
    用户归属校验由 Java 代理层完成（按 JWT username + Redis 会话索引）。
    """
    from agents.graph.graph import _load_graph_state
    state = await _load_graph_state(session_id) or {}
    rows = state.get("messages") or []
    messages = []
    report_text = (state.get("report") or "").strip()
    for msg in rows:
        mtype = getattr(msg, "type", "")
        content = getattr(msg, "content", "") or ""
        if not content:
            continue
        messages.append({
            "role": "assistant" if mtype == "ai" else "user",
            "content": str(content),
        })
    # 轮次报告没有进入全局 messages，历史列表直接用它补齐助手回答
    if report_text and not any(m.get("role") == "assistant" for m in messages):
        messages.append({"role": "assistant", "content": report_text})
    history = {
        "session_id": session_id,
        "messages": messages,
        "summary": state.get("previous_summary") or "",
        "updated_at": state.get("completed_at") or "",
    }
    return {"ok": True, "data": history}


@app.get("/health")
async def health():
    return {"status": "healthy"}


# ============================================================
# RAG 知识库管理闭环 API
# ============================================================

class KnowledgeUploadRequest(BaseModel):
    content: str
    filename: str
    operator: str = "system"
    version: str = ""
    quality_score: float = 0.0
    summary: str = ""


class KnowledgeRollbackRequest(BaseModel):
    source: str
    target_version: str = ""
    operator: str = "system"


class QualityUpdateRequest(BaseModel):
    source: str
    score: float
    note: str = ""


class RagevalRequest(BaseModel):
    dataset: list = None
    llm_judge: bool = False
    k: int = 3


@app.get("/api/rag/knowledge/overview")
async def rag_knowledge_overview():
    km, *_ = _rag_modules()
    try:
        return await asyncio.to_thread(km.overview)
    except Exception as e:
        return {"ok": False, "error": str(e)}


@app.post("/api/rag/knowledge/upload")
async def rag_knowledge_upload(req: KnowledgeUploadRequest):
    km, *_ = _rag_modules()
    try:
        return await asyncio.to_thread(
            km.upload_document, req.content, req.filename,
            req.operator, req.version, req.quality_score, req.summary)
    except Exception as e:
        return {"ok": False, "error": str(e)}


@app.get("/api/rag/knowledge/documents")
async def rag_knowledge_documents():
    km, *_ = _rag_modules()
    try:
        return {"ok": True, "documents": await asyncio.to_thread(km.list_documents)}
    except Exception as e:
        return {"ok": False, "error": str(e)}


@app.get("/api/rag/knowledge/detail")
async def rag_knowledge_detail(source: str):
    km, *_ = _rag_modules()
    try:
        return await asyncio.to_thread(km.document_detail, source)
    except Exception as e:
        return {"ok": False, "error": str(e)}


@app.post("/api/rag/knowledge/rollback")
async def rag_knowledge_rollback(req: KnowledgeRollbackRequest):
    km, *_ = _rag_modules()
    try:
        return await asyncio.to_thread(km.rollback, req.source, req.target_version, req.operator)
    except Exception as e:
        return {"ok": False, "error": str(e)}


@app.post("/api/rag/knowledge/quality")
async def rag_quality_update(req: QualityUpdateRequest):
    km, *_ = _rag_modules()
    try:
        return {"ok": await asyncio.to_thread(km.update_quality, req.source, req.score, req.note)}
    except Exception as e:
        return {"ok": False, "error": str(e)}


@app.post("/api/rag/knowledge/rebuild")
async def rag_knowledge_rebuild(operator: str = "system"):
    km, *_ = _rag_modules()
    try:
        return await asyncio.to_thread(km.rebuild_vector_store, operator)
    except Exception as e:
        return {"ok": False, "error": str(e)}


@app.post("/api/rag/knowledge/delete")
async def rag_knowledge_delete(source: str, operator: str = "system"):
    km, *_ = _rag_modules()
    try:
        return await asyncio.to_thread(km.delete_document, source, operator)
    except Exception as e:
        return {"ok": False, "error": str(e)}


@app.get("/api/rag/knowledge/audit")
async def rag_knowledge_audit(limit: int = 200):
    km, *_ = _rag_modules()
    return {"ok": True, "audit": await asyncio.to_thread(km.list_audit, limit)}


@app.post("/api/rag/knowledge/retry")
async def rag_knowledge_retry():
    km, *_ = _rag_modules()
    try:
        return await asyncio.to_thread(km.retry_failed, "system")
    except Exception as e:
        return {"ok": False, "error": str(e)}


# ---------------- RAG 分层评估 / 调试 ----------------

@app.post("/api/rag/evaluate")
async def rag_evaluate(req: RagevalRequest):
    _, em, _, _ = _rag_modules()
    try:
        if req.dataset:
            dataset = req.dataset
        else:
            import os
            path = os.path.join(os.path.dirname(os.path.abspath(__file__)), "rag", "eval_dataset.json")
            with open(path, "r", encoding="utf-8") as f:
                dataset = json.load(f)
        result = await asyncio.to_thread(
            em.run_eval_from_dataset, dataset, req.llm_judge, req.k)
        return {"ok": True, "metrics": result}
    except Exception as e:
        return {"ok": False, "error": str(e)}


@app.post("/api/rag/debug")
async def rag_debug(query: str = "", k: int = 3):
    """RAG 检索调试：返回原始 Query、各阶段耗时与最终结果。"""
    if not query.strip():
        return {"ok": False, "error": "query 不能为空"}
    try:
        started = time.perf_counter()
        from rag.vector_store import hybrid_search, _bm25_search, _vector_search, _rrf_fuse, _rerank
        raw = query.strip()
        bm25 = await asyncio.to_thread(_bm25_search, raw, k=20)
        vector = await asyncio.to_thread(_vector_search, raw, k=20)
        fused = _rrf_fuse(vector, bm25, k=60, top_n=20)
        final = await asyncio.to_thread(_rerank, raw, fused, k)
        return {
            "ok": True,
            "query": raw,
            "stage_metrics": {
                "bm25_count": len(bm25),
                "vector_count": len(vector),
                "rrf_count": len(fused),
                "rerank_count": len(final),
                "total_ms": round((time.perf_counter() - started) * 1000, 2),
            },
            "results": [{"content": c, "metadata": m, "score": s} for c, m, s in final],
        }
    except Exception as e:
        return {"ok": False, "error": str(e)}


# ---------------- 成本与性能 / 混沌评测 ----------------

@app.get("/api/llm/cost")
async def llm_cost():
    _, _, _, ct = _rag_modules()
    return {"ok": True, "usage": ct.usage()}


@app.post("/api/llm/route")
async def llm_route(model: str = "", prompt: str = "", stage: str = "unknown"):
    """轻量模型路由入口：匹配配置模型，记录调用，返回可用性。

    真正接入时从 this 转发到对应 LLM；当前作为可观测与预算统计的示例。
    """
    _, _, _, ct = _rag_modules()
    model = model or config.LLM_MODEL
    # 只做路由可用性判断，不实际调用外部 LLM（避免 token 消耗）
    cached = ct.cache_get(model, prompt)
    status = "cache_hit" if cached is not None else "route_available"
    ct.record_call(model, prompt, cached or "", 0, 0, 1, stage)
    return {"ok": True, "model": model, "status": status,
            "route": "primary" if model in (config.LLM_MODEL, "deepseek-flash") else "fallback"}


@app.get("/api/chaos/run")
async def chaos_run(llm_judge: bool = False):
    _, _, cm, _ = _rag_modules()
    return {"ok": True, **await asyncio.to_thread(cm.run_chaos_test, llm_judge)}


@app.get("/api/chaos/adversarial")
async def chaos_adversarial(llm_judge: bool = False):
    _, _, cm, _ = _rag_modules()
    return {"ok": True, **await asyncio.to_thread(cm.run_adversarial_test, llm_judge)}


@app.post("/api/agent/approve/{task_id}")
async def approve_operation(task_id: str, action: str = "approve"):
    """模拟人工审批接口，前端可调用此接口批准/拒绝高危操作（预留）"""
    return {"status": "approved", "task_id": task_id, "action": action}


class ResumeRequest(BaseModel):
    requestId: str = ""
    approved: bool = False
    note: str = ""


@app.post("/api/agent/resume/{task_id}")
async def resume_agent_task(request: Request, task_id: str, req: ResumeRequest):
    """Java 审批决定后调用，恢复被 interrupt() 挂起的 LangGraph 任务。

    安全边界：resume 能继续高危操作，必须校验 Java 内部令牌，
    且 requestId 必须与当前挂起中的审批单匹配，防止任意客户端唤醒/伪造审批。
    """
    token = request.headers.get("X-Internal-Token", "")
    if not hmac.compare_digest(token, config.JAVA_INTERNAL_TOKEN):
        raise HTTPException(status_code=401, detail="invalid internal token")

    try:
        result = await resume_graph(task_id, req.model_dump())
        return {"status": "resumed", "task_id": task_id, "approved": req.approved}
    except HTTPException:
        raise
    except ValueError as e:
        logger.warning(f"resume 校验失败 task={task_id}: {e}")
        raise HTTPException(status_code=400, detail=str(e))
    except Exception as e:
        logger.exception(f"恢复任务失败 task={task_id}")
        raise HTTPException(status_code=500, detail=f"resume failed: {e}")


# ============================================================
# 定时主动巡检
# ============================================================

def _build_health_check_alert(task_id: str, description: str) -> Alert:
    return Alert(
        id=task_id,
        severity="INFO",
        title="健康检查",
        description=description,
        serviceName="all",
        host="unknown",
        detail="",
    )


async def scheduled_health_check():
    """
    后台定时巡检任务。

    间隔策略：
    - HEALTH_CHECK_ENABLED=false 时不启动（调用方判断）。
    - HEALTH_CHECK_INTERVAL_HOURS > 0：按该间隔周期性巡检（如 1 小时一次，便于演示/高密场景）。
    - 否则维持默认：每天 8:00 整点巡检一次。
    """
    interval_hours = config.HEALTH_CHECK_INTERVAL_HOURS
    while True:
        now = datetime.datetime.now()
        if interval_hours > 0:
            wait_seconds = interval_hours * 3600
            logger.info(f"周期性巡检：间隔 {interval_hours} 小时一次")
        else:
            target = now.replace(hour=8, minute=0, second=0, microsecond=0)
            if now >= target:
                target += datetime.timedelta(days=1)
            wait_seconds = (target - now).total_seconds()
            logger.info(f"距离下次巡检还有 {wait_seconds / 3600:.2f} 小时")
        await asyncio.sleep(wait_seconds)

        task_id = f"health_check_{datetime.datetime.now().strftime('%Y%m%d_%H%M%S')}"
        logger.info(f"开始执行主动巡检，task_id={task_id}")
        try:
            alert = _build_health_check_alert(
                task_id,
                "全服务健康检查（主动巡检）：请依次检查各核心服务的状态、就绪副本数、异常 Pod 事件与资源使用情况，"
                "对异常服务给出根因推断和修复建议，一切高危修复动作（回滚/大比例扩缩容）必须先申请人工审批。",
            )
            submit(task_id, lambda: run_agent_for_alert(task_id, alert))
        except Exception as e:
            logger.error(f"巡检失败: {e}")


@app.on_event("startup")
async def startup_event():
    """服务启动时，启动 Kafka 工具通道、后台巡检任务，并预热向量库"""
    await kafka_tool_callback.start()
    if config.HEALTH_CHECK_ENABLED:
        asyncio.create_task(scheduled_health_check())
        interval = config.HEALTH_CHECK_INTERVAL_HOURS
        logger.info(
            f"主动巡检后台任务已启动，{'每 ' + str(interval) + ' 小时一次' if interval > 0 else '每天 8:00'} 执行。"
        )
    else:
        logger.info("主动巡检已通过 HEALTH_CHECK_ENABLED=false 关闭。")
    # 预热向量库：放到线程里跑，不阻塞启动；失败只记日志（get_vector_store 内部已兜底）
    asyncio.create_task(asyncio.to_thread(get_vector_store))


@app.on_event("shutdown")
async def shutdown_event():
    logger.info("服务关闭，停止 Kafka 工具调用通道")
    await kafka_tool_callback.stop()


@app.post("/api/agent/trigger_health_check")
async def trigger_health_check():
    """手动触发一次全服务健康检查"""
    task_id = f"manual_check_{datetime.datetime.now().strftime('%Y%m%d_%H%M%S')}"
    alert = _build_health_check_alert(task_id, "手动触发的全服务健康检查")

    if submit(task_id, lambda: run_agent_for_alert(task_id, alert)) == "duplicate":
        raise HTTPException(status_code=409, detail="同名巡检任务正在执行中")

    return {"status": "triggered", "task_id": task_id}


# ============================================================
# 启动入口
# ============================================================

if __name__ == "__main__":
    import uvicorn

    uvicorn.run(app, host="0.0.0.0", port=5000)
