#!/usr/bin/env bash
# kind-mirror-config.sh
# 给 kind 节点配置 Docker Hub 国内镜像加速，避免 Pod 直连 Docker Hub 被拒。
# 适用于当前单节点集群 aiops；重建 kind 后重新执行本脚本即可恢复镜像拉取。
set -euo pipefail

NODE="${1:-aiops-control-plane}"
MIRRORS=(
  "https://docker.1panel.live"
  "https://hub.rat.dev"
  "https://docker.m.daocloud.io"
)

echo "==> 配置 containerd registry mirror: ${NODE}"
docker exec "$NODE" sh -c '
set -e
cp /etc/containerd/config.toml /etc/containerd/config.toml.bak 2>/dev/null || true
cat >> /etc/containerd/config.toml <<EOF

# kind-mirror-config.sh: 使用国内镜像加速
[plugins."io.containerd.grpc.v1.cri".registry]
  config_path = "/etc/containerd/certs.d"
EOF
mkdir -p /etc/containerd/certs.d/docker.io
cat > /etc/containerd/certs.d/docker.io/hosts.toml <<EOF
server = "https://docker.io"
[host."https://docker.1panel.live"]
  capabilities = ["pull", "resolve"]
[host."https://hub.rat.dev"]
  capabilities = ["pull", "resolve"]
[host."https://docker.m.daocloud.io"]
  capabilities = ["pull", "resolve"]
EOF
'

echo "==> 重启 containerd"
docker exec "$NODE" sh -c 'kill -TERM $(pidof containerd) || true; sleep 5'
echo "==> 等待 kubelet 恢复"
sleep 10

echo "==> 重新拉取 Deployment 镜像（可选）"
kubectl --context kind-aiops rollout restart deployment -n default 2>/dev/null || true
kubectl --context kind-aiops rollout status deployment/nginx -n default --timeout=180s 2>/dev/null || true
kubectl --context kind-aiops rollout status deployment/leaky-app -n default --timeout=180s 2>/dev/null || true

echo "==> 完成"
