package com.opsagent.tool;

import com.opsagent.model.ToolExecutionResult;
import io.fabric8.kubernetes.api.model.Node;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 节点排水：为节点打不可调度污点并驱逐非守护 Pod。
 *
 * 高危操作，执行前应有人工审批。fabric8 没有轻量 drain 封装，
 * 这里做安全版：标记 unschedulable，后续由平台调用方决定是否删除 Pod。
 */
@Component
public class NodeDrainTool extends KubernetesToolBase implements Tool {

    @Override
    public String getName() {
        return "node_drain";
    }

    @Override
    public String getDescription() {
        return "将节点标记为不可调度（排水第一步），并返回节点上待迁移 Pod 清单。";
    }

    @Override
    public boolean isDangerous() {
        return true;
    }

    @Override
    public ToolExecutionResult execute(Map<String, Object> parameters) {
        long start = System.currentTimeMillis();
        ToolParams p = ToolParams.of(parameters);
        String nodeName = p.str("node", "nodeName");
        String action = p.strOr("drain", "action");
        if (nodeName == null) {
            return Tool.failure("需要参数 node/nodeName", start);
        }
        try {
            Node node = client.nodes().withName(nodeName).get();
            if (node == null) {
                return Tool.failure("Node not found: " + nodeName, start);
            }
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("node", nodeName);
            data.put("namespace", namespace);

            if ("drain".equalsIgnoreCase(action)) {
                node.getSpec().setUnschedulable(Boolean.TRUE);
                client.nodes().withName(nodeName).patch(node);
                data.put("unschedulable", Boolean.TRUE);
                data.put("message", "节点已标记不可调度；请确认影响面后手工驱逐 Pod（本工具不自动删除 Pod，降低误伤）。");
            } else if ("uncordon".equalsIgnoreCase(action)) {
                node.getSpec().setUnschedulable(Boolean.FALSE);
                client.nodes().withName(nodeName).patch(node);
                data.put("unschedulable", Boolean.FALSE);
                data.put("message", "节点已恢复可调度");
            } else {
                return Tool.failure("action 仅支持 drain / uncordon", start);
            }
            return Tool.success(data.get("message").toString(), data, start);
        } catch (Exception e) {
            return Tool.failure("节点排水失败: " + e.getMessage(), start);
        }
    }
}