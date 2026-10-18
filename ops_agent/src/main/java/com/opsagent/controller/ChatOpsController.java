package com.opsagent.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.opsagent.dto.ApiResponse;
import com.opsagent.service.ChatSessionService;
import com.opsagent.utils.JsonUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import org.springframework.web.reactive.function.client.WebClient;
import java.util.concurrent.TimeoutException;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * ChatOps 交互式诊断。
 *
 * 前端对话窗口 -> Java（JWT 鉴权） -> Python /api/agent/stream（SSE）。
 *
 * 之所以经 Java 中转而不是前端直连 Python：
 * 1) Python 用 X-Internal-Token 鉴权，前端不该持有内部密钥——密钥一出 Java 就失控；
 * 2) 前端已有 JWT 通道与 /api 代理，浏览器跨域/代理问题最少。
 * Java 在这里只是流式代理，不落库——ChatOps 是「问一句」的临时会话。
 */
@Slf4j
@RestController
@RequestMapping("/api/chatops")
@RequiredArgsConstructor
public class ChatOpsController {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final WebClient webClient;
    private final ChatSessionService chatSessionService;

    @Value("${opsagent.agent.url:http://localhost:5000}")
    private String agentBaseUrl;
    // 注意键名是 opsagent.internal.token（internal 下挂 token），不是 agent.internal-token
    @Value("${opsagent.internal.token:dev-internal-token}")
    private String internalToken;

    @PostMapping("/sessions")
    public Mono<ApiResponse<Object>> createSession(ServerWebExchange exchange) {
        String username = exchange.getAttributes().getOrDefault("username", "unknown").toString();
        return chatSessionService.create(username)
                .map(s -> ApiResponse.success((Object) s));
    }

    @GetMapping("/sessions")
    public Mono<ApiResponse<Object>> listSessions(ServerWebExchange exchange) {
        String username = exchange.getAttributes().getOrDefault("username", "unknown").toString();
        return chatSessionService.list(username)
                .map(s -> ApiResponse.success((Object) s));
    }

    @GetMapping("/sessions/{sessionId}")
    public Mono<ApiResponse<Object>> getSession(@PathVariable String sessionId,
                                                ServerWebExchange exchange) {
        String username = exchange.getAttributes().getOrDefault("username", "unknown").toString();
        return chatSessionService.has(username, sessionId)
                .flatMap(owned -> {
                    if (!owned) {
                        return Mono.just(ApiResponse.error(403, "无权访问该会话"));
                    }
                    return webClient.get()
                            .uri(agentBaseUrl + "/api/chatops/sessions/" + sessionId
                                    + "?username=" + username)
                            .header("X-Internal-Token", internalToken)
                            .retrieve()
                            .bodyToMono(String.class)
                            .map(this::wrapPythonData)
                            .onErrorResume(e -> Mono.just(ApiResponse.error(502,
                                    "Python ChatOps 会话服务不可用: " + e.getMessage())));
                });
    }

    private ApiResponse<Object> wrapPythonData(String json) {
        try {
            if (json == null || json.isBlank()) {
                return ApiResponse.success(null);
            }
            JsonNode node = MAPPER.readTree(json);
            if (node.isObject() && node.has("data")) {
                return ApiResponse.success(MAPPER.convertValue(node.path("data"), Object.class));
            }
            return ApiResponse.success(MAPPER.convertValue(node, Object.class));
        } catch (Exception e) {
            return ApiResponse.success(json);
        }
    }

    @PostMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<String>> stream(@RequestBody Map<String, Object> body,
                                                ServerWebExchange exchange) {
        String message = String.valueOf(body.getOrDefault("message", ""));
        String taskId = String.valueOf(body.getOrDefault("taskId", "chatops-" + UUID.randomUUID()));
        String sessionId = String.valueOf(body.getOrDefault("sessionId", ""));
        String username = exchange.getAttributes().getOrDefault("username", "unknown").toString();
        if (message.isBlank()) {
            return Flux.just(ServerSentEvent.<String>builder()
                    .event("error").data(JsonUtils.toJson(Map.of("type", "error", "content", "message 不能为空"))).build());
        }
        if (sessionId.isBlank()) {
            sessionId = "chat-" + UUID.randomUUID();
        }
        String finalUsername = username;
        String finalSessionId = sessionId;
        chatSessionService.touch(finalUsername, finalSessionId, message)
                .doOnError(e -> log.warn("记录 ChatOps 会话失败 username={} sessionId={}: {}",
                        finalUsername, finalSessionId, e.getMessage()))
                .subscribe();

        Map<String, Object> forward = new HashMap<>();
        forward.put("taskId", taskId);
        forward.put("message", message);
        forward.put("sessionId", sessionId);

        return webClient.post()
                .uri(agentBaseUrl + "/api/agent/stream")
                .header("X-Internal-Token", internalToken)
                .bodyValue(forward)
                .retrieve()
                .bodyToFlux(ServerSentEvent.class)
                .map(sse -> {
                    Object data = sse.data();
                    String event = sse.event();
                    return ServerSentEvent.<String>builder()
                            .event(event)
                            .data(data == null ? "" : String.valueOf(data))
                            .build();
                })
                .timeout(Duration.ofMinutes(5))
                .onErrorResume(e -> {
                    String content = "agent 不可用: " + e.getMessage();
                    if (e instanceof TimeoutException) {
                        content = "大模型推理超时，已终止本次分析。";
                    }
                    return Flux.just(
                            ServerSentEvent.<String>builder().event("error")
                                    .data(JsonUtils.toJson(Map.of("type", "error", "content", content))).build(),
                            ServerSentEvent.<String>builder().event("end")
                                    .data(JsonUtils.toJson(Map.of("type", "end", "content", "处理已结束"))).build());
                });
    }

    }