package com.opsagent.service;

import com.opsagent.entity.AlertEntity;
import com.opsagent.model.AgentMessage;
import com.opsagent.model.Task;
import com.opsagent.repository.AlertRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Optional;

/**
 * 告警-审批-验证状态机的唯一裁决点（生产级闭环）。
 *
 * 把「拒绝后会不会骚扰」「验证失败要不要再花钱重试」这类**跨告警、跨任务**的
 * 决策从 Webhook / 审批 / 观察期三个各自为战的类里收拢到一个地方，避免
 * 各管一摊导致的逻辑撕裂（历史上出现过「打破了去重」紧跟「又被降噪聚合吞掉」
 * 这种互相矛盾的日志）。
 *
 * 三条业务规则：
 * 1. **拒绝冷却（防骚扰）**：用户拒绝或审批超时后，故障往往仍在持续。
 *    进入 15 分钟静默期，期间新告警只累加 occurrenceCount、维持 PENDING，
 *    不建任务、不弹审批；冷却到期仍在报错才强制重新拉起。
 * 2. **验证重试预算（防烧钱）**：每轮修复后进入 VERIFYING，验证失败最多自动
 *    重试 {@code max-verification-rounds}（默认 3）轮；达到上限立即把告警钉死为
 *    FAILED 并标记 retryExhaustedAt，此后同一故障只累加次数，绝不再自动弹窗。
 * 3. **验证通过即闭环**：5 分钟观察期内持续健康才落 SUCCESS/RESOLVED。
 *
 * 线程模型：所有方法都是阻塞 JPA 操作，调用方必须已经在 boundedElastic 上
 * （Webhook/审批/定时器均已满足），不会占用 Netty event loop。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AlertLifecycleService {

    /** 告警被抑制的原因。NONE = 正常放行。 */
    public enum SuppressAction {
        /** 冷却期内：只累加次数，不建任务、不弹窗 */
        COOLDOWN,
        /** 重试预算耗尽：只累加次数，永远不再自动拉起 */
        EXHAUSTED,
        /** 无需抑制，调用方继续走正常建任务流程 */
        NONE
    }

    private final AlertRepository alertRepository;
    private final TaskSchedulerService taskScheduler;
    private final WebSocketPushService webSocketPushService;

    /** 拒绝后的静默冷却时长。 */
    @Value("${opsagent.lifecycle.reject-cooldown-minutes:15}")
    private long rejectCooldownMinutes;

    /** 验证失败后允许的最大自动重试轮次。 */
    @Value("${opsagent.lifecycle.max-verification-rounds:3}")
    private int maxVerificationRounds;

    public int maxRounds() {
        return Math.max(1, maxVerificationRounds);
    }

    public long cooldownMinutes() {
        return Math.max(1, rejectCooldownMinutes);
    }

    // ------------------------------------------------------------------
    // 规则一：拒绝冷却
    // ------------------------------------------------------------------

    /**
     * 用户拒绝审批（或审批超时被系统拒绝）后，让关联告警进入静默冷却期。
     *
     * 「拒绝」不代表故障消失：告警必须保持未解决（PENDING），只是暂时不打扰用户。
     * 冷却期内到达的同源告警由 {@link #checkSuppress(AlertEntity)} 拦住。
     *
     * @param taskId 被拒绝的任务 ID（可为空，空时无法定位告警，只打日志）
     * @param note   拒绝原因，记入告警详情便于审计
     */
    public void enterRejectCooldownByTask(String taskId, String note) {
        if (taskId == null || taskId.isBlank()) {
            return;
        }
        try {
            Task task = taskScheduler.getTask(taskId).block(Duration.ofSeconds(5));
            if (task == null) {
                log.warn("[拒绝冷却] 任务不存在，无法定位告警 taskId={}", taskId);
                return;
            }
            enterRejectCooldownByAlertId(task.getAlertId(), note, taskId);
        } catch (Exception e) {
            // 冷却登记失败不能影响审批本身：审批单已经终态落库，只是防骚扰能力降级
            log.warn("[拒绝冷却] 登记失败 taskId={}: {}", taskId, e.getMessage());
        }
    }

    /**
     * 按告警 ID 进入冷却期（定时重试/超时兜底也会复用）。
     *
     * 幂等：同一告警重复进入冷却时以**最新一次**拒绝为准，重新计时并刷新基线次数，
     * 这样用户每拒绝一次都会重新获得完整的 15 分钟安静时间。
     */
    public void enterRejectCooldownByAlertId(String alertId, String note, String taskId) {
        if (alertId == null || alertId.isBlank()) {
            return;
        }
        Optional<AlertEntity> found = alertRepository.findById(alertId);
        if (found.isEmpty()) {
            log.warn("[拒绝冷却] 告警不存在 alertId={} taskId={}", alertId, taskId);
            return;
        }
        AlertEntity alert = found.get();
        // 已经重试耗尽：不再进入冷却（冷却的目的是“稍后再试”，耗尽后不再自动试）
        if (alert.getRetryExhaustedAt() != null) {
            log.info("[拒绝冷却] 告警 {} 已重试耗尽，忽略冷却登记", alertId);
            return;
        }
        LocalDateTime now = LocalDateTime.now();
        int occ = alert.getOccurrenceCount() == null ? 1 : alert.getOccurrenceCount();
        alert.setStatus("PENDING");
        alert.setProcessed(true);
        alert.setLastRejectedAt(now);
        alert.setRejectCooldownUntil(now.plusMinutes(cooldownMinutes()));
        alert.setCooldownBaseOccurrence(occ);
        // 冷却期内不可能有“正在验证”的任务，清掉倒计时字段，前端不会显示假倒计时
        alert.setVerificationDeadline(null);
        alert.setVerificationStartedAt(null);
        alertRepository.save(alert);

        pushEvent("ALERT_COOLDOWN_STARTED", alertId, taskId,
                "用户拒绝修复（" + (note == null ? "未填写原因" : note) + "），系统进入 "
                        + cooldownMinutes() + " 分钟静默冷却期：期间持续告警只累计次数，冷却结束后仍在报错才会重新拉起诊断。"
                        + "|cooldownMinutes=" + cooldownMinutes()
                        + "|cooldownUntil=" + alert.getRejectCooldownUntil());
        log.info("[拒绝冷却] 告警 {} 进入 {} 分钟冷却（基线次数 {}）", alertId, cooldownMinutes(), occ);
    }

    /**
     * 判断一条告警当前是否应该抑制自动处理。
     *
     * 被调用方（Webhook）在命中抑制时应**只累加 occurrenceCount** 并直接返回，
     * 不建任务、不聚合、不弹审批。
     *
     * 冷却已到期时这里会顺手清空冷却字段并返回 NONE：下一次告警就会正常
     * 重新拉起分析（这正是「15 分钟后仍在报错 → 再次强制拉起」的入口）。
     */
    public SuppressAction checkSuppress(AlertEntity alert) {
        if (alert == null) {
            return SuppressAction.NONE;
        }
        if (alert.getRetryExhaustedAt() != null) {
            return SuppressAction.EXHAUSTED;
        }
        LocalDateTime until = alert.getRejectCooldownUntil();
        if (until == null) {
            return SuppressAction.NONE;
        }
        LocalDateTime now = LocalDateTime.now();
        if (until.isAfter(now)) {
            return SuppressAction.COOLDOWN;
        }
        // 冷却到期：清掉标记，让本次告警正常走建任务/重新审批流程。
        // 注意不要把 status 从 PENDING 挪走 —— 未解决就是未解决。
        clearCooldown(alert);
        log.info("[拒绝冷却] 告警 {} 冷却到期，放行重新触发", alert.getId());
        return SuppressAction.NONE;
    }

    /** 清空冷却标记（冷却到期或用户批准后调用）。 */
    public void clearCooldown(AlertEntity alert) {
        if (alert == null) {
            return;
        }
        alert.setRejectCooldownUntil(null);
        alert.setCooldownBaseOccurrence(null);
        try {
            alertRepository.save(alert);
        } catch (Exception e) {
            log.warn("[拒绝冷却] 清理冷却标记失败 alertId={}: {}", alert.getId(), e.getMessage());
        }
    }

    /** 按任务定位告警后清空冷却（审批被批准时调用）。 */
    public void clearCooldownByTask(String taskId) {
        if (taskId == null || taskId.isBlank()) {
            return;
        }
        try {
            Task task = taskScheduler.getTask(taskId).block(Duration.ofSeconds(5));
            if (task == null || task.getAlertId() == null || task.getAlertId().isBlank()) {
                return;
            }
            findAlert(task.getAlertId()).ifPresent(this::clearCooldown);
        } catch (Exception e) {
            log.warn("[拒绝冷却] 按任务清空冷却失败 taskId={}: {}", taskId, e.getMessage());
        }
    }

    /** 供定时器使用：扫描冷却到期且期间仍在报错的告警。 */
    public java.util.List<AlertEntity> findCoolingDownAndStillFiring() {
        try {
            return alertRepository.findCoolingDownAndStillFiring(LocalDateTime.now());
        } catch (Exception e) {
            log.warn("[拒绝冷却] 扫描冷却到期告警失败: {}", e.getMessage());
            return java.util.Collections.emptyList();
        }
    }

    // ------------------------------------------------------------------
    // 规则二/三：验证轮次与重试预算
    // ------------------------------------------------------------------

    /** 验证失败后的裁决结果。 */
    public enum VerificationDecision {
        /** 还有预算：告警打回 PENDING（第 n+1 轮），调用方重新拉起诊断与审批 */
        RETRY,
        /** 预算耗尽：告警钉死 FAILED，禁止再次自动弹窗 */
        EXHAUSTED
    }

    /**
     * 一轮验证失败（REGRESSED）后裁决下一步。
     *
     * - 未达上限：告警回到 PENDING、轮次 +1，调用方负责重建任务（重新审批）；
     * - 达到上限：告警 FAILED 并写 retryExhaustedAt，调用方只发送最终失败报告。
     *
     * @param countBudget 是否消耗重试预算。只有「人工批准的修复」才计入 (n/3)，
     *                    纯只读诊断的观察期复现保持原有语义（不占预算，仅重新诊断）。
     *
     * 该方法会把 verificationDeadline 清空：本轮已经出结论，前端不应再显示倒计时。
     */
    public VerificationDecision onVerificationRegressed(AlertEntity alert, boolean countBudget) {
        if (alert == null) {
            return VerificationDecision.EXHAUSTED;
        }
        int max = alert.getMaxRetries() == null ? maxRounds() : Math.max(1, alert.getMaxRetries());
        int round = alert.getVerificationRound() == null || alert.getVerificationRound() < 1
                ? 1 : alert.getVerificationRound();
        alert.setVerificationDeadline(null);
        alert.setVerificationStartedAt(null);

        if (!countBudget) {
            // 只读诊断：不消耗修复重试预算，打回未解决并允许重新诊断。
            // 同样要清冷却：重试是系统主动发起的，不能被上一轮拒绝冷却挡住。
            alert.setStatus("PENDING");
            alert.setProcessed(true);
            alert.setRejectCooldownUntil(null);
            alert.setCooldownBaseOccurrence(null);
            alertRepository.save(alert);
            log.warn("[观察复现] 告警 {} 为只读诊断观察期复现，打回 PENDING（不占修复重试预算）", alert.getId());
            return VerificationDecision.RETRY;
        }

        if (round >= max) {
            alert.setStatus("FAILED");
            alert.setProcessed(true);
            alert.setRetryExhaustedAt(LocalDateTime.now());
            alert.setRejectCooldownUntil(null);
            alert.setCooldownBaseOccurrence(null);
            alertRepository.save(alert);
            log.warn("[重试耗尽] 告警 {} 连续 {} 轮验证失败，强制 FAILED 并禁止再次自动弹窗",
                    alert.getId(), round);
            pushEvent("ALERT_RETRY_EXHAUSTED", alert.getId(), alert.getTaskId(),
                    "修复连续 " + round + " 轮验证失败，已停止自动重试，请人工介入排查。"
                            + "|round=" + round + "|maxRetries=" + max);
            return VerificationDecision.EXHAUSTED;
        }

        // 验证失败后的重试是系统主动行为：必须清掉可能残留的拒绝冷却标记，
        // 否则第 2 轮审批会被防骚扰闸门挡下，重试预算形同虚设（永远走不到第 3 轮）。
        alert.setStatus("PENDING");
        alert.setProcessed(true);
        alert.setVerificationRound(round + 1);
        alert.setRejectCooldownUntil(null);
        alert.setCooldownBaseOccurrence(null);
        alertRepository.save(alert);
        log.warn("[验证重试] 告警 {} 第 {} 轮验证失败，允许第 {}/{} 轮重试",
                alert.getId(), round, round + 1, max);
        pushEvent("ALERT_VERIFICATION_RETRY", alert.getId(), alert.getTaskId(),
                "第 " + round + " 轮验证失败，已自动发起第 " + (round + 1) + "/" + max + " 轮诊断与审批。"
                        + "|round=" + (round + 1) + "|maxRetries=" + max);
        return VerificationDecision.RETRY;
    }

    /**
     * 验证通过：任务与告警一起闭环。
     *
     * 调用方负责把任务置 SUCCESS；这里把告警落 RESOLVED，并清掉冷却/耗尽标记，
     * 让这条故障记录彻底结束（后续同指纹再有告警会作为全新故障处理）。
     */
    public void onVerificationPassed(AlertEntity alert) {
        if (alert == null) {
            return;
        }
        alert.setStatus("RESOLVED");
        alert.setProcessed(true);
        alert.setVerificationDeadline(null);
        alert.setVerificationStartedAt(null);
        alert.setRejectCooldownUntil(null);
        alert.setCooldownBaseOccurrence(null);
        alert.setRetryExhaustedAt(null);
        alertRepository.save(alert);
        log.info("[闭环] 告警 {} 验证通过，已置 RESOLVED（第 {} 轮）",
                alert.getId(), alert.getVerificationRound());
    }

    /**
     * 任务进入验证期时初始化告警轮次与倒计时字段。
     *
     * 轮次规则：首次验证为第 1 轮；每次验证失败后由 onVerificationRegressed 预先
     * +1，所以新任务重试时直接沿用告警上的轮次，前端始终显示「第 n/max 轮」。
     */
    public int beginVerification(AlertEntity alert, LocalDateTime startedAt, LocalDateTime deadline) {
        int max = alert.getMaxRetries() == null ? maxRounds() : Math.max(1, alert.getMaxRetries());
        int round = alert.getVerificationRound() == null || alert.getVerificationRound() < 1
                ? 1 : alert.getVerificationRound();
        alert.setStatus("VERIFYING");
        alert.setProcessed(true);
        alert.setMaxRetries(max);
        alert.setVerificationRound(round);
        alert.setVerificationStartedAt(startedAt);
        alert.setVerificationDeadline(deadline);
        alert.setRejectCooldownUntil(null);
        alert.setCooldownBaseOccurrence(null);
        alertRepository.save(alert);
        return round;
    }

    /** 读到当前告警（任务回写用），查不到返回空。 */
    public Optional<AlertEntity> findAlert(String alertId) {
        if (alertId == null || alertId.isBlank()) {
            return Optional.empty();
        }
        try {
            return alertRepository.findById(alertId);
        } catch (Exception e) {
            log.warn("[状态机] 查询告警失败 alertId={}: {}", alertId, e.getMessage());
            return Optional.empty();
        }
    }

    /** 保存告警（调用方已在 boundedElastic 上，阻塞 JPA 可接受）。 */
    public void save(AlertEntity alert) {
        if (alert == null) {
            return;
        }
        try {
            alertRepository.save(alert);
        } catch (Exception e) {
            log.warn("[状态机] 保存告警失败 alertId={}: {}", alert.getId(), e.getMessage());
        }
    }

    /**
     * 重试预算是否还有剩余（回收端/守卫端共用）。
     * 未设置 maxRetries 时用全局默认；已耗尽返回 false。
     */
    public boolean hasRetryBudget(AlertEntity alert) {
        if (alert == null) {
            return false;
        }
        if (alert.getRetryExhaustedAt() != null) {
            return false;
        }
        int max = alert.getMaxRetries() == null ? maxRounds() : Math.max(1, alert.getMaxRetries());
        int round = alert.getVerificationRound() == null ? 0 : alert.getVerificationRound();
        return round < max;
    }

    /**
     * 组装「重试耗尽」的最终失败报告。
     *
     * 要求：发送最终失败报告 —— 报告必须能让人直接接手排查，而不是一句“失败了”。
     * 这里把轮次、每轮观察到的问题、告警上下文都写清楚，并明确说明系统已停止自动
     * 修复、不会再有审批弹窗。
     */
    public String buildExhaustedReport(AlertEntity alert, int round, int max, String lastNote) {
        StringBuilder sb = new StringBuilder();
        sb.append("## 自动修复已停止（重试耗尽）\n\n");
        sb.append("- 服务：").append(alert == null || alert.getServiceName() == null ? "unknown" : alert.getServiceName()).append("\n");
        sb.append("- 告警：").append(alert == null || alert.getTitle() == null ? "—" : alert.getTitle()).append("\n");
        sb.append("- 验证轮次：").append(round).append("/").append(max).append(" 均失败\n");
        if (lastNote != null && !lastNote.isBlank()) {
            sb.append("- 末轮验证结论：").append(lastNote).append("\n");
        }
        sb.append("\n**系统已停止自动重试，不会再自动弹窗或执行任何变更。**\n\n");
        sb.append("建议人工介入：\n");
        sb.append("1. 确认 Pod 是否仍处于 CrashLoopBackOff / 副本未就绪；\n");
        sb.append("2. 查看最近一次变更记录与容器日志，判断是修复手段无效还是存在更底层根因；\n");
        sb.append("3. 处理完成后可在告警页手动重新发起诊断。\n");
        return sb.toString();
    }

    /** 广播状态机事件（前端用来即时刷新；断线也不影响库里的真实状态）。 */
    private void pushEvent(String type, String alertId, String taskId, String content) {
        try {
            AgentMessage msg = new AgentMessage();
            msg.setType(type);
            msg.setTaskId(taskId == null ? "" : taskId);
            msg.setStepName("alert_lifecycle");
            msg.setContent(content + "|alertId=" + (alertId == null ? "" : alertId));
            msg.setTimestamp(LocalDateTime.now());
            webSocketPushService.broadcast(msg);
        } catch (Exception e) {
            log.warn("[状态机] 事件推送失败 type={} alertId={}: {}", type, alertId, e.getMessage());
        }
    }
}
