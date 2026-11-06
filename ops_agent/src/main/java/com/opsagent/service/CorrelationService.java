package com.opsagent.service;

import com.opsagent.entity.AlertEntity;
import com.opsagent.model.Alert;
import com.opsagent.repository.AlertRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * 告警降噪聚合服务（从 AlertWebhookService 抽出，纯结构重构，逻辑不变）。
 *
 * 职责：
 * - 构建聚合键（namespace + 去后缀 Pod 前缀）；
 * - 按任务状态判定可否合并（taskCanMerge）与旧记录复用（isActiveForReuse/isStaleTerminal）；
 * - 聚合键 Redis 读写与 PENDING 占位回填（反向索引走 Redis 键，不再用内存 Map）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CorrelationService {

    private final AlertRepository alertRepository;
    private final StringRedisTemplate redisTemplate;
    private final TaskSchedulerService taskScheduler;
    private final RecoveryVerificationService recoveryVerificationService;

    /** 告警降噪聚合窗口（分钟）。0 = 关闭降噪。 */
    @Value("${opsagent.webhook.correlation-window-minutes:3}")
    private long correlationWindowMinutes;

    // ===== 聚合键 Redis 读写 =====

    public String readCorrKey(String corrKey) {
        return redisTemplate.opsForValue().get("webhook:corr:" + corrKey);
    }

    public void deleteCorrKey(String corrKey) {
        redisTemplate.delete("webhook:corr:" + corrKey);
    }

    /**
     * 占住聚合键：先写 PENDING 并携带 alertId，同时写反向键 webhook:corr:pending:{alertId}。
     */
    public void occupyCorrKey(String corrKey, String alertId) {
        redisTemplate.opsForValue().set("webhook:corr:" + corrKey, "PENDING",
                java.time.Duration.ofMinutes(correlationWindowMinutes));
        redisTemplate.opsForValue().set("webhook:corr:pending:" + alertId, corrKey,
                java.time.Duration.ofMinutes(correlationWindowMinutes));
    }

    /** 由 alertId 反查占住的聚合键（替代内存 pendingCorrKeys）。 */
    public String findPendingCorrKey(String alertId) {
        return redisTemplate.opsForValue().get("webhook:corr:pending:" + alertId);
    }

    /**
     * 回填聚合键：轮询等任务建出来，把值从 "PENDING" 更新为真实 taskId 并清理反向键。
     * 最多等 10 秒（processAlert 的建任务是异步的），拿不到就放弃——
     * 后续告警会因键值仍是 "PENDING" 不匹配 taskId 而正常各自建任务，退化为无降噪，安全。
     */
    public void fillCorrKeyWithTaskId(String corrKey, String alertId) {
        Mono.defer(() -> taskScheduler.findLatestTaskByAlertId(alertId))
                .subscribeOn(Schedulers.boundedElastic())
                .repeatWhenEmpty(longFlux -> longFlux.delayElements(java.time.Duration.ofMillis(500)))
                .take(java.time.Duration.ofSeconds(10))
                .subscribe(
                        task -> {
                            try {
                                redisTemplate.opsForValue().set("webhook:corr:" + corrKey, task.getId(),
                                        java.time.Duration.ofMinutes(correlationWindowMinutes));
                                redisTemplate.delete("webhook:corr:pending:" + alertId);
                                log.info("聚合键已回填 taskId={} corrKey={}", task.getId(), corrKey);
                            } catch (Exception redisEx) {
                                // Redis 超时回填失败只是降噪退化为无关联，不影响告警/任务主链路
                                log.warn("聚合键回填 Redis 异常（降噪退化为无关联，安全） corrKey={}: {}",
                                        corrKey, redisEx.getMessage());
                            }
                        },
                        e -> log.warn("聚合键回填失败（降噪退化为无关联，安全）: {}", e.getMessage()));
    }

    // ===== 判定 =====

    public Boolean taskCanMerge(String taskId) {
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
                if (!recoveryVerificationService.isTaskInObservation(taskId)) {
                    log.info("任务 {} 已 SUCCESS 且脱离观察期，拒绝聚合，放行新告警重新建任务",
                            taskId);
                    return Boolean.FALSE;
                }
            } else {
                // REJECTED/FAILED/TIMEOUT/REGRESSED 一律返回 false，强制放行并重新建任务
                return Boolean.FALSE;
            }
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

    public boolean isStaleTerminal(String status) {
        if (status == null || status.isBlank()) {
            return false;
        }
        String upper = status.toUpperCase();
        return "REJECTED".equals(upper) || "FAILED".equals(upper) || "REGRESSED".equals(upper);
    }

    public boolean isActiveForReuse(AlertEntity record) {
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
                String taskId = record.getTaskId();
                return taskId != null && !taskId.isBlank()
                        && recoveryVerificationService.isTaskInObservation(taskId);
            default:
                return false;
        }
    }

    /**
     * 构建降噪聚合键：namespace + pod(去随机后缀)。
     */
    public String buildCorrelationKey(Map<String, String> labels) {
        Map<String, String> l = labels != null ? labels : Map.of();
        String namespace = l.getOrDefault("namespace", "");
        String pod = l.getOrDefault("pod", "");
        String podPrefix = pod;
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

    @Transactional
    public void persistCorrelatedAlert(Alert alert, String taskId, String fingerprint) {
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
                if (taskId != null && (existing.getTaskId() == null || existing.getTaskId().isBlank())) {
                    existing.setTaskId(taskId);
                }
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
}
