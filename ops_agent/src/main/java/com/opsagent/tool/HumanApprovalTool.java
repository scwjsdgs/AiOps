package com.opsagent.tool;

import com.opsagent.model.ToolExecutionResult;
import com.opsagent.service.ApprovalService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 高危操作的人工审批闸口。
 *
 * mode=auto：立即批准，但生成 requestId 并落审计 —— 行为上跟
 * agent 原来那句"模拟批准"等价，区别是这条批准现在有据可查。
 *
 * mode=manual：写入待审批队列（Redis + MySQL）并 WebSocket 推给前端，
 * 然后立即返回。LangGraph 图此刻在 Python 侧 interrupt() 挂起在 Redis
 * Checkpointer；Java 侧不再阻塞任何线程，人工决定由审批页
 * /api/approvals/{id}/decision 提交后，Java 异步回调 Python
 * /api/agent/resume 恢复图。
 *
 * Python 侧读 data.approved / data.pending / data.mode / data.requestId / data.note。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class HumanApprovalTool implements Tool {

    @Value("${opsagent.approval.mode:auto}")
    private String mode;

    @Value("${opsagent.approval.wait-timeout:55}")
    private long waitTimeoutSeconds;

    private final ApprovalService approvalService;

    @Override
    public String getName() {
        return "request_human_approval";
    }

    @Override
    public String getDescription() {
        return "在执行高危操作前请求人工审批。";
    }

    @Override
    public boolean isIdempotent() {
        return true;
    }

    @Override
    public ToolExecutionResult execute(Map<String, Object> parameters) {
        long start = System.currentTimeMillis();
        ToolParams p = ToolParams.of(parameters);
        String operation = p.str("operation");
        if (operation == null) {
            return Tool.failure("Missing 'operation' parameter", start);
        }
        String reason = p.strOr("（未说明原因）", "reason");
        String taskId = p.strOr("", "contextId", "taskId");

        boolean auto = "auto".equalsIgnoreCase(mode == null ? "" : mode.trim());
        String requestId = "apr-" + UUID.randomUUID().toString().substring(0, 8);

        if (auto) {
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("approved", true);
            data.put("requestId", requestId);
            data.put("mode", "auto");
            data.put("operation", operation);
            data.put("reason", reason);
            approvalService.recordAutoApproval(requestId, operation, reason, taskId);
            return Tool.success("Auto-approved (mode=auto, requestId=" + requestId + "): " + operation, data, start);
        }

        // manual 模式：写入待审批队列（Redis + MySQL）并推给前端，然后立即返回。
        // LangGraph 图此刻在 Python 侧 interrupt() 挂起；Java 侧不再阻塞线程，
        // 55 秒超时不再是线程轮询。真正恢复由 /api/approvals/{id}/decision 回调
        // /api/agent/resume 完成。
        try {
            approvalService.submitPending(requestId, operation, reason, taskId);
        } catch (Exception e) {
            // 审批服务挂了不能让 agent 拿到"已批准"：降级为未批准并如实说明
            log.error("审批服务异常 requestId={} operation={}", requestId, operation, e);
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("approved", false);
            data.put("requestId", requestId);
            data.put("mode", "manual");
            data.put("operation", operation);
            data.put("reason", reason);
            data.put("error", e.getMessage());
            return Tool.success("Approval service error (treated as not approved): " + e.getMessage(), data, start);
        }

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("approved", false);
        data.put("pending", true);
        data.put("requestId", requestId);
        data.put("mode", "manual");
        data.put("operation", operation);
        data.put("reason", reason);
        data.put("timeoutSeconds", waitTimeoutSeconds);

        String message = "Approval requested (requestId=" + requestId + ", LangGraph 已挂起): " + operation;
        // 请求本身送达成功即 success=true；批没批由 data.approved/data.pending 表达。
        return Tool.success(message, data, start);
    }
}
