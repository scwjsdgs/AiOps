package com.opsagent.service;


import com.opsagent.model.Task;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.ReactiveRedisTemplate;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class TaskSchedulerService {

    private final ReactiveRedisTemplate<String, Object> redisTemplate;

    private static final String TASK_KEY_PREFIX = "task:";

    /** 单条 step 的字符上限：一条 step 可能是整段日志，不截断会撑爆一次 Redis 写入。 */
    private static final int MAX_STEP_CHARS = 500;
    /** 每个任务保留的 step 条数上限：agent 最多 8 轮，200 条足够排查。 */
    private static final int MAX_STEPS = 200;
    /** 任务保留时长：不设 TTL 的话任务永久堆积，SCAN 列表会越来越慢。7 天足够复盘。 */
    private static final Duration TASK_TTL = Duration.ofDays(7);

    public Mono<Task> createTask(String type, String alertId, String input) {
        Task task = new Task();
        task.setId(UUID.randomUUID().toString());
        task.setType(type);
        task.setAlertId(alertId);
        task.setStatus("PENDING");
        task.setInput(input);
        task.setAgentSteps(new ArrayList<>());
        task.setCreatedBy("system");
        task.setCreatedAt(LocalDateTime.now());
        task.setUpdatedAt(LocalDateTime.now());
        return redisTemplate.opsForValue()
                .set(TASK_KEY_PREFIX + task.getId(), task, TASK_TTL)
                .then(Mono.just(task));
    }

    /**
     * 注意：key 不存在时返回空 Mono（不是 null）。
     * 所有下游都靠 defaultIfEmpty / switchIfEmpty 兜底，否则会静默什么都不做。
     */
    public Mono<Task> getTask(String id) {
        return redisTemplate.opsForValue()
                .get(TASK_KEY_PREFIX + id)
                .cast(Task.class);
    }

    /**
     * 分页列出任务，可按 alertId 过滤。
     *
     * 这是告警页"查看分析"和 Dashboard 统计的数据源：告警与任务靠 alertId
     * 关联，没有这个方法前端就打不开 Agent 的分析报告。
     *
     * 用 SCAN 而不是 KEYS：KEYS 在 key 量大时阻塞 Redis 整个主线程；
     * SCAN 游标遍历不阻塞。注意 ReactiveRedisTemplate.scan 返回的 Flux
     * 必须在 boundedElastic 上跑（下面 subscribeOn），别占 event loop。
     */
    public Mono<List<Task>> listTasks(String alertId, int page, int size) {
        return listTasksViaScan(alertId, page, size)
                .subscribeOn(Schedulers.boundedElastic());
    }

    private Mono<List<Task>> listTasksViaScan(String alertId, int page, int size) {
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
                            .filter(t -> alertId == null || alertId.isBlank() || alertId.equals(t.getAlertId()))
                            .sort(java.util.Comparator.comparing(Task::getCreatedAt,
                                    java.util.Comparator.nullsLast(java.util.Comparator.reverseOrder())))
                            .collectList()
                            .map(list -> {
                                int from = Math.max(page - 1, 0) * size;
                                if (from >= list.size()) {
                                    return new ArrayList<Task>();
                                }
                                int to = Math.min(from + size, list.size());
                                return new ArrayList<>(list.subList(from, to));
                            });
                });
    }

    /** 任务总数（同样支持 alertId 过滤），供分页 total 使用。 */
    public Mono<Long> countTasks(String alertId) {
        return redisTemplate.scan(ScanOptions.scanOptions().match(TASK_KEY_PREFIX + "*").count(500).build())
                .collectList()
                .flatMap(keys -> {
                    if (keys.isEmpty()) {
                        return Mono.just(0L);
                    }
                    if (alertId == null || alertId.isBlank()) {
                        return Mono.just((long) keys.size());
                    }
                    List<Mono<Task>> reads = keys.stream()
                            .map(k -> redisTemplate.<String, Object>opsForValue().get(k).cast(Task.class))
                            .toList();
                    return Flux.merge(reads)
                            .filter(java.util.Objects::nonNull)
                            .filter(t -> alertId.equals(t.getAlertId()))
                            .count();
                });
    }

    public Mono<Boolean> updateTaskStatus(String id, String status) {
        return mutate(id, task -> task.setStatus(status));
    }

    public Mono<Boolean> updateTaskOutput(String id, String output) {
        return mutate(id, task -> task.setOutput(output));
    }

    /** 终态落库：状态和输出一起写，避免出现"状态 SUCCESS 但 output 还是旧的"中间态。 */
    public Mono<Boolean> completeTask(String id, String output, String status) {
        return mutate(id, task -> {
            task.setOutput(output);
            task.setStatus(status);
        });
    }

    /**
     * 追加一条 agent 推理步骤。
     *
     * 这个方法是 Task.agentSteps 唯一的写入点 —— 改造前它是空的，
     * 前端任务详情页的"推理过程"永远显示不出任何内容。
     */
    public Mono<Boolean> appendStep(String id, String step) {
        return appendStepAndMarkRunning(id, step);
    }

    /**
     * 追加一条 step，并在任务仍处于 PENDING 时推进到 RUNNING。
     *
     * 只在 PENDING 时改状态：step 可能晚于 complete 到达（网络乱序），
     * 若无条件覆盖，一个已经 SUCCESS 的任务会被打回 RUNNING 永远不收敛。
     */
    public Mono<Boolean> appendStepAndMarkRunning(String id, String step) {
        if (step == null || step.isBlank()) {
            return Mono.just(false);
        }
        String trimmed = step.length() > MAX_STEP_CHARS
                ? step.substring(0, MAX_STEP_CHARS) + "…（已截断）"
                : step;
        return mutate(id, task -> {
            if ("PENDING".equals(task.getStatus())) {
                task.setStatus("RUNNING");
            }
            List<String> steps = task.getAgentSteps();
            if (steps == null) {
                steps = new ArrayList<>();
                task.setAgentSteps(steps);
            }
            steps.add(trimmed);
            while (steps.size() > MAX_STEPS) {
                steps.remove(0);
            }
        });
    }

    private Mono<Boolean> mutate(String id, java.util.function.Consumer<Task> mutator) {
        return getTask(id)
                .flatMap(task -> {
                    mutator.accept(task);
                    task.setUpdatedAt(LocalDateTime.now());
                    return redisTemplate.opsForValue()
                            .set(TASK_KEY_PREFIX + id, task)
                            .then(Mono.just(true));
                })
                .switchIfEmpty(Mono.defer(() -> {
                    log.warn("任务 {} 不存在，状态更新被忽略（Redis 里没有 {}）", id, TASK_KEY_PREFIX + id);
                    return Mono.just(false);
                }));
    }
}
