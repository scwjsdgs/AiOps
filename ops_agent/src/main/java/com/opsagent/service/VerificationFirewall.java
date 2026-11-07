package com.opsagent.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

/**
 * 恢复验证防火墙。
 *
 * 职责（从 AlertWebhookService 抽出）：
 * - isTaskInObservation(taskId)：任务是否正处于恢复观察期；
 * - isTaskRegressed(taskId)：任务是否已判定复现（REGRESSED）；
 * - verifyServiceHealthy(serviceName)：真实健康度探测（K8s 副本/容器状态）。
 *
 * 只做只读探测与判定，不落库、不触发建任务。调用方按判定结果自行决定告警终态。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class VerificationFirewall {

    private final RecoveryVerificationService recoveryVerificationService;

    public boolean isTaskInObservation(String taskId) {
        return recoveryVerificationService.isTaskInObservation(taskId);
    }

    public boolean isTaskRegressed(String taskId) {
        return recoveryVerificationService.isTaskRegressed(taskId);
    }

    /**
     * 探测服务是否健康。
     *
     * 返回 Boolean：
     *   true  = 健康（可落 RESOLVED）；
     *   false = 不健康（打回 PENDING，拦截假恢复）；
     *   null  = 探测本身异常（调用方按保守策略处理，不应让校验失败阻塞告警终态）。
     */
    public Boolean verifyServiceHealthy(String serviceName) {
        try {
            return recoveryVerificationService.verifyServiceHealthy(serviceName)
                    .block();
        } catch (Exception e) {
            log.warn("[验证防火墙] 服务健康探测异常 service={}: {}", serviceName, e.getMessage());
            return null;
        }
    }
}
