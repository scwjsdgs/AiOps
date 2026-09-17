package com.opsagent.tool;


import com.opsagent.model.ToolExecutionResult;
import io.fabric8.kubernetes.api.model.apps.Deployment;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

@Component
public class ScaleUpTool extends KubernetesToolBase implements Tool {

    /** 上限是防呆：LLM 完全可能把 replicas 写成 9999，那是一次真实的集群打爆。 */
    private static final int MAX_REPLICAS = 50;

    @Override
    public String getName() {
        return "scale_up";
    }

    @Override
    public String getDescription() {
        return "将 Deployment 扩容到目标副本数。";
    }

    @Override
    public boolean isDangerous() {
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

        Integer replicas = p.integer("replicas", "count", "targetReplicas");
        if (replicas == null) {
            return Tool.failure("Missing or invalid 'replicas' parameter（必须是整数）", start);
        }
        if (replicas < 0 || replicas > MAX_REPLICAS) {
            return Tool.failure("'replicas' 必须在 0.." + MAX_REPLICAS + " 之间，收到 " + replicas, start);
        }

        try {
            Deployment deployment = client.apps().deployments()
                    .inNamespace(namespace).withName(name).get();
            if (deployment == null) {
                return Tool.failure("Deployment not found: " + name + " (namespace=" + namespace + ")", start);
            }
            int previous = deployment.getSpec() != null && deployment.getSpec().getReplicas() != null
                    ? deployment.getSpec().getReplicas() : 0;

            client.apps().deployments().inNamespace(namespace).withName(name).scale(replicas, true);

            Map<String, Object> data = new LinkedHashMap<>();
            data.put("deployment", name);
            data.put("namespace", namespace);
            // Python 侧用 previousReplicas 和 replicas 拼"由 X 调整为 Y"的结论
            data.put("previousReplicas", previous);
            data.put("replicas", replicas);
            return Tool.success(
                    "Scaled " + name + " from " + previous + " to " + replicas + " replicas.",
                    data, start);
        } catch (Exception e) {
            return Tool.failure("Scale failed: " + e.getMessage(), start);
        }
    }
}
