#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
task_runner.submit 去重逻辑单元测试。

防守点：同一 taskId 只允许一个 ReAct 循环在跑 —— Java 超时重发或手工重试时，
绝不能让重启/扩容这类非幂等操作被执行两次。
"""
import asyncio
import os
import sys

import pytest

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

from services import task_runner


@pytest.fixture(autouse=True)
def clean_registry():
    """每个用例前后清空在飞任务注册表，保证隔离。"""
    task_runner._inflight.clear()
    yield
    task_runner._inflight.clear()


@pytest.mark.asyncio
async def test_duplicate_submit_rejected():
    """同一 taskId 在飞时，第二次提交必须返回 duplicate。"""
    started = asyncio.Event()
    release = asyncio.Event()

    async def slow_task():
        started.set()
        await release.wait()

    first = task_runner.submit("task-1", slow_task)
    assert first == "accepted"

    await started.wait()  # 确保第一个真的开始执行
    second = task_runner.submit("task-1", slow_task)
    assert second == "duplicate", "同一 taskId 第二次提交必须被拒绝"

    release.set()  # 释放第一个
    await asyncio.sleep(0.05)


@pytest.mark.asyncio
async def test_submit_after_completion_accepted():
    """任务完成后，同 id 再次提交应被接受（非重复）。"""
    done_flag = asyncio.Event()

    async def quick_task():
        done_flag.set()

    assert task_runner.submit("task-2", quick_task) == "accepted"
    await done_flag.wait()
    await asyncio.sleep(0.05)  # 让 finally 里的 _inflight.pop 生效

    async def quick_task2():
        pass

    assert task_runner.submit("task-2", quick_task2) == "accepted"


@pytest.mark.asyncio
async def test_failed_task_reports_failure():
    """任务抛异常时，应通过回调上报 FAILED 状态，且不向上传播。"""
    reported = {}

    class FakeCallback:
        async def complete_task(self, task_id, message, status=None):
            reported["id"] = task_id
            reported["status"] = status

    original = task_runner.callback_service
    task_runner.callback_service = FakeCallback()
    try:
        async def boom():
            raise RuntimeError("模拟失败")

        assert task_runner.submit("task-3", boom) == "accepted"
        await asyncio.sleep(0.1)  # 等异常处理完
        assert reported.get("status") == "FAILED", "失败任务必须回传 FAILED 状态"
        assert "task-3" in reported.get("id", "")
    finally:
        task_runner.callback_service = original
