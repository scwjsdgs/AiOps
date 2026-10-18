package com.opsagent.tool;

import com.opsagent.model.ToolExecutionResult;
import io.fabric8.kubernetes.api.model.Pod;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Pod 驱逐：删除指定 Pod，由控制器（Deployment/StatefulSet）重建。
 *
 * 适合「单 Pod 卡死、需要快速替换」的高危操作；会触发重启。
 */
@Component
public class PodEvictTool extends KubernetesToolBase implements Tool {

    @Override
    public String getName() {
        return "pod_evict";
    }

    @Override
    public String getDescription() {
        return "驱逐指定 Pod（删除后由控制器重建），用于替换卡死实例。";
    }

    @Override
    public boolean isDangerous() {
        return true;
    }

    @Override
    public ToolExecutionResult execute(Map<String, Object> parameters) {
        long start = System.currentTimeMillis();
        ToolParams p = ToolParams.of(parameters);
        String pod = p.str("pod", "podName");
        if (pod == null) {
            return Tool.failure("需要参数 pod/podName", start);
        }
        try {
            Pod existing = client.pods().inNamespace(namespace).withName(pod).get();
            if (existing == null) {
                return Tool.failure("Pod not found: " + pod, start);
            }
            client.pods().inNamespace(namespace).withName(pod).delete();
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("pod", pod);
            data.put("namespace", namespace);
            data.put("evicted", true);
            return Tool.success("Pod " + pod + " 已驱逐，控制器将重建", data, start);
        } catch (Exception e) {
            return Tool.failure("Pod 驱逐失败: " + e.getMessage(), start);
        }
    }
}