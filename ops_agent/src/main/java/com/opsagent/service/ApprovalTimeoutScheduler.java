package com.opsagent.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 审批超时自动拒绝调度器。
 *
 * 每隔固定时间扫描 PENDING 审批，超过 opsagent.approval.wait-timeout（默认 55 秒）
 * 自动转为 TIMEOUT，并广播 APPROVAL_TIMEOUT、通知 Python 以拒绝恢复 LangGraph，
 * 避免审批单和 Redis key 永久堆积成僵尸。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ApprovalTimeoutScheduler {

    private final ApprovalService approvalService;

    @Scheduled(fixedDelayString = "${opsagent.approval.scan-interval-ms:30000}")
    public void scanOverdueApprovals() {
        try {
            int count = approvalService.expirePendingOverdue();
            if (count > 0) {
                log.info("审批超时扫描完成，自动拒绝 {} 条", count);
            }
        } catch (Exception e) {
            log.error("审批超时扫描异常: {}", e.getMessage(), e);
        }
    }
}
