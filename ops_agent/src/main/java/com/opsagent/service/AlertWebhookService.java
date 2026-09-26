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

    @Value("${opsagent.webhook.idempotency.ttlMinutes:5}")
    private long idempotencyTtlMinutes;

    @Value("${opsagent.webhook.fingerprint-keys:alertname,instance,severity}")
    private String fingerprintKeys;

    /** 告警降噪聚合窗口（分钟）。0 = 关闭降噪。窗口内同源故障只触发一次 agent 分析。 */
    @Value("${opsagent.webhook.correlation-window-minutes:3}")
    private long correlationWindowMinutes;

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
        if (alertStatus == null) {
            log.warn("告警缺少 status: labels={}", amAlert.getLabels());
            return;
        }

        String fingerprint = buildFingerprint(amAlert.getLabels());
        if (fingerprint.isBlank()) {
            log.warn("无法生成 fingerprint: labels={}", amAlert.getLabels());
            return;
        }

        // 幂等去重只针对 firing：resolved 是终态事件，也用同一个 key 的话，
        // 5 分钟内先 firing 后 resolved，resolved 会被去重挡掉，告警状态永远停在 ANALYZING。
        String alertStatusLower = alertStatus.toLowerCase();
        if ("firing".equals(alertStatusLower)) {
            String dedupKey = "webhook:dup:" + fingerprint;
            Boolean set = redisTemplate.opsForValue().setIfAbsent(dedupKey, "1", java.time.Duration.ofMinutes(idempotencyTtlMinutes));
            if (Boolean.FALSE.equals(set)) {
                webhookDuplicate.increment();
                log.debug("幂等命中，去重 firing 告警 fingerprint={}", fingerprint);
                return;
            }
        }

        // resolved 先处理：按 fingerprint 定位并更新状态，不触发 AI
        if ("resolved".equalsIgnoreCase(alertStatus)) {
            Mono.fromRunnable(() -> {
                var list = alertRepository.findByFingerprintOrderByCreateTimeDesc(fingerprint);
                if (!list.isEmpty()) {
                    AlertEntity e = list.get(0);
                    e.setStatus("RESOLVED");
                    e.setProcessed(true);
                    alertRepository.save(e);
                    webhookResolved.increment();
                    log.info("告警 resolved，已更新状态 fingerprint={} alertId={}", fingerprint, e.getId());
                } else {
                    log.warn("resolved 但找不到对应告警，fingerprint={}", fingerprint);
                }
            }).subscribeOn(Schedulers.boundedElastic()).subscribe();
            return;
        }

        Alert alert = normalize(amAlert, alertStatus);
        if (alert == null) {
            return;
        }
        alert.setId(alert.getId() != null ? alert.getId() : UUID.randomUUID().toString());

        // ----- 告警降噪（聚合窗口） -----
        // 同一 Pod/服务的多条告警（alertname 不同、fingerprint 不同）在窗口内到达时，
        // 只有第一条触发 agent 分析；后续告警照常落库（不吞告警），但被聚合进
        // 已有的进行中分析任务——同一次故障不被分析 N 遍。
        if (correlationWindowMinutes > 0) {
            String corrKey = buildCorrelationKey(amAlert.getLabels());
            if (!corrKey.isBlank()) {
                String existingTaskId = redisTemplate.opsForValue().get("webhook:corr:" + corrKey);
                if (existingTaskId != null && !existingTaskId.isBlank()) {
                    webhookCorrelated.increment();
                    log.info("降噪命中：告警聚合进已有分析 taskId={} corrKey={} title={}",
                            existingTaskId, corrKey, alert.getTitle());
                    final String taskId = existingTaskId;
                    final Alert finalAlert = alert;
                    Mono.fromRunnable(() ->
                            taskScheduler.appendCorrelatedAlert(taskId, finalAlert.getTitle(), finalAlert.getSeverity())
                                    .subscribe(ok -> { }, e -> log.warn("追加关联告警失败: {}", e)))
                            .subscribeOn(Schedulers.boundedElastic()).subscribe();
                    // 告警记录仍然落库（可见性），但不建新任务、不触发 agent
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
                    entity.setCreateTime(LocalDateTime.now());
                    entity.setFingerprint(fingerprint);
                    alertRepository.save(entity);
                    return;
                }
                // 首条告警：占住聚合键，值 = 正在进行的分析任务（建任务后回填）
                redisTemplate.opsForValue().set("webhook:corr:" + corrKey, "PENDING",
                        java.time.Duration.ofMinutes(correlationWindowMinutes));
                pendingCorrKeys.put(corrKey, alert.getId());
            }
        }

        // firing：先写入 AlertEntity 并带上 fingerprint，便于后续 resolved 定位
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
        entity.setProcessed(false);
        entity.setStatus("PENDING");
        entity.setCreateTime(LocalDateTime.now());
        entity.setFingerprint(fingerprint);
        alertRepository.save(entity);

        // 再交给 AlertIngestionService 统一触发 AI 分析（建任务、通知 Agent）
        final String corrKeyForFill = findPendingCorrKey(alert.getId());
        alertIngestionService.processAlert(alert).subscribe(
                v -> log.info("Webhook firing 已触发分析, alertId={}", alert.getId()),
                e -> log.error("Webhook 处理告警失败 alertId={}", alert.getId(), e),
                null
        );
        // processAlert 内部异步建任务，这里拿不到 taskId 返回值；
        // 用轮询回填：把聚合键的 "PENDING" 更新为真实 taskId，供窗口内后续告警关联。
        if (corrKeyForFill != null) {
            fillCorrKeyWithTaskId(corrKeyForFill, alert.getId());
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
                            redisTemplate.opsForValue().set("webhook:corr:" + corrKey, task.getId(),
                                    java.time.Duration.ofMinutes(correlationWindowMinutes));
                            pendingCorrKeys.remove(corrKey);
                            log.info("聚合键已回填 taskId={} corrKey={}", task.getId(), corrKey);
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

    private Alert normalize(AlertmanagerWebhookPayload.AmAlert amAlert, String status) {
        Map<String, String> labels = amAlert.getLabels() != null ? amAlert.getLabels() : Map.of();
        Map<String, String> annotations = amAlert.getAnnotations() != null ? amAlert.getAnnotations() : Map.of();

        String alertname = labels.getOrDefault("alertname", "unknown");
        String severityRaw = labels.getOrDefault("severity", "WARNING");
        String severity = severityRaw.toUpperCase();
        if (!severity.matches("CRITICAL|WARNING|INFO")) {
            severity = "WARNING";
        }
        String serviceName = labels.getOrDefault("service", labels.getOrDefault("service_name", "unknown"));
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
