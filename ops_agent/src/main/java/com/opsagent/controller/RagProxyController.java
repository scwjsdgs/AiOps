package com.opsagent.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.opsagent.dto.ApiResponse;
import com.opsagent.service.AuditLogService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/**
 * RAG / LLM 成本 / 混沌评测 代理控制器。
 *
 * 前端只走 JWT 通道（Bearer token），这些接口在 Java 侧做代理转发到 Python
 * /api/rag/*、/api/llm/*、/api/chaos/*。内部密钥 X-Internal-Token 不出 Java，
 * 与 ChatOps 中转同一安全模型。
 *
 * 响应统一为 ApiResponse，data 保留 Python 返回体；这样前端 request.js
 * 的 res.code===200 判断一定成立，页面数据从 res.data 读取。
 */
@Slf4j
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class RagProxyController {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final WebClient webClient;
    private final AuditLogService auditLogService;

    @Value("${opsagent.agent.url:http://localhost:5000}")
    private String agentBaseUrl;

    @Value("${opsagent.internal.token:dev-internal-token}")
    private String internalToken;

    // ---------------- RAG 知识库 ----------------

    @GetMapping("/rag/knowledge/overview")
    public Mono<ApiResponse<Object>> knowledgeOverview() {
        return forwardGet("/api/rag/knowledge/overview");
    }

    @GetMapping("/rag/knowledge/documents")
    public Mono<ApiResponse<Object>> knowledgeDocuments() {
        return forwardGet("/api/rag/knowledge/documents");
    }

    @GetMapping("/rag/knowledge/detail")
    public Mono<ApiResponse<Object>> knowledgeDetail(ServerWebExchange exchange) {
        return forwardGet("/api/rag/knowledge/detail", exchange);
    }

    @GetMapping("/rag/knowledge/audit")
    public Mono<ApiResponse<Object>> knowledgeAudit(ServerWebExchange exchange) {
        return forwardGet("/api/rag/knowledge/audit", exchange);
    }

    @PostMapping("/rag/knowledge/upload")
    public Mono<ApiResponse<Object>> knowledgeUpload(@RequestBody String body) {
        return forwardPost("/api/rag/knowledge/upload", body);
    }

    @PostMapping("/rag/knowledge/rollback")
    public Mono<ApiResponse<Object>> knowledgeRollback(@RequestBody String body) {
        return forwardPost("/api/rag/knowledge/rollback", body);
    }

    @PostMapping("/rag/knowledge/quality")
    public Mono<ApiResponse<Object>> knowledgeQuality(@RequestBody String body) {
        return forwardPost("/api/rag/knowledge/quality", body);
    }

    @PostMapping("/rag/knowledge/rebuild")
    public Mono<ApiResponse<Object>> knowledgeRebuild(ServerWebExchange exchange,
                                                      @RequestBody(required = false) String body) {
        return forwardPost("/api/rag/knowledge/rebuild?operator=" + encode(auditLogService.operator(exchange)), body);
    }

    @PostMapping("/rag/knowledge/delete")
    public Mono<ApiResponse<Object>> knowledgeDelete(ServerWebExchange exchange,
                                                     @RequestBody(required = false) String body) {
        String source = exchange.getRequest().getQueryParams().getFirst("source");
        return forwardPost("/api/rag/knowledge/delete?source=" + encode(source == null ? "" : source)
                + "&operator=" + encode(auditLogService.operator(exchange)), body);
    }

    @PostMapping("/rag/knowledge/retry")
    public Mono<ApiResponse<Object>> knowledgeRetry(@RequestBody(required = false) String body) {
        return forwardPost("/api/rag/knowledge/retry", body);
    }

    // ---------------- RAG 评估 / 调试 ----------------

    @PostMapping("/rag/evaluate")
    public Mono<ApiResponse<Object>> ragEvaluate(@RequestBody String body) {
        return forwardPost("/api/rag/evaluate", body);
    }

    @PostMapping("/rag/debug")
    public Mono<ApiResponse<Object>> ragDebug(ServerWebExchange exchange,
                                              @RequestBody(required = false) String body) {
        String query = exchange.getRequest().getQueryParams().getFirst("query");
        String k = exchange.getRequest().getQueryParams().getFirst("k");
        return forwardPost("/api/rag/debug?query=" + encode(query == null ? "" : query)
                + "&k=" + encode(k == null ? "3" : k), body);
    }

    // ---------------- LLM 成本 / 混沌 ----------------

    @GetMapping("/llm/cost")
    public Mono<ApiResponse<Object>> llmCost() {
        return forwardGet("/api/llm/cost");
    }

    @GetMapping("/chaos/run")
    public Mono<ApiResponse<Object>> chaosRun(ServerWebExchange exchange) {
        String llm = exchange.getRequest().getQueryParams().getFirst("llm_judge");
        return forwardGet("/api/chaos/run?llm_judge=" + (llm == null ? "false" : llm));
    }

    @GetMapping("/chaos/adversarial")
    public Mono<ApiResponse<Object>> chaosAdversarial(ServerWebExchange exchange) {
        String llm = exchange.getRequest().getQueryParams().getFirst("llm_judge");
        return forwardGet("/api/chaos/adversarial?llm_judge=" + (llm == null ? "false" : llm));
    }

    // ---------------- 转发实现 ----------------

    private Mono<ApiResponse<Object>> forwardGet(String path) {
        return forwardGet(path, null);
    }

    private Mono<ApiResponse<Object>> forwardGet(String path, ServerWebExchange exchange) {
        StringBuilder url = new StringBuilder(agentBaseUrl).append(path);
        if (exchange != null) {
            String query = exchange.getRequest().getURI().getRawQuery();
            if (query != null && !query.isBlank()) {
                url.append('?').append(query);
            }
        }
        return webClient.get()
                .uri(URI.create(url.toString()))
                .header("X-Internal-Token", internalToken)
                .retrieve()
                .bodyToMono(String.class)
                .map(this::wrap)
                .onErrorResume(e -> Mono.just(ApiResponse.error(502, "Python RAG 服务不可用: " + e.getMessage())));
    }

    private Mono<ApiResponse<Object>> forwardPost(String path) {
        return forwardPost(path, "{}");
    }

    private Mono<ApiResponse<Object>> forwardPost(String path, String body) {
        return webClient.post()
                .uri(URI.create(agentBaseUrl + path))
                .header("X-Internal-Token", internalToken)
                .header("Content-Type", "application/json")
                .bodyValue(body == null || body.isBlank() ? "{}" : body)
                .retrieve()
                .bodyToMono(String.class)
                .map(this::wrap)
                .onErrorResume(e -> Mono.just(ApiResponse.error(502, "Python RAG 服务不可用: " + e.getMessage())));
    }

    /** 把 Python JSON 解析成对象放进 ApiResponse.data，保证前端 res.code 通读正常。 */
    private ApiResponse<Object> wrap(String json) {
        try {
            if (json == null || json.isBlank()) {
                return ApiResponse.success(null);
            }
            JsonNode node = MAPPER.readTree(json);
            if (node.isObject() && node.has("ok") && node.path("ok").asBoolean(false)) {
                return ApiResponse.success(MAPPER.convertValue(node, Object.class));
            }
            if (node.isObject() && node.has("ok") && !node.path("ok").asBoolean(true)) {
                return ApiResponse.error(400, node.path("error").asText("Python 服务返回失败"));
            }
            return ApiResponse.success(MAPPER.convertValue(node, Object.class));
        } catch (Exception e) {
            return ApiResponse.success(json);
        }
    }

    private String encode(String value) {
        try {
            return URLEncoder.encode(value, StandardCharsets.UTF_8);
        } catch (Exception e) {
            return "";
        }
    }
}