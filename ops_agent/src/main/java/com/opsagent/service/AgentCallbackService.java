package com.opsagent.service;

import com.opsagent.model.AgentMessage;
import com.opsagent.model.ToolExecutionRequest;
import com.opsagent.model.ToolExecutionResult;
import com.opsagent.repository.AlertRepository;
import io.github.resilience4j.bulkhead.annotation.Bulkhead;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.time.Duration;
import java.util.LinkedHashMap;

@Slf4j
@Service
@RequiredArgsConstructor
public class AgentCallbackService {

    private final ToolRegistryService toolRegistry;
    private final TaskSchedulerService taskScheduler;
    private final WebSocketPushService webSocketPushService;
    private final AlertRepository alertRepository;
    private final WebClient webClient;
    private final RecoveryVerificationService recoveryVerificationService;
    private final AlertLifecycleService alertLifecycleService;
    private final com.opsagent.repository.ApprovalRepository approvalRepository;

    @Value("${opsagent.agent.url:http://localhost:5000}")
    private String agentBaseUrl;
    @Value("${opsagent.agent.read-timeout:10000}")
    private int readTimeout;

    @Retry(name = "agentCall", fallbackMethod = "fallbackNotifyAgent")
    @CircuitBreaker(name = "agentCall")
    @Bulkhead(name = "agentCall", type = Bulkhead.Type.SEMAPHORE)
    public Mono<Void> notifyAgent(String taskId, String alertDescription) {
        return notifyAgent(taskId, alertDescription, "告警分析", "unknown");
    }

    /**
     * @param title / serviceName 用于给 Python 侧 Agent 传递真实告警上下文。
     *               旧入口只传 description，Python 会把服务名写成 unknown，
     *               导致大模型反复分析根本不存在的服务，并污染案例库。
     */
    public Mono<Void> notifyAgent(String taskId, String alertDescription,
                                  String title, String serviceName) {
        return notifyAgent(taskId, alertDescription, title, serviceName, false);
    }

    /**
     * @param readonly 只读诊断：预测性预警等告警不触发高危 executor/审批。
     */
    public Mono<Void> notifyAgent(String taskId, String alertDescription,
                                  String title, String serviceName, boolean readonly) {
        AgentRequest request = new AgentRequest(taskId, alertDescription, title, serviceName, readonly);
        return webClient.post()
                .uri(agentBaseUrl + "/api/agent/start")
                .bodyValue(request)
                .exchangeToMono(resp -> {
                    int code = resp.statusCode().value();
                    if (resp.statusCode().is2xxSuccessful()) {
                        // Python 侧已改成立即返回 202，这里收到 2xx 就算通知成功
                        return resp.releaseBody().then();
                    }
                    if (code == 409) {
                        // 409 = 同一 taskId 的 ReAct 循环正在跑。这不是失败，
                        // 而且正是我们要的互斥结果，重发只会制造第二份推理。
                        log.warn("任务 {} 已在 agent 侧执行中（409），不再重复投递", taskId);
                        return resp.releaseBody().then();
                    }
                    return resp.createException().flatMap(Mono::error);
                })
                // 刻意不加 retryWhen：/api/agent/start 会拉起一整个 ReAct 循环，
                // 它不是幂等操作。原实现叠了 @Retry(3) × retryWhen(3) = 最多 12 次投递，
                // 一次网络抖动就能让同一个告警被分析 12 遍、重启被执行 12 次。
                .timeout(Duration.ofMillis(readTimeout))
                .doOnSuccess(v -> log.info("Successfully notified Agent for task {}", taskId))
                .doOnError(e -> log.error("Failed to notify Agent for task {}", taskId, e));
    }

    // 降级方法
    public Mono<Void> fallbackNotifyAgent(String taskId, String alertDescription, Throwable t) {
        log.error("通知 Agent 失败，任务 {} 标记为 FAILED: {}", taskId, t.getMessage());
        // 原实现只是 log 一行然后 Mono.empty() —— 任务永远停在 PENDING，
        // 前端看到一个"正在分析中"的僵尸任务，实际什么都没发生
        return taskScheduler.completeTask(taskId, "通知 agent 失败：" + t.getMessage(), "FAILED").then();
    }

    public Mono<Void> handleAgentStep(AgentMessage message) {
        webSocketPushService.pushMessage(message.getTaskId(), message);
        // 这一步同时完成两件事：把任务从 PENDING 推到 RUNNING，并落一条 agentSteps。
        // 改造前 updateTaskStatus 从未被调用过，前端状态标签永远是 PENDING。
        return taskScheduler.appendStepAndMarkRunning(message.getTaskId(), describe(message))
                .then();
    }

    public Mono<ToolExecutionResult> executeTool(ToolExecutionRequest request) {
        // forAgent=true：按 opsagent.tools.agent-exposed 白名单校验，
        // generic_command（执行任意 shell）不在这份名单里
        return Mono.fromCallable(() -> toolRegistry.execute(request, true))
                // 工具是阻塞的（K8s HTTP、SSH、restart 里的 sleep），
                // 跑在 event loop 上会打印 "Blocking call! blocked for 3000ms"
                .subscribeOn(Schedulers.boundedElastic())
                // 刻意不加 retryWhen：这个调用下面挂着 restart_service / scale_up / rollback，
                // 对它们自动重试就是在故障现场反复改线上状态。
                // 失败就以 success=false 返回给 LLM，由 LLM 显式决定是否再来一次（可审计）。
                .onErrorResume(e -> {
                    log.error("工具执行失败 tool={}", request.getToolName(), e);
                    return Mono.just(new ToolExecutionResult(
                            false, "Tool execution failed: " + e.getMessage(),
                            new LinkedHashMap<String, Object>(), 0));
                });
    }

    public Mono<Void> taskComplete(String taskId, String result) {
        return taskComplete(taskId, result, "SUCCESS");
    }

    /**
     * @param status SUCCESS 或 FAILED。
     *               agent 侧推理失败时必须落 FAILED —— 原实现无论成败都写 SUCCESS，
     *               一个失败的分析在界面上看起来是"已完成"。
     *
     * 生产闭环（批准 → 验证 → 闭环）：
     * agent 回传 SUCCESS **不代表**修复已闭环，只代表「执行完成」。这里把它改判为
     * VERIFYING 并启动 5 分钟观察期；观察期结论由 RecoveryVerificationService 决定：
     *   通过 → 任务/告警 SUCCESS/RESOLVED（真正闭环）
     *   失败 → REGRESSED → 重试或耗尽（见 onVerificationRegressed）
     * 非修复类任务（没有已批准审批单的纯诊断）保持原有语义，不进入验证轮次。
     */
    public Mono<Void> taskComplete(String taskId, String result, String status) {
        return taskScheduler.completeTask(taskId, result, status)
                // 任务落终态后，把终态回写到关联告警。改造前这里就断了：
                // 告警永远停在 ANALYZING，列表里所有告警看起来"永远在分析中"，
                // Dashboard 的"已处理"统计也对不上。
                .then(markRelatedAlert(taskId, status))
                // 前瞻性增强：修复类任务成功收尾后进入回归观察期——「执行完」不等于
                // 「修好了」，观察期内若故障复现将任务改判 REGRESSED 并重新触发分析。
                // 这里只是登记观察（内部异步，立即返回），不阻塞回调本身。
                //
                // 无论任务最终 SUCCESS 还是 FAILED，只要是一次真实分析/修复尝试，
                // 都要进入观察期：执行失败也可能是资源/网络瞬时抖动，观察期能继续
                // 监控它是否自愈或在 5 分钟内复现。
                .doOnSuccess(v -> scheduleRecoveryVerification(taskId, status))
                .then();
    }

    /**
     * 只要任务已经进入终态（SUCCESS 或 FAILED，且不是被人工拒绝）都安排回归验证。
     * 失败任务同样需要观察：K8s 侧可能自愈，也可能持续崩溃，不能因为一次工具报错就放弃监控。
     */
    private void scheduleRecoveryVerification(String taskId, String status) {
        String upper = status == null ? "" : status.toUpperCase();
        if ("REJECTED".equals(upper) || "PENDING".equals(upper) || "RUNNING".equals(upper)) {
            return;
        }
        try {
            recoveryVerificationService.scheduleVerification(taskId);
        } catch (Exception e) {
            // 回归验证是增强能力，绝不能让它让任务完成回调失败
            log.warn("登记回归验证失败 taskId={}: {}", taskId, e.getMessage());
        }
    }

    /**
     * 任务终态 -> 告警终态：
     * - SUCCESS 且该任务确实获得过人工批准（有 APPROVED 审批单）→ VERIFYING
     *   （修复已执行但未证明恢复，进入 5 分钟验证期，由观察期决出 SUCCESS/RESOLVED）
     * - SUCCESS 但只是只读诊断 → RESOLVED（没有写操作，无需验证轮次）
     * - REGRESSED / FAILED → PENDING（未解决，交由重试预算裁决）
     * - REJECTED（人工拒绝审批）→ REJECTED，不映射成红色 FAILED
     * 任务可能没有关联告警（手动巡检、工具演练），查不到 alertId 就跳过。
     */
    private Mono<Void> markRelatedAlert(String taskId, String taskStatus) {
        return taskScheduler.getTask(taskId)
                .flatMap(task -> {
                    String alertId = task.getAlertId();
                    if (alertId == null || alertId.isBlank()) {
                        return Mono.empty();
                    }
                    // 审批状态查询是阻塞 JPA，必须挪进 boundedElastic 并在链内执行，
                    // 否则会跑在 Netty event loop 上（回调是 WebFlux 入口）。
                    return Mono.fromCallable(() -> {
                                boolean approvedRepair = isApprovedRepair(taskId);
                                if ("SUCCESS".equalsIgnoreCase(taskStatus)) {
                                    // 成功：一律先进入验证期（执行完成 ≠ 已修复）。
                                    // 只有真正被人工批准过的修复才计入 (n/3) 轮次与重试预算；
                                    // 纯只读诊断走「诊断观察期」，保持原有重诊断语义。
                                    beginVerification(taskId, alertId, approvedRepair);
                                    return true;
                                }
                                applyAlertTerminalStatus(taskId, alertId, taskStatus, approvedRepair);
                                log.info("任务 {} 终态 {} 已回写告警 {}（approvedRepair={}）",
                                        taskId, taskStatus, alertId, approvedRepair);
                                return true;
                            })
                            .subscribeOn(Schedulers.boundedElastic())
                            .onErrorResume(e -> {
                                log.error("回写告警终态失败 alertId={} taskId={}", alertId, taskId, e);
                                return Mono.just(false);
                            })
                            .then();
                })
                // 任务不存在（Redis 已过期等）不影响任务完成回调本身，
                // 但需明确标记告警回写失败，便于运维定位告警滞后问题。
                .onErrorResume(e -> {
                    log.error("回写告警终态时任务查询失败（告警可能滞后） taskId={}: {}", taskId, e.getMessage());
                    return Mono.empty();
                })
                .then();
    }

    /** 该任务是否存在已批准的审批单（= 本次是一次真正被授权的修复）。 */
    private boolean isApprovedRepair(String taskId) {
        if (taskId == null || taskId.isBlank()) {
            return false;
        }
        try {
            return approvalRepository.existsByTaskIdAndStatus(taskId, "APPROVED");
        } catch (Exception e) {
            log.warn("查询任务审批状态失败（按非修复任务处理） taskId={}: {}", taskId, e.getMessage());
            return false;
        }
    }

    /**
     * 进入 VERIFYING：把任务与告警一起切到「验证中」，并写入轮次/倒计时。
     *
     * @param approvedRepair 本次是否是一次被人工批准的修复。
     *                       true  → 走生产闭环：轮次 (n/max) + 重试预算，验证失败可自动重试/耗尽；
     *                       false → 只读诊断：不看轮次预算，验证失败按「观察期复现」重新诊断。
     *
     * 这里只做状态与计时登记，真正的健康复查由 RecoveryVerificationService 的
     * 观察期负责（scheduleRecoveryVerification 随后启动）。
     */
    private void beginVerification(String taskId, String alertId, boolean approvedRepair) {
        java.time.LocalDateTime startedAt = java.time.LocalDateTime.now();
        // 验证窗口与观察期保持一致（默认 5 分钟）：前端倒计时的终点
        // 就是观察期结束、系统给出结论的时刻。
        long observeMinutes = recoveryVerificationService.observeMinutes();
        java.time.LocalDateTime deadline = startedAt.plusMinutes(Math.max(1, observeMinutes));
        alertLifecycleService.findAlert(alertId).ifPresentOrElse(alert -> {
            int max = alert.getMaxRetries() == null
                    ? alertLifecycleService.maxRounds() : Math.max(1, alert.getMaxRetries());
            int round = alertLifecycleService.beginVerification(alert, startedAt, deadline);
            if (approvedRepair) {
                recordVerifyStep(taskId, "[verify] 修复已执行，进入验证期：第 " + round + "/" + max
                        + " 轮，观察 " + observeMinutes + " 分钟后给出结论");
                markVerifying(taskId, round, max, startedAt, deadline,
                        "修复已执行，等待 " + observeMinutes + " 分钟验证结果");
                log.info("[验证] 任务 {} 进入 VERIFYING 第 {}/{} 轮，截止 {}", taskId, round, max, deadline);
            } else {
                // 只读诊断：不占轮次预算，但同样给前端一个验证倒计时
                recordVerifyStep(taskId, "[verify] 诊断完成，进入 " + observeMinutes + " 分钟健康观察");
                markVerifying(taskId, 0, max, startedAt, deadline,
                        "诊断完成，观察服务是否恢复");
                log.info("[验证] 只读诊断任务 {} 进入观察期，截止 {}", taskId, deadline);
            }
        }, () -> {
            // 告警读不到（极少数异常路径）：至少要保证任务不是假的 SUCCESS
            markVerifying(taskId, approvedRepair ? 1 : 0,
                    alertLifecycleService.maxRounds(), startedAt, deadline, "等待验证结果");
            log.warn("[验证] 任务 {} 找不到关联告警 {}，仅标记任务 VERIFYING", taskId, alertId);
        });
    }

    /**
     * 按任务状态把告警落到对应终态（REGRESSED/FAILED 需撕掉假聚合）。
     *
     * @param approvedRepair 是否是一次被人工批准的修复。REGRESSED 时只有修复任务才
     *                       消耗重试预算；只读诊断的观察期复现只打回 PENDING。
     */
    private void applyAlertTerminalStatus(String taskId, String alertId, String taskStatus,
                                          boolean approvedRepair) {
        String alertStatus;
        boolean processed;
        if ("REJECTED".equalsIgnoreCase(taskStatus)) {
            // 任务被人工拒绝：告警仍未解决，且不是系统失败，用 REJECTED 语义保留。
            // 但拒绝后已进入静默冷却期时，告警必须保持 PENDING（未解决）——
            // Python resume 也会回传一次 REJECTED，绝不能把冷却中的 PENDING 覆盖掉，
            // 否则冷却规则失效、告警提前被钉成已处理。
            var optional = alertLifecycleService.findAlert(alertId);
            if (optional.isPresent() && optional.get().getRejectCooldownUntil() != null) {
                log.info("任务 {} 回写 REJECTED：告警 {} 正在拒绝冷却期，保持 PENDING 不覆盖",
                        taskId, alertId);
                return;
            }
            alertStatus = "REJECTED";
            processed = true;
        } else if ("REGRESSED".equalsIgnoreCase(taskStatus)) {
            // 验证失败：不能直接落 PENDING/FAILED —— 必须经重试预算裁决
            alertLifecycleService.findAlert(alertId).ifPresent(alert -> {
                AlertLifecycleService.VerificationDecision decision =
                        alertLifecycleService.onVerificationRegressed(alert, approvedRepair);
                int max = alert.getMaxRetries() == null
                        ? alertLifecycleService.maxRounds() : Math.max(1, alert.getMaxRetries());
                int round = alert.getVerificationRound() == null ? 1 : alert.getVerificationRound();
                if (decision == AlertLifecycleService.VerificationDecision.EXHAUSTED) {
                    // 耗尽：任务改 FAILED 并发送最终失败报告（含人工介入指引）
                    String report = alertLifecycleService.buildExhaustedReport(
                            alert, Math.max(round, max), max, "连续验证失败");
                    markTaskExhaustedFailed(taskId, report);
                } else {
                    // 还有预算：本任务标记 REGRESSED 收尾，由 RecoveryVerificationService
                    // 负责拉起下一轮诊断（新任务 → 重新审批 → 再次验证）。
                    taskScheduler.appendStep(taskId,
                            "[verify] 第 " + round + "/" + max + " 轮验证失败，已进入自动重试").subscribe();
                }
            });
            return;
        } else if ("FAILED".equalsIgnoreCase(taskStatus)) {
            // 执行失败：保留红色 FAILED 提示，交由人工介入（不消耗验证轮次）
            alertStatus = "FAILED";
            processed = true;
        } else {
            alertStatus = "FAILED";
            processed = false;
        }
        alertLifecycleService.findAlert(alertId).ifPresent(alert -> {
            // 撕掉假聚合：任务 FAILED 且告警还在 CORRELATED，必须回退并累加次数
            if ("CORRELATED".equalsIgnoreCase(alert.getStatus()) && "FAILED".equals(alertStatus)) {
                int next = (alert.getOccurrenceCount() == null ? 1 : alert.getOccurrenceCount()) + 1;
                alert.setOccurrenceCount(next);
            }
            alert.setStatus(alertStatus);
            alert.setProcessed(processed);
            alertLifecycleService.save(alert);
        });
    }

    /** 重试耗尽：任务落 FAILED 并写入最终失败报告，告警已在状态机里钉死为 FAILED。 */
    private void markTaskExhaustedFailed(String taskId, String report) {
        if (taskId == null || taskId.isBlank()) {
            return;
        }
        taskScheduler.completeTask(taskId, report, "FAILED")
                .then(taskScheduler.appendStep(taskId,
                        "[verify] 自动重试预算耗尽，停止自动修复，已发送最终失败报告"))
                .subscribe(
                        v -> log.warn("[重试耗尽] 任务 {} 已置 FAILED 并输出最终失败报告", taskId),
                        e -> log.warn("[重试耗尽] 标记任务 FAILED 失败 taskId={}: {}", taskId, e.getMessage()));
    }

    /** 记录验证期步骤，异常只记日志，不阻塞主链路。 */
    private void recordVerifyStep(String taskId, String step) {
        taskScheduler.appendStep(taskId, step)
                .subscribeOn(Schedulers.boundedElastic())
                .subscribe(
                        v -> { },
                        e -> log.warn("记录验证期步骤失败 taskId={}: {}", taskId, e.getMessage()));
    }

    /** 标记任务进入验证期，异常只记日志，不阻塞主链路。 */
    private void markVerifying(String taskId, int round, int max,
                               java.time.LocalDateTime startedAt, java.time.LocalDateTime deadline,
                               String note) {
        taskScheduler.markVerifying(taskId, round, max, startedAt, deadline, note)
                .subscribeOn(Schedulers.boundedElastic())
                .subscribe(
                        v -> { },
                        e -> log.warn("标记任务验证期失败 taskId={}: {}", taskId, e.getMessage()));
    }

    /** 把 AgentMessage 压成一行便于前端展示与排查的摘要。 */
    private String describe(AgentMessage m) {
        StringBuilder sb = new StringBuilder();
        if (m.getStepName() != null && !m.getStepName().isBlank()) {
            sb.append('[').append(m.getStepName()).append("] ");
        }
        if (m.getType() != null && !m.getType().isBlank()) {
            sb.append('(').append(m.getType()).append(") ");
        }
        if (m.getContent() != null) {
            sb.append(m.getContent());
        }
        return sb.toString().trim();
    }

    static class AgentRequest {
        public String taskId;
        public String description;
        public String title;
        public String serviceName;
        public boolean readonly;
        public AgentRequest(String taskId, String description, String title, String serviceName, boolean readonly) {
            this.taskId = taskId;
            this.description = description;
            this.title = title;
            this.serviceName = serviceName;
            this.readonly = readonly;
        }
    }
}
