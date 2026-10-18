package com.opsagent.tool;

import com.opsagent.model.ToolExecutionResult;
import io.fabric8.kubernetes.api.model.Service;
import io.fabric8.kubernetes.api.model.ServiceSpec;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 流量切换：调整 Service 后端 selector 指向备用 Deployment。
 *
 * 用于蓝绿/金丝雀演练：把 Service 从 active 切到 standby。
 * 参数：service（Service 名）、activeDeployment（当前后端）、standbyDeployment（目标后端）。
 */
@Component
public class TrafficSwitchTool extends KubernetesToolBase implements Tool {

    @Override
    public String getName() {
        return "traffic_switch";
    }

    @Override
    public String getDescription() {
        return "切换 Service 的后端选择器到另一个 Deployment（蓝绿/金丝雀流量切换）。";
    }

    @Override
    public boolean isDangerous() {
        return true;
    }

    @Override
    public ToolExecutionResult execute(Map<String, Object> parameters) {
        long start = System.currentTimeMillis();
        ToolParams p = ToolParams.of(parameters);
        String serviceName = p.str("service", "serviceName");
        String active = p.str("activeDeployment");
        String standby = p.str("standbyDeployment");
        if (serviceName == null || active == null || standby == null) {
            return Tool.failure("需要参数 service、activeDeployment、standbyDeployment", start);
        }
        try {
            Service service = client.services().inNamespace(namespace).withName(serviceName).get();
            if (service == null) {
                return Tool.failure("Service not found: " + serviceName, start);
            }
            ServiceSpec spec = service.getSpec();
            if (spec == null || spec.getSelector() == null) {
                return Tool.failure("Service 没有 selector，无法切换", start);
            }
            Map<String, String> selector = new LinkedHashMap<>(spec.getSelector());
            if (!active.equals(selector.get("app"))) {
                // 允许自定义 selector key，默认 app
                String key = selector.keySet().stream().findFirst().orElse("app");
                selector.put(key, standby);
            } else {
                selector.put("app", standby);
            }
            spec.setSelector(selector);
            client.services().inNamespace(namespace).withName(serviceName).patch(service);

            Map<String, Object> data = new LinkedHashMap<>();
            data.put("service", serviceName);
            data.put("namespace", namespace);
            data.put("from", active);
            data.put("to", standby);
            data.put("selector", selector);
            return Tool.success("Service " + serviceName + " 流量入口已切换至 " + standby, data, start);
        } catch (Exception e) {
            return Tool.failure("流量切换失败: " + e.getMessage(), start);
        }
    }
}