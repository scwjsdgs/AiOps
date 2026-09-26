# -*- coding: utf-8 -*-
"""
告警洪峰压测脚本。

目标：验证告警洪峰（默认 100 条/分钟）下，
1) 告警降噪（聚合窗口）把同源故障合并的效果
2) Java 任务队列 / webhook 接口的延迟表现（P50/P95）
3) agent 不被压垮（任务成功率）

不触发真实 ReAct 推理太多——默认只有少量任务被真正分析（降噪挡住了大部分），
压测重点是 webhook 吞吐与降噪比，不是烧 LLM token。

用法：
  python load_test.py                 # 100 条/分钟，持续 60s
  python load_test.py --count 200 --duration 120
"""
import argparse
import json
import random
import statistics
import sys
import time
from concurrent.futures import ThreadPoolExecutor

import httpx

BASE_URL = "http://localhost:8081"
WEBHOOK_URL = f"{BASE_URL}/api/alerts/webhook"
WEBHOOK_TOKEN = "dev-webhook-token"

_services = ["leaky-app", "nginx", "payment", "user-svc", "order-svc"]
_alertnames = ["KubePodCrashLooping", "KubePodNotReady", "ReplicasMismatch", "HighCPU", "HighMemory"]


def gen_alert(i: int, burst_of_same_service: bool) -> dict:
    """生成一条告警。burst_of_same_service=True 时归到 leaky-app 同源故障
    （相同 namespace + pod 前缀），模拟一次崩溃触发多条告警的真实场景。"""
    if burst_of_same_service:
        service = "leaky-app"
        alertname = random.choice(["KubePodCrashLooping", "KubePodNotReady", "ReplicasMismatch"])
        pod = f"leaky-app-b7c4d9cf4-{random.choice(['a','b','c','d'])}{i%10}"
    else:
        service = random.choice(_services)
        alertname = random.choice(_alertnames)
        pod = f"{service}-demo-{i%3}-xyz{i}"
    return {
        "status": "firing",
        "alerts": [{
            "status": "firing",
            "labels": {
                "alertname": alertname,
                "namespace": "default",
                "pod": pod,
                "severity": random.choice(["critical", "warning"]),
                "service": service,
                "instance": f"10.244.0.{i % 200}:8080",
            },
            "annotations": {
                "summary": f"{alertname} on {pod}",
                "description": f"压测告警 #{i}: {service} {alertname}",
            },
        }],
    }


def send_webhook(client: httpx.Client, alert: dict, latencies: list, errors: list):
    """发送一条 webhook，记录延迟/错误。线程安全（list.append 原子）。"""
    t0 = time.monotonic()
    try:
        r = client.post(WEBHOOK_URL, json=alert,
                        headers={"X-Webhook-Token": WEBHOOK_TOKEN}, timeout=15)
        dt = (time.monotonic() - t0) * 1000
        latencies.append(dt)
        if r.status_code not in (200, 202):
            errors.append(f"HTTP {r.status_code}")
    except Exception as e:
        latencies.append((time.monotonic() - t0) * 1000)
        errors.append(str(e)[:60])


def pct(values: list, p: float) -> float:
    if not values:
        return 0.0
    s = sorted(values)
    idx = min(int(len(s) * p / 100), len(s) - 1)
    return s[idx]


def main():
    global BASE_URL, WEBHOOK_URL
    parser = argparse.ArgumentParser(description="告警洪峰压测")
    parser.add_argument("--count", type=int, default=100, help="总告警条数（默认 100）")
    parser.add_argument("--duration", type=int, default=60, help="持续秒数（默认 60，即约 100 条/分钟）")
    parser.add_argument("--base", default=BASE_URL, help="Java 后端地址")
    parser.add_argument("--json", action="store_true", help="输出原始 JSON")
    args = parser.parse_args()

    # 解析时 BASE_URL / WEBHOOK_URL 是全局变量，先改局部再赋回
    BASE_URL = args.base
    WEBHOOK_URL = f"{BASE_URL}/api/alerts/webhook"

    print(f"=== 告警洪峰压测：{args.count} 条 / {args.duration}s ===")
    print(f"目标: {WEBHOOK_URL}")

    # 前置健康检查
    try:
        r = httpx.get(f"{BASE_URL}/actuator/health", timeout=5)
        print(f"Java 健康: {r.status_code}")
    except Exception as e:
        print(f"Java 不可达: {e}")
        sys.exit(1)

    # 记录压测前任务数
    latencies: list = []
    errors: list = []

    interval = args.duration / max(args.count, 1)
    t_start = time.monotonic()

    with ThreadPoolExecutor(max_workers=10) as pool:
        with httpx.Client() as client:
            futures = []
            for i in range(args.count):
                # 每 4 条里有 3 条归为同源故障（模拟一次崩溃多条告警）
                burst = (i % 4 != 0)
                alert = gen_alert(i, burst)
                futures.append(pool.submit(send_webhook, client, alert, latencies, errors))
                time.sleep(interval)
            for f in futures:
                f.result()

    wall = time.monotonic() - t_start

    # 等任务创建与降噪判定
    print("等待任务创建与降噪判定（10s）...")
    time.sleep(10)

    # 统计结果
    ok = len(latencies) - len(errors)
    p50 = pct(latencies, 50)
    p95 = pct(latencies, 95)
    p99 = pct(latencies, 99)

    if args.json:
        print(json.dumps({
            "count": args.count,
            "wall_seconds": round(wall, 1),
            "ok": ok,
            "errors": len(errors),
            "error_samples": errors[:5],
            "latency_ms": {"p50": round(p50, 1), "p95": round(p95, 1), "p99": round(p99, 1),
                           "mean": round(statistics.mean(latencies), 1) if latencies else 0},
        }, ensure_ascii=False, indent=2))
        return

    print()
    print("=== 压测结果 ===")
    print(f"总告警: {args.count} | 成功: {ok} | 失败: {len(errors)}")
    if errors:
        print(f"错误样例: {errors[:3]}")
    print(f"吞吐: {args.count / wall:.1f} 条/秒 (实际墙钟 {wall:.1f}s)")
    print()
    print("=== webhook 延迟 ===")
    print(f"  P50: {p50:.0f} ms")
    print(f"  P95: {p95:.0f} ms")
    print(f"  P99: {p99:.0f} ms")
    if latencies:
        print(f"  均值: {statistics.mean(latencies):.0f} ms")
    print()
    print("=== 降噪效果（同源故障 3/4 占比下） ===")
    print("任务列表（最新 5 条，CORRELATED = 被降噪聚合）:")
    try:
        tok = httpx.post(f"{BASE_URL}/api/auth/login",
                         json={"username": "admin", "password": "admin123"}, timeout=10).json()["data"]["token"]
        r = httpx.get(f"{BASE_URL}/api/tasks", params={"page": 1, "size": 5},
                      headers={"Authorization": f"Bearer {tok}"}, timeout=10).json()
        for t in (r.get("data") or {}).get("records") or []:
            print(f"  {t.get('status')} | {(t.get('input') or '')[:60]}")
    except Exception as e:
        print(f"  任务列表获取失败: {e}")

    print()
    print("结论参考: P95 < 1000ms 且无 5xx => webhook 吞吐合格;")
    print("          同源故障只产生 1 个分析任务 => 降噪生效。")


if __name__ == "__main__":
    main()
