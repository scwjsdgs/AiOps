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

    public Mono<Map<String, Object>> graph(String deployment, int limit) {
        return listRecentTasks(limit)
                .flatMap(tasks -> Mono.fromCallable(() -> buildGraph(tasks, deployment)))
                .subscribeOn(Schedulers.boundedElastic());
    }

    public Mono<List<Map<String, Object>>> similarTasks(String deployment, String keyword,
                                                        int limit) {
        return listRecentTasks(200)
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
                            Map<String, Object> item = new LinkedHashMap<>();
                            item.put("id", t.getId());
                            item.put("status", t.getStatus());
                            item.put("alertId", t.getAlertId());
                            item.put("createdAt", t.getCreatedAt());
                            item.put("service", deployment == null ? "unknown" : deployment);
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

    private Mono<List<Task>> listRecentTasks(int limit) {
        return redisTemplate.scan(ScanOptions.scanOptions().match(TASK_KEY_PREFIX + "*").count(500).build())
                .collectList()
                .flatMap(keys -> {
                    if (keys.isEmpty()) {
                        return Mono.just(new ArrayList<Task>());
                    }
                    List<Mono<Task>> reads = keys.stream()
                            .map(k -> redisTemplate.<String, Object>opsForValue().get(k).cast(Task.class))
                            .toList();
                    return Flux.merge(reads)
                            .filter(java.util.Objects::nonNull)
                            .sort(Comparator.comparing(Task::getCreatedAt,
                                    Comparator.nullsLast(Comparator.reverseOrder())))
                            .take(limit)
                            .collectList();
                });
    }

    private Map<String, Object> buildGraph(List<Task> tasks, String deployment) {
        List<Map<String, Object>> nodes = new ArrayList<>();
        List<Map<String, Object>> links = new ArrayList<>();
        Set<String> nodeIds = new LinkedHashSet<>();
        Map<String, String> serviceById = new LinkedHashMap<>();
        Map<String, String> stepByName = new LinkedHashMap<>();

        for (Task t : tasks) {
            String input = t.getInput() == null ? "" : t.getInput();
            if (deployment != null && !deployment.isBlank()
                    && !input.toLowerCase().contains(deployment.toLowerCase())) {
                continue;
            }
            String taskId = t.getId();
            String service = extractService(input);
            List<String> steps = t.getAgentSteps() == null ? List.of() : t.getAgentSteps();
            String prevId = null;
            for (int i = 0; i < steps.size(); i++) {
                String step = steps.get(i);
                String stepId = taskId + ":step:" + i;
                String label = compact(step, 28);
                if (nodeIds.add(stepId)) {
                    nodes.add(node(label, "step", step, t.getStatus(), service));
                }
                stepByName.put(stepId, step);
                if (prevId != null) {
                    links.add(edge(prevId, stepId, "next"));
                }
                prevId = stepId;
            }
            if (service != null && !service.isBlank()) {
                serviceById.put(taskId, service);
                String svcId = "svc:" + service.toLowerCase();
                if (nodeIds.add(svcId)) {
                    nodes.add(node(service, "service", "受影响服务", t.getStatus(), service));
                }
                if (prevId != null) {
                    links.add(edge(svcId, prevId, "affects"));
                } else {
                    String alertId = "alert:" + (t.getAlertId() == null ? t.getId().substring(0, 8) : t.getAlertId());
                    if (nodeIds.add(alertId)) {
                        nodes.add(node("告警 " + compact(t.getAlertId() == null ? "" : t.getAlertId(), 8),
                                "alert", t.getInput() == null ? "" : t.getInput(), t.getStatus(), service));
                    }
                    links.add(edge(svcId, alertId, "triggers"));
                    links.add(edge(alertId, taskId, "analyzed_by"));
                    String taskNode = "task:" + taskId;
                    if (nodeIds.add(taskNode)) {
                        nodes.add(node("任务 " + compact(taskId, 8), "task", "", t.getStatus(), service));
                    }
                    prevId = taskNode;
                }
            }
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("nodes", nodes);
        result.put("links", links);
        result.put("taskCount", tasks.size());
        result.put("generatedAt", LocalDateTime.now());
        return result;
    }

    private String extractService(String input) {
        if (input == null) {
            return "unknown";
        }
        var m = java.util.regex.Pattern.compile("服务\\s+([A-Za-z0-9][A-Za-z0-9_.\\-]{0,62})")
                .matcher(input);
        return m.find() ? m.group(1) : "unknown";
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

    private Map<String, Object> node(String name, String category, String label,
                                     String status, String service) {
        Map<String, Object> n = new LinkedHashMap<>();
        n.put("id", "node_" + Math.abs(name.hashCode()) + "_" + category + "_" + status);
        n.put("name", name);
        n.put("category", category);
        n.put("label", label);
        n.put("status", status);
        n.put("service", service);
        return n;
    }

    private Map<String, Object> edge(String source, String target, String relation) {
        Map<String, Object> e = new LinkedHashMap<>();
        e.put("source", source);
        e.put("target", target);
        e.put("relation", relation);
        return e;
    }
}