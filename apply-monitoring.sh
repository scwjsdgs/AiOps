#!/usr/bin/env bash
# apply-monitoring.sh
# 一键恢复本地监控栈：kube-state-metrics / node-exporter / Prometheus / Alertmanager / Grafana
# 依赖本机已有镜像，不依赖 Helm / GitHub 在线 manifest。
set -euo pipefail
CONTEXT="${1:-kind-aiops}"
DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/cluster-backup/monitoring-recreated"

kubectl --context "$CONTEXT" apply -f "$DIR/01-kube-state-metrics.yaml"
kubectl --context "$CONTEXT" apply -f "$DIR/02-node-exporter.yaml"
kubectl --context "$CONTEXT" apply -f "$DIR/03b-prometheus-rbac.yaml"
kubectl --context "$CONTEXT" apply -f "$DIR/03-prometheus.yaml"
kubectl --context "$CONTEXT" apply -f "$DIR/04-alertmanager.yaml"
kubectl --context "$CONTEXT" apply -f "$DIR/05-grafana.yaml"

echo "==> 等待监控 Pod 就绪"
kubectl --context "$CONTEXT" -n monitoring rollout status deploy/prometheus --timeout=180s
kubectl --context "$CONTEXT" -n monitoring rollout status deploy/alertmanager --timeout=180s
kubectl --context "$CONTEXT" -n monitoring rollout status deploy/grafana --timeout=180s
kubectl --context "$CONTEXT" -n monitoring rollout status deploy/kube-state-metrics --timeout=180s
kubectl --context "$CONTEXT" -n monitoring rollout status daemonset/node-exporter --timeout=180s

echo "==> 完成。验证:"
echo "  kubectl -n monitoring get pods"
echo "  curl -s http://localhost:9090/-/healthy"
