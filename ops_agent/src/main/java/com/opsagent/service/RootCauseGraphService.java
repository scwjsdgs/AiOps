package com.opsagent.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.opsagent.model.Task;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.ReactiveRedisTemplate;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 告警根因图谱与历史相似故障推荐。
 *
 * 基于已有 Task/告警/工具调用沉淀，离线式构建：
 *  - 节点：告警标题、服务、故障阶段（诊断/修复/验证）、结论关键词
 *  - 边：同一任务内步骤先后关系、相似故障关联
 *
 * 不引入图数据库，用现有 Redis 任务数据重算，前端用 ECharts 可渲染。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RootCauseGraphService {

    private final TaskSchedulerService taskScheduler;
    private final ReactiveRedisTemplate<String, Object> redisTemplate;

    private static final String TASK_KEY_PREFIX = "task:";
    private static final ObjectMapper MAPPER = new ObjectMapper();
    /** 后端硬上限：防止几千个步骤节点全量返回，前端图不可读。 */
    private static final int MAX_GRAPH_NODES = 50;

    public Mono<Map<String, Object>> graph(String deployment, int limit) {
        int nodeLimit = Math.max(1, Math.min(limit <= 0 ? MAX_GRAPH_NODES : limit, MAX_GRAPH_NODES));
        // 有过滤参数（deployment）时先扫更多最近任务再做包含过滤，避免匹配任务被“最近极小值”挤出；
        // 无过滤参数时只取最近 1 笔任务，避免把所有服务的孤岛全画出来，默认视图聚焦最近一次故障链路。
        boolean hasFilter = deployment != null && !deployment.isBlank();
        int scanLimit = hasFilter ? 50 : 1;
        int renderTasks = hasFilter ? 3 : 1;
        return listRecentTasks(deployment, scanLimit)
                .map(tasks -> tasks.stream().limit(renderTasks).toList())
                .flatMap(tasks -> Mono.fromCallable(() -> buildGraph(tasks, deployment, nodeLimit)))
                .onErrorReturn(Map.of(
                        "nodes", List.of(),
                        "links", List.of(),
                        "taskCount", 0,
                        "generatedAt", LocalDateTime.now()))
                .subscribeOn(Schedulers.boundedElastic());
    }

    public Mono<List<Map<String, Object>>> similarTasks(String deployment, String keyword,
                                                        int limit) {
        // 相似推荐同样只取最近数据，避免遍历全量历史任务。
        return listRecentTasks(null, Math.max(1, Math.min(limit <= 0 ? 20 : limit, 50)))
                .map(tasks -> {
                    List<Map<String, Object>> result = new ArrayList<>();
                    for (Task t : tasks) {
                        String text = ((t.getInput() == null ? "" : t.getInput())
                                + " " + (t.getOutput() == null ? "" : t.getOutput())).toLowerCase();
                        boolean matchDeployment = deployment == null || deployment.isBlank()
                                || text.contains(deployment.toLowerCase());
                        boolean matchKeyword = keyword == null || keyword.isBlank()
                                || text.contains(keyword.toLowerCase());
                        if (matchDeployment && matchKeyword) {
                            // 服务名只从任务输入/告警关联里解析，明确排除工具名前缀，
                            // 不能把 query_metrics 这类 Agent 工具名当成服务名。
                            String service = resolveServiceName(t);
                            Map<String, Object> item = new LinkedHashMap<>();
                            item.put("id", t.getId());
                            item.put("status", t.getStatus());
                            item.put("alertId", t.getAlertId());
                            item.put("createdAt", t.getCreatedAt());
                            item.put("service", service);
                            item.put("keywords", extractKeywords(t));
                            item.put("recommendation", "历史相似故障：" + (t.getStatus() == null ? "未知" : t.getStatus())
                                    + "，建议复查该任务的最终报告与工具调用链");
                            result.add(item);
                        }
                    }
                    return result.stream().limit(Math.max(1, limit)).toList();
                })
                .subscribeOn(Schedulers.boundedElastic());
    }

    private Mono<List<Task>> listRecentTasks(String deployment, int limit) {
        return redisTemplate.scan(ScanOptions.scanOptions().match(TASK_KEY_PREFIX + "*").count(500).build())
                .collectList()
                .flatMap(keys -> {
                    if (keys.isEmpty()) {
                        return Mono.just(new ArrayList<Task>());
                    }
                    List<Mono<Task>> reads = keys.stream()
                            .map(k -> redisTemplate.<String, Object>opsForValue().get(k)
                                    .cast(Task.class)
                                    // 单条历史任务格式损坏/字段缺失时跳过，而不是让整张图失败。
                                    // 修复前这会直接 500，前端只能看到“暂无根因图谱数据”。
                                    .onErrorResume(e -> {
                                        log.warn("读取历史任务失败 key={}: {}", k, e.getMessage());
                                        return Mono.empty();
                                    }))
                            .toList();
                    return Flux.concat(reads)
                            .filter(java.util.Objects::nonNull)
                            .filter(task -> taskMatchesDeployment(task, deployment))
                            .sort(Comparator.comparing(Task::getCreatedAt,
                                    Comparator.nullsLast(Comparator.reverseOrder())))
                            .take(limit)
                            .collectList();
                });
    }

    private boolean taskMatchesDeployment(Task t, String deployment) {
        if (deployment == null || deployment.isBlank()) {
            return true;
        }
        String text = ((t.getInput() == null ? "" : t.getInput())
                + " " + (t.getOutput() == null ? "" : t.getOutput())
                + " " + String.join(" ", t.getAgentSteps() == null ? List.of() : t.getAgentSteps())).toLowerCase();
        return text.contains(deployment.toLowerCase());
    }

    private Map<String, Object> buildGraph(List<Task> tasks, String deployment, int nodeLimit) {
        List<Map<String, Object>> nodes = new ArrayList<>();
        List<Map<String, Object>> links = new ArrayList<>();
        Set<String> nodeIds = new LinkedHashSet<>();

        for (Task t : tasks) {
            if (t == null || t.getId() == null || t.getId().isBlank()) {
                continue;
            }
            if (nodes.size() >= nodeLimit) {
                break;
            }
            String taskId = t.getId();
            String input = t.getInput() == null ? "" : t.getInput();
            String output = t.getOutput() == null ? "" : t.getOutput();
            List<String> steps = t.getAgentSteps() == null ? List.of() : t.getAgentSteps();
            if (deployment != null && !deployment.isBlank()
                    && !taskMatchesDeployment(t, deployment)) {
                continue;
            }
            String service = resolveServiceName(t);

            // 后端默认不展开步骤：任务节点代表整条推理链，步骤传给前端但默认折叠。
            String svcId = "svc:" + service.toLowerCase();
            if (nodeIds.add(svcId) && nodes.size() < nodeLimit) {
                nodes.add(node(svcId, service, "service", "受影响服务", t.getStatus(), service));
            }

            String alertId = "alert:" + (t.getAlertId() == null || t.getAlertId().isBlank()
                    ? "task-" + compact(taskId, 8) : t.getAlertId());
            if (nodeIds.add(alertId) && nodes.size() < nodeLimit) {
                nodes.add(node(alertId, compact(t.getAlertId() == null ? "" : t.getAlertId(), 8),
                        "alert", compact(input, 800), t.getStatus(), service));
            }
            links.add(edge(svcId, alertId, "triggers"));

            String taskNode = "task:" + taskId;
            if (nodeIds.add(taskNode) && nodes.size() < nodeLimit) {
                nodes.add(node(taskNode, "任务 " + compact(taskId, 8), "task", "", t.getStatus(), service));
            }
            links.add(edge(alertId, taskNode, "analyzed_by"));
            links.add(edge(taskNode, svcId, "involves"));

            // 步骤作为任务节点的 children 下发，前端默认不绘制成独立节点。
            List<Map<String, Object>> stepList = new ArrayList<>();
            for (int i = 0; i < steps.size(); i++) {
                String step = steps.get(i);
                Map<String, Object> stepNode = node(stepNodeId(taskId, i), compact(step, 28),
                        "step", step, t.getStatus(), service);
                stepList.add(stepNode);
            }
            if (!steps.isEmpty()) {
                // 往前端唯一挂载节点里塞 steps，让点击任务节点时可展开步骤。
                for (Map<String, Object> n : nodes) {
                    if (taskNode.equals(n.get("id"))) {
                        n.put("steps", stepList);
                        break;
                    }
                }
                // 默认主干链接只到任务，不画步骤边，避免几十个步骤挤爆视图。
            }
        }

        // 仍保留最后一道硬闸门，防止任何单任务步骤把数据撑爆。
        if (nodes.size() > nodeLimit) {
            List<Map<String, Object>> keptNodes = new ArrayList<>(nodes.subList(0, nodeLimit));
            Set<String> keptIds = new LinkedHashSet<>();
            for (Map<String, Object> n : keptNodes) {
                keptIds.add(String.valueOf(n.get("id")));
            }
            List<Map<String, Object>> keptLinks = links.stream()
                    .filter(l -> keptIds.contains(l.get("source")) && keptIds.contains(l.get("target")))
                    .toList();
            nodes = keptNodes;
            links = keptLinks;
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("nodes", nodes);
        result.put("links", links);
        result.put("taskCount", tasks.size());
        result.put("maxNodes", nodeLimit);
        result.put("generatedAt", LocalDateTime.now());
        return result;
    }

    private String resolveServiceName(Task t) {
        boolean chatops = t != null && "CHATOPS".equalsIgnoreCase(t.getType());
        if (t == null) {
            return chatops ? "ChatOps 会话" : "系统级告警";
        }
        String input = t.getInput() == null ? "" : t.getInput();
        String output = t.getOutput() == null ? "" : t.getOutput();

        // 1) input 里明确“服务 xxx”，最常见告警格式。
        String fromInput = extractExplicitService(input);
        if (fromInput != null) {
            return fromInput;
        }

        // 2) 兼容 input 里“xxx Pod 重启/xxx 服务/排查 xxx”等自由句。
        fromInput = extractImplicitService(input);
        if (fromInput != null) {
            return fromInput;
        }

        // 3) input 没找到时，再从最终报告里找“服务 xxx”，避免 b2944 这类报告明确但 input 不匹配的任务变 unknown。
        String fromOutput = extractExplicitService(output);
        if (fromOutput != null) {
            return fromOutput;
        }

        // 兜底：绝不返回 unknown。ChatOps 会话 / 内部巡检 / 系统级告警。
        if (chatops) {
            return "ChatOps 会话";
        }
        if (t.getId() != null && t.getId().startsWith("health_check_")) {
            return "内部巡检";
        }
        String status = t.getStatus() == null ? "" : t.getStatus();
        if ("FAILED".equalsIgnoreCase(status)
                || t.getAlertId() == null || t.getAlertId().isBlank()) {
            return "内部巡检";
        }
        return "系统级告警";
    }

    private String extractExplicitService(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        var m = java.util.regex.Pattern.compile("服务\\s+([A-Za-z0-9][A-Za-z0-9_.\\-]{1,62})")
                .matcher(text);
        return m.find() ? m.group(1) : null;
    }

    private String extractImplicitService(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        var m = java.util.regex.Pattern.compile(
                        "(?i)(?:排查|分析|检查|查看)?\\s*([A-Za-z0-9][A-Za-z0-9_.\\-]{1,62})\\s*(?:服务|Pod|为什么|为何|频繁|一直)")
                .matcher(text);
        if (!m.find()) {
            return null;
        }
        String candidate = m.group(1);
        return isLikelyServiceName(candidate) ? candidate : null;
    }

    private boolean isLikelyServiceName(String candidate) {
        if (candidate == null || candidate.isBlank()) {
            return false;
        }
        String lower = candidate.toLowerCase();
        // 明确排除工具名/步骤名伪服务：query_、tool_、get_、search_ 等。
        return !lower.startsWith("query_")
                && !lower.startsWith("tool_")
                && !lower.startsWith("get_")
                && !lower.startsWith("search_")
                && !lower.startsWith("list_")
                && !lower.startsWith("analyze_")
                && !lower.equals("the")
                && !lower.equals("a")
                && !lower.equals("an")
                && !lower.equals("of");
    }

    private List<String> extractKeywords(Task task) {
        Set<String> keywords = new LinkedHashSet<>();
        String output = task.getOutput() == null ? "" : task.getOutput();
        String[] patterns = {"根因", "OOM", "CrashLoop", "ImagePull", "重启", "扩容", "回滚", "超时", "断流", "探针"};
        for (String p : patterns) {
            if (output.contains(p)) {
                keywords.add(p);
            }
        }
        return new ArrayList<>(keywords).stream().limit(6).toList();
    }

    private String compact(String s, int max) {
        if (s == null) {
            return "";
        }
        s = s.replaceAll("\\s+", " ").trim();
        return s.length() <= max ? s : s.substring(0, max) + "...";
    }

    private Map<String, Object> node(String id, String name, String category, String label,
                                     String status, String service) {
        Map<String, Object> n = new LinkedHashMap<>();
        n.put("id", id);
        n.put("name", name);
        n.put("category", category);
        n.put("label", label);
        n.put("status", status);
        n.put("service", service);
        return n;
    }

    private String stepNodeId(String taskId, int index) {
        return taskId + ":step:" + index;
    }

    private Map<String, Object> edge(String source, String target, String relation) {
        Map<String, Object> e = new LinkedHashMap<>();
        e.put("source", source);
        e.put("target", target);
        e.put("relation", relation);
        return e;
    }
}