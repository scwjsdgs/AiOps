# -*- coding: utf-8 -*-
"""
LangFuse 可观测性接入。

配置齐了（LANGFUSE_ENABLED=true + public/secret key）就自动初始化 LangFuse，
并生成 LangChain CallbackHandler 挂到 LLM 上，追踪 LLM 调用、Token 消耗、
工具执行与慢 Trace 瀑布图。

Docker 启动方式见 docs/langfuse.md。
"""
import logging

from config import config

logger = logging.getLogger(__name__)

_callback_handler = None
_langfuse = None


def get_langfuse():
    global _langfuse
    if _langfuse is not None:
        return _langfuse
    if not config.LANGFUSE_ENABLED:
        return None
    try:
        from langfuse import Langfuse
        _langfuse = Langfuse(
            public_key=config.LANGFUSE_PUBLIC_KEY,
            secret_key=config.LANGFUSE_SECRET_KEY,
            host=config.LANGFUSE_HOST,
        )
        logger.info("LangFuse 已初始化")
    except Exception as e:
        logger.warning(f"LangFuse 初始化失败，降级为不追踪: {e}")
        _langfuse = None
    return _langfuse


def get_langfuse_callback_handler():
    """返回 LangChain CallbackHandler（供 llm.callbacks / tools.callbacks 使用）。

    LangFuse 4.x 的路径是 langfuse.langchain.CallbackHandler（旧版是
    langfuse.callback.CallbackHandler），这里两个都试一遍以兼容。
    """
    global _callback_handler
    if _callback_handler is not None:
        return _callback_handler
    lf = get_langfuse()
    if lf is None:
        return None
    try:
        from langfuse.langchain import CallbackHandler
        _callback_handler = CallbackHandler()
        return _callback_handler
    except Exception:
        pass
    try:
        from langfuse.callback import CallbackHandler as LegacyHandler
        _callback_handler = LegacyHandler()
        return _callback_handler
    except Exception as e:
        logger.warning(f"LangFuse CallbackHandler 初始化失败: {e}")
        return None


def finalize():
    """等待 LangFuse 异步 flush，避免进程退出时丢 trace。"""
    lf = get_langfuse()
    if lf is not None:
        try:
            lf.flush()
        except Exception:
            pass