package com.opsagent.controller;

import com.opsagent.dto.ApiResponse;
import com.opsagent.model.DocumentChangeEvent;
import com.opsagent.service.AuditLogService;
import com.opsagent.service.DocumentChangeService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * 文档系统 Webhook -> Kafka 事件驱动 RAG 更新。
 *
 * 路由：POST /api/rag/docs/webhook
 * 认证：X-Doc-Webhook-Token 头
 * 响应：立即返回 202，事件异步发到 Kafka `doc-change`。
 */
@Slf4j
@RestController
@RequestMapping("/api/rag/docs")
@RequiredArgsConstructor
public class DocumentWebhookController {

    private final DocumentChangeService documentChangeService;
    private final AuditLogService auditLogService;

    @Value("${opsagent.webhook.docs.token:}")
    private String docsWebhookToken;

    @PostMapping("/webhook")
    public Mono<ResponseEntity<ApiResponse<Object>>> receive(@RequestBody(required = false) DocumentChangeEvent event,
                                                             @RequestHeader(value = "X-Doc-Webhook-Token", required = false) String token,
                                                             ServerWebExchange exchange) {
        // Webhook 无法携带 JWT，必须依赖 X-Doc-Webhook-Token 校验。
        // 未配置 token 时直接拒绝，避免 webhook 路由无保护暴露（fail-closed）。
        if (docsWebhookToken == null || docsWebhookToken.isBlank()) {
            auditLogService.record("RAG", "doc_webhook_auth_failed", "unknown",
                    "WEBHOOK", event == null ? "" : event.getDocId(), "文档变更 Webhook token 未配置", false);
            return Mono.just(ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(ApiResponse.error(401, "Webhook token not configured")));
        }
        if (token == null || token.isBlank() || !docsWebhookToken.equals(token)) {
            auditLogService.record("RAG", "doc_webhook_auth_failed", "unknown",
                    "WEBHOOK", event == null ? "" : event.getDocId(), "文档变更 Webhook token 校验失败", false);
            return Mono.just(ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(ApiResponse.error(401, "Invalid webhook token")));
        }
        if (event == null || event.getDocId() == null || event.getDocId().isBlank()) {
            return Mono.just(ResponseEntity.badRequest()
                    .body(ApiResponse.error(400, "docId 不能为空")));
        }
        if (event.getFilename() == null || event.getFilename().isBlank()) {
            event.setFilename(event.getDocId() + ".txt");
        }
        if (event.getOperator() == null || event.getOperator().isBlank()) {
            event.setOperator("doc-system");
        }

        auditLogService.record("RAG", "doc_webhook_received",
                auditLogService.operator(exchange), "WEBHOOK", event.getDocId(),
                "文档变更事件已接收 status=" + event.getStatus(), true);
        documentChangeService.sendAsync(event).subscribe(
                null,
                e -> auditLogService.record("RAG", "doc_webhook_send_failed",
                        "doc-system", "WEBHOOK", event.getDocId(), e.getMessage(), false)
        );
        return Mono.just(ResponseEntity.accepted()
                .body(ApiResponse.success(java.util.Map.of("docId", event.getDocId(), "accepted", true))));
    }
}