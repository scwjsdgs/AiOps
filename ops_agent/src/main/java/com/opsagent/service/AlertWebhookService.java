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
    private final MeterRegistry meterRegistry;

    @Value("${opsagent.webhook.idempotency.ttlMinutes:5}")
    private long idempotencyTtlMinutes;

    @Value("${opsagent.webhook.fingerprint-keys:alertname,instance,severity}")
    private String fingerprintKeys;

    private Counter webhookReceived;
    private Counter webhookDuplicate;
    private Counter webhookResolved;

    @PostConstruct
    public void initMetrics() {
        webhookReceived = Counter.builder("agent.webhook.received").register(meterRegistry);
        webhookDuplicate = Counter.builder("agent.webhook.duplicate").register(meterRegistry);
        webhookResolved = Counter.builder("agent.webhook.resolved").register(meterRegistry);
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
        alertIngestionService.processAlert(alert).subscribe(
                v -> log.info("Webhook firing 已触发分析, alertId={}", alert.getId()),
                e -> log.error("Webhook 处理告警失败 alertId={}", alert.getId(), e)
        );
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

    private void copyToEntity(Alert alert, AlertEntity entity) {
        entity.setId(alert.getId());
        entity.setSource(alert.getSource());
        entity.setSeverity(alert.getSeverity());
        entity.setTitle(alert.getTitle());
        entity.setDescription(alert.getDescription());
        entity.setServiceName(alert.getServiceName());
        entity.setHost(alert.getHost());
        entity.setDetail(alert.getDetail());
        entity.setTimestamp(alert.getTimestamp());
        entity.setProcessed(alert.isProcessed());
        entity.setStatus(alert.getStatus());
    }

    private String alertStatusNormal(String alertStatus) {
        return alertStatus.toUpperCase();
    }
}
