package com.opsagent.service;

import com.opsagent.model.Alert;
import com.opsagent.model.Task;
import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.api.model.apps.Deployment;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 修复后持续回归验证（前瞻性增强方案三）。
 *
 * 解决的问题：「重启执行完成」不等于「服务真的恢复了」。现实中很常见的是——
 * 重启成功 30 秒后又 CrashLoop 回去，而此时任务已经躺在 SUCCESS 里、告警也已
 * RESOLVED，没人知道故障其实复现了。agent 原来只在修复后查一次 get_status 就下结论，
 * 这个缺口在本项目的评测里也体现为「修复验证」维度得分偏低。
 *
 * 做法：任务终态为 SUCCESS 且类型为 ROOT_CAUSE_ANALYSIS 时，启动一段「观察期」
 * （默认 5 分钟），每隔若干秒复查一次服务健康度：
 *   1. 就绪/可用副本是否回到期望副本数
 *   2. 容器是否处于 CrashLoopBackOff 之类的等待态
 * 观察期内连续失败达到阈值 → 任务状态改为 REGRESSED（已修复但复现），并重新触发
 * 一次分析（输入带上「上次修复后复现」的上下文，让 LLM 查「为什么没保持住」），
 * 把闭环从「执行完」真正升级到「验证恢复」。
 *
 * 全程不阻塞任何主链路：观察期跑在独立的 Reactor 定时流上，失败只记日志。
 */
@Slf4j
@Service
public class RecoveryVerificationService {

    private static final Pattern SERVICE_PATTERN =
            Pattern.compile("服务\\s+([A-Za-z0-9][A-Za-z0-9_.\\-]{0,62})");

    private final KubernetesToolClientHolder holder;
    private final TaskSchedulerService taskScheduler;
    private final AlertIngestionService alertIngestionService;
    private final MeterRegistry meterRegistry;

    /**
     * 显式构造器：AlertIngestionService 的回调链路里挂着 AgentCallbackService，
     * 而本服务是被 AgentCallbackService 调用的 —— 直接构造注入会形成
     * AgentCallbackService → 本服务 → AlertIngestionService → AgentCallbackService
     * 的循环依赖。用 @Lazy 打断它，只在真正要重新触发分析时才解析这个 bean。
     */
    public RecoveryVerificationService(KubernetesToolClientHolder holder,
                                       TaskSchedulerService taskScheduler,
                                       @Lazy AlertIngestionService alertIngestionService,
                                       MeterRegistry meterRegistry) {
        this.holder = holder;
        this.taskScheduler = taskScheduler;
        this.alertIngestionService = alertIngestionService;
        this.meterRegistry = meterRegistry;
    }

    @Value("${opsagent.recovery.observe-minutes:5}")
    private long observeMinutes;

    @Value("${opsagent.recovery.interval-seconds:30}")
    private long intervalSeconds;

    /** 连续失败多少次才判定 REGRESSED。单次抖动（Pod 正在滚动更新）不算复现。 */
    @Value("${opsagent.recovery.failure-threshold:2}")
    private int failureThreshold;

    /** 总开关。关掉时 scheduleVerification 直接返回，不注册任何定时流。 */
    @Value("${opsagent.recovery.enabled:true}")
    private boolean enabled;

    private Counter verifyStarted;
    private Counter verifyPassed;
    private Counter verifyRegressed;

    /** 正在观察的任务 -> 定时流引用，任务被重复调度时可取消旧观察。 */
    private final Map<String, Disposable> activeObservations = new ConcurrentHashMap<>();

    @PostConstruct
    public void initMetrics() {
        verifyStarted = Counter.builder("agent.recovery.verify.started")
                .description("启动回归观察期的任务数").register(meterRegistry);
        verifyPassed = Counter.builder("agent.recovery.verify.passed")
                .description("观察期结束仍健康的任务数").register(meterRegistry);
        verifyRegressed = Counter.builder("agent.recovery.verify.regressed")
                .description("观察期内故障复现（REGRESSED）的任务数").register(meterRegistry);
    }

    /**
     * 为一个已成功的修复任务安排回归观察。
     *
     * 由 AgentCallbackService.taskComplete() 在任务落 SUCCESS 后调用。异步执行并立即返回
     * —— 回调线程绝不能在这里等满整个观察期。
     */
    public void scheduleVerification(String taskId) {
        if (!enabled || taskId == null || taskId.isBlank()) {
            return;
        }
        // 同一任务重复调度时先取消上一轮观察，避免两条流同时判同一任务
        Disposable previous = activeObservations.remove(taskId);
        if (previous != null && !previous.isDisposed()) {
            previous.dispose();
        }

        long intervalSecondsSafe = Math.max(5, intervalSeconds);
        int totalChecks = Math.max(1, (int) (observeMinutes * 60 / intervalSecondsSafe));

        Disposable subscription = Flux.interval(Duration.ofSeconds(intervalSecondsSafe),
                        Duration.ofSeconds(intervalSecondsSafe))
                .take(totalChecks)
                .concatMap(tick -> checkOnce(taskId, tick.intValue() + 1)
                        .subscribeOn(Schedulers.boundedElastic()))
                // scan 累积观察状态，takeUntil 在判定复现的那一刻立刻收尾 ——
                // 不必空跑完剩下的轮次，故障复现要尽快重新触发分析。
                .scan(new ObservationSummary(failureThreshold), ObservationSummary::merge)
                .takeUntil(ObservationSummary::isRegressed)
                .last()
                .subscribeOn(Schedulers.boundedElastic())
                .subscribe(
                        summary -> finishVerification(taskId, summary, totalChecks),
                        e -> {
                            activeObservations.remove(taskId);
                            log.warn("回归观察异常结束 taskId={}: {}", taskId, e.getMessage());
                        });

        activeObservations.put(taskId, subscription);
        verifyStarted.increment();
        log.info("任务 {} 进入回归观察期：{} 分钟 / 每 {} 秒复查一次（共 {} 次）",
                taskId, observeMinutes, intervalSecondsSafe, totalChecks);
    }

    /** 单次复查：查 Deployment + Pod，判定服务是否健康。 */
    private Mono<ObservationResult> checkOnce(String taskId, int round) {
        return Mono.fromCallable(() -> {
            ObservationResult result = new ObservationResult();
            result.round = round;

            Task task = taskScheduler.getTask(taskId).block();
            if (task == null) {
                // 任务已过期（Redis TTL）或被清理，观察无意义，按通过处理避免误报复现
                result.healthy = true;
                result.note = "任务已不存在，跳过观察";
                return result;
            }
            String deploymentName = resolveDeployment(task);
            if (deploymentName == null || deploymentName.isBlank()) {
                result.healthy = true;
                result.note = "无法从任务解析出服务名，跳过观察";
                return result;
            }
            result.deployment = deploymentName;

            Deployment deployment = holder.client().apps().deployments()
                    .inNamespace(holder.namespace()).withName(deploymentName).get();
            if (deployment == null) {
                // 服务被删掉属于另一类变更，不是「修复复现」
                result.healthy = true;
                result.note = "Deployment 不存在，跳过观察";
                return result;
            }

            int desired = deployment.getSpec() != null && deployment.getSpec().getReplicas() != null
                    ? deployment.getSpec().getReplicas() : 0;
            var status = deployment.getStatus();
            int ready = status != null && status.getReadyReplicas() != null ? status.getReadyReplicas() : 0;
            int available = status != null && status.getAvailableReplicas() != null ? status.getAvailableReplicas() : 0;

            List<Pod> pods = holder.client().pods().inNamespace(holder.namespace())
                    .withLabelSelector(deployment.getSpec().getSelector())
                    .list().getItems();

            long restarts = 0;
            List<String> problems = new ArrayList<>();
            for (Pod pod : pods) {
                if (pod.getStatus() == null || pod.getStatus().getContainerStatuses() == null) {
                    continue;
                }
                for (var cs : pod.getStatus().getContainerStatuses()) {
                    if (cs.getRestartCount() != null) {
                        restarts += cs.getRestartCount();
                    }
                    if (cs.getState() != null && cs.getState().getWaiting() != null
                            && cs.getState().getWaiting().getReason() != null) {
                        problems.add(pod.getMetadata().getName() + ":" + cs.getState().getWaiting().getReason());
                    }
                }
            }

            result.readyReplicas = ready;
            result.desiredReplicas = desired;
            result.restarts = restarts;
            result.problems = problems;

            // 判定：就绪与可用都要达到期望，且不存在 CrashLoop 类等待原因
            boolean replicasOk = desired == 0 || (ready >= desired && available >= desired);
            boolean noCrash = problems.isEmpty();
            result.healthy = replicasOk && noCrash;
            result.note = "第 " + round + " 次复查：就绪 " + ready + "/" + desired
                    + "，重启累计 " + restarts
                    + (problems.isEmpty() ? "" : "，异常 " + problems);
            return result;
        }).subscribeOn(Schedulers.boundedElastic());
    }

    /**
     * 观察期结束：出现过复现则把任务改为 REGRESSED 并重新触发分析；
     * 否则追加一条「回归验证通过」的 step，作为任务真的修好了的证据。
     */
    private void finishVerification(String taskId, ObservationSummary summary, int totalChecks) {
        activeObservations.remove(taskId);

        if (summary.regressed) {
            verifyRegressed.increment();
            String note = "回归验证未通过：修复后服务再次异常（" + summary.lastNote + "）。"
                    + "任务已标记 REGRESSED 并重新触发分析。";
            log.warn("任务 {} 回归验证未通过，判定为 REGRESSED：{}", taskId, summary.lastNote);

            taskScheduler.completeTask(taskId, note, "REGRESSED")
                    .then(taskScheduler.appendStep(taskId, "[regression] " + note))
                    .subscribe(v -> { }, e -> log.warn("标记 REGRESSED 失败 taskId={}: {}", taskId, e.getMessage()));

            retriggerAnalysis(taskId, summary);
            return;
        }

        verifyPassed.increment();
        String note = "回归验证通过：观察 " + observeMinutes + " 分钟内服务持续健康（"
                + summary.rounds + "/" + totalChecks + " 次复查，末次就绪 "
                + summary.lastReady + "/" + summary.lastDesired + "）。";
        log.info("任务 {} 回归验证通过：{}", taskId, note);
        taskScheduler.appendStep(taskId, "[regression] " + note)
                .subscribe(v -> { }, e -> log.warn("追加回归验证 step 失败 taskId={}", taskId));
    }

    /**
     * 故障复现：用一条新告警重新触发分析。
     *
     * 复用 AlertIngestionService.processAlert —— 建新任务、走降噪、通知 agent 全部
     * 与首次触发同一条链路。输入里带上「上次修复后复现」，让 LLM 知道这不是新故障，
     * 而是上次修复没保持住，重点应查「为什么没修好」。
     */
    private void retriggerAnalysis(String taskId, ObservationSummary summary) {
        taskScheduler.getTask(taskId)
                .flatMap(task -> {
                    String alertId = task.getAlertId();
                    if (alertId == null || alertId.isBlank()) {
                        log.info("任务 {} 无关联告警，跳过复现重触发", taskId);
                        return Mono.empty();
                    }
                    Alert alert = new Alert();
                    alert.setId(UUID.randomUUID().toString());
                    alert.setSource("recovery-verifier");
                    alert.setSeverity("CRITICAL");
                    alert.setTitle("[修复复现] " + summary.deployment + " 修复后再次异常");
                    alert.setDescription("服务 " + summary.deployment + " 在上一轮修复后的回归观察期内再次异常。"
                            + "上次任务=" + taskId + "，观察结果：" + summary.lastNote
                            + "。请重点排查「为什么修复没有保持」，而不仅是重复执行上次的修复动作。");
                    alert.setServiceName(summary.deployment);
                    alert.setHost("unknown");
                    alert.setDetail("前序任务 " + taskId + " 已标记 REGRESSED");
                    return alertIngestionService.processAlert(alert).then();
                })
                .subscribe(v -> { },
                        e -> log.warn("复现重触发失败 taskId={}: {}", taskId, e.getMessage()));
    }

    /**
     * 从任务输入里解析服务名。
     *
     * 任务的 input 是告警描述文本（「服务 leaky-app 发生告警：...」），没有结构化字段。
     * 降噪追加的关联告警行加在尾部、不破坏这个前缀，所以正则匹配仍然可靠。
     */
    private String resolveDeployment(Task task) {
        return parseDeployment(task.getInput());
    }

    /**
     * 从文本里解析服务名（包级可见以便单测直接覆盖解析规则）。
     *
     * 任务的 input 是告警描述文本（「服务 leaky-app 发生告警：...」），没有结构化字段。
     * 降噪追加的关联告警行加在尾部、不破坏这个前缀，所以正则匹配仍然可靠。
     * 解析不出（如「压测告警 #1: leaky-app ...」这种无「服务」前缀的格式）返回 null，
     * 调用方据此跳过观察 —— 宁可少观察，也不对错误的 Deployment 下健康判断。
     */
    static String parseDeployment(String input) {
        if (input == null || input.isBlank()) {
            return null;
        }
        Matcher m = SERVICE_PATTERN.matcher(input);
        return m.find() ? m.group(1) : null;
    }

    /** 单次复查结果。包级可见以便单测构造。 */
    static final class ObservationResult {
        boolean healthy = true;
        int round;
        String note = "";
        String deployment = "";
        int readyReplicas;
        int desiredReplicas;
        long restarts;
        List<String> problems = List.of();
    }

    /** 整个观察期的累积状态。连续失败达到阈值才判复现（包级可见以便单测）。 */
    static final class ObservationSummary {
        private final int failureThreshold;
        boolean regressed = false;
        int rounds = 0;
        int consecutiveFailures = 0;
        String lastNote = "";
        String deployment = "";
        int lastReady = 0;
        int lastDesired = 0;

        ObservationSummary(int failureThreshold) {
            this.failureThreshold = Math.max(1, failureThreshold);
        }

        /** takeUntil 需要方法引用（字段访问器），不能直接用字段。 */
        boolean isRegressed() {
            return regressed;
        }

        ObservationSummary merge(ObservationResult r) {
            if (r == null) {
                return this;
            }
            rounds++;
            lastNote = r.note;
            if (!r.deployment.isBlank()) {
                deployment = r.deployment;
            }
            lastReady = r.readyReplicas;
            lastDesired = r.desiredReplicas;
            if (r.healthy) {
                consecutiveFailures = 0;
            } else {
                consecutiveFailures++;
                if (consecutiveFailures >= failureThreshold) {
                    regressed = true;
                }
            }
            return this;
        }
    }
}
