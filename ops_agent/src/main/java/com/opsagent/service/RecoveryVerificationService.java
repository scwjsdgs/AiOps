package com.opsagent.service;

import com.opsagent.entity.AlertEntity;
import com.opsagent.model.Task;
import com.opsagent.repository.AlertRepository;
import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.api.model.apps.Deployment;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
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
    private final MeterRegistry meterRegistry;
    private final AlertRepository alertRepository;
    private final AgentCallbackService agentCallbackService;
    private final AlertLifecycleService alertLifecycleService;
    private final com.opsagent.repository.ApprovalRepository approvalRepository;

    public RecoveryVerificationService(KubernetesToolClientHolder holder,
                                       TaskSchedulerService taskScheduler,
                                       AlertRepository alertRepository,
                                       MeterRegistry meterRegistry,
                                       AlertLifecycleService alertLifecycleService,
                                       com.opsagent.repository.ApprovalRepository approvalRepository,
                                       @org.springframework.context.annotation.Lazy AgentCallbackService agentCallbackService) {
        this.holder = holder;
        this.taskScheduler = taskScheduler;
        this.alertRepository = alertRepository;
        this.meterRegistry = meterRegistry;
        this.alertLifecycleService = alertLifecycleService;
        this.approvalRepository = approvalRepository;
        this.agentCallbackService = agentCallbackService;
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

    /** 观察期总时长（分钟）。供 AgentCallbackService 计算前端倒计时终点。 */
    public long observeMinutes() {
        return Math.max(1, observeMinutes);
    }

    /** 供 AlertWebhookService 判断某个任务/告警是否正处于恢复观察期。 */
    public boolean isTaskInObservation(String taskId) {
        if (taskId == null || taskId.isBlank()) {
            return false;
        }
        Disposable d = activeObservations.get(taskId);
        return d != null && !d.isDisposed();
    }

    /** 观察期内是否已判定复现（供 resolved 优先逻辑使用）。 */
    public boolean isTaskRegressed(String taskId) {
        if (taskId == null) {
            return false;
        }
        try {
            String status = taskScheduler.getTask(taskId).map(Task::getStatus).block();
            return "REGRESSED".equalsIgnoreCase(status);
        } catch (Exception e) {
            // Redis command timed out / 任务查询异常：按“未复现”处理，绝不向调用方抛异常。
            // 判定失败比误判复现更安全：不会误触发打回/重拉，等观察期或真实健康验证兜底。
            log.warn("查询任务是否 REGRESSED 异常（按未复现处理） taskId={}: {}", taskId, e.getMessage());
            return false;
        }
    }

    /** 观察开始时的容器重启总数基线：taskId -> 观察期起点的累计 restartCount。
     *  用于 5 分钟观察期结束时对比重启次数是否增长（假恢复判定核心）。 */
    private final Map<String, Long> baselineRestarts = new ConcurrentHashMap<>();

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

        // 观察期起点基线：抓一次“当前累计 restartCount”。
        // 观察期结束时哪怕 Ready 看着正常，只要 restartCount 涨了，就是假恢复。
        Long baseline = null;
        try {
            baseline = readCurrentRestarts(taskId);
        } catch (Exception e) {
            log.warn("读取观察期起点 restartCount 失败（按 0 处理） taskId={}: {}", taskId, e.getMessage());
        }
        baselineRestarts.put(taskId, baseline == null ? 0L : baseline);

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
                            baselineRestarts.remove(taskId);
                            log.warn("回归观察异常结束 taskId={}: {}", taskId, e.getMessage());
                        });

        activeObservations.put(taskId, subscription);
        verifyStarted.increment();
        log.info("任务 {} 进入回归观察期：{} 分钟 / 每 {} 秒复查一次（共 {} 次），观察起点 restartCount={}",
                taskId, observeMinutes, intervalSecondsSafe, totalChecks, baseline == null ? "?" : baseline);
    }

    /** 读取某个任务对应 Deployment 下所有 Pod 的累计 restartCount。 */
    private Long readCurrentRestarts(String taskId) {
        Task task = taskScheduler.getTask(taskId).block();
        if (task == null) {
            return 0L;
        }
        String deploymentName = resolveDeployment(task);
        if (deploymentName == null || deploymentName.isBlank()) {
            return 0L;
        }
        List<Pod> pods = holder.client().pods().inNamespace(holder.namespace())
                .withLabelSelector(holder.client().apps().deployments()
                        .inNamespace(holder.namespace()).withName(deploymentName).get().getSpec().getSelector())
                .list().getItems();
        long total = 0;
        for (Pod pod : pods) {
            if (pod.getStatus() == null || pod.getStatus().getContainerStatuses() == null) {
                continue;
            }
            for (var cs : pod.getStatus().getContainerStatuses()) {
                if (cs.getRestartCount() != null) {
                    total += cs.getRestartCount();
                }
            }
        }
        return total;
    }

    /**
     * resolved 信号到达时校验服务是否真的健康。
     *
     * 返回 true = K8s 侧确认健康，可以落 RESOLVED；
     * 返回 false = 仍存在 CrashLoop/副本未就绪等故障，调用方应拦截假恢复信号。
     */
    public Mono<Boolean> verifyServiceHealthy(String serviceName) {
        if (serviceName == null || serviceName.isBlank()) {
            // 没有服务名就无法验证，保守起见视为健康放行，避免阻塞整张告警终态。
            return Mono.just(true);
        }
        return Mono.fromCallable(() -> {
            Deployment deployment = holder.client().apps().deployments()
                    .inNamespace(holder.namespace()).withName(serviceName).get();
            if (deployment == null) {
                // Deployment 已删，resolved 作为正常终态放行。
                return true;
            }
            int desired = deployment.getSpec() != null && deployment.getSpec().getReplicas() != null
                    ? deployment.getSpec().getReplicas() : 0;
            var status = deployment.getStatus();
            int ready = status != null && status.getReadyReplicas() != null ? status.getReadyReplicas() : 0;
            int available = status != null && status.getAvailableReplicas() != null ? status.getAvailableReplicas() : 0;

            // 缩容到 0 的假健康：readyReplicas==0 且 specReplicas==0 是「人为 scale 到 0」，
            // 不是服务恢复，绝不能据此判定健康放行。这里直接判不健康（SCALED_TO_ZERO）。
            if (ready == 0 && desired == 0) {
                log.warn("回归验证拦截 SCALED_TO_ZERO：服务 {} 就绪 0/0（被缩容到 0），不能判定健康 service={}",
                        serviceName, serviceName);
                return false;
            }

            List<Pod> pods = holder.client().pods().inNamespace(holder.namespace())
                    .withLabelSelector(deployment.getSpec().getSelector())
                    .list().getItems();
            for (Pod pod : pods) {
                if (pod.getStatus() == null || pod.getStatus().getContainerStatuses() == null) {
                    continue;
                }
                for (var cs : pod.getStatus().getContainerStatuses()) {
                    // 正在 Waiting（CrashLoopBackOff / BackOff / ImagePull）
                    if (cs.getState() != null && cs.getState().getWaiting() != null
                            && cs.getState().getWaiting().getReason() != null
                            && (cs.getState().getWaiting().getReason().contains("CrashLoop")
                                    || cs.getState().getWaiting().getReason().contains("BackOff")
                                    || cs.getState().getWaiting().getReason().contains("ImagePull"))) {
                        return false;
                    }
                    // 刚 Exited Error（Pod 显示 Running 但容器反复退出）——假恢复的关键
                    if (cs.getLastState() != null && cs.getLastState().getTerminated() != null
                            && cs.getLastState().getTerminated().getReason() != null
                            && cs.getLastState().getTerminated().getReason().contains("Error")) {
                        return false;
                    }
                    // restartCount 异常增长（>0 且最近在涨）也按不健康处理
                    if (cs.getRestartCount() != null && cs.getRestartCount() > 5) {
                        return false;
                    }
                }
            }
            // 不允许「0/0」这种空转通过：只要还有期望副本就必须就绪/可用全达标；
            // desired==0 已在上面被 SCALED_TO_ZERO 拦截，这里不再出现
            return ready >= desired && available >= desired;
        }).subscribeOn(Schedulers.boundedElastic());
    }

    /** 单次复查：查 Deployment + Pod，判定服务是否健康。 */
    private Mono<ObservationResult> checkOnce(String taskId, int round) {
        return Mono.fromCallable(() -> {
            ObservationResult result = new ObservationResult();
            result.round = round;
            try {
                return doCheckOnce(taskId, round, result);
            } catch (Exception e) {
                // 查询/校验异常绝不能中断观察定时器：保守判为不健康（复现方向），
                // 保证 5 分钟观察期必然跑完并强制决出终态。
                log.warn("回归验证单次检查异常（按不健康处理，定时器不中断） taskId={}: {}",
                        taskId, e.getMessage());
                result.healthy = false;
                result.note = "观察检查异常：" + e.getMessage();
                return result;
            }
        }).subscribeOn(Schedulers.boundedElastic());
    }

    private ObservationResult doCheckOnce(String taskId, int round, ObservationResult result) {
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
                    String reason = "";
                    // 当前 waiting 状态：CrashLoopBackOff / ImagePullBackOff 等
                    if (cs.getState() != null && cs.getState().getWaiting() != null
                            && cs.getState().getWaiting().getReason() != null) {
                        reason = cs.getState().getWaiting().getReason();
                    }
                    // lastTerminated 状态：容器刚退出 Error，Pod 可能是 Running / Waiting
                    if (reason.isBlank() && cs.getLastState() != null
                            && cs.getLastState().getTerminated() != null
                            && cs.getLastState().getTerminated().getReason() != null) {
                        reason = "last:" + cs.getLastState().getTerminated().getReason();
                    }
                    if (isCrashLikeReason(reason)) {
                        problems.add(pod.getMetadata().getName() + ":" + reason);
                    }
                }
            }

            result.readyReplicas = ready;
            result.desiredReplicas = desired;
            result.restarts = restarts;
            result.problems = problems;
            result.baselineRestarts = baselineRestarts.getOrDefault(taskId, 0L);
            result.restartGrowth = restarts > result.baselineRestarts;

            // 缩容到 0（就绪 0/0）是人为把副本降到 0，不是服务恢复：
            // 绝不能判定健康、绝不能给绿色通过，立即按硬复现处理。
            if (ready == 0 && desired == 0) {
                result.healthy = false;
                result.scaledToZero = true;
                result.note = "第 " + round + " 次复查：就绪 0/0 —— 服务被缩容到 0（SCALED_TO_ZERO），"
                        + "非健康状态，不允许判定恢复。";
                return result;
            }

            // 判定：就绪与可用都要达到期望、无 CrashLoop 类等待原因；
            // 且最重要的：相对观察起点 restartCount 不能增长。
            // Pod 在 1/1 Running 与 Error 之间反复横跳时，Ready 可能瞬间为 1，
            // 只有 restartCount 增长才能揭示“假恢复”。
            boolean replicasOk = ready >= desired && available >= desired;
            boolean noCrash = problems.isEmpty();
            boolean noRestartGrowth = !result.restartGrowth;
            result.healthy = replicasOk && noCrash && noRestartGrowth;
            result.note = "第 " + round + " 次复查：就绪 " + ready + "/" + desired
                    + "，重启累计 " + restarts
                    + "（观察起点 " + result.baselineRestarts + "）"
                    + (result.restartGrowth ? "，restartCount 增长 -> 假恢复" : "")
                    + (problems.isEmpty() ? "" : "，异常 " + problems);
            return result;
    }

    private boolean isCrashLikeReason(String reason) {
        if (reason == null || reason.isBlank()) {
            return false;
        }
        String lower = reason.toLowerCase();
        return lower.contains("crashloop")
                || lower.contains("backoff")
                || lower.contains("imagepull")
                || lower.contains("error")
                || lower.contains("failed");
    }

    /**
     * 观察期结束：出现过复现则交状态机裁决「重试 or 耗尽」；
     * 否则任务置 SUCCESS、告警置 RESOLVED，彻底闭环。
     */
    private void finishVerification(String taskId, ObservationSummary summary, int totalChecks) {
        activeObservations.remove(taskId);

        if (summary.regressed) {
            verifyRegressed.increment();
            String note = "第 " + summary.rounds + " 次复查判定验证失败：服务在观察期内未恢复（"
                    + summary.lastNote + "）。";
            log.warn("[验证失败] 任务 {} 观察期内未恢复：{}", taskId, summary.lastNote);

            // 任务先落 REGRESSED：这是「修过但没保住」的独立语义，不是 SUCCESS。
            taskScheduler.completeTask(taskId, note, "REGRESSED")
                    .then(taskScheduler.appendStep(taskId, "[verify] " + note))
                    .then(Mono.fromCallable(() -> {
                        handleRegressedDecision(taskId, summary);
                        return true;
                    }).subscribeOn(Schedulers.boundedElastic()))
                    .subscribe(
                            v -> { },
                            e -> log.warn("处理 REGRESSED 裁决失败 taskId={}: {}", taskId, e.getMessage()));
            return;
        }

        verifyPassed.increment();
        String note = "观察 " + observeMinutes + " 分钟内服务持续健康（"
                + summary.rounds + "/" + totalChecks + " 次复查，末次就绪 "
                + summary.lastReady + "/" + summary.lastDesired + "），闭环完成。";
        log.info("任务 {} 验证通过：{}", taskId, note);
        // 验证通过：任务 SUCCESS + 告警 RESOLVED，一起落终态。
        // 同时清空倒计时字段，避免前端在已完成任务上继续显示「验证中」。
        taskScheduler.completeTask(taskId, "修复已验证有效。" + note, "SUCCESS")
                .then(taskScheduler.clearVerifyingCountdown(taskId))
                .then(taskScheduler.appendStep(taskId, "[verify] 验证通过：" + note))
                .then(Mono.fromCallable(() -> {
                    resolveAlertByTask(taskId);
                    return true;
                }).subscribeOn(Schedulers.boundedElastic()))
                .subscribe(
                        v -> log.info("[闭环] 任务 {} 验证通过，任务/告警已闭环", taskId),
                        e -> log.warn("验证通过落终态失败 taskId={}: {}", taskId, e.getMessage()));
    }

    /** 验证通过后把关联告警落 RESOLVED（清冷却/耗尽标记，彻底结束这条故障）。 */
    private void resolveAlertByTask(String taskId) {
        try {
            Task task = taskScheduler.getTask(taskId).block();
            if (task == null || task.getAlertId() == null || task.getAlertId().isBlank()) {
                return;
            }
            alertLifecycleService.findAlert(task.getAlertId())
                    .ifPresent(alertLifecycleService::onVerificationPassed);
        } catch (Exception e) {
            log.warn("验证通过后更新告警 RESOLVED 失败 taskId={}: {}", taskId, e.getMessage());
        }
    }

    /**
     * 验证失败后的重试预算裁决（防烧钱的唯一闸门）。
     *
     * - 还有预算：告警回到 PENDING、轮次 +1，自动拉起下一轮诊断（会重新走审批）；
     * - 预算耗尽：告警钉死 FAILED 并写 retryExhaustedAt，任务改 FAILED、发送最终失败报告，
     *   此后同一故障只累加次数，**绝不再自动弹窗**。
     */
    private void handleRegressedDecision(String taskId, ObservationSummary summary) {
        Task task = taskScheduler.getTask(taskId).block();
        if (task == null || task.getAlertId() == null || task.getAlertId().isBlank()) {
            log.warn("[验证失败] 任务 {} 无关联告警，无法裁决重试预算，保持 REGRESSED", taskId);
            return;
        }
        var alertOpt = alertLifecycleService.findAlert(task.getAlertId());
        if (alertOpt.isEmpty()) {
            log.warn("[验证失败] 告警 {} 不存在，无法裁决重试预算", task.getAlertId());
            return;
        }
        var alert = alertOpt.get();
        boolean approvedRepair = isApprovedRepair(taskId);
        AlertLifecycleService.VerificationDecision decision =
                alertLifecycleService.onVerificationRegressed(alert, approvedRepair);
        int max = alert.getMaxRetries() == null
                ? alertLifecycleService.maxRounds() : Math.max(1, alert.getMaxRetries());
        int round = alert.getVerificationRound() == null ? 1 : alert.getVerificationRound();

        if (decision == AlertLifecycleService.VerificationDecision.EXHAUSTED) {
            String report = alertLifecycleService.buildExhaustedReport(
                    alert, round, max, summary.lastNote);
            taskScheduler.completeTask(taskId, report, "FAILED")
                    .then(taskScheduler.appendStep(taskId,
                            "[verify] 验证连续 " + round + " 轮失败，重试预算耗尽，停止自动修复"))
                    .subscribe(
                            v -> log.warn("[重试耗尽] 任务 {} 已置 FAILED，最终失败报告已生成", taskId),
                            e -> log.warn("[重试耗尽] 落 FAILED 失败 taskId={}: {}", taskId, e.getMessage()));
            return;
        }

        // 还有预算：拉起下一轮诊断（重新审批），轮次已由状态机 +1。
        // 注意这里必须清掉旧任务的倒计时，避免前端在旧任务上继续显示「验证中」。
        taskScheduler.clearVerifyingCountdown(taskId).subscribe();
        retriggerFromRegressed(taskId, round);
    }

    /** 该任务是否存在已批准的审批单（= 本次是一次被授权的高危修复）。 */
    private boolean isApprovedRepair(String taskId) {
        if (taskId == null || taskId.isBlank()) {
            return false;
        }
        try {
            return approvalRepository.existsByTaskIdAndStatus(taskId, "APPROVED");
        } catch (Exception e) {
            log.warn("查询任务审批状态失败（按只读诊断处理） taskId={}: {}", taskId, e.getMessage());
            return false;
        }
    }

    /**
     * 验证失败（还有重试预算）后，基于旧告警重新拉起一次 AI 诊断。
     *
     * 不再走 processAlert（避免又被降噪聚合拦截）；直接用旧告警的 serviceName/description
     * 创建一个新的根因分析任务并通知 Agent。轮次已由状态机在裁决时 +1，
     * 新任务会带着这个轮次重新走审批 → 执行 → 验证。
     */
    private void retriggerFromRegressed(String taskId, int round) {
        try {
            taskScheduler.getTask(taskId)
                    .flatMap(task -> {
                        String alertId = task.getAlertId();
                        if (alertId == null || alertId.isBlank()) {
                            log.warn("[验证重试] 任务 {} 无关联告警，跳过重新拉起诊断", taskId);
                            return Mono.empty();
                        }
                        String alertIdFinal = alertId;
                        return Mono.fromCallable(() -> alertRepository.findById(alertIdFinal).orElse(null))
                                .flatMap(alert -> {
                                    if (alert == null) {
                                        log.warn("[验证重试] 旧告警不存在 alertId={}", alertIdFinal);
                                        return Mono.empty();
                                    }
                                    String serviceName = alert.getServiceName() == null ? "unknown" : alert.getServiceName();
                                    int max = alert.getMaxRetries() == null
                                            ? alertLifecycleService.maxRounds() : Math.max(1, alert.getMaxRetries());
                                    String input = "服务 " + serviceName
                                            + " 发生故障且修复未生效（上一轮任务 " + taskId
                                            + " 验证失败，第 " + round + "/" + max + " 轮）"
                                            + "，Pod 仍在崩溃重启。请重新诊断根因，并给出与上次不同的处置方案。"
                                            + (alert.getDescription() == null ? "" : "\n" + alert.getDescription());
                                    return taskScheduler.createTask("ROOT_CAUSE_ANALYSIS", alertIdFinal, input)
                                            .flatMap(newTask -> {
                                                // 新任务必须回填到告警实体的 task_id，否则 shouldBreachDedup
                                                // 看到的是旧 REGRESSED 任务，降噪聚合/前端「查看分析」也拿不到新任务。
                                                Mono<Void> backfill = Mono.fromRunnable(() -> alertRepository
                                                        .findById(alertIdFinal)
                                                        .ifPresent(a -> {
                                                            a.setTaskId(newTask.getId());
                                                            // 带着新任务的关联打回未解决：绝不能让刚被
                                                            // 拉起的诊断任务被当成已解决/已聚合处理。
                                                            a.setStatus("PENDING");
                                                            a.setProcessed(true);
                                                            alertRepository.save(a);
                                                        }))
                                                        .then();
                                                backfill
                                                        .subscribeOn(Schedulers.boundedElastic())
                                                        .subscribe(
                                                                v -> log.warn("[验证重试] 新诊断任务已回填告警 {} newTaskId={}",
                                                                        alertIdFinal, newTask.getId()),
                                                                e -> log.warn("[验证重试] 回填新任务失败 alertId={} newTaskId={}: {}",
                                                                        alertIdFinal, newTask.getId(), e.getMessage()));

                                                agentCallbackService.notifyAgent(newTask.getId(), input,
                                                        "[验证重试] " + serviceName + " 第 " + round + " 轮重新诊断",
                                                        serviceName)
                                                        .subscribe(null,
                                                                e -> log.warn("[验证重试] 重新诊断任务通知失败 taskId={}: {}",
                                                                        newTask.getId(), e.getMessage()));
                                                log.warn("[验证重试] 已拉起新诊断任务 oldTask={} newTask={} service={} round={}/{}",
                                                        taskId, newTask.getId(), serviceName, round, max);
                                                return Mono.empty();
                                            });
                                });
                    })
                    .subscribe(v -> { }, e -> log.warn("[验证重试] 重新拉起诊断失败 taskId={}: {}", taskId, e.getMessage()));
        } catch (Exception e) {
            log.warn("[验证重试] 重新拉起诊断异常 taskId={}: {}", taskId, e.getMessage());
        }
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
        boolean restartGrowth = false;   // 本次复查 restartCount 相对观察起点是否增长（假恢复核心标志）
        boolean scaledToZero = false;    // 就绪 0/0（被缩容到 0）——铁证级不可恢复，立即判失败
        int round;
        String note = "";
        String deployment = "";
        int readyReplicas;
        int desiredReplicas;
        long restarts;
        long baselineRestarts;
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
            // 缩容到 0 同样是我等不得的失败：readyReplicas==0 且 specReplicas==0 时
            // 直接判复现/失败，不靠连续失败阈值，绝不进入「绿色通过」分支。
            if (r.scaledToZero) {
                regressed = true;
                return this;
            }
            // 重启次数增长是「假恢复」的铁证：哪怕瞬间 Ready=1，只要 restartCount 在涨，
            // 就说明 Pod 仍在崩溃重启，立刻判复现（不依赖连续失败阈值)。
            if (r.restartGrowth) {
                regressed = true;
                return this;
            }
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
