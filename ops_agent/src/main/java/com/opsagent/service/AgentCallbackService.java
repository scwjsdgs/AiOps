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

    @Value("${opsagent.agent.url:http://localhost:5000}")
    private String agentBaseUrl;
    @Value("${opsagent.agent.read-timeout:10000}")
    private int readTimeout;

    @Retry(name = "agentCall", fallbackMethod = "fallbackNotifyAgent")
    @CircuitBreaker(name = "agentCall")
    @Bulkhead(name = "agentCall", type = Bulkhead.Type.SEMAPHORE)
    public Mono<Void> notifyAgent(String taskId, String alertDescription) {
        AgentRequest request = new AgentRequest(taskId, alertDescription);
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
                .doOnSuccess(v -> scheduleRecoveryVerification(taskId, status))
                .then();
    }

    /**
     * 只对「成功收尾的根因分析任务」安排回归验证：失败任务没有可验证的修复动作，
     * 巡检/报告类任务也没有明确的服务对象。
     */
    private void scheduleRecoveryVerification(String taskId, String status) {
        if (!"SUCCESS".equals(status)) {
            return;
        }
        try {
            recoveryVerificationService.scheduleVerification(taskId);
        } catch (Exception e) {
            // 回归验证是增强能力，绝不能因为它让任务完成回调失败
            log.warn("登记回归验证失败 taskId={}: {}", taskId, e.getMessage());
        }
    }

    /**
     * 任务终态 -> 告警终态：SUCCESS 映射 RESOLVED，FAILED 映射 FAILED。
     * 任务可能没有关联告警（手动巡检、工具演练），查不到 alertId 就跳过。
     */
    private Mono<Void> markRelatedAlert(String taskId, String taskStatus) {
        String alertStatus = "SUCCESS".equals(taskStatus) ? "RESOLVED" : "FAILED";
        boolean processed = "SUCCESS".equals(taskStatus);
        return taskScheduler.getTask(taskId)
                .flatMap(task -> {
                    String alertId = task.getAlertId();
                    if (alertId == null || alertId.isBlank()) {
                        return Mono.empty();
                    }
                    // alertRepository 是阻塞 JPA（findById 返回 Optional 不是 Mono）��
                    // 必须包进 fromCallable 并挪到 boundedElastic，
                    // 否则跑在 event loop 上，还会把 Optional 当 Reactor 类型编译失败
                    return Mono.fromCallable(() -> {
                                alertRepository.findById(alertId).ifPresent(alert -> {
                                    // 只从 ANALYZING 推进到终态，不覆盖更早的状态语义
                                    alert.setStatus(alertStatus);
                                    alert.setProcessed(processed);
                                    alertRepository.save(alert);
                                });
                                log.info("任务 {} 终态 {} 已回写告警 {}", taskId, taskStatus, alertId);
                                return true;
                            })
                            .subscribeOn(Schedulers.boundedElastic())
                            .onErrorResume(e -> {
                                log.error("回写告警终态失败 alertId={} taskId={}", alertId, taskId, e);
                                return Mono.just(false);
                            })
                            .then();
                })
                // 任务不存在（Redis 已过期等）不影响任务完成回调本身
                .onErrorResume(e -> {
                    log.warn("回写告警终态时任务查询失败 taskId={}", taskId, e);
                    return Mono.empty();
                })
                .then();
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
        public AgentRequest(String taskId, String description) {
            this.taskId = taskId;
            this.description = description;
        }
    }
}
