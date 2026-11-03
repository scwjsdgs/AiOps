package com.opsagent.service;

import com.opsagent.entity.AlertEntity;
import com.opsagent.model.Alert;
import com.opsagent.model.AlertmanagerWebhookPayload;
import com.opsagent.repository.AlertRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Alertmanager Webhook 处理服务。
 *
 * 职责：
 * - 按 fingerprint 幂等去重（Redis SETNX，TTL 5 分钟）避免告警抖动导致重复分析。
 * - 将 Alertmanager 的 firing/resolved 标准化为内部 Alert 模型。
 * - firing：交给 AlertIngestionService 走完整 ReAct 分析链路。
 * - resolved：只更新告警终态为 RESOLVED，不再触发 agent。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AlertWebhookService {

    private final AlertRepository alertRepository;
    private final AlertIngestionService alertIngestionService;
    private final StringRedisTemplate redisTemplate;
    private final TaskSchedulerService taskScheduler;
    private final MeterRegistry meterRegistry;
    private final RecoveryVerificationService recoveryVerificationService;
    private final AlertLifecycleService alertLifecycleService;

    @Value("${opsagent.webhook.idempotency.ttlMinutes:5}")
    private long idempotencyTtlMinutes;

    @Value("${opsagent.webhook.fingerprint-keys:alertname,instance,severity}")
    private String fingerprintKeys;

    /** 告警降噪聚合窗口（分钟）。0 = 关闭降噪。窗口内同源故障只触发一次 agent 分析。 */
    @Value("${opsagent.webhook.correlation-window-minutes:3}")
    private long correlationWindowMinutes;

    /**
     * 报警升级阈值：同一指纹历史 occurrence 达到该值，新告警标题加 [严重恶化] 前缀。
     * 核心场景：REJECTED/FAILED/REGRESSED 死锁被打破后，故障仍在反复爆发
     * （底层服务持续崩溃、修复一而再被拒/超时），必须让前端高亮提示不是普通告警。
     */
    private static final int ESCALATION_OCCURRENCE_THRESHOLD = 3;

    /** 报警升级标题前缀。前端 Alerts 页读到该前缀即渲染高亮样式。 */
    public static final String ESCALATION_TITLE_PREFIX = "[严重恶化] ";

    private Counter webhookReceived;
    private Counter webhookDuplicate;
    private Counter webhookResolved;
    private Counter webhookCorrelated;

    /**
     * 窗口内暂存 corrKey -> alertId：首条告警占住聚合键时任务还没建出来
     * （值先写 "PENDING"），任务建好后在这里回填真实 taskId。
     * 用 ConcurrentHashMap：handleSingleAlert 在 boundedElastic 上跑，可能并发。
     */
    private final java.util.concurrent.ConcurrentHashMap<String, String> pendingCorrKeys =
            new java.util.concurrent.ConcurrentHashMap<>();

    @PostConstruct
    public void initMetrics() {
        webhookReceived = Counter.builder("agent.webhook.received").register(meterRegistry);
        webhookDuplicate = Counter.builder("agent.webhook.duplicate").register(meterRegistry);
        webhookResolved = Counter.builder("agent.webhook.resolved").register(meterRegistry);
        webhookCorrelated = Counter.builder("agent.webhook.correlated")
                .description("窗口内被聚合进已有分析的告警数（降噪命中）")
                .register(meterRegistry);
    }

    /**
     * 处理完整 Alertmanager webhook 负载。
     */
    public Mono<Void> processWebhook(AlertmanagerWebhookPayload payload) {
        if (payload == null || payload.getAlerts() == null || payload.getAlerts().isEmpty()) {
            log.warn("空的 webhook payload");
            return Mono.empty();
        }
        webhookReceived.increment();

        return Mono.fromRunnable(() -> {
            for (AlertmanagerWebhookPayload.AmAlert amAlert : payload.getAlerts()) {
                handleSingleAlert(amAlert, payload.getStatus());
            }
        }).subscribeOn(Schedulers.boundedElastic()).then();
    }

    private void handleSingleAlert(AlertmanagerWebhookPayload.AmAlert amAlert, String groupStatus) {
        String alertStatus = amAlert.getStatus() != null ? amAlert.getStatus() : groupStatus;
        log.info("[Webhook] 收到 Alertmanager 告警 status={} labels={} annotations={}",
                alertStatus, amAlert.getLabels(), amAlert.getAnnotations());
        if (alertStatus == null) {
            log.warn("告警缺少 status: labels={}", amAlert.getLabels());
            return;
        }

        String fingerprint = buildFingerprint(amAlert.getLabels());
        log.info("[Webhook] 生成指纹 fingerprint={}（keys={}）", fingerprint, fingerprintKeys);
        if (fingerprint.isBlank()) {
            log.warn("无法生成 fingerprint: labels={}", amAlert.getLabels());
            return;
        }

        // 【业务规则一：拒绝冷却 / 重试耗尽 防骚扰】
        // 用户拒绝（或审批超时）后、或自动重试预算耗尽后，底层故障若仍在持续，
        // 绝不能再建任务、再弹审批去骚扰用户。这里在一切降噪/去重之前统一拦截：
        //   COOLDOWN / EXHAUSTED → 只在这条告警上累加 occurrenceCount、维持 PENDING/FAILED，
        //   不建任务、不弹窗，然后直接返回。
        // 冷却到期由 AlertLifecycleService 自动解除，本条告警随即正常放行。
        if (!"resolved".equalsIgnoreCase(alertStatus)) {
            Boolean suppressed = suppressIfCoolingOrExhausted(fingerprint, alertStatus);
            if (Boolean.TRUE.equals(suppressed)) {
                return;
            }
        }

        // 幂等去重只针对 firing：resolved 是终态事件，也用同一个 key 的话，
        // 5 分钟内先 firing 后 resolved，resolved 会被去重挡掉，告警状态永远停在 ANALYZING。
        String alertStatusLower = alertStatus.toLowerCase();
        // 本帧是否已判定「必须重新触发任务」：一旦为 true，下方降噪聚合必须整体跳过，
        // 否则就会复现日志里「打破去重重新触发」紧跟「降噪命中…聚合」的逻辑撕裂——
        // 新告警被判了必须重建任务，却又被活动任务聚合并吞掉，前端永远收不到。
        // 判定放行后一路走到建任务流程，绝不再进聚合分支。
        boolean forceRetrigger = false;
        if ("firing".equals(alertStatusLower)) {
            String dedupKey = "webhook:dup:" + fingerprint;
            Boolean set = null;
            try {
                set = redisTemplate.opsForValue().setIfAbsent(dedupKey, "1",
                        java.time.Duration.ofMinutes(idempotencyTtlMinutes));
            } catch (Exception e) {
                // Redis 挂了/超时：绝不能让去重键异常把告警吞掉。按「键未占用」放行，
                // 宁可重复处理也不能静默丢失。
                log.warn("[Webhook] Redis 幂等去重异常（放行本帧） dedupKey={}: {}", dedupKey, e.getMessage());
            }
            if (Boolean.FALSE.equals(set)) {
                // 关键：dedup 命中 ≠ 一律忽略。若关联的最近任务已经终态失败（FAILED）/
                // 被拒（REJECTED）/复现（REGRESSED）/任务不存在，说明上次处理没成功，
                // 这次 firing 是新的故障信号，必须打破去重、重新触发任务，否则告警会被
                // Redis 的 5 分钟去重永久吞掉，前端永远收不到。
                forceRetrigger = shouldBreachDedup(fingerprint);
                if (forceRetrigger) {
                    log.info("[Webhook] 幂等命中但关联任务已失败/复现，打破去重重新触发 fingerprint={}", fingerprint);
                    try {
                        redisTemplate.delete(dedupKey);
                    } catch (Exception e) {
                        log.warn("删除去重键失败 dedupKey={}: {}", dedupKey, e.getMessage());
                    }
                    // 重新占位续 TTL，防止并发重发
                    try {
                        redisTemplate.opsForValue().set(dedupKey, "1", java.time.Duration.ofMinutes(idempotencyTtlMinutes));
                    } catch (Exception e) {
                        log.warn("重建去重键失败 dedupKey={}: {}", dedupKey, e.getMessage());
                    }
                } else {
                    webhookDuplicate.increment();
                    log.info("[Webhook] 幂等命中，去重 firing（关联任务仍活动） fingerprint={}", fingerprint);
                    return;
                }
            }
        }

        // resolved 先处理：先做一次真实恢复验证，确认服务健康才落 RESOLVED。
        // Alertmanager 的 resolved 可能只是规则恢复/数据抖动，服务实际还在 CrashLoop，
        // 直接置终态会把假恢复信号当成真正修复。
        if ("resolved".equalsIgnoreCase(alertStatus)) {
            Mono.fromRunnable(() -> {
                var list = alertRepository.findByFingerprintOrderByCreateTimeDesc(fingerprint);
                if (list.isEmpty()) {
                    log.warn("resolved 但找不到对应告警，fingerprint={}", fingerprint);
                    return;
                }
                AlertEntity e = list.get(0);
                // 强绑定：该告警关联任务若仍处于观察期、已 REGRESSED，或最终 FAILED/REJECTED，
                // 一律拒绝关闭告警，并打回未解决。不能因为 Alertmanager 发 resolved 就翻成已解决。
                Boolean observing = null;
                try {
                    observing = taskScheduler.findLatestTaskByAlertId(e.getId())
                            .map(t -> {
                                String taskId = t.getId();
                                String taskStatus = t.getStatus();
                                if (recoveryVerificationService.isTaskInObservation(taskId)
                                        || recoveryVerificationService.isTaskRegressed(taskId)
                                        || "FAILED".equalsIgnoreCase(taskStatus)) {
                                    return Boolean.TRUE;
                                }
                                // REJECTED 允许 resolved：人工拒绝不等于当前 CrashLoop 仍在发生，
                                // 不再用它永久锁死告警终态。
                                return Boolean.FALSE;
                            })
                            .block();
                } catch (Exception redisOrTaskEx) {
                    // Redis command timed out / 任务查询异常：不能中断 resolved 全链路。
                    // observing 按 false 处理，交给下方 verifyServiceHealthy 的真实健康判定
                    // 来决定是否拒绝假恢复——K8s 不健康照样打回 PENDING，Redis 故障不影响。
                    log.warn("resolved 强绑定任务查询失败（按不 observing 处理，交给真实健康验证） "
                                    + "fingerprint={} alertId={}: {}",
                            fingerprint, e.getId(), redisOrTaskEx.getMessage());
                }
                if (Boolean.TRUE.equals(observing)) {
                    // 强绑定：关联任务未成功结束（观察期/REGRESSED/FAILED）时，resolved 是假信号，
                    // 打回未解决。日志在改状态后打，避免“保持 CORRELATED”这类误导措辞。
                    String beforeStatus = e.getStatus();
                    if (!"PENDING".equalsIgnoreCase(beforeStatus)
                            && !"FIRING".equalsIgnoreCase(beforeStatus)) {
                        e.setStatus("PENDING");
                        e.setProcessed(true);
                        alertRepository.save(e);
                        log.warn("强绑定：关联任务未成功结束，忽略 Alertmanager resolved，并打回未解决 "
                                        + "（原 {}）fingerprint={} alertId={} service={} taskStatusCheck=done",
                                beforeStatus, fingerprint, e.getId(), e.getServiceName());
                    } else {
                        log.warn("强绑定：关联任务未成功结束，告警已处于 {}，忽略 Alertmanager resolved "
                                        + "fingerprint={} alertId={} service={} taskStatusCheck=done",
                                beforeStatus, fingerprint, e.getId(), e.getServiceName());
                    }
                    return;
                }
                // 兼容旧状态：如果告警本身仍是 VERIFYING，也忽略 resolved
                if ("VERIFYING".equalsIgnoreCase(e.getStatus())) {
                    log.info("告警 VERIFYING 中，忽略 Alertmanager resolved：fingerprint={} alertId={} service={}",
                            fingerprint, e.getId(), e.getServiceName());
                    return;
                }
                String serviceName = e.getServiceName();
                recoveryVerificationService.verifyServiceHealthy(serviceName)
                        .subscribe(healthy -> {
                            if (!Boolean.TRUE.equals(healthy)) {
                                // 服务仍有 CrashLoop/副本未就绪：驳回假恢复信号。K8s 不健康就说明
                                // 故障很严重（旧改 CORRELATED/ANALYZING/VERIFYING 全是不该有的假终态），
                                // 必须无条件强制打回 PENDING，让前端亮起红灯重新处理，绝不“保持”原状态。
                                // 若任务处于 VERIFYING 观察期，也不打断它——由 RecoveryVerificationService
                                // 观察期决出终态；这里只负责把告警状态钉回未解决。
                                String beforeStatus = e.getStatus();
                                e.setStatus("PENDING");
                                e.setProcessed(true);
                                try {
                                    alertRepository.save(e);
                                } catch (Exception saveEx) {
                                    log.warn("[假恢复拦截] 落库 PENDING 失败 alertId={}: {}", e.getId(), saveEx.getMessage());
                                }
                                log.warn("[假恢复拦截] 收到 resolved 信号，但 K8s 真实状态仍不健康，强制打回 PENDING（原 {}）"
                                                + " fingerprint={} alertId={} service={}",
                                        beforeStatus, fingerprint, e.getId(), serviceName);
                                return;
                            }
                            e.setStatus("RESOLVED");
                            e.setProcessed(true);
                            alertRepository.save(e);
                            webhookResolved.increment();
                            log.info("告警 resolved，且 K8s 服务 {} 健康，已更新状态 fingerprint={} alertId={}",
                                    serviceName, fingerprint, e.getId());
                        }, err -> {
                            // 验证异常时保守起见也落 RESOLVED：不能让外部校验失败阻塞告警终态
                            log.warn("resolved 健康验证异常，按正常 resolved 处理 fingerprint={}: {}",
                                    fingerprint, err.getMessage());
                            e.setStatus("RESOLVED");
                            e.setProcessed(true);
                            alertRepository.save(e);
                            webhookResolved.increment();
                        });
            }).subscribeOn(Schedulers.boundedElastic()).subscribe();
            return;
        }

        Alert alert = normalize(amAlert, alertStatus);
        if (alert == null) {
            return;
        }
        alert.setId(alert.getId() != null ? alert.getId() : UUID.randomUUID().toString());
        alert.setFingerprint(fingerprint);

        // ----- 降噪聚合（按任务状态决定） -----
        // 同一 Pod/服务的多条告警在窗口内到达时，只有当关联的最近任务还“值得聚合”时才聚合：
        //   PENDING（待审批/分析中）或 SUCCESS（观察期内）可以合并，避免重复弹窗/重复诊断；
        //   REJECTED/FAILED/TIMEOUT/REGRESSED 绝对放行——说明上次诊断/修复未成功，
        //   必须重新建任务、重新审批，否则运维人员想“反悔”都没有入口。
        // 强制重触发短路（最核心）：本帧已被判定必须重新建任务（forceRetrigger=true）时，
        // 绕过整个聚合判定，绝不能再被“关联任务仍活动”的降噪逻辑聚合吞掉——那是日志里
        // “打破去重重新触发”却紧跟“降噪命中聚合”的根本原因。必须直接走建任务流程。
        Boolean canMerge = null; // null=未判定，true=可合并，false=必须放行
        String corrKey = null;
        if (correlationWindowMinutes > 0) {
            corrKey = buildCorrelationKey(amAlert.getLabels());
        }
        boolean tryCorrelate = !forceRetrigger && corrKey != null && !corrKey.isBlank();

        // 1) 优先按关联最近任务状态判定：这是最权威的“是否还有活动任务”。
        String existingTaskId = null;
        if (tryCorrelate) {
            try {
                existingTaskId = redisTemplate.opsForValue().get("webhook:corr:" + corrKey);
            } catch (Exception e) {
                // Redis 超时/不可用：拿不到聚合键就按无聚合处理，绝不阻塞主流程。
                // existingTaskId 保持 null 走 DB 兜底，宁建新任务也不吞告警。
                log.warn("读取聚合键异常（按无聚合键处理） corrKey={}: {}", corrKey, e.getMessage());
            }
        }
        if (tryCorrelate && existingTaskId != null && !existingTaskId.isBlank()) {
            canMerge = taskCanMerge(existingTaskId);
            if (Boolean.FALSE.equals(canMerge)) {
                log.warn("降噪检查：任务 {} 已非活动（REJECTED/FAILED/TIMEOUT），放行新告警并重新建任务 title={}",
                        existingTaskId, alert.getTitle());
                // 清掉已经失效的聚合键，避免后续告警继续被旧任务拦住
                if (corrKey != null) {
                    try {
                        redisTemplate.delete("webhook:corr:" + corrKey);
                    } catch (Exception e) {
                        log.warn("清理失效聚合键失败 corrKey={}: {}", corrKey, e.getMessage());
                    }
                }
            }
        } else if (!forceRetrigger) {
            // 2) 没有 Redis 聚合键时，按最近告警/任务状态兜底：只允许活跃任务聚合。
            String service = alert.getServiceName();
            if (service != null && !service.isBlank()) {
                try {
                    java.util.List<AlertEntity> recent = alertRepository
                            .findFirstByServiceNameAndStatusInOrderByCreateTimeDesc(
                                    service, java.util.List.of("PENDING", "ANALYZING", "FIRING", "RESOLVED", "REJECTED", "FAILED"));
                    if (!recent.isEmpty()) {
                        AlertEntity recentAlert = recent.get(0);
                        String recentTaskId = taskScheduler.findLatestTaskByAlertId(recentAlert.getId())
                                .map(t -> t.getId()).block();
                        if (recentTaskId != null) {
                            canMerge = taskCanMerge(recentTaskId);
                            // 关键修复：DB 兜底分支找到了关联任务，必须同步回填 existingTaskId。
                            // 否则下方聚合分支的 resolvedTaskId 永远为空，即使 canMerge=true 也会
                            // 走进「兜底 Lock」分支——只打日志不建任务、把新告警落成 CORRELATED 后
                            // return，等于静默吞掉一条本应重新触发的告警（前端永远收不到）。
                            existingTaskId = recentTaskId;
                        }
                    }
                } catch (Exception e) {
                    // 查询失败不能吞掉告警：按放行处理，宁建新任务也不丢
                    log.warn("降噪兜底查询失败（放行新告警） service={}: {}", service, e.getMessage());
                }
            }
            if (canMerge == null) {
                // 没有任何可参考任务：默认放行，宁建新任务也不吞告警
                canMerge = Boolean.FALSE;
            }
        }

        if (Boolean.TRUE.equals(canMerge)) {
            // existingTaskId 来自 Redis 聚合键或 DB 兜底。拿不到真实 taskId 时，
            // 聚合就无从谈起：既不能把 null 塞进 appendCorrelatedAlert / setTaskId
            //（前者 mutate→getTask(null) 查不到任务，后者会把告警的 taskId 撕掉，
            // 事件被静默丢失），也不能把这条新告警落成 CORRELATED 后 return——
            // 那样等于静默吞掉一条本应重新触发的告警（前端永远收不到）。
            // 正确做法：拿不到 taskId 就放弃本帧聚合，fall through 到下方「放行建任务」流程。
            final String resolvedTaskId = (existingTaskId == null || existingTaskId.isBlank())
                    ? null : existingTaskId;
            if (resolvedTaskId == null) {
                // 兜底 Lock：判定「可聚合」却定位不到任务（DB 兜底命中活跃态但
                // findLatestTaskByAlertId 返回空/失败）。硬聚合只会崩/丢，直接放行。
                // 下方常规 firing 流程会新建任务，宁多建也不吞。
                log.warn("聚合判定可合并但 taskId 为空（兜底锁定），放弃聚合并放行重新建任务 title={}",
                        alert.getTitle());
            } else {
                webhookCorrelated.increment();
                log.info("降噪命中：告警聚合进已有活动分析 taskId={} corrKey={} title={}",
                        resolvedTaskId, corrKey, alert.getTitle());
                final String taskId = resolvedTaskId;
                final Alert finalAlert = alert;
                Mono.fromRunnable(() ->
                        taskScheduler.appendCorrelatedAlert(taskId, finalAlert.getTitle(), finalAlert.getSeverity())
                                .subscribe(ok -> { }, e -> log.warn("追加关联告警失败: {}", e)))
                        .subscribeOn(Schedulers.boundedElastic()).subscribe();

                // 聚合落库：不盲目 insert 新行。优先找同服务活跃（PENDING/FIRING/CORRELATED）
                // 的旧告警，命中则更新 occurrenceCount + 时间戳/内容；没有才新建。
                persistCorrelatedAlert(alert, resolvedTaskId, fingerprint);
                return;
            }
        } else {
            // canMerge == false：走下方放行建任务流程（正常触发分析）。
        }

        // ===== 放行分支（canMerge == false 或 resolvedTaskId == null 都会走到这里）=====
        // canMerge=true 但 resolvedTaskId 为空时，上方「兜底 Lock」仅打日志、不本帧聚合，
        // 直接落到这里走常规建任务流程。拿不到主任务却把新告警落成 CORRELATED 会吞掉一条
        // 本应重新触发的告警（前端永远收不到），所以不能让那种情况在本帧静止。
        // ===== 常规 firing 流程（占聚合键 / 复用老记录 / 建任务）见下方 =====

        // 放行：建任务/占聚合键都已交给下方常规 firing 流程。
        // firstAlertId 保存原始体征 ID：下方复用老记录时会把 alert.id 换成老记录 ID，
        // 而 pendingCorrKeys 的占位值仍是原始 ID，回填聚合键必须按原始 ID 匹配。
        String firstAlertId = alert.getId();
        if (corrKey != null && !corrKey.isBlank()) {
            // 占聚合键是增强降噪的可选项，绝不能因 Redis 超时让它中断主流程
            // （否则下面复用老记录/建任务全被跳过，告警静默丢失）。
            try {
                redisTemplate.opsForValue().set("webhook:corr:" + corrKey, "PENDING",
                        java.time.Duration.ofMinutes(correlationWindowMinutes));
                pendingCorrKeys.put(corrKey, firstAlertId);
            } catch (Exception e) {
                log.warn("占位聚合键失败（降噪退化为无关联，安全） corrKey={}: {}", corrKey, e.getMessage());
            }
        }

        // firing：优先复用同指纹的旧记录。
        //
        // 关键防死锁规则：旧记录若处于终态失败/复现（REJECTED / FAILED / REGRESSED），
        // 绝不能继续复用——那等于把后续 firing 永远聚合吞进一条已失败的历史记录里：
        // 底层服务还在崩溃，前端却永远只看到一条 REJECTED 老记录，再也收不到新告警。
        // 这类旧记录必须强制走「新建一条全新 PENDING 记录 + 新任务 + 重新审批」，
        // 让前端立刻冒出新告警。PENDING/ANALYZING/FIRING/观察期内 SUCCESS 才允许复用。
        //
        // 报警升级：同一指纹反复爆发（历史 occurrence 已达阈值）时，新告警标题加
        // [严重恶化] 前缀，前端据此高亮——故障一而再地被拒/超时/复现，必须让用户
        // 感知到「不是普通一次告警，而是反复发作」。
        AlertEntity entity = null;
        java.util.List<AlertEntity> sameFingerprint = java.util.Collections.emptyList();
        try {
            sameFingerprint = alertRepository.findByFingerprintOrderByCreateTimeDesc(fingerprint);
        } catch (Exception dbEx) {
            // DB 查询失败：绝不能中断 handleSingleAlert 导致告警静默丢失，
            // 按无历史记录处理（安全地走新建分支新建一帧），宁多建也不吞。
            log.warn("查询同指纹告警异常（按首次告警处理） fingerprint={}: {}", fingerprint, dbEx.getMessage());
        }

        // 报警升级判定：该指纹历史上已爆发多少次（复用记录 occ 累加值或 1）。
        int historicalOccurrence = sameFingerprint.isEmpty() || sameFingerprint.get(0).getOccurrenceCount() == null
                ? 1 : sameFingerprint.get(0).getOccurrenceCount();
        String escalationPrefix = historicalOccurrence >= ESCALATION_OCCURRENCE_THRESHOLD
                ? ESCALATION_TITLE_PREFIX : "";

        // 复用白名单：只有「真正活跃」的记录才值得复用（状态在途或观察期内）。
        // REJECTED/FAILED/REGRESSED 是终态失败/复现（isStaleTerminal）；
        // 脱离观察期的 SUCCESS、TIMEOUT 同样视为「旧任务已定型」——新 firing 是新的
        // 故障信号，必须新建一条 PENDING 记录重新拉起，不能挂在定型旧任务上。
        boolean reuseOld = !sameFingerprint.isEmpty()
                && isActiveForReuse(sameFingerprint.get(0));
        // 冷却/重试耗尽期间的同指纹记录绝不作为新任务的载体：它处于 PENDING 或 FAILED，
        // 但语义上「已停止自动处理」。能走到这里说明 checkSuppress 已放行（冷却刚到期），
        // 此时清空轮次标记、以它为新周期起点重新复用；若已耗尽（FAILED）则直接新建。
        if (reuseOld && sameFingerprint.get(0).getRejectCooldownUntil() != null) {
            log.info("[拒绝冷却] 冷却到期后的首个告警：以旧记录 {} 为起点重新复用并清空冷却标记",
                    sameFingerprint.get(0).getId());
        }
        if (reuseOld) {
            // 复用活跃/可合并的旧记录（PENDING/ANALYZING/FIRING/观察期内 SUCCESS）：
            // 状态强制重置为 PENDING、累加发生次数、更新内容；taskId 由
            // AlertIngestionService 建任务后回填，这里先置空避免残留旧任务的过期 ID。
            entity = sameFingerprint.get(0);
            String reusedId = entity.getId();
            int next = (entity.getOccurrenceCount() == null ? 1 : entity.getOccurrenceCount()) + 1;
            entity.setOccurrenceCount(next);
            entity.setStatus("PENDING");
            entity.setProcessed(false);
            entity.setTaskId(null);
            // 冷却到期后的复用是新一轮处理的起点：清空冷却/验证计时字段，
            // 否则前端会继续显示旧一轮的倒计时或冷却标记。
            entity.setRejectCooldownUntil(null);
            entity.setCooldownBaseOccurrence(null);
            entity.setVerificationDeadline(null);
            entity.setVerificationStartedAt(null);
            entity.setTitle(alert.getTitle());
            entity.setDescription(alert.getDescription());
            entity.setDetail(alert.getDetail());
            entity.setSeverity(alert.getSeverity());
            entity.setServiceName(alert.getServiceName());
            entity.setHost(alert.getHost());
            entity.setTimestamp(alert.getTimestamp());
            alertRepository.save(entity);
            log.info("解除聚合：复用老记录 {}（第 {} 次）重置为 PENDING，不再新建行 fingerprint={} service={}",
                    reusedId, next, fingerprint, alert.getServiceName());
            // 关键：后续 processAlert 建任务必须关联到这条被复用的老记录，
            // 否则新任务挂在随机 UUID 上，回写终态时又找不到复用主体。
            alert.setId(reusedId);
            alert.setFingerprint(fingerprint);
        } else {
            // 新建一条全新记录：首次告警、或旧记录已是 REJECTED/FAILED/REGRESSED
            // （终态死锁，必须重新拉起，绝不复用吞掉）。新 UUID + occ=1 + PENDING，
            // 前端会看到一条全新的告警冒出来。
            entity = new AlertEntity();
            entity.setId(alert.getId());
            entity.setSource(alert.getSource());
            entity.setSeverity(alert.getSeverity());
            entity.setTitle(escalationPrefix + alert.getTitle());
            entity.setDescription(alert.getDescription());
            entity.setServiceName(alert.getServiceName());
            entity.setHost(alert.getHost());
            entity.setDetail(alert.getDetail());
            entity.setTimestamp(alert.getTimestamp());
            entity.setProcessed(false);
            entity.setStatus("PENDING");
            entity.setCreateTime(LocalDateTime.now());
            entity.setFingerprint(fingerprint);
            entity.setOccurrenceCount(1);
            alertRepository.save(entity);
            if (sameFingerprint.isEmpty()) {
                log.info("首次告警：新建告警记录 id={} fingerprint={} service={}",
                        entity.getId(), fingerprint, alert.getServiceName());
            } else {
                log.warn("终态死锁打破：旧记录 {} 已 {}（{}），不再复用；新建 PENDING 记录 {} "
                                + "并重新触发分析（底层服务可能仍在故障） fingerprint={} service={}",
                        sameFingerprint.get(0).getId(), sameFingerprint.get(0).getStatus(),
                        isStaleTerminal(sameFingerprint.get(0).getStatus())
                                ? "终态失败/复现" : "旧任务已定型",
                        entity.getId(), fingerprint, alert.getServiceName());
            }
            // 报警升级标记：前端读到标题前缀即可高亮。
            // alert.title 必须同步加前缀——processAlert 用它建任务/通知 agent，
            // 否则告警列表带 [严重恶化] 而任务列表不带，两边语义割裂。
            if (!escalationPrefix.isEmpty()) {
                alert.setTitle(escalationPrefix + alert.getTitle());
                log.warn("告警升级：指纹 {} 已爆发 {} 次，新告警标题加 [严重恶化] 前缀 service={}",
                        fingerprint, historicalOccurrence, alert.getServiceName());
            }
            // alert.getId() 保持新 UUID：processAlert(reuseExisting=true) 会以它为锚点
            // 建任务并回填 taskId，从而让新任务挂到这条全新记录上。
            alert.setFingerprint(fingerprint);
        }

        // 再交给 AlertIngestionService 统一触发 AI 分析（建任务、通知 Agent）。
        // 传 reuseExisting=true：processAlert 内部不再自行新建 AlertEntity，
        // 直接以已有实体（复用主体或刚落库的首条）为锚点建任务并回填 taskId。
        final String corrKeyForFill = findPendingCorrKey(firstAlertId);
        // 实际落库实体 ID：复用场景下是老记录 ID，首告警场景下就是 firstAlertId
        final String persistedAlertId = entity.getId();
        alertIngestionService.processAlert(alert, true).subscribe(
                v -> log.info("Webhook firing 已触发分析, alertId={}", persistedAlertId),
                e -> log.error("Webhook 处理告警失败 alertId={}", persistedAlertId, e),
                null
        );
        // processAlert 内部异步建任务，这里拿不到 taskId 返回值；
        // 用轮询回填：把聚合键的 "PENDING" 更新为真实 taskId，供窗口内后续告警关联。
        if (corrKeyForFill != null) {
            fillCorrKeyWithTaskId(corrKeyForFill, persistedAlertId);
        }
    }

    /**
     * 拒绝冷却 / 重试耗尽 的抑制入口（防骚扰、防烧钱）。
     *
     * 命中时：只把该指纹最新一条告警的 occurrenceCount +1、维持未解决状态，
     * 绝不建任务、绝不再弹审批；返回 true 让调用方直接结束本帧。
     *
     * 冷却已到期时 {@link AlertLifecycleService#checkSuppress} 会顺手清掉冷却标记，
     * 返回 false，本条及后续告警恢复正常建任务流程 —— 这正是「15 分钟后仍报错
     * 才重新强制拉起」的入口。
     */
    private Boolean suppressIfCoolingOrExhausted(String fingerprint, String alertStatus) {
        try {
            List<AlertEntity> list = alertRepository.findByFingerprintOrderByCreateTimeDesc(fingerprint);
            if (list.isEmpty()) {
                // 没有历史记录谈不上冷却/耗尽
                return Boolean.FALSE;
            }
            AlertEntity latest = list.get(0);
            AlertLifecycleService.SuppressAction action = alertLifecycleService.checkSuppress(latest);
            if (action == AlertLifecycleService.SuppressAction.COOLDOWN
                    || action == AlertLifecycleService.SuppressAction.EXHAUSTED) {
                int next = (latest.getOccurrenceCount() == null ? 1 : latest.getOccurrenceCount()) + 1;
                latest.setOccurrenceCount(next);
                // 维持未解决语义：EXHAUSTED 时告警已是 FAILED（人工处理态），COOLDOWN 时是 PENDING
                latest.setTimestamp(LocalDateTime.now());
                alertRepository.save(latest);
                webhookDuplicate.increment();
                log.info("[{}] 指纹 {} 命中抑制，仅累加次数（第 {} 次），不建任务、不弹审批 service={}",
                        action, fingerprint, next, latest.getServiceName());
                return Boolean.TRUE;
            }
            return Boolean.FALSE;
        } catch (Exception e) {
            // 抑制判定失败时宁愿放行也不要吞告警：吞掉等于用户永远收不到这条故障
            log.warn("[冷却/耗尽] 抑制判定异常（放行新告警） fingerprint={}: {}", fingerprint, e.getMessage());
            return Boolean.FALSE;
        }
    }

    /**
     * 判断某任务是否“仍然值得聚合新告警”。
     *
     * 允许合并的状态：
     * - PENDING / RUNNING / VERIFYING：任务仍在处理中/观察中，新告警并入主任务合理；
     * - SUCCESS：仅当任务仍处于「回归观察期」（isTaskInObservation=true）时允许合并。
     *   观察期一旦结束，SUCCESS 任务已定型——后续同服务告警是新的故障信号，
     *   必须重新建任务重新分析，否则前端看到的永远是旧任务，永远感知不到新告警。
     *
     * REJECTED/FAILED/TIMEOUT/REGRESSED 一律放行——上次诊断/修复未成功，
     * 新告警必须重新建任务并重新审批。
     */
    private Boolean taskCanMerge(String taskId) {
        if (taskId == null || taskId.isBlank()) {
            return Boolean.FALSE;
        }
        try {
            String status = taskScheduler.getTask(taskId)
                    .map(t -> t.getStatus())
                    .block();
            if (status == null) {
                return Boolean.FALSE;
            }
            String upper = status.toUpperCase();
            if ("PENDING".equals(upper) || "RUNNING".equals(upper) || "VERIFYING".equals(upper)) {
                // 仍在处理中：允许聚合，走下方 REJECTED 主告警兜底检查
            } else if ("SUCCESS".equals(upper)) {
                // SUCCESS 只允许在观察期内聚合；观察期已结束的任务必须放行，
                // 否则同服务的新告警全被一条定型任务吞掉（前端永远收不到新分析）。
                if (!recoveryVerificationService.isTaskInObservation(taskId)) {
                    log.info("任务 {} 已 SUCCESS 且脱离观察期，拒绝聚合，放行新告警重新建任务",
                            taskId);
                    return Boolean.FALSE;
                }
            } else {
                // REJECTED/FAILED/TIMEOUT/REGRESSED 一律返回 false，强制放行并重新建任务
                return Boolean.FALSE;
            }
            // 关键兜底：即使任务状态看起来还“活动”（例如 VERIFYING/SUCCESS 观察期），
            // 只要它关联的主告警是 REJECTED（用户拒绝过审批），就绝不能聚合——
            // 拒绝不等于故障消失，必须放行让新告警重新拉起审批。
            try {
                String alertId = taskScheduler.getTask(taskId)
                        .map(t -> t.getAlertId()).block();
                if (alertId != null && !alertId.isBlank()) {
                    AlertEntity alert = alertRepository.findById(alertId).orElse(null);
                    if (alert != null && "REJECTED".equalsIgnoreCase(alert.getStatus())) {
                        log.warn("[拒绝后再次爆发] 任务 {} 关联主告警为 REJECTED，拒绝聚合并放行新告警", taskId);
                        return Boolean.FALSE;
                    }
                }
            } catch (Exception e) {
                log.warn("查询主告警状态失败（按放行处理） taskId={}: {}", taskId, e.getMessage());
                return Boolean.FALSE;
            }
            return Boolean.TRUE;
        } catch (Exception e) {
            log.warn("查询任务状态失败（按放行处理） taskId={}: {}", taskId, e.getMessage());
            return Boolean.FALSE;
        }
    }

    /**
     * 判断旧告警记录是否处于「终态失败/复现」——这类记录绝不能作为聚合载体。
     *
     * REJECTED：人工拒绝了修复（或审批超时自动拒绝），但底层服务可能还在崩溃；
     * FAILED：上次分析/执行失败；
     * REGRESSED：修复后观察期复现。
     *
     * 三种状态都说明「上次处理没成功」，继续把新 firing 聚合进去，前端就永远
     * 只看到一条失败的老记录，永远收不到新告警——这正是要打破的死锁。
     */
    private boolean isStaleTerminal(String status) {
        if (status == null || status.isBlank()) {
            return false;
        }
        String upper = status.toUpperCase();
        return "REJECTED".equals(upper) || "FAILED".equals(upper) || "REGRESSED".equals(upper);
    }

    /**
     * 判断旧告警记录是否「活跃到可以复用为新告警的载体」。
     *
     * 只有状态仍在途/观察期才复用：PENDING、ANALYZING、FIRING、CORRELATED、
     * VERIFYING，以及仍处观察期的 SUCCESS。
     * 其余一律走新建：终态失败（REJECTED/FAILED/REGRESSED，isStaleTerminal）、
     * TIMEOUT、脱离观察期的 SUCCESS —— 旧任务已定型，新 firing 必须建新告警，
     * 否则前端永远只看到一条定型/失败的老记录。
     */
    private boolean isActiveForReuse(AlertEntity record) {
        if (record == null || record.getStatus() == null || record.getStatus().isBlank()) {
            return false;
        }
        String upper = record.getStatus().toUpperCase();
        switch (upper) {
            case "PENDING":
            case "ANALYZING":
            case "FIRING":
            case "CORRELATED":
            case "VERIFYING":
                return true;
            case "SUCCESS":
                // 观察期内的 SUCCESS 才允许复用（同一条持续故障仍在被回归观察）；
                // 脱离观察期视为已定型，放行新建。
                String taskId = record.getTaskId();
                return taskId != null && !taskId.isBlank()
                        && recoveryVerificationService.isTaskInObservation(taskId);
            default:
                return false;
        }
    }

    /**
     * 判断要不要打破 webhook:dup 去重。
     *
     * firing 去重的本意是挡住「同一帧 5 分钟内的重发抖动」。但若该 fingerprint
     * 关联的最近任务已经终态失败/被拒/复现，或者根本没有任务，说明上次处理
     * 没跑通，这次 firing 是新的故障信号 —— 必须放行重新触发，否则告警会被
     * Redis 去重永久吞掉（典型症状：Alertmanager 疯狂 Firing，前端一条收不到）。
     *
     * @return true = 打破去重（重新触发任务）；false = 正常去重忽略
     */
    private boolean shouldBreachDedup(String fingerprint) {
        try {
            java.util.List<AlertEntity> list =
                    alertRepository.findByFingerprintOrderByCreateTimeDesc(fingerprint);
            if (list.isEmpty()) {
                // 有去重键但库里没记录：异常态，放行重建
                log.warn("[Webhook] dedup 键存在但库中无该指纹告警，放行重建 fingerprint={}", fingerprint);
                return true;
            }
            AlertEntity e = list.get(0);
            String taskId = e.getTaskId();
            if (taskId == null || taskId.isBlank()) {
                // 无关联任务：上次可能连任务都没建成，放行重试
                log.info("[Webhook] 指纹 {} 无关联任务（alertId={}），放行重新触发", fingerprint, e.getId());
                return true;
            }
            // 任务仍活动（PENDING/RUNNING/SUCCESS 观察期）→ 正常去重；
            // 任务已终态失败（FAILED/REJECTED/REGRESSED/TIMEOUT，即 taskCanMerge=false）→ 打破。
            return !Boolean.TRUE.equals(taskCanMerge(taskId));
        } catch (Exception ex) {
            // 查询失败时保守放行：宁可重复处理也不能吞告警
            log.warn("[Webhook] 打破去重判定异常（放行） fingerprint={}: {}", fingerprint, ex.getMessage());
            return true;
        }
    }

    /** 找到该 alertId 占住的聚合键（首条告警在建任务前把 alertId 记在 pendingCorrKeys）。 */
    private String findPendingCorrKey(String alertId) {
        return pendingCorrKeys.entrySet().stream()
                .filter(e -> alertId.equals(e.getValue()))
                .map(java.util.Map.Entry::getKey)
                .findFirst()
                .orElse(null);
    }

    /**
     * 回填聚合键：轮询等任务建出来，把值从 "PENDING" 更新为真实 taskId。
     * 最多等 10 秒（processAlert 的建任务是异步的），拿不到就放弃——
     * 后续告警会因键值仍是 "PENDING" 不匹配 taskId 而正常各自建任务，退化为无降噪，安全。
     */
    private void fillCorrKeyWithTaskId(String corrKey, String alertId) {
        Mono.defer(() -> taskScheduler.findLatestTaskByAlertId(alertId))
                .subscribeOn(Schedulers.boundedElastic())
                .repeatWhenEmpty(longFlux -> longFlux.delayElements(java.time.Duration.ofMillis(500)))
                .take(java.time.Duration.ofSeconds(10))
                .subscribe(
                        task -> {
                            try {
                                redisTemplate.opsForValue().set("webhook:corr:" + corrKey, task.getId(),
                                        java.time.Duration.ofMinutes(correlationWindowMinutes));
                                pendingCorrKeys.remove(corrKey);
                                log.info("聚合键已回填 taskId={} corrKey={}", task.getId(), corrKey);
                            } catch (Exception redisEx) {
                                // Redis 超时回填失败只是降噪退化为无关联，不影响告警/任务主链路
                                log.warn("聚合键回填 Redis 异常（降噪退化为无关联，安全） corrKey={}: {}",
                                        corrKey, redisEx.getMessage());
                            }
                        },
                        e -> log.warn("聚合键回填失败（降噪退化为无关联，安全）: {}", e.getMessage()));
    }

    /**
     * 构建降噪聚合键：namespace + pod(去随机后缀) 。
     * kube-state-metrics 的 pod 名带随机后缀（leaky-app-b7c4d9cf4-zqd24），
     * 去掉后缀才能把同一 Deployment 的前后多个 Pod 聚到一起。
     * 没有 namespace/pod 标签的告警返回空串（不参与降噪）。
     */
    private String buildCorrelationKey(Map<String, String> labels) {
        Map<String, String> l = labels != null ? labels : Map.of();
        String namespace = l.getOrDefault("namespace", "");
        String pod = l.getOrDefault("pod", "");
        String podPrefix = pod;
        // ReplicaSet/Job Pod 名形如 <deploy>-<rs-hash>-<pod-hash>，去掉两段哈希
        if (podPrefix.contains("-")) {
            String[] parts = podPrefix.split("-");
            if (parts.length >= 3) {
                podPrefix = String.join("-", java.util.Arrays.copyOfRange(parts, 0, parts.length - 2));
            }
        }
        if (namespace.isBlank() && podPrefix.isBlank()) {
            return "";
        }
        return namespace + "|" + (podPrefix.isBlank() ? l.getOrDefault("service", "") : podPrefix);
    }

    /**
     * 聚合落库：把被聚合的新告警归并进同服务已有活跃记录（PENDING/FIRING/CORRELATED），
     * 命中则累加 occurrenceCount；找不到才新建一行 CORRELATED。
     *
     * 只在确实拿到主任务 ID（resolvedTaskId 非空）时调用——聚合必须有明确的载体任务，
     * 否则硬落 CORRELATED 会把一条本应重新触发分析的新告警静默吞掉。
     */
    private void persistCorrelatedAlert(Alert alert, String taskId, String fingerprint) {
        try {
            java.util.List<AlertEntity> activeSameService = alertRepository
                    .findFirstByServiceNameAndStatusInOrderByCreateTimeDesc(
                            alert.getServiceName(),
                            java.util.List.of("PENDING", "FIRING", "CORRELATED"));
            if (!activeSameService.isEmpty()) {
                AlertEntity existing = activeSameService.get(0);
                existing.setTitle(alert.getTitle());
                existing.setDescription(alert.getDescription());
                existing.setTimestamp(alert.getTimestamp());
                existing.setProcessed(true);
                existing.setStatus("CORRELATED");
                // CORRELATED 告警的 taskId 指向主任务：前端「查看分析」能直接打开主报告
                // 仅在确实拿到主任务 ID 时回填；为 null 不覆盖已有值，保护旧关联不丢失。
                if (taskId != null && (existing.getTaskId() == null || existing.getTaskId().isBlank())) {
                    existing.setTaskId(taskId);
                }
                // 历史 NULL 兼容：用默认值 1，再 +1，避免空指针
                int next = (existing.getOccurrenceCount() == null ? 1 : existing.getOccurrenceCount()) + 1;
                existing.setOccurrenceCount(next);
                alertRepository.save(existing);
                log.info("聚合去重：更新主告警 {} 为第 {} 次，taskId={} 不再新增行 service={}",
                        existing.getId(), next, taskId == null ? "?" : taskId, alert.getServiceName());
            } else {
                AlertEntity entity = new AlertEntity();
                entity.setId(alert.getId());
                entity.setSource(alert.getSource());
                entity.setSeverity(alert.getSeverity());
                entity.setTitle(alert.getTitle());
                entity.setDescription(alert.getDescription());
                entity.setServiceName(alert.getServiceName());
                entity.setHost(alert.getHost());
                entity.setDetail(alert.getDetail());
                entity.setTimestamp(alert.getTimestamp());
                entity.setProcessed(true);
                entity.setStatus("CORRELATED");
                if (taskId != null) {
                    entity.setTaskId(taskId);
                }
                entity.setCreateTime(LocalDateTime.now());
                entity.setFingerprint(fingerprint);
                entity.setOccurrenceCount(1);
                alertRepository.save(entity);
            }
        } catch (Exception e) {
            log.warn("聚合落库去重失败（回退新建） service={}: {}", alert.getServiceName(), e.getMessage());
            AlertEntity entity = new AlertEntity();
            entity.setId(alert.getId());
            entity.setSource(alert.getSource());
            entity.setSeverity(alert.getSeverity());
            entity.setTitle(alert.getTitle());
            entity.setDescription(alert.getDescription());
            entity.setServiceName(alert.getServiceName());
            entity.setHost(alert.getHost());
            entity.setDetail(alert.getDetail());
            entity.setTimestamp(alert.getTimestamp());
            entity.setProcessed(true);
            entity.setStatus("CORRELATED");
            if (taskId != null) {
                entity.setTaskId(taskId);
            }
            entity.setCreateTime(LocalDateTime.now());
            entity.setFingerprint(fingerprint);
            entity.setOccurrenceCount(1);
            alertRepository.save(entity);
        }
    }

    private Alert normalize(AlertmanagerWebhookPayload.AmAlert amAlert, String status) {
        Map<String, String> labels = amAlert.getLabels() != null ? amAlert.getLabels() : Map.of();
        Map<String, String> annotations = amAlert.getAnnotations() != null ? amAlert.getAnnotations() : Map.of();

        String alertname = labels.getOrDefault("alertname", "unknown");
        String severityRaw = labels.getOrDefault("severity", "WARNING");
        String severity = severityRaw.toUpperCase();
        if (!severity.matches("CRITICAL|WARNING|INFO")) {
            severity = "WARNING";
        }
        String serviceName = labels.getOrDefault("service",
                labels.getOrDefault("service_name",
                        labels.getOrDefault("namespace",
                                labels.getOrDefault("job", labels.getOrDefault("alertname", "unknown")))));
        String instance = labels.getOrDefault("instance", labels.getOrDefault("job", "unknown"));
        String host = labels.getOrDefault("instance", labels.getOrDefault("host", "unknown"));

        String title = annotations.getOrDefault("summary", alertname);
        String description = annotations.getOrDefault("description", "");
        String detail = "generatorURL=" + amAlert.getGeneratorURL();

        Alert alert = new Alert();
        // 用 UUID：同一 fingerprint 的告警抖动已被 Redis 去重挡住，
        // 能走到这里的 firing 都是"新告警"，固定 ID 会让新告警 upsert 覆盖旧记录。
        alert.setId(UUID.randomUUID().toString());
        alert.setSource("alertmanager");
        alert.setSeverity(severity);
        alert.setTitle(title);
        alert.setDescription(description);
        alert.setServiceName(serviceName);
        alert.setHost(host);
        alert.setDetail(detail);
        alert.setTimestamp(LocalDateTime.now());
        alert.setStatus(status.toUpperCase());
        return alert;
    }

    private String buildFingerprint(Map<String, String> labels) {
        String[] keys = fingerprintKeys.split(",");
        StringBuilder sb = new StringBuilder();
        for (String k : keys) {
            String v = labels.getOrDefault(k.trim(), "");
            sb.append(v).append("|");
        }
        return sb.toString();
    }
}
