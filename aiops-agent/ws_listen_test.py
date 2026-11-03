# -*- coding: utf-8 -*-
"""WS 实时广播测试：登录拿 JWT -> 建立 /ws/agent 广播连接 -> 收消息 90 秒打印。"""
import asyncio
import json
import urllib.request
import websockets


def login():
    req = urllib.request.Request(
        "http://localhost:8081/api/auth/login",
        data=json.dumps({"username": "admin", "password": "admin123"}).encode(),
        headers={"Content-Type": "application/json"},
    )
    with urllib.request.urlopen(req) as resp:
        body = json.loads(resp.read())
    return body["data"]["token"]


async def main():
    token = login()
    url = f"ws://localhost:8081/ws/agent?taskId=&token={token}"
    print(f"WS 连接: {url[:60]}...")
    async with websockets.connect(url) as ws:
        print("WS 已连接，等待消息（90 秒超时）...")
        try:
            async for raw in ws:
                try:
                    msg = json.loads(raw)
                    print(f"[RECV] type={msg.get('type')} content={str(msg.get('content'))[:120]}")
                except Exception as e:
                    print(f"[RECV] 原始: {raw[:120]} | err={e}")
        except asyncio.TimeoutError:
            print("90s 无消息，结束")


if __name__ == "__main__":
    asyncio.run(main())
