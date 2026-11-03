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

        // 提取目标服务名与命名空间（供审批弹窗显眼展示「目标服务」）
        String serviceName = extractServiceName(operation, reason);
        String namespace = extractNamespace(operation, reason, p.str("namespace"));
        // 提取具体的 Pod/容器名（如果 reason/参数里有），供审批弹窗展示精确目标
        String podName = extractPodName(operation, reason);
        String containerName = extractContainerName(operation, reason);

        // 系统核心组件（kube-system）严禁自动回滚/高危修复：fail-closed，
        // 直接在审批层拦截并返回「禁止自动修复」，不让 LLM 把回滚当可选项。
        if (isCoreSystemComponent(serviceName, namespace)) {
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("approved", false);
            data.put("requestId", "apr-" + UUID.randomUUID().toString().substring(0, 8));
            data.put("mode", "manual");
            data.put("operation", operation);
            data.put("reason", reason);
            data.put("serviceName", serviceName);
            data.put("namespace", namespace);
            data.put("blocked", true);
            log.warn("拦截系统核心组件高危修复操作 service={} namespace={} operation={}", serviceName, namespace, operation);
            return Tool.success(
                    "系统核心组件，禁止自动修复：目标服务 " + serviceName
                            + "（namespace: " + namespace + "）不允许回滚/高危变更。请人工线下处理。",
                    data, start);
        }

        boolean auto = "auto".equalsIgnoreCase(mode == null ? "" : mode.trim());
        String requestId = "apr-" + UUID.randomUUID().toString().substring(0, 8);

        if (auto) {
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("approved", true);
            data.put("requestId", requestId);
            data.put("mode", "auto");
            data.put("operation", operation);
            data.put("reason", reason);
            data.put("serviceName", serviceName);
            data.put("namespace", namespace);
            approvalService.recordAutoApproval(requestId, operation, reason, taskId, serviceName, namespace);
            return Tool.success("Auto-approved (mode=auto, requestId=" + requestId + "): " + operation, data, start);
        }

        // 精确目标展示：操作栏变成「重启故障服务（目标：podName）」
        String displayOperation = operation;
        if (podName != null && !podName.isBlank()) {
            displayOperation = operation + "（目标：" + podName
                    + (containerName != null && !containerName.isBlank() ? " 容器 " + containerName : "")
                    + "）";
        }

        // manual 模式：写入待审批队列（Redis + MySQL）并推给前端，然后立即返回。
        // LangGraph 图此刻在 Python 侧 interrupt() 挂起；Java 侧不再阻塞线程，
        // 55 秒超时不再是线程轮询。真正恢复由 /api/approvals/{id}/decision 回调
        // /api/agent/resume 完成。
        try {
            approvalService.submitPending(requestId, displayOperation, reason, taskId, serviceName, namespace);
        } catch (Exception e) {
            // 审批服务挂了不能让 agent 拿到"已批准"：降级为未批准并如实说明
            log.error("审批服务异常 requestId={} operation={}", requestId, operation, e);
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("approved", false);
            data.put("requestId", requestId);
            data.put("mode", "manual");
            data.put("operation", displayOperation);
            data.put("reason", reason);
            data.put("error", e.getMessage());
            return Tool.success("Approval service error (treated as not approved): " + e.getMessage(), data, start);
        }

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("approved", false);
        data.put("pending", true);
        data.put("requestId", requestId);
        data.put("mode", "manual");
        data.put("operation", displayOperation);
        data.put("reason", reason);
        data.put("serviceName", serviceName);
        data.put("namespace", namespace);
        data.put("podName", podName);
        data.put("containerName", containerName);
        data.put("timeoutSeconds", waitTimeoutSeconds);

        String message = "Approval requested (requestId=" + requestId + ", LangGraph 已挂起): " + operation;
        // 请求本身送达成功即 success=true；批没批由 data.approved/data.pending 表达。
        return Tool.success(message, data, start);
    }

    /** 从操作/理由文本里提取目标服务名；提取不到返回空串。 */
    private String extractServiceName(String operation, String reason) {
        String text = (operation == null ? "" : operation) + " " + (reason == null ? "" : reason);
        var m = java.util.regex.Pattern.compile("服务\\s+([A-Za-z0-9][A-Za-z0-9_.\\-]{1,62})").matcher(text);
        if (m.find()) {
            return m.group(1);
        }
        // 兼容「回滚 xxx」「重启 xxx」等不带“服务”前缀的写法
        var m2 = java.util.regex.Pattern.compile("(?:回滚|重启|扩容|缩容|清缓存)\\s+([A-Za-z0-9][A-Za-z0-9_.\\-]{1,62})").matcher(text);
        return m2.find() ? m2.group(1) : "";
    }

    /** 提取命名空间；显式参数优先，否则从服务名/文本猜测，缺省 default。 */
    private String extractNamespace(String operation, String reason, String explicit) {
        if (explicit != null && !explicit.isBlank()) {
            return explicit;
        }
        String text = (operation == null ? "" : operation) + " " + (reason == null ? "" : reason);
        var m = java.util.regex.Pattern.compile("namespace\\s*[:=]\\s*([a-z0-9-]+)").matcher(text);
        return m.find() ? m.group(1) : "default";
    }

    /** 从操作/理由文本里提取具体 Pod 名（形如 <deploy>-<rs>-<pod>）。 */
    private String extractPodName(String operation, String reason) {
        String text = (operation == null ? "" : operation) + " " + (reason == null ? "" : reason);
        var m = java.util.regex.Pattern.compile(
                "(?i)\\b([a-z0-9]+-[a-z0-9]{6,10}-[a-z0-9]{5})\\b").matcher(text);
        return m.find() ? m.group(1) : "";
    }

    /** 从操作/理由文本里提取容器名；提取不到返回空串。 */
    private String extractContainerName(String operation, String reason) {
        String text = (operation == null ? "" : operation) + " " + (reason == null ? "" : reason);
        var m = java.util.regex.Pattern.compile("容器\\s+([A-Za-z0-9][A-Za-z0-9_.\\-]{0,62})").matcher(text);
        return m.find() ? m.group(1) : "";
    }

    /** kube-system 核心组件一律禁止自动修复（回滚/高危变更）。 */
    private boolean isCoreSystemComponent(String serviceName, String namespace) {
        if (namespace != null && "kube-system".equals(namespace)) {
            return true;
        }
        if (serviceName == null || serviceName.isBlank()) {
            return false;
        }
        String lower = serviceName.toLowerCase();
        return lower.equals("coredns")
                || lower.equals("kube-apiserver")
                || lower.equals("kube-controller-manager")
                || lower.equals("kube-scheduler")
                || lower.equals("kube-proxy")
                || lower.equals("etcd")
                || lower.startsWith("kube-state-metrics")
                || lower.startsWith("metrics-server");
    }
}
