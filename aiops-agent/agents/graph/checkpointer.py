# -*- coding: utf-8 -*-
"""
LangGraph Checkpointer：审批断点续传与任务状态持久化。

LangGraph 状态图的所有节点都是 async 函数，必须使用异步 Checkpointer。
本项目原来的 RedisSaver 强依赖 RedisSearch/RedisVL（FT.INFO/FT.CREATE），
而当前本机 Redis 是 redis:7 普通容器，没有该模块，运行必然报
`unknown command 'FT.INFO'`。这里改用官方 SqliteSaver：
- 完全适配 async 图（aget_tuple/aput/aput_writes）；
- 本地单文件持久化，审批挂起后 Java 回调 /api/agent/resume 仍能恢复；
- 不依赖 RedisSearch，不额外要求中间件容器变化。

MySQL/Redis 仍用于 Java 侧任务/审批/审计，这里只负责 LangGraph 断点。
"""
from __future__ import annotations

import asyncio
import sqlite3
from typing import Any, AsyncIterator, Iterator, Optional, Sequence

from langchain_core.runnables import RunnableConfig
from langgraph.checkpoint.base import (
    Checkpoint,
    CheckpointMetadata,
    CheckpointTuple,
    ChannelVersions,
)
from langgraph.checkpoint.sqlite import SqliteSaver

from config import config

CHECKPOINT_DB_PATH = config.GRAPH_CHECKPOINT_DB_PATH


class SqliteCheckpointer(SqliteSaver):
    """给同步 SqliteSaver 补 async 包装。

    SqliteSaver 自带线程锁，把同步方法丢到线程池即可，避免把整个图改成同步调用。
    """

    async def aget_tuple(self, config: RunnableConfig) -> CheckpointTuple | None:
        return await asyncio.to_thread(self.get_tuple, config)

    async def aput(
        self,
        config: RunnableConfig,
        checkpoint: Checkpoint,
        metadata: CheckpointMetadata,
        new_versions: ChannelVersions,
    ) -> RunnableConfig:
        return await asyncio.to_thread(
            self.put, config, checkpoint, metadata, new_versions
        )

    async def aput_writes(
        self,
        config: RunnableConfig,
        writes: Sequence[tuple[str, Any]],
        task_id: str,
        task_path: str = "",
    ) -> None:
        await asyncio.to_thread(self.put_writes, config, writes, task_id, task_path)

    async def adelete_thread(self, thread_id: str) -> None:
        await asyncio.to_thread(self.delete_thread, thread_id)

    async def alist(
        self,
        config: RunnableConfig | None,
        *,
        filter: dict[str, Any] | None = None,
        before: RunnableConfig | None = None,
        limit: int | None = None,
    ) -> AsyncIterator[CheckpointTuple]:
        iterator: Iterator[CheckpointTuple] = self.list(
            config, filter=filter, before=before, limit=limit
        )
        for item in iterator:
            yield item


def get_checkpointer() -> SqliteCheckpointer:
    """进程内缓存：每个任务共用一个 saver，避免反复建 SQLite 连接。

    延迟到第一次构建图时再连接 SQLite，避免后端文件不可写时
    Python 进程连 import main 都失败。
    """
    global _saver
    if _saver is None:
        conn = sqlite3.connect(CHECKPOINT_DB_PATH, check_same_thread=False)
        _saver = SqliteCheckpointer(conn)
        _saver.setup()
    return _saver


def clear_checkpoint(thread_id: str) -> None:
    """任务结束后删除该 task 的所有 graph checkpoint，避免积压。"""
    try:
        get_checkpointer().delete_thread(thread_id)
    except Exception as e:
        print(f"Checkpointer 清理失败 thread_id={thread_id}: {e}")


_saver: Optional[SqliteCheckpointer] = None