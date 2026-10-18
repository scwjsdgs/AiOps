# -*- coding: utf-8 -*-
"""
Worker 子图公用件：消息滑动窗口 trim 与工具调用消息清洗。

诊断/执行/报告子图每次进入 LLM 前调用：
- 优先按 token 压缩：trim_messages(max_tokens=TRIM_MESSAGES_MAX_TOKENS, 默认 4000)；
- 同时保证最近 MESSAGES_WINDOW_SIZE（默认 12）条上下文不被裁光；
- 首条 System 系统提示始终保留，避免角色约束被 trim 丢掉；
- 任何裁剪完成后都会清洗孤儿 ToolMessage，防止 LLM 收到
  “tool must follow AIMessage(tool_calls)” 的非法上下文。
"""
from __future__ import annotations

import logging
from typing import List, Optional

from langchain_core.messages import (
    BaseMessage,
    AIMessage,
    ToolMessage,
    RemoveMessage,
    trim_messages,
)

from config import config

logger = logging.getLogger(__name__)


def _call_id(tool_call) -> str:
    if isinstance(tool_call, dict):
        return tool_call.get("id") or ""
    return getattr(tool_call, "id", "") or ""


def _tool_call_id(message: BaseMessage) -> str:
    return getattr(message, "tool_call_id", "") or ""


def sanitize_messages(messages: List[BaseMessage]) -> List[BaseMessage]:
    """剔除 tool 消息与前置 AIMessage(tool_calls) 不配对造成的脏状态。

    裁剪/恢复可能让 ToolMessage 前面只剩 HumanMessage/SystemMessage，
    直接发给 LLM 会触发 “tool must follow tool_calls” 错误。这里按最新
    顺序扫描，只保留能明确配对的 ToolMessage，并用 RemoveMessage 标记
    删除，让 LangGraph 也能同步清理 checkpoint 里的脏消息；同时把没有
    对应 ToolMessage 的 tool_calls 从 AIMessage 上摘除，避免半截调用。
    """
    if not messages:
        return messages

    pending_call_ids: set[str] = set()
    clean: List[BaseMessage] = []
    removed_ids: List[str] = []

    for msg in messages:
        mtype = getattr(msg, "type", "")
        if mtype == "ai":
            for tc in (getattr(msg, "tool_calls", None) or []):
                pending_call_ids.add(_call_id(tc))
            clean.append(msg)
            continue

        if mtype == "tool":
            call_id = _tool_call_id(msg)
            if call_id and call_id in pending_call_ids:
                pending_call_ids.discard(call_id)
                clean.append(msg)
            else:
                removed_ids.append(call_id)
            continue

        clean.append(msg)

    # 同一 AIMessage 里后续未配对的 tool_calls 也会让 LLM 报错，
    # 因此这里顺带清掉没有对应 ToolMessage 的调用声明。
    kept_call_ids = {
        _tool_call_id(m) for m in clean if getattr(m, "type", "") == "tool"
    }
    result: List[BaseMessage] = []
    for msg in clean:
        if getattr(msg, "type", "") != "ai":
            result.append(msg)
            continue
        stale_calls = [
            tc for tc in (getattr(msg, "tool_calls", None) or [])
            if _call_id(tc) not in kept_call_ids
        ]
        if stale_calls:
            msg = AIMessage(
                content=getattr(msg, "content", ""),
                tool_calls=[],
                additional_kwargs=dict(getattr(msg, "additional_kwargs", None) or {}),
            )
        result.append(msg)

    for mid in removed_ids:
        if mid:
            logger.debug("清理无前置 tool_calls 的孤儿 ToolMessage: %s", mid)
            result.append(RemoveMessage(id=mid))
    return result


def _align_tail(raw: List[BaseMessage], source: List[BaseMessage]) -> List[BaseMessage]:
    """把落在窗口起点的 ToolMessage 往回补上它的 AIMessage(tool_calls) 前缀。

    raw 是截取后的最近窗口；source 是截取前的完整消息列表。只有当 raw 的
    第一条是 ToolMessage 时才需要回找前置 AI，避免把孤立的 NormalMessage
    当成新的事实开头。
    """
    if not raw or getattr(raw[0], "type", "") != "tool":
        return raw

    first_call_id = _tool_call_id(raw[0])
    source_index = next(i for i, m in enumerate(source) if m is raw[0])
    for i in range(source_index - 1, -1, -1):
        prev = source[i]
        if getattr(prev, "type", "") != "ai" or not (getattr(prev, "tool_calls", None) or []):
            break
        if any(_call_id(tc) == first_call_id for tc in prev.tool_calls):
            return source[i:]
    # 找不到前置 AI：原样返回，交给 sanitize_messages 清理孤儿 ToolMessage
    return raw


def _pair_tool_calls(messages: List[BaseMessage]) -> List[BaseMessage]:
    """保证 AI 的 tool_calls 与后续 ToolMessage 成对出现。

    allow_partial=True 后 trim_messages 不保证调用回合完整，因此这里手动
    剔除无法配对的 ToolMessage，并摘掉没有 ToolMessage 回应的 tool_calls。
    """
    if not messages:
        return messages

    pending_call_ids: set[str] = set()
    kept: List[BaseMessage] = []
    for msg in messages:
        mtype = getattr(msg, "type", "")
        if mtype == "ai":
            for tc in (getattr(msg, "tool_calls", None) or []):
                pending_call_ids.add(_call_id(tc))
            kept.append(msg)
            continue
        if mtype == "tool":
            call_id = _tool_call_id(msg)
            if not call_id or call_id not in pending_call_ids:
                continue
            pending_call_ids.discard(call_id)
        kept.append(msg)

    kept_call_ids = {_tool_call_id(m) for m in kept if getattr(m, "type", "") == "tool"}
    result: List[BaseMessage] = []
    for msg in kept:
        if getattr(msg, "type", "") != "ai":
            result.append(msg)
            continue
        stale = [
            tc for tc in (getattr(msg, "tool_calls", None) or [])
            if _call_id(tc) not in kept_call_ids
        ]
        if stale:
            msg = AIMessage(
                content=getattr(msg, "content", ""),
                tool_calls=[],
                additional_kwargs=dict(getattr(msg, "additional_kwargs", None) or {}),
            )
        result.append(msg)
    return result


def trim_worker_messages(messages: List[BaseMessage],
                         window: Optional[int] = None) -> List[BaseMessage]:
    """滑动窗口：token 上限 4000 + 最近 window 条双保险，保护首条 System。

    裁剪时不允许把 AIMessage(tool_calls) 和紧随其后的 ToolMessage 切断；
    最后统一清洗孤儿的 ToolMessage，确保 LLM 只看到成对调用。
    """
    if not messages:
        return messages
    window = window or config.MESSAGES_WINDOW_SIZE
    max_tokens = config.TRIM_MESSAGES_MAX_TOKENS

    system = [m for m in messages if getattr(m, "type", "") == "system"]
    body = [m for m in messages if getattr(m, "type", "") != "system"]
    if len(body) <= window:
        return sanitize_messages(system + body)

    # 按条裁剪时以“完整工具回合”为单位：如果窗口起点正好落在
    # ToolMessage 上，就往前多保留它的 AIMessage(tool_calls)。
    aligned_tail = _align_tail(body[-window:], body)

    try:
        # 1) 先按 token 压缩（近似 token 计数，避免精确 tokenizer 依赖）。
        #    allow_partial=True 让 trim 不强制配对，完整性由 _pair_tool_calls
        #    在压缩后统一补齐。
        trimmed = trim_messages(
            body,
            token_counter="approximate",
            max_tokens=max_tokens,
            strategy="last",
            include_system=False,
            allow_partial=True,
        )
        if not isinstance(trimmed, list):
            trimmed = list(trimmed)
        # 2) 再按最近 window 条兜底：token 压缩可能仍保留非常多短消息
        if len(trimmed) > window:
            trimmed = _align_tail(trimmed[-window:], trimmed)
        return sanitize_messages(system + _pair_tool_calls(trimmed))
    except Exception as e:
        logger.warning(f"trim_messages 调用失败，退化为最近 {window} 条: {e}")
        return sanitize_messages(system + _pair_tool_calls(aligned_tail))