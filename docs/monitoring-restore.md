# 监控恢复说明（kind 重建后）

## 已恢复的监控栈

当前 kind 集群 `aiops`（API Server 固定 `127.0.0.1:16443`）已通过手动清单恢复：

| 组件 | 镜像 | 状态 |
|---|---|---|
| Prometheus | `quay.m.daocloud.io/prometheus/prometheus:v3.14.0-distroless` | 9090 |
| Alertmanager | `quay.m.daocloud.io/prometheus/alertmanager:v0.34.0` | 9093 |
| Grafana | `docker.m.daocloud.io/grafana/grafana:13.2.1-distroless` | NodePort 30300 |
| kube-state-metrics | `m.daocloud.io/registry.k8s.io/kube-state-metrics/kube-state-metrics:v2.20.0` | 8080 |
| node-exporter | `quay.m.daocloud.io/prometheus/node-exporter:v1.12.1-distroless` | 9100 |

全部使用本机已有镜像，不依赖 Helm / GitHub 在线 manifest / 新镜像。

## 目录

恢复清单保存在 `cluster-backup/monitoring-recreated/`：

- `01-kube-state-metrics.yaml`：SA + ClusterRole/Binding + Deployment + Service
- `02-node-exporter.yaml`：DaemonSet + Service（hostNetwork）
- `03-prometheus.yaml`：Prometheus ConfigMap/Deployment/Service（NodePort 30900）
- `03b-prometheus-rbac.yaml`：Prometheus SA + kubelet cAdvisor RBAC
- `04-alertmanager.yaml`：Alertmanager 配置 + Deployment + Service（webhook 到 Java 8081）
- `05-grafana.yaml`：Grafana Secret/ConfigMap/Deployment/Service（NodePort 30300）

一键恢复命令：

```bash
bash apply-monitoring.sh          # 默认 kind-aiops
bash apply-monitoring.sh <context>
```

## Prometheus 暴露到本机 9090

Java `opsagent.prometheus.url=http://localhost:9090`，前端通过 Java 代理。

- `kind-config.yaml` 已加入 `extraPortMappings`：宿主 `127.0.0.1:9090` -> Prometheus NodePort `30900`。
- 但 `extraPortMappings` 只对**新建集群**生效。当前集群是通过 `kubectl port-forward` 暴露的。
- 重建集群后执行：
  1. `kind create cluster --config kind-config.yaml`
  2. `bash kind-mirror-config.sh`
  3. `bash apply-monitoring.sh`

## 验证

```bash
# Prometheus 可用
curl -s http://localhost:9090/-/healthy

# 各采集目标
curl -s 'http://localhost:9090/api/v1/query?query=up'

# Deployment 副本
curl -s 'http://localhost:9090/api/v1/query?query=kube_deployment_spec_replicas'

# 容器 CPU/内存（Java 大盘依赖）
curl -s 'http://localhost:9090/api/v1/query?query=sum(rate(container_cpu_usage_seconds_total{container!="POD",image!=""}[3m])) by (pod)'
curl -s 'http://localhost:9090/api/v1/query?query=sum(container_memory_working_set_bytes{container!="POD",image!=""}) by (pod)'

# Java 指标大盘
curl -H "Authorization: Bearer <JWT>" http://localhost:8081/api/cluster/metrics
# 期望 connected=true，且有 deploymentReplicas/containerCpu/containerMemory 数据
```

## 关键踩坑记录

- Prometheus 的 serviceAccountToken projected token 必须使用默认 audience，不能设 `audience: api`，否则 Kubernetes SD 持续 Unauthorized。
- kubelet 的 cAdvisor 抓取路径是 `https://<node-ip>:10250/metrics/cadvisor`，带 SA token，RBAC 需授权 `nodes/metrics`。
- Alertmanager 根路由不允许 matcher，`route.match/match_re` 必须去掉或移到子路由。
- Alertmanager `webhook_configs.http_config` 在 v0.34 不接受 `timeout` 字段。