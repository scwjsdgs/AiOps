package com.opsagent.service;

import com.opsagent.entity.Approval;
import com.opsagent.exception.BusinessException;
import com.opsagent.repository.ApprovalRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.scheduler.Schedulers;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 处理人工审批相关逻辑。
 *
 * 业务流程：
 * 1. 调用方发起审批请求时，先在 MySQL 写一条 status=PENDING 并记录。
 * 2. 同时在 Redis 写一条待审批的记录，用来让前端网页实时推送，一旦有人操作，WebSocket
 *    或 HTTP POST 给后端，后端会更新 Redis status 为 APPROVED / REJECTED，并写回 MySQL。
 * 3. 对于 auto 模式，只需要记录一条 commit 并直接将 status 设置为 APPROVED，
 *    并将 mode=auto 记在 Redis 中。
 *
 * 线程安全：所有写入使用 Spring Data JPA 的默认事务；Redis 写操作是原子性的
 *    (Hash 操作)。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ApprovalService {

    private final StringRedisTemplate redisTemplate;
    private final ApprovalRepository approvalRepository;
    private final WebSocketPushService webSocketPushService;
    private final TaskSchedulerService taskSchedulerService;
    private final AlertLifecycleService alertLifecycleService;
    private final WebClient webClient;

    private static final String PREFIX = "approval:";
    /** Redis 审批键 TTL：防止 PENDING/终态审批在 Redis 永久堆积成僵尸 key。 */
    private static final Duration APPROVAL_REDIS_TTL = Duration.ofHours(24);

    /**
     * Python Agent 地址：审批决定后异步回调恢复 LangGraph 断点。
     * 原实现由 HumanApprovalTool 在工具内阻塞等待 55 秒，这里改为
     * 提交后立即返回，真正的人工决定由 /api/approvals/{id}/decision 触发。
     */
    @Value("${opsagent.agent.url:http://localhost:5000}")
    private String agentBaseUrl;

    @Value("${opsagent.internal.token:dev-internal-token}")
    private String internalToken;

    /**
     * 等待人工决定的超时（秒）。保留配置兼容；新 LangGraph 审批不再阻塞，
     * 超时只作为审批列表/前端展示的提示，不用于线程轮询。
     */
    @org.springframework.beans.factory.annotation.Value("${opsagent.approval.wait-timeout:55}")
    private long waitTimeoutSeconds;

    /** 仅用于内部决策返回。record 的访问器是 approved()/mode()/note()，与调用端 HumanApprovalTool 的写法一致。 */
    public record Decision(boolean approved, String mode, String note) {
    }

    /**
     * 记录自动审批的结果，直接写入 MySQL 并返回。
     */
    public void recordAutoApproval(String requestId, String operation, String reason, String taskId) {
        recordAutoApproval(requestId, operation, reason, taskId, null, null);
    }

    public void recordAutoApproval(String requestId, String operation, String reason, String taskId,
                                   String serviceName, String namespace) {
        Approval approval = new Approval();
        approval.setRequestId(requestId);
        approval.setOperation(operation);
        approval.setReason(reason);
        approval.setTaskId(taskId);
        approval.setStatus("APPROVED");
        approval.setDecidedBy("system");
        approval.setDecisionNote(null);
        approval.setCreatedAt(LocalDateTime.now());
        approval.setDecidedAt(LocalDateTime.now());
        approvalRepository.save(approval);

        // 写到 Redis，以供前端查询或推送。StringRedisTemplate 的 hash 值类型是 String，
        // 用 Object 会在 putAll 处直接编译失败。
        Map<String, String> map = new HashMap<>();
        map.put("status", "APPROVED");
        map.put("mode", "auto");
        map.put("operation", operation);
        map.put("reason", reason);
        map.put("taskId", taskId);
        map.put("serviceName", serviceName == null ? "" : serviceName);
        map.put("namespace", namespace == null ? "" : namespace);
        map.put("createdAt", LocalDateTime.now().toString());
        redisTemplate.opsForHash().putAll(PREFIX + requestId, map);
        redisTemplate.expire(PREFIX + requestId, APPROVAL_REDIS_TTL);
    }

    /**
     * 创建审批请求并立即返回，不再阻塞等待人工决定。
     *
     * LangGraph 的 interrupt() 会把 Python 图挂起在 Redis Checkpointer；
     * Java 侧只需要落一条 PENDING 审批单、推给前端，等待
     * /api/approvals/{id}/decision 回调恢复。55 秒超时不再阻塞线程，
     * 人工审批可以持续到 24h Checkpointer TTL 内任意时间。
     */
    public void submitPending(String requestId, String operation, String reason, String taskId) {
        submitPending(requestId, operation, reason, taskId, null, null);
    }

    public void submitPending(String requestId, String operation, String reason, String taskId,
                              String serviceName, String namespace) {
        // 【防骚扰闸门】关联告警处于拒绝冷却期 / 重试耗尽时，绝不再创建新的待审批单。
        // Python 侧图在超时（=拒绝）恢复后仍可能继续推理并再次 request_human_approval；
        // 若不拦截，用户刚拒绝/超时一次，界面上马上又跳出一个新弹窗 —— 正是要消除的骚扰。
        // 命中时：落一条 REJECTED 审计（保留可追溯性），广播 APPROVAL_DECIDED 让前端关闭弹窗，
        // 并给 Python 返回「未批准」，让图 fail-closed 收尾，不再产生写操作。
        if (isSuppressedByLifecycle(taskId, requestId, operation, reason, serviceName, namespace)) {
            return;
        }
        Approval approval = new Approval();
        approval.setRequestId(requestId);
        approval.setOperation(operation);
        approval.setReason(reason);
        approval.setTaskId(taskId);
        approval.setStatus("PENDING");
        approval.setCreatedAt(LocalDateTime.now());
        approvalRepository.save(approval);

        Map<String, String> redisMap = new HashMap<>();
        redisMap.put("status", "PENDING");
        redisMap.put("mode", "manual");
        redisMap.put("operation", operation);
        redisMap.put("reason", reason);
        redisMap.put("taskId", taskId);
        redisMap.put("serviceName", serviceName == null ? "" : serviceName);
        redisMap.put("namespace", namespace == null ? "" : namespace);
        redisMap.put("createdAt", LocalDateTime.now().toString());
        redisTemplate.opsForHash().putAll(PREFIX + requestId, redisMap);
        redisTemplate.expire(PREFIX + requestId, APPROVAL_REDIS_TTL);

        pushApprovalEvent("APPROVAL_PENDING", requestId, operation, reason, taskId,
                "待审批：" + operation + "（LangGraph 已挂起等待人工决定）",
                serviceName, namespace);
    }

    /**
     * 创建审批请求并同步等待结果（旧协议兼容）。
     *
     * 新 LangGraph 流程不再调用这个方法；保留它是为了兼容
     * 工具页/旧代码直接调用 request_human_approval 的同步语义，
     * 但内部也改为非阻塞提交 + 返回一个仍在等待的决定。
     * 真正的恢复由 LangGraph resume 完成。
     */
    public Decision createAndAwait(String requestId, String operation, String reason, String taskId) throws Exception {
        submitPending(requestId, operation, reason, taskId);
        return new Decision(false, "pending", "审批请求已提交，LangGraph 图已挂起等待人工决定");
    }

    /**
     * 分页查询审批单，供前端审批页使用。status 传 null 查全部。
     * JPA 是阻塞 IO，调用方（Controller）必须挪到 boundedElastic。
     */
    public Page<Approval> listApprovals(String status, int page, int size) {
        PageRequest pageRequest = PageRequest.of(Math.max(page - 1, 0), size,
                Sort.by(Sort.Direction.DESC, "createdAt"));
        if (status == null || status.isBlank()) {
            return approvalRepository.findAll(pageRequest);
        }
        return approvalRepository.findByStatus(status, pageRequest);
    }

    /**
     * 前端审批决定：更新 MySQL 终态 + 同步 Redis 状态，并通知 Python 恢复 LangGraph。
     *
     * @throws BusinessException 单号不存在或已决（不允许重复审批）时抛出
     */
    public Decision decide(String requestId, boolean approved, String note, String decidedBy) {
        Approval approval = approvalRepository.findById(requestId)
                .orElseThrow(() -> new BusinessException("审批单不存在: " + requestId));
        // 超时防御：若已超过 waitTimeoutSeconds 但仍为 PENDING，先转 TIMEOUT，
        // 防止“前端弹窗已超时、后端还能批准”的僵尸审批继续执行高危操作。
        if ("PENDING".equals(approval.getStatus()) && isExpired(approval)) {
            expire(approval);
            throw new BusinessException("审批单已超时，不能继续审批");
        }
        if (!"PENDING".equals(approval.getStatus())) {
            throw new BusinessException("审批单已处理（当前状态 " + approval.getStatus() + "），不允许重复审批");
        }

        String status = approved ? "APPROVED" : "REJECTED";
        approval.setStatus(status);
        approval.setDecidedBy(decidedBy);
        approval.setDecisionNote(note);
        approval.setDecidedAt(LocalDateTime.now());
        approvalRepository.save(approval);

        // 同步 Redis：供前端列表/审批页读取终态
        Map<String, String> update = new HashMap<>();
        update.put("status", status);
        if (note != null) {
            update.put("note", note);
        }
        redisTemplate.opsForHash().putAll(PREFIX + requestId, update);

        // 广播决定结果：其它打开着的页面要把弹窗关掉，列表也要刷新状态
        pushApprovalEvent("APPROVAL_DECIDED", requestId, approval.getOperation(),
                approval.getReason(), approval.getTaskId(),
                "APPROVAL_DECIDED:" + requestId + ":" + status);

        // LangGraph 恢复：审批决定后异步通知 Python resume 被 interrupt() 挂起的图。
        // 回调失败不能影响审批本身；Python 侧 Checkpointer 仍在，可手工重放恢复。
        notifyAgentResume(requestId, approved, note, approval.getTaskId());

        // 人工拒绝审批 ≠ 系统失败：把任务从 RUNNING 改为 REJECTED，并补充明确提示。
        // 不能让它继续显示成刺眼红色 FAILED，也不该显示成 SUCCESS（实际上没执行变更）。
        if (!approved) {
            markTaskRejected(approval.getTaskId(), note, decidedBy, requestId);
        } else {
            // 批准：清除可能残留的拒绝冷却，让后续验证轮次能正常推进
            clearCooldownByTask(approval.getTaskId());
        }

        log.info("审批单 {} 已由 {} {}: {}", requestId, decidedBy, status, note);
        return new Decision(approved, status.toLowerCase(), note);
    }

    /**
     * 人工拒绝审批后，把关联任务语义改为 REJECTED（已拒绝），并追加报告提示。
     *
     * 同时让关联告警进入 15 分钟静默冷却期（防骚扰）：
     * 拒绝不等于故障消失，告警保持未解决，但冷却期内同源告警只累加次数，
     * 不会再建任务、再弹窗；冷却到期仍持续报错才重新拉起诊断与审批。
     *
     * Python 侧由于后续 reporter 仍会把状态写回 SUCCESS，这里在 Java 侧做最终
     * 语义兜底：审批单 REJECTED -> 任务 REJECTED，保证任务列表不是红色 FAILED。
     */
    private void markTaskRejected(String taskId, String note, String decidedBy, String requestId) {
        if (taskId == null || taskId.isBlank()) {
            return;
        }
        try {
            String suffix = "\n\n---\n任务已终止：用户拒绝了高危修复操作，未对集群做任何变更。"
                    + "\n\n系统将在 " + alertLifecycleService.cooldownMinutes()
                    + " 分钟静默冷却期内继续观察：期间仅累加告警次数，不会自动弹窗；"
                    + "冷却结束时若故障仍在持续，将自动重新发起诊断与审批。";
            taskSchedulerService.getTask(taskId)
                    .flatMap(task -> {
                        String report = task.getOutput() == null
                                ? ("审批未通过，未执行任何写操作。" + suffix)
                                : task.getOutput() + suffix;
                        return taskSchedulerService.completeTask(taskId, report, "REJECTED")
                                .then(taskSchedulerService.appendStep(taskId,
                                        "审批被拒绝（" + (decidedBy == null ? "unknown" : decidedBy)
                                                + "），任务标记 REJECTED，告警进入 "
                                                + alertLifecycleService.cooldownMinutes()
                                                + " 分钟静默冷却期"));
                    })
                    .subscribe(
                            v -> { },
                            e -> log.warn("标记审批拒绝任务 REJECTED 失败 taskId={}: {}", taskId, e.getMessage())
                    );
            // 进入冷却与任务标记相互独立：即使任务已过期，冷却也要登记
            alertLifecycleService.enterRejectCooldownByTask(taskId,
                    note == null || note.isBlank() ? ("审批单 " + requestId + " 被拒绝") : note);
        } catch (Exception e) {
            log.warn("标记审批拒绝任务/冷却异常 taskId={}: {}", taskId, e.getMessage());
        }
    }

    /** 批准后清理告警上可能残留的拒绝冷却标记（防止上一轮拒绝的冷却误拦本轮验证）。 */
    private void clearCooldownByTask(String taskId) {
        try {
            alertLifecycleService.clearCooldownByTask(taskId);
        } catch (Exception e) {
            log.warn("清理拒绝冷却失败 taskId={}: {}", taskId, e.getMessage());
        }
    }

    /**
     * 审批创建前的生命周期闸门（防骚扰核心）。
     *
     * 关联告警处于拒绝冷却期或已重试耗尽时，返回 true 并做三件事：
     *   1. 落一条 REJECTED 审计记录（requestId 唯一，保留可追溯性）；
     *   2. 广播 APPROVAL_DECIDED，让前端把可能已弹出的弹窗关掉；
     *   3. 不写 Redis PENDING key —— Python 侧的 request_human_approval 会拿到
     *      approved=false / pending 不成立，从而 fail-closed 不执行写操作。
     *
     * 查不到关联告警（手动审批、无告警工具演练）时返回 false，保持原有行为。
     */
    private boolean isSuppressedByLifecycle(String taskId, String requestId, String operation,
                                            String reason, String serviceName, String namespace) {
        if (taskId == null || taskId.isBlank()) {
            return false;
        }
        try {
            String alertId = taskSchedulerService.getTask(taskId)
                    .map(t -> t.getAlertId()).block(Duration.ofSeconds(5));
            if (alertId == null || alertId.isBlank()) {
                return false;
            }
            var optional = alertLifecycleService.findAlert(alertId);
            if (optional.isEmpty()) {
                return false;
            }
            var action = alertLifecycleService.checkSuppress(optional.get());
            if (action == AlertLifecycleService.SuppressAction.NONE) {
                return false;
            }
            // 落审计：这次审批被生命周期规则挡下，不是没人处理
            Approval blocked = new Approval();
            blocked.setRequestId(requestId);
            blocked.setOperation(operation);
            blocked.setReason(reason);
            blocked.setTaskId(taskId);
            blocked.setStatus("REJECTED");
            blocked.setDecidedBy("system");
            blocked.setDecisionNote(action == AlertLifecycleService.SuppressAction.COOLDOWN
                    ? "告警处于拒绝冷却期，本次审批请求被自动抑制"
                    : "告警已重试耗尽（停止自动修复），本次审批请求被自动抑制");
            blocked.setCreatedAt(LocalDateTime.now());
            blocked.setDecidedAt(LocalDateTime.now());
            try {
                approvalRepository.save(blocked);
            } catch (Exception saveEx) {
                log.warn("记录被抑制审批失败 requestId={}: {}", requestId, saveEx.getMessage());
            }
            pushApprovalEvent("APPROVAL_DECIDED", requestId, operation, reason, taskId,
                    "APPROVAL_DECIDED:" + requestId + ":SUPPRESSED", serviceName, namespace);
            log.warn("[防骚扰] 审批请求 {} 被生命周期规则抑制（{}，告警 {}），不再弹窗",
                    requestId, action, alertId);
            return true;
        } catch (Exception e) {
            // 判定失败时放行：宁可多一次弹窗，也不能因为查询异常把合法审批永久吞掉
            log.warn("[防骚扰] 审批抑制判定异常（放行） requestId={} taskId={}: {}",
                    requestId, taskId, e.getMessage());
            return false;
        }
    }

    /**
     * 扫描并处理所有超时 PENDING 审批，供后台定时任务调用。
     *
     * 原设计只把 55 秒作为前端展示提示，没有真正把超时审批落成 TIMEOUT，
     * 导致 PENDING 审批单和 Redis key 永久堆积成僵尸。这里按 createdAt 判定：
     * createdAt + waitTimeoutSeconds 早于当前时间即超时。
     */
    public int expirePendingOverdue() {
        LocalDateTime deadline = LocalDateTime.now().minusSeconds(waitTimeoutSeconds);
        List<Approval> overdue = approvalRepository.findByStatusAndCreatedAtBefore("PENDING", deadline);
        int count = 0;
        for (Approval approval : overdue) {
            try {
                expire(approval);
                count++;
            } catch (Exception e) {
                log.warn("审批超时处理失败 requestId={}: {}", approval.getRequestId(), e.getMessage());
            }
        }
        // 兜底清理只存在 Redis、MySQL 里没有对应审批单的孤儿 PENDING key。
        // 正常链路创建时会同时落库，出现孤儿通常是审批服务抖动/旧版本留下的，
        // 不清理会一直占着 Redis，前端弹窗也只会收到“已超时”却关不掉。
        int redisOrphans = expireRedisOrphanPending(deadline);
        if (count + redisOrphans > 0) {
            log.info("审批超时自动拒绝：{} 条 MySQL PENDING + {} 条 Redis 孤儿 PENDING 已处理",
                    count, redisOrphans);
        }
        return count + redisOrphans;
    }

    private int expireRedisOrphanPending(LocalDateTime deadline) {
        try {
            // 用 SCAN 而不是 KEYS：审批 key 可能很多，KEYS 会阻塞 Redis 主线程。
            Set<String> keys = new java.util.HashSet<>();
            try (org.springframework.data.redis.core.Cursor<byte[]> cursor =
                         redisTemplate.executeWithStickyConnection(
                                 connection -> connection.scan(
                                         org.springframework.data.redis.core.ScanOptions
                                                 .scanOptions().match(PREFIX + "*").count(500).build()))) {
                if (cursor != null) {
                    cursor.forEachRemaining(bytes -> keys.add(new String(bytes, java.nio.charset.StandardCharsets.UTF_8)));
                }
            }
            int cleaned = 0;
            if (keys.isEmpty()) {
                return 0;
            }
            for (String key : keys) {
                try {
                    String status = (String) redisTemplate.opsForHash().get(key, "status");
                    String createdAt = (String) redisTemplate.opsForHash().get(key, "createdAt");
                    if (!"PENDING".equals(status)) {
                        continue;
                    }
                    boolean expired = false;
                    if (createdAt != null && !createdAt.isBlank()) {
                        try {
                            LocalDateTime created = LocalDateTime.parse(createdAt);
                            expired = created.plusSeconds(waitTimeoutSeconds).isBefore(LocalDateTime.now());
                        } catch (Exception ignore) {
                            // 时间格式异常时靠 deadline 兜底，不因为坏数据漏清
                            expired = true;
                        }
                    } else {
                        expired = true;
                    }
                    if (expired) {
                        redisTemplate.delete(key);
                        cleaned++;
                    }
                } catch (Exception e) {
                    log.warn("清理 Redis 孤儿审批失败 key={}: {}", key, e.getMessage());
                }
            }
            return cleaned;
        } catch (Exception e) {
            log.warn("扫描 Redis 孤儿审批失败: {}", e.getMessage());
            return 0;
        }
    }

    private boolean isExpired(Approval approval) {
        return approval.getCreatedAt() != null
                && approval.getCreatedAt().plusSeconds(waitTimeoutSeconds).isBefore(LocalDateTime.now());
    }

    private void expire(Approval approval) {
        if (approval == null || !"PENDING".equals(approval.getStatus())) {
            return;
        }
        approval.setStatus("TIMEOUT");
        approval.setDecidedBy("system");
        approval.setDecisionNote("审批超时，系统自动拒绝");
        approval.setDecidedAt(LocalDateTime.now());
        approvalRepository.save(approval);

        Map<String, String> update = new HashMap<>();
        update.put("status", "TIMEOUT");
        update.put("note", "审批超时，系统自动拒绝");
        update.put("decidedBy", "system");
        update.put("decidedAt", LocalDateTime.now().toString());
        redisTemplate.opsForHash().putAll(PREFIX + approval.getRequestId(), update);
        redisTemplate.expire(PREFIX + approval.getRequestId(), APPROVAL_REDIS_TTL);

        pushApprovalEvent("APPROVAL_TIMEOUT", approval.getRequestId(), approval.getOperation(),
                approval.getReason(), approval.getTaskId(),
                "APPROVAL_TIMEOUT:" + approval.getRequestId());

        // 通知 Python 以“拒绝”恢复挂起图，避免超时审批对应的 LangGraph 任务永久挂起。
        notifyAgentResume(approval.getRequestId(), false, "审批超时，系统自动拒绝", approval.getTaskId());

        // 超时 = 系统代用户拒绝：同样进入静默冷却期，避免超时后立刻又弹一个新的审批
        // 把用户烦死。冷却到期若仍在报错，才重新拉起诊断与审批（重试预算仍受控）。
        try {
            markTaskRejected(approval.getTaskId(), "审批超时，系统自动拒绝", "system", approval.getRequestId());
        } catch (Exception e) {
            log.warn("审批超时登记冷却失败 requestId={}: {}", approval.getRequestId(), e.getMessage());
        }

        log.info("审批单 {} 已超时自动拒绝", approval.getRequestId());
    }

    /**
     * 审批决定后通知 Python LangGraph 恢复执行。
     *
     * 原实现靠 Java 工具阻塞 55 秒轮询 Redis；现在 Python 图在审批节点
     * interrupt() 挂起，这里通过 HTTP 调用 /api/agent/resume 传入决定。
     * 这是异步增强：网络失败时审批仍已终态落库，运维可重放/人工介入。
     *
     * 安全校验：调用 Python resume 接口前，将请求体中的 requestId 与挂起的 requestId 比对；
     * 不匹配时记录警告日志并拒绝调用（直接 return，不抛异常），不影响审批主流程。
     */
    void notifyAgentResume(String requestId, boolean approved, String note, String taskId) {
        if (taskId == null || taskId.isBlank()) {
            return;
        }

        // 安全校验：调用 Python resume 接口前，将请求体中的 requestId 与挂起的 requestId 比对；
        // 不匹配（伪造/过期/跨任务回调）则只记警告并拒绝调用（直接 return，不抛异常），
        // 不影响审批主流程（审批决定本身已经落库）。
        try {
            if (approvalRepository != null) {
                Approval suspendedApproval = approvalRepository.findById(requestId).orElse(null);
                if (suspendedApproval == null) {
                    // 未按 requestId 直接命中时，回退按 taskId 查找挂起/已决审批单
                    try {
                        for (Approval candidate : approvalRepository.findAll()) {
                            if (candidate != null && taskId.equals(candidate.getTaskId())) {
                                suspendedApproval = candidate;
                                break;
                            }
                        }
                    } catch (Exception findAllEx) {
                        log.warn("查找挂起审批单 findAll 异常 taskId={} requestId={}: {}",
                                taskId, requestId, findAllEx.getMessage());
                    }
                }
                if (suspendedApproval == null) {
                    log.warn("通知 Python 恢复被拒绝：未找到挂起审批单 requestId={} taskId={}", requestId, taskId);
                    return;
                }
                // 校验 requestId 匹配
                String suspendedRequestId = suspendedApproval.getRequestId();
                if (suspendedRequestId == null || !suspendedRequestId.equals(requestId)) {
                    log.warn("通知 Python 恢复被拒绝：requestId 与挂起审批单不匹配 incoming={} suspended={} taskId={}",
                            requestId, suspendedRequestId, taskId);
                    return;
                }
                // 校验 taskId 匹配
                if (suspendedApproval.getTaskId() != null && !suspendedApproval.getTaskId().equals(taskId)) {
                    log.warn("通知 Python 恢复被拒绝：审批单 taskId 与请求 taskId 不匹配 requestId={} approvalTaskId={} taskId={}",
                            requestId, suspendedApproval.getTaskId(), taskId);
                    return;
                }
            }
        } catch (Exception e) {
            log.warn("校验挂起审批单 requestId 异常 taskId={} requestId={}: {}", taskId, requestId, e.getMessage());
            return;
        }

        // 校验通过，调用 Python resume 接口
        try {
            Map<String, Object> payload = new HashMap<>();
            payload.put("requestId", requestId);
            payload.put("approved", approved);
            payload.put("note", note == null ? "" : note);

            webClient.post()
                    .uri(agentBaseUrl + "/api/agent/resume/" + taskId)
                    .header("X-Internal-Token", internalToken)
                    .bodyValue(payload)
                    .retrieve()
                    .bodyToMono(String.class)
                    .timeout(Duration.ofSeconds(60))
                    .subscribeOn(Schedulers.boundedElastic())
                    .doOnSuccess(v -> log.info("已通知 Python 恢复任务 taskId={} requestId={} approved={}",
                            taskId, requestId, approved))
                    .subscribe(
                            null,
                            e -> log.warn("通知 Python 恢复失败 taskId={} requestId={}: {}",
                                    taskId, requestId, e.getMessage()));
        } catch (Exception e) {
            // 审批决定本身已经落库；通知失败只记日志，运维可重放/人工介入
            log.warn("通知 Python 恢复异常 taskId={} requestId={}: {}", taskId, requestId, e.getMessage());
        }
    }

    /**
     * 广播审批事件到所有 WebSocket 连接。
     *
     * AgentMessage 的字段被复用：type 传事件类型，stepName 存操作名，
     * content 存人类可读描述，taskId 保留原始任务关联。
     */
    private void pushApprovalEvent(String type, String requestId, String operation,
                                   String reason, String taskId, String content) {
        pushApprovalEvent(type, requestId, operation, reason, taskId, content, null, null);
    }

    private void pushApprovalEvent(String type, String requestId, String operation,
                                   String reason, String taskId, String content,
                                   String serviceName, String namespace) {
        try {
            com.opsagent.model.AgentMessage msg = new com.opsagent.model.AgentMessage();
            msg.setType(type);
            msg.setTaskId(taskId == null ? "" : taskId);
            msg.setStepName("request_human_approval");
            // requestId 必须带上：前端要靠它调审批接口，没有它弹窗点不动
            msg.setContent(content + "|requestId=" + requestId
                    + "|operation=" + operation
                    + "|serviceName=" + (serviceName == null ? "" : serviceName)
                    + "|namespace=" + (namespace == null ? "" : namespace)
                    + "|reason=" + (reason == null ? "" : reason)
                    + "|timeout=" + waitTimeoutSeconds);
            msg.setTimestamp(LocalDateTime.now());
            webSocketPushService.broadcast(msg);
        } catch (Exception e) {
            // 推送失败不该影响审批主流程：记录仍在库中，用户可去审批页处理
            log.warn("审批事件推送失败 requestId={} type={}", requestId, type, e);
        }
    }
}
