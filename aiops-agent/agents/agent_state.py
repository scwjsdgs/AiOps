# -*- coding: utf-8 -*-
"""
AgentState 状态管理。

在 ReAct 循环中显式维护：
  - core_intent:     本次任务的核心意图（从告警/用户问题提取）
  - current_subtask: 当前正在推进的子任务
  - pending_tools:   已经发起但尚未完成/尚未确认的工具调用
  - completed_steps: 已完成的关键步骤（诊断、修复、验证）
  - context:         检索到的知识库/历史案例等上下文，供断点续传使用

设计目标：
  1. 状态集中在单一结构里，方便审计与恢复；
  2. 相当于状态机，notebook 风格的 ReAct 循环每轮更新；
  3. 在触发人工审批（Human-in-the-loop）挂起前，把 AgentState 序列化为 JSON
     存入 Redis（带 TTL），为后续断点续传/审批恢复做准备。
"""
import json
import time
from dataclasses import dataclass, field, asdict
from typing import List, Optional

import redis

from config import config

STATE_PREFIX = "agent:state:"
DEFAULT_TTL_SECONDS = 24 * 3600  # 24h 足够跨会话恢复；超时自然过期


def _get_redis():
    return redis.Redis(
        host=config.REDIS_HOST,
        port=config.REDIS_PORT,
        db=config.REDIS_DB,
        decode_responses=True,
    )


@dataclass
class AgentState:
    task_id: str
    core_intent: str = ""
    current_subtask: str = ""
    pending_tools: List[dict] = field(default_factory=list)
    completed_steps: List[str] = field(default_factory=list)
    context: dict = field(default_factory=dict)

    def add_step(self, step: str):
        """记录一个关键步骤；避免重复记录完全相同内容。"""
        if step and (not self.completed_steps or self.completed_steps[-1] != step):
            self.completed_steps.append(step)

    def start_tool(self, tool_name: str, args: dict = None):
        """记录一个已发起但未完成的工具调用。"""
        self.pending_tools.append({
            "name": tool_name,
            "args": args or {},
            "ts": time.time(),
        })

    def finish_tool(self, tool_name: str):
        """标定指定工具为已完成（从 pending 移除）。"""
        self.pending_tools = [p for p in self.pending_tools if p.get("name") != tool_name]

    def to_json(self) -> str:
        return json.dumps(asdict(self), ensure_ascii=False)

    @classmethod
    def from_json(cls, task_id: str, raw: str):
        data = json.loads(raw)
        data["task_id"] = task_id
        return cls(**data)


def save_state(state: AgentState, ttl: int = DEFAULT_TTL_SECONDS) -> bool:
    """把状态序列化为 JSON 存入 Redis（幂等，可重复调用）。"""
    try:
        key = STATE_PREFIX + state.task_id
        _get_redis().set(key, state.to_json(), ex=ttl)
        return True
    except Exception as e:
        print(f"AgentState 保存失败（可忽略，仅影响恢复）: {e}")
        return False


def load_state(task_id: str) -> Optional[AgentState]:
    """从 Redis 读取状态；不存在或解析失败返回 None。"""
    try:
        key = STATE_PREFIX + task_id
        raw = _get_redis().get(key)
        if raw:
            return AgentState.from_json(task_id, raw)
    except Exception as e:
        print(f"AgentState 加载失败（可忽略）: {e}")
    return None


def clear_state(task_id: str):
    """任务结束后清理状态，避免 Redis 积压。"""
    try:
        _get_redis().delete(STATE_PREFIX + task_id)
    except Exception:
        pass


def suspend_for_approval(state: AgentState, approval_request: dict):
    """在触发人工审批挂起前，保存 AgentState + 审批上下文到 Redis。

    这样审批结束后可恢复：pending_tools 保留在途工具，context 里有申请/理由。
    """
    state.context["approval"] = approval_request
    save_state(state)