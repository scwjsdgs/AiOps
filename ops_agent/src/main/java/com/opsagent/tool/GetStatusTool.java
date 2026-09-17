package com.opsagent.tool;

import com.opsagent.model.ToolExecutionResult;
import io.fabric8.kubernetes.api.model.Container;
import io.fabric8.kubernetes.api.model.apps.Deployment;
import io.fabric8.kubernetes.api.model.apps.DeploymentCondition;
import io.fabric8.kubernetes.api.model.apps.DeploymentStatus;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 查询 deployment 的运行状态。
 *
 * 这是 agent 决策链的起点：不知道当前副本数和就绪情况就调 scale，
 * 等于闭着眼睛改线上。Python 侧读 data.status / replicas / readyReplicas / image。
 */
@Component
public class GetStatusTool extends KubernetesToolBase implements Tool {

    @Override
    public String getName() {
        return "get_status";
    }

    @Override
    public String getDescription() {
        return "获取 Deployment 的运行状态：副本数、就绪副本数、镜像、状态条件。";
    }

    @Override
    public boolean isIdempotent() {
        return true;
    }

    @Override
    public ToolExecutionResult execute(Map<String, Object> parameters) {
        long start = System.currentTimeMillis();
        ToolParams p = ToolParams.of(parameters);
        String name = p.str("deployment", "service");
        if (name == null) {
            return Tool.failure("Missing 'deployment' parameter（也接受 'service'）", start);
        }

        try {
            Deployment deployment = client.apps().deployments()
                    .inNamespace(namespace).withName(name).get();

            if (deployment == null) {
                // 这里返回 success=true 而不是失败：deployment 不存在是一个事实，
                // 不是异常。让 LLM 拿到 status=not_found 才能判断"服务根本没部署"，
                // 而不是看到"查询失败"后去重试别的工具。
                Map<String, Object> data = new LinkedHashMap<>();
                data.put("deployment", name);
                data.put("namespace", namespace);
                data.put("status", "not_found");
                data.put("replicas", 0);
                data.put("readyReplicas", 0);
                data.put("image", "unknown");
                data.put("conditions", List.of());
                return Tool.success("Deployment " + name + " not found in namespace " + namespace, data, start);
            }

            int replicas = deployment.getSpec() != null && deployment.getSpec().getReplicas() != null
                    ? deployment.getSpec().getReplicas() : 0;

            var status = deployment.getStatus();
            int ready = status != null && status.getReadyReplicas() != null ? status.getReadyReplicas() : 0;
            int available = status != null && status.getAvailableReplicas() != null ? status.getAvailableReplicas() : 0;
            int unavailable = status != null && status.getUnavailableReplicas() != null ? status.getUnavailableReplicas() : 0;

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

            Map<String, Object> data = new LinkedHashMap<>();
            data.put("deployment", name);
            data.put("namespace", namespace);
            // status 是 Python 侧直接读的 key，必须始终存在
            data.put("status", derived);
            data.put("replicas", replicas);
            data.put("readyReplicas", ready);
            data.put("availableReplicas", available);
            data.put("unavailableReplicas", unavailable);
            data.put("image", firstImage(deployment));
            data.put("createdAt", deployment.getMetadata() != null
                    ? deployment.getMetadata().getCreationTimestamp() : null);
            data.put("conditions", conditions(status));

            return Tool.success(
                    "Deployment " + name + " is " + derived
                            + " (" + ready + "/" + replicas + " ready)",
                    data, start);
        } catch (Exception e) {
            return Tool.failure("Get status failed: " + e.getMessage(), start);
        }
    }

    private String firstImage(Deployment deployment) {
        if (deployment.getSpec() == null || deployment.getSpec().getTemplate() == null
                || deployment.getSpec().getTemplate().getSpec() == null) {
            return "unknown";
        }
        List<Container> containers = deployment.getSpec().getTemplate().getSpec().getContainers();
        if (containers == null || containers.isEmpty() || containers.get(0).getImage() == null) {
            return "unknown";
        }
        return containers.get(0).getImage();
    }

    private List<Map<String, String>> conditions(DeploymentStatus status) {
        List<Map<String, String>> result = new ArrayList<>();
        if (status == null || status.getConditions() == null) {
            return result;
        }
        for (DeploymentCondition c : status.getConditions()) {
            Map<String, String> item = new LinkedHashMap<>();
            item.put("type", c.getType());
            item.put("status", c.getStatus());
            item.put("reason", c.getReason());
            item.put("message", c.getMessage());
            result.add(item);
        }
        return result;
    }
}
