package com.opsagent.service;

import com.opsagent.entity.AlertEntity;
import com.opsagent.model.Alert;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 拒绝冷却到期扫描器（防骚扰规则的「后半句」）。
 *
 * 前半句在 Webhook/审批侧：拒绝后 15 分钟内，新告警只累加次数、不弹窗。
 * 后半句在这里：冷却结束时，如果这 15 分钟内**还在持续报错**（occurrenceCount
 * 相对基线有增长），就必须强制拉起新一轮诊断和审批，不能因为曾经拒绝过就永远
 * 沉默 —— 那样故障会烂在库里，没人知道。
 *
 * 设计取舍：
 * - 冷却到期本身不是一个「事件」，没有告警进来就不会有人触发它，所以必须靠定时扫描。
 * - 扫描条件（PENDING + 冷却到期 + 次数增长）全部下推到 SQL，避免把全表拉进内存。
 * - 重新拉起走 AlertIngestionService.processAlert：它会先做一次 checkSuppress，
 *   到期后返回 NONE，随后正常建任务 → 走审批。重试预算耗尽（retryExhaustedAt）
 *   的记录不会被这条 SQL 命中，天然免打扰。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CooldownExpiryScheduler {

    private final AlertLifecycleService alertLifecycleService;
    private final AlertIngestionService alertIngestionService;

    /** 扫描间隔（毫秒）。冷却粒度是 15 分钟，30 秒一轮足够，且不会给库造成压力。 */
    @Value("${opsagent.lifecycle.cooldown-scan-interval-ms:30000}")
    private long scanIntervalMs;

    @Scheduled(fixedDelayString = "${opsagent.lifecycle.cooldown-scan-interval-ms:30000}")
    public void scanExpiredCooldowns() {
        try {
            List<AlertEntity> candidates = alertLifecycleService.findCoolingDownAndStillFiring();
            if (candidates.isEmpty()) {
                return;
            }
            log.info("[拒绝冷却到期] 发现 {} 条冷却结束且期间持续报错的告警，重新拉起诊断", candidates.size());
            for (AlertEntity alert : candidates) {
                retrigger(alert);
            }
        } catch (Exception e) {
            log.error("拒绝冷却到期扫描异常: {}", e.getMessage(), e);
        }
    }

    /**
     * 冷却到期且仍在报错：清空冷却标记并把告警重新交回正常处理链路。
     *
     * 这里不直接建任务，而是调 processAlert —— 复用它的影响面注入、任务 ID 回填、
     * Agent 通知等全部逻辑，保证「冷却后重新拉起」和「第一次告警」走同一条链路，
     * 不会出现两套行为。
     */
    private void retrigger(AlertEntity entity) {
        try {
            // 先清冷却，后面 processAlert 的 checkSuppress 才会返回 NONE
            entity.setRejectCooldownUntil(null);
            entity.setCooldownBaseOccurrence(null);
            entity.setStatus("PENDING");
            entity.setProcessed(false);
            alertLifecycleService.save(entity);

            Alert alert = new Alert();
            alert.setId(entity.getId());
            alert.setSource(entity.getSource());
            alert.setSeverity(entity.getSeverity());
            alert.setTitle(entity.getTitle());
            alert.setDescription(entity.getDescription());
            alert.setServiceName(entity.getServiceName());
            alert.setHost(entity.getHost());
            alert.setDetail(entity.getDetail());
            alert.setTimestamp(LocalDateTime.now());
            alert.setFingerprint(entity.getFingerprint());
            alert.setStatus("PENDING");

            log.warn("[拒绝冷却到期] 告警 {}（服务 {}）冷却结束仍在报错，强制重新拉起诊断与审批",
                    entity.getId(), entity.getServiceName());
            alertIngestionService.processAlert(alert, true).subscribe(
                    v -> { },
                    e -> log.warn("[拒绝冷却到期] 重新拉起失败 alertId={}: {}", entity.getId(), e.getMessage()));
        } catch (Exception e) {
            log.warn("[拒绝冷却到期] 处理告警异常 alertId={}: {}", entity.getId(), e.getMessage());
        }
    }
}
