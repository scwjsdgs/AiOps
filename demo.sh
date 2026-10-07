#!/usr/bin/env bash
# -*- coding: utf-8 -*-
# AIOps 全自动闭环一键演示脚本
# 目标：造病灶 -> 触发告警 -> Alertmanager Webhook -> Java 降噪 -> Agent 诊断 -> 案例入库 -> 验证
# 运行前请先保证：Java:8081 / Python:5000 / 前端:3000 已启动，kind 集群已创建并部署了 leaky-app

set -euo pipefail

RED="\033[31m"; GREEN="\033[32m"; YELLOW="\033[33m"; NC="\033[0m"
log(){ echo -e "${GREEN}[$(date +%H:%M:%S)]${NC} $*"; }
warn(){ echo -e "${YELLOW}[WARN]${NC} $*"; }

BASE_URL="http://localhost:8081"
LOGIN_URL="${BASE_URL}/api/auth/login"
TOKEN=""
DURATION_SEC=180

get_token() {
  log "获取 JWT..."
  TOKEN=$(curl -s -X POST "$LOGIN_URL" -H "Content-Type: application/json" -d '{"username":"admin","password":"admin123"}' \
    | python3 - <<'PY'
import sys,json
print(json.load(sys.stdin)['data']['token'])
PY
)
  if [[ -z "$TOKEN" ]]; then echo "登录失败"; exit 1; fi
  log "登录成功"
}

check_health() {
  log "健康检查..."
  curl -s -o /dev/null -w " Java:${http_code}\n" -o /dev/null -w "" http://localhost:8081/actuator/health || true
  curl -s -o /dev/null -w " Python:%{http_code}\n" http://localhost:5000/health || true
}

create_pathology() {
  log "=== 造病灶：把 leaky-app 扩到 2 副本（周期性 CrashLoop） ==="
  kubectl scale deploy leaky-app -n default --replicas=2 >/dev/null
  kubectl set image deploy/leaky-app -n default leaky-app=nginx:1.24 >/dev/null
  log "病灶已创建，等待 Prometheus 触发 DemoAppCrashLoop"
}

start_trace() {
  log "=== 实时跟踪任务创建（30s） ==="
  start=$(date +%s)
  while [[ $(($(date +%s)-start)) -lt 30 ]]; do
    tasks=$(curl -s -H "Authorization: Bearer $TOKEN" "${BASE_URL}/api/tasks?page=1&size=5" \
      | python3 - <<'PY'
import sys,json
data=json.load(sys.stdin).get('data',{})
print(len(data.get('records',[])))
PY
)
    echo "  当前任务数: $tasks"
    sleep 5
  done
}

verify_correlation() {
  log "=== 验证告警降噪与案例入库 ==="
  # 取最新任务
  TASK_ID=$(curl -s -H "Authorization: Bearer $TOKEN" "${BASE_URL}/api/tasks?page=1&size=1" \
    | python3 - <<'PY'
import sys,json
r=json.load(sys.stdin)
print(r['data']['records'][0]['id'])
PY
)
  log "最新任务 ID: $TASK_ID"

  log "等待任务完成（最多 120s）"
  for i in {1..24}; do
    status=$(curl -s -H "Authorization: Bearer $TOKEN" "${BASE_URL}/api/tasks/${TASK_ID}" \
      | python3 - <<PY
import sys,json
print(json.load(sys.stdin)['data']['status'])
PY
)
    echo "  状态: $status"
    [[ "$status" == "SUCCESS" || "$status" == "FAILED" ]] && break
    sleep 5
  done

  log "任务 input 摘要："
  curl -s -H "Authorization: Bearer $TOKEN" "${BASE_URL}/api/tasks/${TASK_ID}" \
    | python3 - <<'PY'
import sys,json
t=json.load(sys.stdin)['data']
inp=t.get('input') or ''
print(inp[:300].replace('\n',' / '))
PY

  log "检查案例库：历史案例是否入库"
  # 这里用 Python API 查询 case_store
  python3 - <<'PY'
import sys
sys.path.insert(0,'D:/桌面/智能运维项目/aiops-agent')
from rag.case_store import case_count, search_cases
print('当前案例数:', case_count())
res = search_cases('leaky-app CrashLoop', k=1)
if res:
    print('检索命中服务:', res[0]['service'], '状态:', res[0]['status'])
else:
    print('未检索到案例')
PY
}

cleanup() {
  log "=== 清理病灶 ==="
  kubectl scale deploy leaky-app -n default --replicas=0 >/dev/null || true
  log "demo 结束"
}

main() {
  check_health
  get_token
  create_pathology

  # 等待告警触发（Alertmanager 规则 for 15s）
  sleep 20

  start_trace
  verify_correlation
  cleanup
}

main
