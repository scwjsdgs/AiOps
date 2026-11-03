package com.opsagent.service;

import com.opsagent.model.Task;
import com.opsagent.repository.ApprovalRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 任务排队超时兜底调度器。
 *
 * 问题：前端大量任务卡在「排队中（PENDING）」，创建了很久耗时却近乎为 0 ——
 * 这些任务要么 Python 侧 `submit` 返回 duplicate（同 taskId 在飞）、要么 Python
 * 进程异常而 Java 侧无人推进状态。任务会永远停在 PENDING 欺骗用户。
 *
 * 做法：定时扫描 Redis 里的 task:*，对状态为 PENDING、且 createdAt 距今超过
 * queue-timeout-seconds（默认 120 秒 = 2 分钟）的任务，强制置为 FAILED 并写入
 * 明确的错误原因（System busy / Task queue timeout），不再让它卡着假装在排队。
 *
 * 例外：处于人工审批挂起（存在 PENDING 审批单）的任务不超时 —— 那种 PENDING
 * 是合法的等待人工决定，强杀会破坏审批语义。
 *
 * 这是纯 Java 侧兜底，不依赖 Python 状态回传 —— 即使 Python 侧完全无响应，
 * 僵尸排队任务也会被清理。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TaskTimeoutScheduler {

    private final TaskSchedulerService taskScheduler;
    private final ApprovalRepository approvalRepository;

    /** 排队超时秒数：PENDING 超过该时间仍未开始执行（RUNNING）即判为超时。 */
    @Value("${opsagent.task.queue-timeout-seconds:120}")
    private long queueTimeoutSeconds;

    /** 扫描间隔（毫秒）。默认 60 秒一轮，与排队超时 2 分钟匹配，最多滞后一个周期。 */
    @Value("${opsagent.task.queue-scan-interval-ms:60000}")
    private long queueScanIntervalMs;

    @Scheduled(fixedDelayString = "${opsagent.task.queue-scan-interval-ms:60000}")
    public void scanOverdueQueuedTasks() {
        if (queueTimeoutSeconds <= 0) {
            return;
        }
        try {
            List<Task> all = taskScheduler.listAllTasks().block();
            if (all == null || all.isEmpty()) {
                return;
            }
            LocalDateTime deadline = LocalDateTime.now().minusSeconds(queueTimeoutSeconds);
            int forced = 0;
            for (Task task : all) {
                // 只处理仍处于 PENDING（排队）且已超过排队时限的任务
                if (task.getStatus() == null || !"PENDING".equalsIgnoreCase(task.getStatus())) {
                    continue;
                }
                if (task.getCreatedAt() == null) {
                    continue;
                }
                if (!task.getCreatedAt().isBefore(deadline)) {
                    continue;
                }
                // 审批挂起的任务：PENDING 是合法等待人工决定，绝不能强杀
                try {
                    boolean hasPendingApproval = task.getId() != null
                            && approvalRepository.existsByStatusAndTaskId("PENDING", task.getId());
                    if (hasPendingApproval) {
                        log.info("任务 {} 处于审批挂起（PENDING 审批单存在），跳过排队超时兜底", task.getId());
                        continue;
                    }
                } catch (Exception e) {
                    log.warn("检查任务审批挂起状态失败（按无审批单处理） taskId={}: {}", task.getId(), e.getMessage());
                }
                String message = "任务排队超时（Task queue timeout / System busy），"
                        + "创建于 " + task.getCreatedAt() + " 后超过 "
                        + queueTimeoutSeconds + " 秒仍未开始执行，已强制标记失败。";
                // 幂等保护：若任务在扫描期间已被推进到 RUNNING 或已完成，则不再动它
                String latestStatus = taskScheduler.getTask(task.getId())
                        .map(t -> t.getStatus()).block();
                if (latestStatus == null || "PENDING".equalsIgnoreCase(latestStatus)) {
                    taskScheduler.completeTask(task.getId(), message, "FAILED")
                            .subscribe(ok -> {
                                if (Boolean.TRUE.equals(ok)) {
                                    log.warn("任务排队超时已强制 FAILED taskId={} createdAt={}",
                                            task.getId(), task.getCreatedAt());
                                }
                            }, e -> log.warn("任务超时置 FAILED 失败 taskId={}: {}", task.getId(), e.getMessage()));
                    forced++;
                }
            }
            if (forced > 0) {
                log.info("排队超时扫描完成，强制 FAILED {} 条", forced);
            }
        } catch (Exception e) {
            log.error("排队超时扫描异常: {}", e.getMessage(), e);
        }
    }
}
