package com.opsagent.service;

import io.fabric8.kubernetes.api.model.apps.Deployment;
import io.fabric8.kubernetes.client.KubernetesClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 集群状态查询，供 Dashboard「集群状态」卡片展示。
 *
 * 复用 KubernetesToolBase 已建好的 client（Config.autoConfigure 读 kubeconfig），
 * 只读、无副作用 —— 不在这里做任何 scale/rollback 之类的变更操作。
 *
 * K8s HTTP 是阻塞 IO，调用方（Controller）必须挪到 boundedElastic。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ClusterStatusService {

    private final KubernetesToolClientHolder holder;

    /** 拿到共享的 KubernetesClient 与 namespace。 */
    public Map<String, Object> status() {
        Map<String, Object> result = new LinkedHashMap<>();
        List<Map<String, Object>> deployments = new ArrayList<>();

        try {
            KubernetesClient client = holder.client();
            String namespace = holder.namespace();

            List<Deployment> items = client.apps().deployments().inNamespace(namespace).list().getItems();
            for (Deployment d : items) {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("name", d.getMetadata() != null ? d.getMetadata().getName() : "unknown");
                item.put("namespace", namespace);

                int replicas = d.getSpec() != null && d.getSpec().getReplicas() != null
                        ? d.getSpec().getReplicas() : 0;
                var status = d.getStatus();
                int ready = status != null && status.getReadyReplicas() != null ? status.getReadyReplicas() : 0;
                int available = status != null && status.getAvailableReplicas() != null ? status.getAvailableReplicas() : 0;
                int unavailable = status != null && status.getUnavailableReplicas() != null ? status.getUnavailableReplicas() : 0;

                // 与 GetStatusTool 相同的推导规则，前端按这个给颜色标签
                String derived;
                if (replicas == 0) {
                    derived = "stopped";
                } else if (unavailable > 0) {
                    derived = "degraded";
                } else if (ready >= replicas) {
                    derived = "running";
                } else {
                    derived = "pending";
                }

                item.put("replicas", replicas);
                item.put("readyReplicas", ready);
                item.put("availableReplicas", available);
                item.put("unavailableReplicas", unavailable);
                item.put("status", derived);
                item.put("image", firstImage(d));
                item.put("createdAt", d.getMetadata() != null ? d.getMetadata().getCreationTimestamp() : null);
                deployments.add(item);
            }

            result.put("connected", true);
            result.put("namespace", namespace);
            result.put("deployments", deployments);
        } catch (Exception e) {
            // 集群连不上不算接口错误：前端显示"集群不可用"，Dashboard 其它卡片照常工作
            log.warn("查询集群状态失败: {}", e.getMessage());
            result.put("connected", false);
            result.put("error", e.getMessage());
            result.put("deployments", deployments);
        }
        return result;
    }

    private String firstImage(Deployment deployment) {
        if (deployment.getSpec() == null || deployment.getSpec().getTemplate() == null
                || deployment.getSpec().getTemplate().getSpec() == null) {
            return "unknown";
        }
        var containers = deployment.getSpec().getTemplate().getSpec().getContainers();
        if (containers == null || containers.isEmpty() || containers.get(0).getImage() == null) {
            return "unknown";
        }
        return containers.get(0).getImage();
    }
}
