# K8s 故障注入手册（本地 kind 集群）

用于验证 AIOps Agent 对四类经典 Pod 故障的诊断与恢复验证能力。
所有资源统一部署在 `default` 命名空间。

## 四种故障总览

| 文件 | Deployment 名 | 故障类型 | 触发方式 |
|---|---|---|---|
| `01-oom-killed.yaml` | `fault-oom` | OOMKilled（内存超限被杀） | `resources.limits.memory: 10Mi`，nginx 启动即超出 → 被 cgroup OOM killer 终止 |
| `02-image-pull.yaml` | `fault-image-pull` | ImagePullBackOff（镜像不存在） | 镜像 `nginx:not-exist-v999` 不存在，拉取失败 |
| `03-probe-failed.yaml` | `fault-probe` | readiness 探针失败（未就绪） | readinessProbe 请求不存在的 `/health-error`，永不就绪 |
| `04-pending.yaml` | `fault-pending` | Pending（无法调度） | `nodeSelector` 匹配不存在的标签 `disktype: ssd-not-exist` |

## 部署命令（按顺序 apply）

```bash
kubectl apply -f 01-oom-killed.yaml
kubectl apply -f 02-image-pull.yaml
kubectl apply -f 03-probe-failed.yaml
kubectl apply -f 04-pending.yaml
```

或一次全部部署：

```bash
kubectl apply -f k8s-faults/
```

## 清理命令

```bash
kubectl delete deployment fault-oom fault-image-pull fault-probe fault-pending
```

或按文件逐个删除：

```bash
kubectl delete -f 01-oom-killed.yaml
kubectl delete -f 02-image-pull.yaml
kubectl delete -f 03-probe-failed.yaml
kubectl delete -f 04-pending.yaml
```

## 观察故障

```bash
# 查看所有故障 Deployment 的状态
kubectl get deployments -l "app in (fault-oom, fault-image-pull, fault-probe, fault-pending)"

# 查看 Pod 状态（CrashLoopBackOff / ImagePullBackOff / Running / Pending）
kubectl get pods -n default | grep fault-

# 查看具体故障原因
kubectl describe pod -l app=fault-oom          # OOMKilled 原因
kubectl describe pod -l app=fault-image-pull    # ImagePullBackOff
kubectl describe pod -l app=fault-probe         # readiness 探针失败
kubectl describe pod -l app=fault-pending       # 0/1 Pending 无法调度
```

## 与 AIOps Agent 联调建议

1. 先 apply 一个故障，观察 Agent 是否能识别根因：
   - `fault-oom` → 期望识别 OOMKilled（内存不足）
   - `fault-image-pull` → 期望识别 ImagePullBackOff
   - `fault-probe` → 期望识别 readiness 探针失败 / 未就绪
   - `fault-pending` → 期望识别无法调度（nodeSelector 不匹配）
2. 触发方式：通过前端“告警列表 → 新增告警”，或直接 POST `/api/alerts`（携带 JWT）。
3. 恢复验证：故障仍持续时（Pod 崩溃循环/未就绪），观察任务是否在 5 分钟观察期内被改判 REGRESSED，告警是否保持“验证中”而非被 resolved 提前锁死。

> 注意：`fault-pending` 因无法调度不产生 Pod 崩溃事件，Agent 主要从 Deployment 状态/事件判断；
> 其余三个故障会持续触发 CrashLoopBackOff / ImagePullBackOff 事件，适合验证“假恢复信号拦截”。
