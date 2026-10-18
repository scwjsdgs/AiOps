import asyncio
import logging

from services.callback import callback_service

logger = logging.getLogger(__name__)

# 在飞任务注册表：同一个 taskId 只允许一个 LangGraph 状态图在跑。
# 这是「重复执行」问题的第一道闸门 —— 上游（Java）无论因为超时重发还是手工重试，
# 都不会让重启/扩容这类非幂等操作被执行两次。
# 审批挂起后图状态由 Redis Checkpointer 接管，Java resume 走 /api/agent/resume。
_inflight: dict[str, asyncio.Task] = {}


def submit(task_id: str, coro_factory) -> str:
    """提交一个后台任务，立即返回。

    返回 "accepted"（已受理）或 "duplicate"（同一 taskId 正在执行）。
    """
    existing = _inflight.get(task_id)
    if existing is not None and not existing.done():
        logger.warning(f"任务 {task_id} 仍在执行中，拒绝重复提交")
        return "duplicate"

    async def _runner():
        try:
            await coro_factory()
        except Exception as e:
            logger.exception(f"任务 {task_id} 执行失败: {e}")
            try:
                await callback_service.complete_task(task_id, f"执行失败：{e}", status="FAILED")
            except Exception as cb_err:
                logger.error(f"失败状态回传也失败了: {cb_err}")
        finally:
            _inflight.pop(task_id, None)

    _inflight[task_id] = asyncio.create_task(_runner())
    return "accepted"


def is_running(task_id: str) -> bool:
    task = _inflight.get(task_id)
    return task is not None and not task.done()
