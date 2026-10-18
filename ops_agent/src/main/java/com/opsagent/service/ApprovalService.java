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
import java.util.Map;
import java.util.Optional;

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
    private final WebClient webClient;

    private static final String PREFIX = "approval:";

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
        map.put("createdAt", LocalDateTime.now().toString());
        redisTemplate.opsForHash().putAll(PREFIX + requestId, map);
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
        redisMap.put("createdAt", LocalDateTime.now().toString());
        redisTemplate.opsForHash().putAll(PREFIX + requestId, redisMap);

        pushApprovalEvent("APPROVAL_PENDING", requestId, operation, reason, taskId,
                "待审批：" + operation + "（LangGraph 已挂起等待人工决定）");
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

        log.info("审批单 {} 已由 {} {}: {}", requestId, decidedBy, status, note);
        return new Decision(approved, status.toLowerCase(), note);
    }

    /**
     * 审批决定后通知 Python LangGraph 恢复执行。
     *
     * 原实现靠 Java 工具阻塞 55 秒轮询 Redis；现在 Python 图在审批节点
     * interrupt() 挂起，这里通过 HTTP 调用 /api/agent/resume 传入决定。
     * 这是异步增强：网络失败时审批仍已终态落库，运维可重放/人工介入。
     */
    private void notifyAgentResume(String requestId, boolean approved, String note, String taskId) {
        if (taskId == null || taskId.isBlank()) {
            return;
        }
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
                    .doOnError(e -> log.warn("通知 Python 恢复失败 taskId={} requestId={}: {}",
                            taskId, requestId, e.getMessage()))
                    .subscribe();
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
        try {
            com.opsagent.model.AgentMessage msg = new com.opsagent.model.AgentMessage();
            msg.setType(type);
            msg.setTaskId(taskId == null ? "" : taskId);
            msg.setStepName("request_human_approval");
            // requestId 必须带上：前端要靠它调审批接口，没有它弹窗点不动
            msg.setContent(content + "|requestId=" + requestId
                    + "|operation=" + operation
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
