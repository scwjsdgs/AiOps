package com.opsagent.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.opsagent.dto.ApiResponse;
import com.opsagent.model.Task;
import com.opsagent.service.ChatMemoryService;
import com.opsagent.service.ChatSessionService;
import com.opsagent.service.TaskSchedulerService;
import com.opsagent.utils.JsonUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeoutException;

/**
 * ChatOps 高并发流式网关。
 *
 * 前端对话窗口 -> Java（JWT 鉴权 + Redis Memory + SSE 代理） -> Python /api/agent/stream。
 *
 * Java 只负责：
 *  1. JWT/Webhook 鉴权；
 *  2. Redis 按 userId+sessionId 保存最近 5 轮对话 Memory，TTL 30 分钟；
 *  3. 用 WebClient 非阻塞转发给 Python Agent；
 *  4. 接收 Python SSE 事件原样透传给前端；
 *  5. 正常结束时异步将本轮 user/assistant 写入 Redis Memory；
 *  6. 前端断开时调用 Python /api/agent/cancel/{taskId} 级联取消推理。
 */
@Slf4j
@RestController
@RequestMapping("/api/chatops")
@RequiredArgsConstructor
public class ChatOpsController {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final WebClient webClient;
    private final ChatSessionService chatSessionService;
    private final ChatMemoryService chatMemoryService;
    private final TaskSchedulerService taskScheduler;

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

    @DeleteMapping("/sessions/{sessionId}")
    public Mono<ApiResponse<Object>> deleteSession(@PathVariable String sessionId,
                                                   ServerWebExchange exchange) {
        String username = exchange.getAttributes().getOrDefault("username", "unknown").toString();
        return chatSessionService.has(username, sessionId)
                .flatMap(owned -> {
                    if (!owned) {
                        return Mono.just(ApiResponse.error(403, "无权访问该会话"));
                    }
                    return chatSessionService.delete(username, sessionId)
                            .flatMap(removed -> {
                                if (!removed) {
                                    return Mono.just(ApiResponse.error(404, "会话不存在"));
                                }
                                chatMemoryService.delete(username, sessionId).subscribe();
                                webClient.delete()
                                        .uri(agentBaseUrl + "/api/chatops/sessions/" + sessionId
                                                + "?username=" + username)
                                        .header("X-Internal-Token", internalToken)
                                        .retrieve()
                                        .bodyToMono(String.class)
                                        .subscribe(null, e -> log.warn(
                                                "Python ChatOps 会话历史清理失败 sessionId={}: {}",
                                                sessionId, e.getMessage()));
                                return Mono.just(ApiResponse.success(Map.of(
                                        "session_id", sessionId,
                                        "deleted", true
                                )));
                            });
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
            return errorSse("message 不能为空");
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

        Task task = new Task();
        task.setId(taskId);
        task.setType("CHATOPS");
        task.setAlertId(null);
        task.setStatus("RUNNING");
        task.setInput(message);
        task.setOutput("");
        task.setAgentSteps(new ArrayList<>());
        task.setCreatedBy(username);
        task.setCreatedAt(java.time.LocalDateTime.now());
        task.setUpdatedAt(java.time.LocalDateTime.now());
        return taskScheduler.createOrReplace(task)
                .flatMapMany(saved -> {
                    if (!saved) {
                        return errorSse("任务创建失败，请稍后重试");
                    }
                    return chatMemoryService.recent(finalUsername, finalSessionId)
                            .flatMapMany(history -> forwardToAgent(taskId, message, finalSessionId,
                                    history, finalUsername));
                })
                .onErrorResume(e -> {
                    log.warn("ChatOps 任务 {} 启动失败: {}", taskId, e.getMessage());
                    return errorSse("任务创建失败，请稍后重试");
                });
    }

    private Flux<ServerSentEvent<String>> forwardToAgent(String taskId, String message,
                                                         String sessionId, List<Map<String, String>> history,
                                                         String username) {
        Map<String, Object> forward = new HashMap<>();
        forward.put("taskId", taskId);
        forward.put("message", message);
        forward.put("sessionId", sessionId);
        forward.put("history", history == null ? List.of() : history);

        java.util.concurrent.atomic.AtomicReference<List<String>> assistantTokensRef =
                new java.util.concurrent.atomic.AtomicReference<>(new ArrayList<>());
        return webClient.post()
                .uri(agentBaseUrl + "/api/agent/stream")
                .header("X-Internal-Token", internalToken)
                .bodyValue(forward)
                .accept(MediaType.TEXT_EVENT_STREAM)
                .retrieve()
                .bodyToFlux(String.class)
                .flatMap(json -> {
                    if (json == null || json.isBlank()) {
                        return Mono.just(json);
                    }
                    return Mono.just(json)
                            .doOnNext(raw -> trackEvent(taskId, raw, assistantTokensRef.get()))
                            .flatMap(raw -> {
                                if (isTerminalEvent(raw)) {
                                    return persistTerminal(taskId, raw)
                                            .thenReturn(raw);
                                }
                                return Mono.just(raw);
                            });
                })
                .doOnComplete(() -> {
                    // Python 侧已经把最终 report_token 发完，这里异步落 Redis Memory。
                    // 前端断开时 onCancel 分支不会走 doOnComplete。
                    persistMemoryAsync(username, sessionId, message, assistantTokensRef.get());
                })
                .map(json -> ServerSentEvent.<String>builder()
                        .data(json == null ? "" : json).build())
                .onErrorResume(e -> {
                    if (e instanceof java.util.concurrent.CancellationException
                            || reactor.core.Exceptions.isCancel(e)) {
                        // 前端主动断开/超时取消：通知 Python 停止推理，不落本轮 Memory。
                        log.info("ChatOps 前端断开，级联取消 task={}", taskId);
                        cancelAgentTask(taskId);
                        return Flux.empty();
                    }
                    String content = "agent 不可用: " + e.getMessage();
                    if (e instanceof TimeoutException) {
                        content = "大模型推理超时，已终止本次分析。";
                    }
                    return taskScheduler.completeTask(taskId, content, "FAILED")
                            .thenMany(Flux.just(
                                    ServerSentEvent.<String>builder().data(errorJson(content)).build(),
                                    ServerSentEvent.<String>builder().data(endJson()).build()));
                });
    }

    private void trackEvent(String taskId, String json, List<String> assistantTokens) {
        try {
            JsonNode evt = MAPPER.readTree(json);
            String type = evt.path("type").asText("");
            String content = evt.path("content").asText("");

            // text 是逐字 token，不逐条落 agentSteps，避免把推理摘要刷成几百条；
            // 只在内存累积最终报告，用于 Redis Memory 和任务快照。
            if ("text".equals(type) && !content.isBlank()) {
                assistantTokens.add(content);
                return;
            }

            // tool_start 只有 tool 字段没有 content，单独落一条“正在调用”。
            if ("tool_start".equals(type)) {
                String tool = evt.path("tool").asText("");
                if (!tool.isBlank()) {
                    taskScheduler.appendStep(taskId, "正在调用工具 " + tool)
                            .subscribe(null, e -> log.warn("ChatOps 任务 {} 步骤写入失败: {}", taskId, e));
                }
                return;
            }

            // tool_end/error/progress 等结构化事件才落步骤，便于任务页排查。
            if (!content.isBlank() && !"final".equals(type)) {
                taskScheduler.appendStep(taskId, type + " " + content)
                        .subscribe(null, e -> log.warn("ChatOps 任务 {} 步骤写入失败: {}", taskId, e));
            }
        } catch (Exception parseErr) {
            log.debug("ChatOps 事件截断，跳过步骤落库: {}", parseErr.getMessage());
        }
    }

    private boolean isTerminalEvent(String json) {
        if (json == null || json.isBlank()) {
            return false;
        }
        try {
            String type = MAPPER.readTree(json).path("type").asText("");
            return "end".equals(type) || "error".equals(type);
        } catch (Exception e) {
            return false;
        }
    }

    private Mono<Void> persistTerminal(String taskId, String json) {
        return Mono.fromCallable(() -> {
            JsonNode node = MAPPER.readTree(json);
            String type = node.path("type").asText("");
            String content = node.path("content").asText("");
            String status = "error".equals(type) ? "FAILED" : "SUCCESS";
            String output = content.isBlank()
                    ? ("error".equals(type) ? "处理失败" : "处理已结束")
                    : content;
            return new TerminalPayload(status, output);
        }).flatMap(payload -> taskScheduler.completeTask(taskId, payload.output, payload.status).then())
                .onErrorResume(e -> {
                    log.warn("ChatOps 终端状态持久化失败 taskId={}: {}", taskId, e.getMessage());
                    return Mono.empty();
                });
    }

    private void persistMemoryAsync(String username, String sessionId, String userMessage,
                                    List<String> assistantTokens) {
        if (assistantTokens == null || assistantTokens.isEmpty()) {
            return;
        }
        Map<String, String> userMsg = Map.of("role", "user", "content", userMessage);
        Map<String, String> assistantMsg = Map.of("role", "assistant", "content",
                String.join("", assistantTokens));
        chatMemoryService.appendAsync(username, sessionId, List.of(userMsg, assistantMsg));
    }

    private void cancelAgentTask(String taskId) {
        if (taskId == null || taskId.isBlank()) {
            return;
        }
        webClient.post()
                .uri(agentBaseUrl + "/api/agent/cancel/" + taskId)
                .header("X-Internal-Token", internalToken)
                .retrieve()
                .bodyToMono(String.class)
                .subscribe(
                        null,
                        e -> log.warn("取消 ChatOps 任务失败 taskId={}: {}", taskId, e.getMessage())
                );
    }

    private String errorJson(String content) {
        return JsonUtils.toJson(Map.of("type", "error", "content", content));
    }

    private String endJson() {
        return JsonUtils.toJson(Map.of("type", "end", "content", "处理已结束"));
    }

    private Flux<ServerSentEvent<String>> errorSse(String content) {
        return Flux.just(ServerSentEvent.<String>builder()
                .data(errorJson(content)).build());
    }

    private static class TerminalPayload {
        private final String status;
        private final String output;
        TerminalPayload(String status, String output) {
            this.status = status;
            this.output = output;
        }
    }
}
