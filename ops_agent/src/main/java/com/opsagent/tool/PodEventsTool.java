package com.opsagent.tool;

import com.opsagent.model.ToolExecutionResult;
import io.fabric8.kubernetes.api.model.Event;
import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.api.model.apps.Deployment;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 查询 Pod 事件（Events）。
 *
 * 这是根因定位最直接的数据源：ImagePullBackOff、CrashLoopBackOff、OOMKilled、
 * 探针失败这些关键信息都在 Events 里，之前 Agent 完全拿不到——它只能看到
 * "服务不健康"，看不到"为什么"。
 *
 * Python 侧读 data.events（事件数组）/ eventCount / pod。
 */
@Component
public class PodEventsTool extends KubernetesToolBase implements Tool {

    private static final int MAX_EVENTS = 30;
    private static final DateTimeFormatter TS_FMT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.systemDefault());

    @Override
    public String getName() {
        return "query_pod_events";
    }

    @Override
    public String getDescription() {
        return "查询 Deployment 下 Pod 的 Kubernetes 事件（ImagePullBackOff、CrashLoopBackOff、OOMKilled、探针失败等），根因定位首选。";
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
                return Tool.failure("Deployment not found: " + name + " (namespace=" + namespace + ")", start);
            }

            List<Pod> pods = client.pods().inNamespace(namespace)
                    .withLabelSelector(deployment.getSpec().getSelector())
                    .list().getItems();

            List<Map<String, Object>> events = new ArrayList<>();
            for (Pod pod : pods) {
                String podName = pod.getMetadata().getName();
                // pod 状态摘要：waiting reason（如 ImagePullBackOff）是告警根因的高频来源
                Map<String, Object> podSummary = new LinkedHashMap<>();
                podSummary.put("pod", podName);
                podSummary.put("phase", pod.getStatus() != null ? pod.getStatus().getPhase() : "unknown");
                if (pod.getStatus() != null && pod.getStatus().getContainerStatuses() != null) {
                    List<Map<String, String>> cs = new ArrayList<>();
                    pod.getStatus().getContainerStatuses().forEach(s -> {
                        Map<String, String> m = new LinkedHashMap<>();
                        m.put("name", s.getName());
                        m.put("ready", String.valueOf(s.getState() != null && s.getState().getRunning() != null));
                        if (s.getState() != null && s.getState().getWaiting() != null) {
                            m.put("waitingReason", s.getState().getWaiting().getReason());
                        }
                        if (s.getState() != null && s.getState().getTerminated() != null) {
                            m.put("terminatedReason", s.getState().getTerminated().getReason());
                            m.put("exitCode", String.valueOf(s.getState().getTerminated().getExitCode()));
                        }
                        cs.add(m);
                    });
                    podSummary.put("containers", cs);
                }
                events.add(podSummary);

                List<Event> podEvents = client.v1().events().inNamespace(namespace)
                        .withField("involvedObject.name", podName)
                        .list().getItems();
                for (Event e : podEvents) {
                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("pod", podName);
                    item.put("type", e.getType());
                    item.put("reason", e.getReason());
                    item.put("message", e.getMessage());
                    item.put("count", e.getCount() != null ? e.getCount() : 1);
                    if (e.getLastTimestamp() != null && !e.getLastTimestamp().isBlank()) {
                        // fabric8 的 getLastTimestamp() 返回 ISO8601 字符串（如 2026-09-14T10:00:00Z），
                        // 不是时间对象；解析后转本地格式
                        try {
                            item.put("lastSeen", TS_FMT.format(Instant.parse(e.getLastTimestamp())));
                        } catch (Exception parseError) {
                            item.put("lastSeen", e.getLastTimestamp());
                        }
                    }
                    events.add(item);
                    if (events.size() >= MAX_EVENTS + pods.size()) {
                        break;
                    }
                }
            }

            Map<String, Object> data = new LinkedHashMap<>();
            data.put("deployment", name);
            data.put("namespace", namespace);
            data.put("podCount", pods.size());
            data.put("eventCount", events.size());
            data.put("events", events);

            return Tool.success("查询到 " + pods.size() + " 个 Pod、" + events.size() + " 条事件记录", data, start);
        } catch (Exception e) {
            return Tool.failure("查询 Pod 事件失败: " + e.getMessage(), start);
        }
    }
}
