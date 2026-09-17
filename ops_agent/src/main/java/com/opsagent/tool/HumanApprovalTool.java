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
 * 然后阻塞等待人工决定（上限 opsagent.approval.wait-timeout，默认 55s）。
 * 超时视为未批准——人工审批不能无限期挂住自动流程，agent 会拿到
 * "审批超时"转而走稳妥路径。等待逻辑全在本工具内（boundedElastic 线程），
 * Python 侧仍是同步 HTTP 调用，无需感知。
 *
 * Python 侧读 data.approved / data.mode / data.requestId / data.note。
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

        // manual 模式：写入待审批队列（Redis + MySQL）并推给前端，然后阻塞等待人工决定。
        // 这里运行在 boundedElastic 线程上（AgentCallbackService.executeTool 已调度），
        // 阻塞等待不影响 Netty event loop。等待上限必须小于 Python 侧
        // HTTP_READ_TIMEOUT(60s)，否则审批还没结束 Python 先超时了。
        ApprovalService.Decision decision;
        try {
            decision = approvalService.createAndAwait(requestId, operation, reason, taskId);
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
        data.put("approved", decision.approved());
        data.put("requestId", requestId);
        data.put("mode", decision.mode());
        data.put("operation", operation);
        data.put("reason", reason);
        if (decision.note() != null && !decision.note().isBlank()) {
            data.put("note", decision.note());
        }

        String message;
        if (decision.approved()) {
            message = "Approved by human (mode=" + decision.mode() + ", requestId=" + requestId + "): " + operation;
        } else if ("timeout".equals(decision.mode())) {
            message = "Approval timed out after " + waitTimeoutSeconds + "s (requestId=" + requestId + "): " + operation
                    + " — proceed with the safe alternative, do NOT execute the high-risk operation.";
        } else {
            message = "Rejected by human (requestId=" + requestId + "): " + operation
                    + (decision.note() != null && !decision.note().isBlank() ? " — reason: " + decision.note() : "");
        }

        // 请求本身送达成功即 success=true；批没批由 data.approved 表达。
        // 两者混在一起会让 Python 侧无法区分"审批被拒"和"审批服务挂了"。
        return Tool.success(message, data, start);
    }
}
