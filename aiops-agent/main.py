from fastapi import FastAPI, HTTPException, Request, status
from fastapi.responses import StreamingResponse, JSONResponse
from fastapi.middleware.cors import CORSMiddleware
from pydantic import BaseModel
from models import Alert
from config import config
from agents.react_agent import run_agent_for_alert, run_react_loop
from services.callback import callback_service
from services.task_runner import submit
from rag.vector_store import get_vector_store
import logging
import json
import asyncio
import datetime
import time
import uuid

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


class StreamRequest(BaseModel):
    taskId: str
    message: str


# ============================================================
# 业务接口
# ============================================================

@app.post("/api/agent/start")
async def start_agent(request: StartRequest):
    """受理一个告警分析任务。

    立即返回 202，真正的推理在后台跑，最终报告通过
    /api/agent/callback/complete 回传。

    之所以不在这里 await 到推理结束：整个 ReAct 循环通常要几十秒到几分钟，
    远超 Java 侧的调用超时，会让 Java 以为"调用失败"而重发，
    导致同一个任务被重复执行（重启/扩容等危险操作被执行多次）。
    """
    task_id = request.taskId
    alert = Alert(
        id=task_id,
        severity="CRITICAL",
        title="告警分析",
        description=request.description,
        serviceName="unknown",
        host="unknown",
        detail="",
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
    await callback_service.send_step(task_id, "开始流式推理", "stream_start")

    async def event_generator():
        try:
            async for event in run_react_loop(task_id, user_msg):
                # event 本身不带换行，这里统一拼 SSE 帧
                yield f"data: {event}\n\n"
            yield f"data: {json.dumps({'type': 'end', 'data': '流式处理完成'}, ensure_ascii=False)}\n\n"
        except Exception as e:
            error_json = json.dumps({"type": "error", "data": str(e)}, ensure_ascii=False)
            yield f"data: {error_json}\n\n"

    return StreamingResponse(event_generator(), media_type="text/event-stream")


@app.get("/health")
async def health():
    return {"status": "healthy"}


@app.post("/api/agent/approve/{task_id}")
async def approve_operation(task_id: str, action: str = "approve"):
    """模拟人工审批接口，前端可调用此接口批准/拒绝高危操作（预留）"""
    return {"status": "approved", "task_id": task_id, "action": action}


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
    """服务启动时，启动后台巡检任务，并预热向量库"""
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
