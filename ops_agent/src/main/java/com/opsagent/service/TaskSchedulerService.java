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
    /**
     * 任务保留时长：不设 TTL 的话任务永久堆积，SCAN 列表会越来越慢。
     * 至少 24 小时，且每次更新都需要继续续期，否则一次不带 TTL 的 set
     * 就会把整个任务的过期时间清掉（前端断线后任务莫名变孤儿）。
     */
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
                .then(Mono.just(task))
                .onErrorResume(e -> {
                    log.warn("Redis 创建任务失败（降级为空，任务不再持久化） id={}: {}", task.getId(), e.getMessage());
                    return Mono.empty();
                });
    }

    /**
     * 创建或覆盖一个任务（ChatOps 每次提问都会生成一个新的 taskId，用同一个
     * 入口写 Redis，避免出现“任务不存在，状态更新被忽略”）。
     */
    public Mono<Boolean> createOrReplace(Task task) {
        if (task == null || task.getId() == null || task.getId().isBlank()) {
            return Mono.just(false);
        }
        if (task.getCreatedAt() == null) {
            task.setCreatedAt(LocalDateTime.now());
        }
        if (task.getUpdatedAt() == null) {
            task.setUpdatedAt(LocalDateTime.now());
        }
        if (task.getAgentSteps() == null) {
            task.setAgentSteps(new ArrayList<>());
        }
        return redisTemplate.opsForValue()
                .set(TASK_KEY_PREFIX + task.getId(), task, TASK_TTL)
                .onErrorResume(e -> {
                    log.warn("Redis 写入任务失败（降级） id={}: {}", task.getId(), e.getMessage());
                    return Mono.just(false);
                });
    }

    /**
     * 注意：key 不存在时返回空 Mono（不是 null）。
     * 所有下游都靠 defaultIfEmpty / switchIfEmpty 兜底，否则会静默什么都不做。
     */
    public Mono<Task> getTask(String id) {
        return redisTemplate.opsForValue()
                .get(TASK_KEY_PREFIX + id)
                .cast(Task.class)
                .onErrorResume(e -> {
                    log.warn("Redis 读取任务失败（按不存在降级） id={}: {}", id, e.getMessage());
                    return Mono.empty();
                });
    }

    /** 分页列出任务（兼容原有调用：不带 status/keyword 过滤）。 */
    public Mono<List<Task>> listTasks(String alertId, int page, int size) {
        return listTasks(alertId, null, null, page, size);
    }

    /**
     * 分页列出任务，可按 alertId / status / keyword 过滤。
     *
     * 这是告警页"查看分析"和 Dashboard 统计的数据源：告警与任务靠 alertId
     * 关联，没有这个方法前端就打不开 Agent 的分析报告。
     *
     * status / keyword 在服务端过滤，不是前端本地筛：
     * 前端若只过滤已取回的那一页，搜索看上去"无效"——搜到的永远是当前页的子集。
     *
     * 用 SCAN 而不是 KEYS：KEYS 在 key 量大时阻塞 Redis 整个主线程；
     * SCAN 游标遍历不阻塞。注意 ReactiveRedisTemplate.scan 返回的 Flux
     * 必须在 boundedElastic 上跑（下面 subscribeOn），别占 event loop。
     */
    public Mono<List<Task>> listTasks(String alertId, String status, String keyword, int page, int size) {
        return listTasksViaScan(alertId, status, keyword, page, size)
                .subscribeOn(Schedulers.boundedElastic())
                .onErrorResume(e -> {
                    log.warn("Redis 任务列表查询超时降级为空列表: {}", e.getMessage());
                    return Mono.just(new ArrayList<>());
                });
    }

    /**
     * 列出全部任务（用于后台扫描超时/僵尸任务，不参与前端分页）。
     * 复用 SCAN 实现，忽略分页；与 listTasks 共用同一套容错（单条坏数据降级为空）。
     */
    public Mono<List<Task>> listAllTasks() {
        return listTasksViaScan(null, null, null, 1, Integer.MAX_VALUE)
                .subscribeOn(Schedulers.boundedElastic())
                .onErrorResume(e -> {
                    log.warn("Redis 全量任务扫描超时降级为空列表: {}", e.getMessage());
                    return Mono.just(new ArrayList<>());
                });
    }

    /**
     * 清理残留任务（运维端点用）。
     *
     * @param status          只清理指定状态的任务；null/blank 表示清理全部
     * @param olderThanMinutes 只清理创建时间早于 now-该分钟数的任务；<=0 表示不按时间过滤
     * @return 清理的任务 key 数量
     */
    public Mono<Integer> purgeTasks(String status, int olderThanMinutes) {
        return listAllTasks()
                .flatMapMany(Flux::fromIterable)
                .filter(task -> task != null && task.getId() != null && !task.getId().isBlank())
                .filter(task -> status == null || status.isBlank()
                        || status.equalsIgnoreCase(task.getStatus() == null ? "" : task.getStatus()))
                .filter(task -> olderThanMinutes <= 0 || task.getCreatedAt() == null
                        || task.getCreatedAt().isBefore(LocalDateTime.now().minusMinutes(olderThanMinutes)))
                .flatMap(task -> redisTemplate.opsForValue().delete(TASK_KEY_PREFIX + task.getId())
                        .map(deleted -> Boolean.TRUE.equals(deleted) ? 1 : 0)
                        .onErrorResume(e -> {
                            log.warn("清理任务 key 失败 id={}: {}", task.getId(), e.getMessage());
                            return Mono.just(0);
                        }))
                .reduce(0, Integer::sum)
                .subscribeOn(Schedulers.boundedElastic())
                .onErrorResume(e -> {
                    log.warn("清理任务失败（降级为 0）: {}", e.getMessage());
                    return Mono.just(0);
                });
    }

    private Mono<List<Task>> listTasksViaScan(String alertId, String status, String keyword, int page, int size) {
        return redisTemplate.scan(ScanOptions.scanOptions().match(TASK_KEY_PREFIX + "*").count(500).build())
                .collectList()
                .flatMap(keys -> {
                    if (keys.isEmpty()) {
                        return Mono.just(new ArrayList<Task>());
                    }
                    List<Mono<Task>> reads = keys.stream()
                            .map(k -> redisTemplate.<String, Object>opsForValue().get(k)
                                    .cast(Task.class)
                                    // 单条读取超时/坏数据不能拖垮整页；降级为空并记录一次
                                    .onErrorResume(e -> {
                                        log.warn("读取任务 key {} 失败（跳过）: {}", k, e.getMessage());
                                        return Mono.empty();
                                    }))
                            .toList();
                    // 限并发读取：Redis 连接池有限，key 多时一次全并发会触发 command timed out。
                    return Flux.merge(Flux.fromIterable(reads), 8)
                            .filter(java.util.Objects::nonNull)
                            .filter(t -> alertId == null || alertId.isBlank() || alertId.equals(t.getAlertId()))
                            .filter(t -> matchesStatus(t, status))
                            .filter(t -> matchesKeyword(t, keyword))
                            .sort(java.util.Comparator.comparing(Task::getCreatedAt,
                                    java.util.Comparator.nullsLast(java.util.Comparator.reverseOrder())))
                            .collectList()
                            .onErrorResume(e -> {
                                log.warn("任务列表扫描失败（降级为空列表）: {}", e.getMessage());
                                return Mono.just(new ArrayList<>());
                            })
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

    /** 状态精确匹配，大小写不敏感。 */
    private boolean matchesStatus(Task task, String status) {
        if (status == null || status.isBlank()) {
            return true;
        }
        return task.getStatus() != null && task.getStatus().equalsIgnoreCase(status.trim());
    }

    /**
     * 关键词模糊匹配：搜任务 ID、告警 ID、输入内容（含服务名/告警标题）、输出报告。
     * 覆盖运维人员最常用的两种找法——「按服务名找」和「按报告里的一句话找」。
     */
    private boolean matchesKeyword(Task task, String keyword) {
        if (keyword == null || keyword.isBlank()) {
            return true;
        }
        String kw = keyword.trim().toLowerCase();
        return containsIgnoreCase(task.getId(), kw)
                || containsIgnoreCase(task.getAlertId(), kw)
                || containsIgnoreCase(task.getInput(), kw)
                || containsIgnoreCase(task.getOutput(), kw);
    }

    private boolean containsIgnoreCase(String haystack, String lowerNeedle) {
        return haystack != null && haystack.toLowerCase().contains(lowerNeedle);
    }

    /**
     * 找到某告警最新的任务（告警降噪回填聚合键用）。
     * 找不到返回空 Mono —— 调用方（fillCorrKeyWithTaskId）用 repeatWhenEmpty 轮询等待。
     */
    public Mono<Task> findLatestTaskByAlertId(String alertId) {
        if (alertId == null || alertId.isBlank()) {
            return Mono.empty();
        }
        return listTasks(alertId, 1, 1)
                .flatMap(list -> list.isEmpty() ? Mono.empty() : Mono.just(list.get(0)));
    }

    /** 任务总数（兼容原有调用），供分页 total 使用。 */
    public Mono<Long> countTasks(String alertId) {
        return countTasks(alertId, null, null);
    }

    /**
     * 任务总数，过滤条件必须与 listTasks 完全一致。
     *
     * 否则筛选后 total 仍是全量数字，前端分页会出现"共 100 条"却只有 3 条结果的错乱。
     * 所以这里不做「无过滤就快速返回 keys.size()」的短路——有 status/keyword 时必须实读。
     */
    public Mono<Long> countTasks(String alertId, String status, String keyword) {
        boolean noFilter = (alertId == null || alertId.isBlank())
                && (status == null || status.isBlank())
                && (keyword == null || keyword.isBlank());
        return redisTemplate.scan(ScanOptions.scanOptions().match(TASK_KEY_PREFIX + "*").count(500).build())
                .collectList()
                .flatMap(keys -> {
                    if (keys.isEmpty()) {
                        return Mono.just(0L);
                    }
                    if (noFilter) {
                        return Mono.just((long) keys.size());
                    }
                    List<Mono<Task>> reads = keys.stream()
                            .map(k -> redisTemplate.<String, Object>opsForValue().get(k)
                                    .cast(Task.class)
                                    .onErrorResume(e -> {
                                        log.warn("读取任务 key {} 失败（跳过）: {}", k, e.getMessage());
                                        return Mono.empty();
                                    }))
                            .toList();
                    return Flux.merge(Flux.fromIterable(reads), 8)
                            .filter(java.util.Objects::nonNull)
                            .filter(t -> alertId == null || alertId.isBlank() || alertId.equals(t.getAlertId()))
                            .filter(t -> matchesStatus(t, status))
                            .filter(t -> matchesKeyword(t, keyword))
                            .count()
                            .onErrorResume(e -> {
                                log.warn("Redis 任务计数超时降级为 0: {}", e.getMessage());
                                return Mono.just(0L);
                            });
                })
                .onErrorResume(e -> {
                    log.warn("Redis 任务计数扫描超时降级为 0: {}", e.getMessage());
                    return Mono.just(0L);
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
     * 进入验证态：修复已批准并执行完成，接下来 5 分钟观察是否真正恢复。
     *
     * 轮次 / 截止时间写在任务上，前端任务详情页据此展示「第 n/3 轮 + 倒计时」，
     * 用户不用猜系统在干什么。任务状态保持 VERIFYING（非终态），
     * 前端会继续轮询，直到验证得出 SUCCESS / REGRESSED / FAILED。
     */
    public Mono<Boolean> markVerifying(String id, int round, int maxRetries,
                                       LocalDateTime startedAt, LocalDateTime deadline, String note) {
        return mutate(id, task -> {
            task.setStatus("VERIFYING");
            task.setVerificationRound(round);
            task.setMaxRetries(maxRetries);
            task.setVerificationStartedAt(startedAt);
            task.setVerificationDeadline(deadline);
            task.setVerifyNote(note);
        });
    }

    /** 验证结束（通过/失败/耗尽）后清掉倒计时，避免前端显示已过期的"验证中"。 */
    public Mono<Boolean> clearVerifyingCountdown(String id) {
        return mutate(id, task -> {
            task.setVerificationDeadline(null);
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

    /**
     * 告警降噪：把同源故障窗口内后续到达的告警追加进已有任务的 input。
     *
     * 背景：一个 Pod 崩溃会同时触发 4 条告警（CrashLoop/NotReady/ReplicasMismatch...），
     * alertname 不同 → fingerprint 不同 → 各自建任务各跑一遍全量 ReAct，
     * 同一次故障被分析 4 次。降噪的正确做法不是吞掉告警（记录照常落库），
     * 而是不重复触发 agent，并把新症状合并进正在进行的分析。
     *
     * 追加为 input 的一行摘要，前缀 [关联告警]，让 LLM 一次看到全部症状。
     */
    public Mono<Boolean> appendCorrelatedAlert(String id, String alertTitle, String severity) {
        if (alertTitle == null || alertTitle.isBlank()) {
            return Mono.just(false);
        }
        String line = "[关联告警 " + (severity == null ? "" : severity) + "] " + alertTitle;
        return mutate(id, task -> {
            String input = task.getInput();
            if (input == null || input.isBlank()) {
                input = line;
            } else if (!input.contains(alertTitle)) {
                input = input + "\n" + line;
            }
            task.setInput(input.length() > MAX_STEP_CHARS * 2
                    ? input.substring(0, MAX_STEP_CHARS * 2) + "…（已截断）" : input);
        });
    }

    private Mono<Boolean> mutate(String id, java.util.function.Consumer<Task> mutator) {
        return getTask(id)
                .flatMap(task -> {
                    mutator.accept(task);
                    task.setUpdatedAt(LocalDateTime.now());
                    // 每次写回都带 TTL 续期。不带 TTL 的 set 会把任务的过期时间
                    // 清成永久键；一旦超过实例内存上限触发淘汰，回调查询就会看到
                    // “任务不存在”，形成孤儿任务。这里统一续期到 TASK_TTL。
                    return redisTemplate.opsForValue()
                            .set(TASK_KEY_PREFIX + id, task, TASK_TTL)
                            .then(Mono.just(true))
                            .onErrorResume(e -> {
                                log.warn("Redis 更新任务失败（降级） id={}: {}", id, e.getMessage());
                                return Mono.just(false);
                            });
                })
                .switchIfEmpty(Mono.defer(() -> {
                    log.warn("任务 {} 不存在，状态更新被忽略（Redis 里没有 {}）", id, TASK_KEY_PREFIX + id);
                    return Mono.just(false);
                }));
    }
}
