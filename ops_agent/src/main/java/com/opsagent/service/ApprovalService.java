package com.opsagent.service;

import com.opsagent.entity.Approval;
import com.opsagent.exception.BusinessException;
import com.opsagent.repository.ApprovalRepository;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

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

    private static final String PREFIX = "approval:";
    private static final long WAIT_TIMEOUT_MS = 55_000; // 55 秒超时

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
     * 创建审批请求并同步等待结果。
     * @throws Exception 任务运行过程中若出现异常（如 Redis 连接中断）将被抛出，工具层会捕获并返回 failure。
     */
    public Decision createAndAwait(String requestId, String operation, String reason, String taskId) throws Exception {
        // 1. 写入临时 PENDING 记录
        Approval approval = new Approval();
        approval.setRequestId(requestId);
        approval.setOperation(operation);
        approval.setReason(reason);
        approval.setTaskId(taskId);
        approval.setStatus("PENDING");
        approval.setCreatedAt(LocalDateTime.now());
        approvalRepository.save(approval);

        // 2. 写入 Redis，等待前端或 WebSocket 触发变更
        Map<String, Object> redisMap = new HashMap<>();
        redisMap.put("status", "PENDING");
        redisMap.put("mode", "manual");
        redisMap.put("operation", operation);
        redisMap.put("reason", reason);
        redisMap.put("taskId", taskId);
        redisMap.put("createdAt", LocalDateTime.now().toString());
        redisTemplate.opsForHash().putAll(PREFIX + requestId, redisMap);

        long start = System.currentTimeMillis();
        while (true) {
            Object statusObj = redisTemplate.opsForHash().get(PREFIX + requestId, "status");
            if (statusObj != null && !"PENDING".equals(statusObj)) {
                String status = statusObj.toString();
                boolean approved = "APPROVED".equalsIgnoreCase(status);
                String mode = status.toLowerCase();
                String note = (String) redisTemplate.opsForHash().get(PREFIX + requestId, "note");

                // 更新 MySQL 记录
                Optional<Approval> opt = approvalRepository.findById(requestId);
                Approval a = opt.orElse(approval);
                a.setStatus(status);
                a.setDecidedBy("human");
                a.setDecisionNote(note);
                a.setDecidedAt(LocalDateTime.now());
                approvalRepository.save(a);

                return new Decision(approved, mode, note);
            }

            if (System.currentTimeMillis() - start > WAIT_TIMEOUT_MS) {
                // 超时处理
                redisTemplate.opsForHash().put(PREFIX + requestId, "status", "TIMEOUT");
                redisTemplate.opsForHash().put(PREFIX + requestId, "note", "Waiting approval timed out");
                Approval a = approvalRepository.findById(requestId).orElse(approval);
                a.setStatus("TIMEOUT");
                a.setDecidedBy("system");
                a.setDecisionNote("Waiting approval timed out");
                a.setDecidedAt(LocalDateTime.now());
                approvalRepository.save(a);
                return new Decision(false, "timeout", "Waiting approval timed out");
            }

            Thread.sleep(200); // 防止占用 CPU
        }
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
     * 前端审批决定：更新 MySQL 终态 + 同步 Redis 状态。
     * createAndAwait 的等待循环每 200ms 轮询 Redis，这里写入后等待方最多 200ms 内拿到结果。
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

        // 同步 Redis：让 createAndAwait 的等待循环立刻感知决定
        Map<String, String> update = new HashMap<>();
        update.put("status", status);
        if (note != null) {
            update.put("note", note);
        }
        redisTemplate.opsForHash().putAll(PREFIX + requestId, update);

        log.info("审批单 {} 已由 {} {}: {}", requestId, decidedBy, status, note);
        return new Decision(approved, status.toLowerCase(), note);
    }
}
