package com.opsagent.tool;

import com.opsagent.model.ToolExecutionResult;
import io.fabric8.kubernetes.api.model.apps.Deployment;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 连接池调整：修改 Deployment 环境变量中的连接池参数并滚动重启。
 *
 * 参数：deployment、poolKey（如 DB_POOL_MAX）、poolValue。
 * 演示环境使用环境变量透传，生产可按实际应用注入 ConfigMap/启动参数。
 */
@Component
public class ConnectionPoolTool extends KubernetesToolBase implements Tool {

    @Override
    public String getName() {
        return "adjust_connection_pool";
    }

    @Override
    public String getDescription() {
        return "调整应用连接池参数（环境变量方式）并触发滚动重启。";
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
        String poolKey = p.str("poolKey", "key");
        String poolValue = p.str("poolValue", "value");
        if (name == null || poolKey == null || poolValue == null) {
            return Tool.failure("需要参数 deployment、poolKey、poolValue", start);
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
            var env = containers.get(0).getEnv();
            boolean updated = false;
            if (env != null) {
                for (var e : env) {
                    if (poolKey.equals(e.getName())) {
                        e.setValue(poolValue);
                        updated = true;
                        break;
                    }
                }
            }
            if (!updated) {
                containers.get(0).getEnv().add(
                        new io.fabric8.kubernetes.api.model.EnvVarBuilder()
                                .withName(poolKey).withValue(poolValue).build());
            }
            client.apps().deployments().inNamespace(namespace).withName(name).patch(deployment);

            Map<String, Object> data = new LinkedHashMap<>();
            data.put("deployment", name);
            data.put("namespace", namespace);
            data.put("poolKey", poolKey);
            data.put("poolValue", poolValue);
            data.put("rollingRestart", true);
            return Tool.success("连接池参数 " + poolKey + " 已调整为 " + poolValue + " 并触发滚动更新", data, start);
        } catch (Exception e) {
            return Tool.failure("连接池调整失败: " + e.getMessage(), start);
        }
    }
}