package com.opsagent.tool;

import com.opsagent.model.ToolExecutionResult;
import io.fabric8.kubernetes.api.model.apps.Deployment;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 配置回滚：把 Deployment 回滚到上一个 Revision。
 *
 * 使用 Kubernetes Deployment 原生 rollout undo，不修改镜像/配置内容，
 * 适合「上一版配置或镜像导致故障」的快速回退。
 */
@Component
public class ConfigRollbackTool extends KubernetesToolBase implements Tool {

    @Override
    public String getName() {
        return "config_rollback";
    }

    @Override
    public String getDescription() {
        return "将 Deployment 的配置/模板回滚到上一个 Revision（rollout undo）。";
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
        try {
            String resource = "deployments/" + name;
            // fabric8 6.x 的 rolling().undo() 无返回值；回滚后等待少量时间让控制器生效，
            // 后续如需确认可查询 ReplicaSet/Revision 注释。
            client.apps().deployments().inNamespace(namespace).withName(name).rolling().undo();
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("deployment", name);
            data.put("namespace", namespace);
            data.put("rolledBack", true);
            return Tool.success("Deployment " + name + " 配置已回滚到上一 Revision（" + resource + "）", data, start);
        } catch (Exception e) {
            return Tool.failure("配置回滚失败: " + e.getMessage(), start);
        }
    }
}