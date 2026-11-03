# -*- coding: utf-8 -*-
"""Kafka 文档变更事件消费者。

Java 文档 Webhook 收到内部文档系统事件后发送到 topic `doc-change`，
本消费者负责：
  1. 反序列化事件 {docId, filename, content, status, version, operator}
  2. published/updated/upsert -> 调用 knowledge_manager.upload_document 完成
     切片 + embedding + 增量 upsert
  3. archived/deleted -> 调用 knowledge_manager.soft_delete_document 打软删除标记
  4. 所有失败只记日志，不阻塞 Kafka 消费；避免单条脏数据拖垮整条 RAG 更新链。
"""
import asyncio
import json
import logging

from aiokafka import AIOKafkaConsumer

from config import config
from rag import knowledge_manager

logger = logging.getLogger(__name__)

_TOPIC = "doc-change"


class DocChangeConsumer:
    def __init__(self):
        self._consumer: AIOKafkaConsumer | None = None
        self._task: asyncio.Task | None = None
        self._ready = asyncio.Event()

    async def start(self):
        if self._consumer is not None:
            return
        self._consumer = AIOKafkaConsumer(
            config.KAFKA_DOC_CHANGE_TOPIC,
            bootstrap_servers=config.KAFKA_BOOTSTRAP_SERVERS,
            group_id=config.KAFKA_DOC_CHANGE_GROUP_ID,
            auto_offset_reset="earliest",
            enable_auto_commit=True,
        )
        await self._consumer.start()
        self._task = asyncio.create_task(self._consume_loop())
        logger.info("文档变更 Kafka 消费者已启动 topic=%s", config.KAFKA_DOC_CHANGE_TOPIC)

    async def stop(self):
        if self._task is not None:
            self._task.cancel()
            try:
                await self._task
            except (asyncio.CancelledError, Exception):
                pass
            self._task = None
        if self._consumer is not None:
            try:
                await self._consumer.stop()
            except Exception as e:
                logger.warning("停止文档变更消费者异常: %s", e)
            self._consumer = None

    async def _consume_loop(self):
        if self._consumer is None:
            return
        try:
            async for msg in self._consumer:
                try:
                    payload = json.loads(msg.value.decode("utf-8"))
                    await asyncio.to_thread(self._handle, payload)
                except Exception as e:
                    logger.error("文档变更事件处理失败 offset=%s: %s", msg.offset, e)
        except asyncio.CancelledError:
            logger.info("文档变更消费者已停止")
        except Exception as e:
            logger.error("文档变更消费者异常退出: %s", e)

    def _handle(self, event: dict):
        doc_id = str(event.get("docId") or "")
        filename = str(event.get("filename") or "")
        content = str(event.get("content") or "").strip()
        status = str(event.get("status") or "published").lower()
        version = str(event.get("version") or "")
        operator = str(event.get("operator") or "doc-system")

        if not doc_id:
            logger.warning("文档变更事件缺少 docId，丢弃")
            return
        if not filename:
            filename = doc_id + ".txt"

        if status in ("archived", "deleted", "disable", "disabled"):
            result = knowledge_manager.soft_delete_document(filename, operator=operator)
            logger.info("文档变更软删除处理完成 docId=%s filename=%s result=%s",
                        doc_id, filename, result)
            return
        if not content:
            logger.warning("文档变更内容为空 docId=%s filename=%s status=%s，跳过 upsert",
                           doc_id, filename, status)
            return

        result = knowledge_manager.upload_document(
            content, filename, operator=operator, version=version)
        # 上传是主动 upsert，若已存在软删除标记，恢复为 ACTIVE。
        if result.get("ok"):
            self._restore_active_if_deleted(filename)
        logger.info("文档变更 upsert 处理完成 docId=%s filename=%s result=%s",
                    doc_id, filename, result)

    def _restore_active_if_deleted(self, filename: str):
        try:
            result = knowledge_manager.restore_document(filename, operator="doc-system")
            if not result.get("ok"):
                logger.warning("恢复文档 ACTIVE 状态失败 filename=%s: %s", filename, result.get("reason"))
        except Exception as e:
            logger.warning("恢复文档 ACTIVE 状态失败 filename=%s: %s", filename, e)


doc_change_consumer = DocChangeConsumer()