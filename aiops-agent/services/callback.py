import asyncio
from datetime import datetime

import httpx

from models import AgentMessage, ToolExecutionRequest, ToolExecutionResult
from config import config


class CallbackService:
    """Python Agent -> Java 后端的回调通道。

    所有请求都带 X-Internal-Token，Java 侧过滤器据此放行回调接口。
    超时统一为 连接 5s / 读取 60s / 写入 10s —— 读取必须够长，
    因为 restart_service 这类工具在 Java 侧本身就要跑好几秒。
    """

    def __init__(self):
        self.base_url = config.JAVA_BASE_URL
        self._timeout = httpx.Timeout(
            connect=config.HTTP_CONNECT_TIMEOUT,
            read=config.HTTP_READ_TIMEOUT,
            write=config.HTTP_WRITE_TIMEOUT,
            pool=config.HTTP_CONNECT_TIMEOUT,
        )

    @property
    def _headers(self) -> dict:
        return {"X-Internal-Token": config.JAVA_INTERNAL_TOKEN}

    async def send_step(self, task_id: str, content: str, step_name: str = None):
        message = AgentMessage(
            type="STEP",
            taskId=task_id,
            content=content,
            stepName=step_name,
        )
        payload = message.model_dump()
        # Java 侧 AgentMessage.timestamp 是 LocalDateTime，前端实时页要显示这一列
        payload["timestamp"] = datetime.now().isoformat()

        async with httpx.AsyncClient(timeout=self._timeout) as client:
            try:
                await client.post(
                    f"{self.base_url}/api/agent/callback/step",
                    json=payload,
                    headers=self._headers,
                )
            except Exception as e:
                print(f"回调失败（可忽略）: {e}")

    async def execute_tool(self, task_id: str, tool_name: str, parameters: dict) -> ToolExecutionResult:
        request = ToolExecutionRequest(
            toolName=tool_name,
            parameters=parameters,
            contextId=task_id,
        )
        async with httpx.AsyncClient(timeout=self._timeout) as client:
            resp = await client.post(
                f"{self.base_url}/api/agent/callback/tool",
                json=request.model_dump(),
                headers=self._headers,
            )
            resp.raise_for_status()
            body = resp.json()

            if body.get("code") != 200:
                return ToolExecutionResult(
                    success=False,
                    message=f"Java 返回错误：{body.get('message')}",
                    data={},
                )

            data = body.get("data") or {}
            return ToolExecutionResult(
                success=bool(data.get("success", False)),
                message=data.get("message") or "",
                # 始终给 dict，避免调用方 .get() 时踩到 None
                data=data.get("data") or {},
            )

    async def complete_task(self, task_id: str, result: str, status: str = "SUCCESS"):
        """回传最终报告。

        报告可能上万字且含换行、&、#，必须走 JSON body。
        之前拼在 query string 里，轻则被 # 截断，重则超出请求行长度上限被拒。
        """
        async with httpx.AsyncClient(timeout=self._timeout) as client:
            try:
                resp = await client.post(
                    f"{self.base_url}/api/agent/callback/complete/{task_id}",
                    json={"result": result, "status": status},
                    headers=self._headers,
                )
                resp.raise_for_status()
            except Exception as e:
                print(f"完成回调失败: {e}")

        # 自学习闭环：报告回传成功后，异步把它沉淀进案例库。
        # 这是增强不是主链路——入库失败绝不影响回调本身，也不阻塞返回。
        try:
            from rag.case_store import save_case
            await asyncio.to_thread(save_case, result, "", "", status)
        except Exception as e:
            print(f"案例入库失败（可忽略）: {e}")


callback_service = CallbackService()
