package com.opsagent.tool;

import com.opsagent.model.ToolExecutionResult;
import io.fabric8.kubernetes.api.model.apps.Deployment;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

@Component
public class RestartServiceTool extends KubernetesToolBase implements Tool {

    @Override
    public String getName() {
        return "restart_service";
    }

    @Override
    public String getDescription() {
        return "通过先将副本缩容到 0、再恢复到原始副本数来重启 Deployment。";
    }

    @Override
    public boolean isDangerous() {
        return true;
    }

    @Override
    public ToolExecutionResult execute(Map<String, Object> parameters) {
        long start = System.currentTimeMillis();
        ToolParams p = ToolParams.of(parameters);
        // Python 侧 execute_repair_action 会同时发 service 和 deployment 两个 key
        String name = p.str("deployment", "service");
        if (name == null) {
            return Tool.failure("Missing 'deployment' parameter（也接受 'service'）", start);
        }

        try {
            Deployment deployment = client.apps().deployments()
                    .inNamespace(namespace).withName(name).get();
            if (deployment == null) {
                return Tool.failure("Deployment not found: " + name + " (namespace=" + namespace + ")", start);
            }
            int replicas = deployment.getSpec() != null && deployment.getSpec().getReplicas() != null
                    ? deployment.getSpec().getReplicas() : 1;

            // 这里刻意不做本地重试（原实现最多试 3 次）。重启不是幂等操作，
            // 重试等于对生产服务反复摘流量；失败就如实返回，让 LLM 显式决定是否再来一次。
            boolean scaledDown = false;
            try {
                client.apps().deployments().inNamespace(namespace).withName(name).scale(0, true);
                scaledDown = true;
                Thread.sleep(3000);
                client.apps().deployments().inNamespace(namespace).withName(name).scale(replicas, true);
                scaledDown = false;
            } finally {
                // 缩容成功但扩容失败时，服务会停在 0 副本静默下线。
                // 这是本工具最危险的失败模式，必须尽力恢复。
                if (scaledDown) {
                    try {
                        client.apps().deployments().inNamespace(namespace).withName(name).scale(replicas, true);
                    } catch (Exception restoreError) {
                        return Tool.failure(
                                "Restart 失败且副本数恢复也失败！" + name + " 可能仍停在 0 副本，"
                                        + "请立即手工处理。恢复错误：" + restoreError.getMessage(),
                                start);
                    }
                }
            }

            Map<String, Object> data = new LinkedHashMap<>();
            data.put("deployment", name);
            data.put("namespace", namespace);
            data.put("previousReplicas", replicas);
            data.put("replicas", replicas);
            return Tool.success("Deployment " + name + " restarted (scaled 0 -> " + replicas + ").", data, start);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Tool.failure("Restart interrupted: " + e.getMessage(), start);
        } catch (Exception e) {
            return Tool.failure("Restart failed: " + e.getMessage(), start);
        }
    }
}
