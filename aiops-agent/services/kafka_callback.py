import asyncio
import json
import logging
import uuid
from typing import Optional

from aiokafka import AIOKafkaConsumer, AIOKafkaProducer

from config import config
from models import ToolExecutionResult

logger = logging.getLogger(__name__)


class KafkaToolCallback:
    """Python AI 大脑到 Java 的 Kafka 工具调用通道。

    双通道兼容设计：
    - CALLBACK_MODE=kafka（默认）：生成 requestId，发送到 agent-tool-request，
      异步等待 agent-tool-result 中同 requestId 的结果。
    - CALLBACK_MODE=http：完全走原有 HTTP 回调。
    - Kafka 连接失败、发送失败、结果超时等异常都会触发 HTTP 降级，
      不会因为 Kafka 挂了把整条工具调用链打断。
    """

    def __init__(
        self,
        bootstrap_servers: str = config.KAFKA_BOOTSTRAP_SERVERS,
        request_topic: str = config.KAFKA_TOOL_REQUEST_TOPIC,
        result_topic: str = config.KAFKA_TOOL_RESULT_TOPIC,
        group_id: str = config.KAFKA_GROUP_ID,
        timeout_seconds: float = config.KAFKA_CALLBACK_TIMEOUT_SECONDS,
    ):
        self.bootstrap_servers = bootstrap_servers
        self.request_topic = request_topic
        self.result_topic = result_topic
        self.group_id = group_id
        self.timeout_seconds = timeout_seconds

        self._producer: Optional[AIOKafkaProducer] = None
        self._consumer: Optional[AIOKafkaConsumer] = None
        self._consumer_task: Optional[asyncio.Task] = None
        self._ready = asyncio.Event()
        self._waiters: dict[str, asyncio.Future] = {}

    @property
    def enabled(self) -> bool:
        return config.CALLBACK_MODE.lower() == "kafka"

    async def start(self):
        if not self.enabled:
            logger.info("CALLBACK_MODE=http，Kafka 工具调用通道不启动")
            return
        try:
            self._producer = AIOKafkaProducer(
                bootstrap_servers=self.bootstrap_servers,
                value_serializer=lambda v: json.dumps(v, ensure_ascii=False).encode("utf-8"),
                request_timeout_ms=int(self.timeout_seconds * 1000),
            )
            self._consumer = AIOKafkaConsumer(
                self.result_topic,
                bootstrap_servers=self.bootstrap_servers,
                group_id=self.group_id,
                auto_offset_reset="latest",
                enable_auto_commit=True,
                auto_commit_interval_ms=1000,
                value_deserializer=lambda b: json.loads(b.decode("utf-8")),
            )
            await self._producer.start()
            await self._consumer.start()
            # 等待 Kafka 完成 group rebalance 和 partition 分配；
            # 否则刚启动的消费者可能还没拿到 result topic 分区，丢新消息。
            await self._wait_partitions()
            self._consumer_task = asyncio.create_task(self._consume())
            self._ready.set()
            logger.info(
                "Kafka 工具调用通道已启动 broker=%s request=%s result=%s",
                self.bootstrap_servers,
                self.request_topic,
                self.result_topic,
            )
        except Exception as e:
            logger.warning(f"Kafka 工具调用通道启动失败，自动降级 HTTP: {e}")
            await self.close()

    async def stop(self):
        await self.close()

    async def close(self):
        self._ready.clear()
        for fut in list(self._waiters.values()):
            if not fut.done():
                fut.set_exception(RuntimeError("Kafka 通道已关闭"))
        self._waiters.clear()
        if self._consumer_task is not None:
            self._consumer_task.cancel()
            try:
                await self._consumer_task
            except asyncio.CancelledError:
                pass
            self._consumer_task = None
        if self._consumer is not None:
            await self._consumer.stop()
            self._consumer = None
        if self._producer is not None:
            await self._producer.stop()
            self._producer = None

    async def execute_tool(
        self, task_id: str, tool_name: str, parameters: dict
    ) -> ToolExecutionResult:
        """Kafka 模式执行工具；任何异常都抛给调用方由 HTTP 兜底。"""
        request_id = f"tool-{uuid.uuid4().hex}"
        future: asyncio.Future = asyncio.get_running_loop().create_future()
        self._waiters[request_id] = future
        try:
            if not self._ready.is_set() or self._producer is None:
                raise RuntimeError("Kafka 通道未就绪")

            message = {
                "requestId": request_id,
                "taskId": task_id,
                "toolName": tool_name,
                "parameters": parameters,
                "contextId": task_id,
            }
            try:
                await asyncio.wait_for(
                    self._producer.send(self.request_topic, value=message, key=task_id.encode()),
                    timeout=self.timeout_seconds,
                )
            except Exception as e:
                logger.warning(
                    f"Kafka 工具请求发送失败 tool={tool_name} task={task_id}，降级 HTTP: {e}"
                )
                raise

            result = await asyncio.wait_for(future, timeout=self.timeout_seconds)
            return result
        except (asyncio.TimeoutError, Exception) as e:
            if not future.done():
                future.cancel()
            if isinstance(e, asyncio.TimeoutError):
                logger.warning(
                    f"Kafka 工具结果等待超时 tool={tool_name} task={task_id} requestId={request_id}，降级 HTTP"
                )
            raise
        finally:
            self._waiters.pop(request_id, None)

    async def _wait_partitions(self, timeout: float = 15.0):
        """等待 result topic 的所有分区被当前消费者分配。"""
        if self._consumer is None:
            return
        deadline = asyncio.get_event_loop().time() + timeout
        while True:
            assignment = self._consumer.assignment()
            if assignment:
                logger.info("Kafka 工具结果消费者已分配分区: %s", sorted(str(tp) for tp in assignment))
                return
            if asyncio.get_event_loop().time() >= deadline:
                logger.warning("等待 Kafka result topic 分区分配超时，可能无法收到回传结果")
                return
            await asyncio.sleep(0.2)

    async def _consume(self):
        """持续消费 agent-tool-result，按 requestId 唤醒对应 Future。

        并发场景下每个 requestId 有独立 Future，结果只会精确匹配，
        不会出现 A 拿到 B 的结果。
        """
        while True:
            try:
                async for msg in self._consumer:
                    payload = msg.value
                    if not isinstance(payload, dict):
                        logger.warning("Kafka 工具结果非 JSON 对象，丢弃: %s", payload)
                        continue
                    request_id = payload.get("requestId") or ""
                    logger.info("Kafka 工具结果已消费 requestId=%s offset=%s", request_id, msg.offset)
                    future = self._waiters.get(request_id)
                    if future is None:
                        logger.warning("Kafka 工具结果没有对应等待方 requestId=%s，丢弃", request_id)
                        continue
                    if future.done():
                        continue
                    future.set_result(
                        ToolExecutionResult(
                            success=bool(payload.get("success", False)),
                            message=str(payload.get("message") or ""),
                            data=payload.get("data") or {},
                        )
                    )
            except asyncio.CancelledError:
                raise
            except Exception as e:
                logger.error(f"Kafka 工具结果消费异常: {e}")
                await asyncio.sleep(1)


kafka_tool_callback = KafkaToolCallback()
