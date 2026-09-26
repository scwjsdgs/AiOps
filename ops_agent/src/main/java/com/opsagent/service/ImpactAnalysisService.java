package com.opsagent.service;

import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.api.model.apps.Deployment;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 故障影响面分析（前瞻性增强方案二）。
 *
 * 解决的问题：agent 原来只能告诉运维「leaky-app 的 Pod 在崩溃循环」，
 * 说不了「这影响了哪个服务入口、流量是不是断了、哪些下游在受影响」。
 * 后者才是运维决策真正需要的信息 —— 定位到 Pod 只是第一步，
 * 判断要不要立刻介入，看的是有没有业务流量被切断。
 *
 * 做法：基于 {@link TopologyService} 的 Service→Pod 映射反查故障实例，
 * 输出结构化的影响面：受影响的 Service、各 Service 的端点健康度（是否断流）、
 * 该 Deployment 的整体就绪情况，并渲染成一段可直接塞进 LLM 上下文的文字摘要。
 *
 * 只读、无副作用。K8s HTTP 是阻塞 IO，调用方必须挪到 boundedElastic。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ImpactAnalysisService {

    private final TopologyService topologyService;
    private final KubernetesToolClientHolder holder;

    /**
     * 分析某个 Deployment 的故障影响面。
     *
     * @param deploymentName 故障服务名。找不到时返回 available=false 而非抛异常 ——
     *                       「查不到」是个事实，交给调用方决定怎么展示。
     */
    public Map<String, Object> analyze(String deploymentName) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("deployment", deploymentName);

        // 先校验参数，再去碰集群：名字都没给就没必要发起任何 K8s 调用
        if (deploymentName == null || deploymentName.isBlank()) {
            result.put("available", false);
            result.put("reason", "未提供 deployment 名称");
            return result;
        }
        result.put("namespace", holder.namespace());

        try {
            Deployment deployment = holder.client().apps().deployments()
                    .inNamespace(holder.namespace()).withName(deploymentName).get();
            if (deployment == null) {
                result.put("available", false);
                result.put("reason", "Deployment 不存在：" + deploymentName);
                return result;
            }

            // 1. 自身健康度
            int desired = deployment.getSpec() != null && deployment.getSpec().getReplicas() != null
                    ? deployment.getSpec().getReplicas() : 0;
            var status = deployment.getStatus();
            int ready = status != null && status.getReadyReplicas() != null ? status.getReadyReplicas() : 0;
            int unavailable = status != null && status.getUnavailableReplicas() != null
                    ? status.getUnavailableReplicas() : 0;
            result.put("desiredReplicas", desired);
            result.put("readyReplicas", ready);
            result.put("unavailableReplicas", unavailable);
            result.put("selfDegraded", unavailable > 0 || (desired > 0 && ready < desired));

            // 2. 受影响的 Service（流量入口）
            List<String> services = topologyService.servicesOfDeployment(deploymentName);
            List<Map<String, Object>> serviceImpacts = new ArrayList<>();
            boolean anyOutage = false;
            for (String svc : services) {
                Map<String, Object> health = topologyService.serviceEndpointHealth(svc);
                // 断流是最严重的信号：有实例但全部不可用，业务流量此刻已经中断
                anyOutage = anyOutage || Boolean.TRUE.equals(health.get("outage"));
                serviceImpacts.add(health);
            }
            result.put("affectedServices", serviceImpacts);
            // outage = 至少一个入口服务已断流
            result.put("outage", anyOutage);

            // 3. 受影响 Pod 明细（未就绪的挑出来，这是"哪些实例在拖后腿"的直接答案）
            List<Map<String, Object>> affectedPods = new ArrayList<>();
            try {
                List<Pod> pods = holder.client().pods().inNamespace(holder.namespace())
                        .withLabelSelector(deployment.getSpec().getSelector()).list().getItems();
                for (Pod pod : pods) {
                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("pod", pod.getMetadata().getName());
                    item.put("ready", isPodReady(pod));
                    item.put("phase", pod.getStatus() != null ? pod.getStatus().getPhase() : "unknown");
                    List<String> reasons = containerProblems(pod);
                    if (!reasons.isEmpty()) {
                        item.put("problems", reasons);
                    }
                    affectedPods.add(item);
                }
            } catch (Exception e) {
                log.debug("列举受影响 Pod 失败 deployment={}: {}", deploymentName, e.getMessage());
            }
            result.put("affectedPods", affectedPods);

            // 4. 影响面文字摘要：直接追加进任务 input / 作为工具返回，让 LLM 一次看到结论
            result.put("summary", renderSummary(deploymentName, desired, ready, unavailable,
                    serviceImpacts, affectedPods, anyOutage));
            result.put("available", true);
            return result;

        } catch (Exception e) {
            log.warn("影响面分析失败 deployment={}: {}", deploymentName, e.getMessage());
            result.put("available", false);
            result.put("reason", "影响面分析失败：" + e.getMessage());
            return result;
        }
    }

    /**
     * 渲染人类可读 / LLM 可读的影响面摘要。
     *
     * 写成这种「结论先行 + 分点」的格式，是因为它会被直接拼进 LLM 的输入：
     * 结构化短句比 JSON 更容易被模型正确引用，也更适合出现在最终报告里。
     */
    private String renderSummary(String deploymentName, int desired, int ready, int unavailable,
                                 List<Map<String, Object>> serviceImpacts,
                                 List<Map<String, Object>> affectedPods, boolean outage) {
        StringBuilder sb = new StringBuilder();
        sb.append("【影响面分析】服务 ").append(deploymentName).append("：就绪 ")
                .append(ready).append("/").append(desired);
        if (unavailable > 0) {
            sb.append("，不可用 ").append(unavailable).append(" 个");
        }

        if (outage) {
            sb.append("。⚠️ 已断流：");
            boolean first = true;
            for (Map<String, Object> svc : serviceImpacts) {
                if (Boolean.TRUE.equals(svc.get("outage"))) {
                    if (!first) {
                        sb.append("、");
                    }
                    sb.append(svc.get("service"));
                    first = false;
                }
            }
            sb.append(" 的就绪端点为 0，业务流量已中断。");
        } else if (serviceImpacts.isEmpty()) {
            sb.append("。未发现关联的 Service（可能没有对外暴露流量入口）。");
        } else {
            sb.append("。流量入口未断流（");
            for (int i = 0; i < serviceImpacts.size(); i++) {
                Map<String, Object> svc = serviceImpacts.get(i);
                if (i > 0) {
                    sb.append("、");
                }
                sb.append(svc.get("service")).append(" 就绪端点 ")
                        .append(svc.get("readyEndpoints"));
            }
            sb.append("），业务暂未受直接影响。");
        }

        List<String> problemPods = affectedPods.stream()
                .filter(p -> Boolean.FALSE.equals(p.get("ready")))
                .map(p -> p.get("pod").toString())
                .toList();
        if (!problemPods.isEmpty()) {
            sb.append(" 异常实例 ").append(problemPods.size()).append(" 个：")
                    .append(String.join("、", problemPods)).append("。");
        }
        return sb.toString();
    }

    private boolean isPodReady(Pod pod) {
        if (pod.getStatus() == null || pod.getStatus().getContainerStatuses() == null
                || pod.getStatus().getContainerStatuses().isEmpty()) {
            return false;
        }
        return pod.getStatus().getContainerStatuses().stream()
                .allMatch(cs -> Boolean.TRUE.equals(cs.getReady()));
    }

    /** 容器异常原因（CrashLoopBackOff / ImagePullBackOff 等）。 */
    private List<String> containerProblems(Pod pod) {
        List<String> problems = new ArrayList<>();
        if (pod.getStatus() == null || pod.getStatus().getContainerStatuses() == null) {
            return problems;
        }
        for (var cs : pod.getStatus().getContainerStatuses()) {
            if (cs.getState() == null) {
                continue;
            }
            if (cs.getState().getWaiting() != null && cs.getState().getWaiting().getReason() != null) {
                problems.add(cs.getState().getWaiting().getReason());
            }
            if (cs.getState().getTerminated() != null && cs.getState().getTerminated().getReason() != null) {
                problems.add("terminated:" + cs.getState().getTerminated().getReason());
            }
            if (cs.getRestartCount() != null && cs.getRestartCount() > 0) {
                problems.add("restarts=" + cs.getRestartCount());
            }
        }
        return problems;
    }

    /**
     * 从告警描述文本里解析服务名，供告警触发时自动分析影响面。
     *
     * 告警描述格式为「服务 leaky-app 发生告警：...」（Dashboard / Webhook 都遵循），
     * 解析不到时返回 null，调用方跳过影响面注入即可 —— 不影响主链路。
     */
    public String resolveServiceFromText(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("服务\\s+([A-Za-z0-9][A-Za-z0-9_.\\-]{0,62})")
                .matcher(text);
        return m.find() ? m.group(1) : null;
    }
}
