package com.opsagent.tool;

import com.opsagent.model.ToolExecutionResult;
import io.fabric8.kubernetes.api.model.apps.Deployment;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 健康检查参数调整：修改 Deployment 探针的 initialDelaySeconds / failureThreshold。
 *
 * 参数：deployment、probe=liveness|readiness、initialDelaySeconds、failureThreshold。
 */
@Component
public class HealthCheckParamTool extends KubernetesToolBase implements Tool {

    @Override
    public String getName() {
        return "adjust_health_check";
    }

    @Override
    public String getDescription() {
        return "调整 Deployment 的 liveness/readiness 探针参数并触发滚动更新。";
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
        String probe = p.strOr("readiness", "probe");
        Integer initialDelay = p.integer("initialDelaySeconds");
        Integer failureThreshold = p.integer("failureThreshold");
        if (name == null) {
            return Tool.failure("需要参数 deployment", start);
        }
        try {
            Deployment deployment = client.apps().deployments()
                    .inNamespace(namespace).withName(name).get();
            if (deployment == null) {
                return Tool.failure("Deployment not found: " + name, start);
            }
            var containers = deployment.getSpec().getTemplate().getSpec().getContainers();
            if (containers == null || containers.isEmpty()) {
                return Tool.failure("Deployment 没有容器", start);
            }
            var container = containers.get(0);
            if ("liveness".equalsIgnoreCase(probe)) {
                var probeCfg = container.getLivenessProbe();
                if (probeCfg == null) {
                    return Tool.failure("该容器没有 livenessProbe", start);
                }
                if (initialDelay != null) {
                    probeCfg.setInitialDelaySeconds(initialDelay);
                }
                if (failureThreshold != null) {
                    probeCfg.setFailureThreshold(failureThreshold);
                }
            } else {
                var probeCfg = container.getReadinessProbe();
                if (probeCfg == null) {
                    return Tool.failure("该容器没有 readinessProbe", start);
                }
                if (initialDelay != null) {
                    probeCfg.setInitialDelaySeconds(initialDelay);
                }
                if (failureThreshold != null) {
                    probeCfg.setFailureThreshold(failureThreshold);
                }
            }
            client.apps().deployments().inNamespace(namespace).withName(name).patch(deployment);

            Map<String, Object> data = new LinkedHashMap<>();
            data.put("deployment", name);
            data.put("namespace", namespace);
            data.put("probe", probe);
            data.put("initialDelaySeconds", initialDelay);
            data.put("failureThreshold", failureThreshold);
            return Tool.success("健康检查 " + probe + " 参数已更新并触发滚动更新", data, start);
        } catch (Exception e) {
            return Tool.failure("健康检查参数调整失败: " + e.getMessage(), start);
        }
    }
}