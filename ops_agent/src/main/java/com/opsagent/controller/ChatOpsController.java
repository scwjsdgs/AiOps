package com.opsagent.controller;

import com.opsagent.utils.JsonUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;
import org.springframework.web.reactive.function.client.WebClient;

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

    private final WebClient webClient;

    @Value("${opsagent.agent.url:http://localhost:5000}")
    private String agentBaseUrl;
    // 注意键名是 opsagent.internal.token（internal 下挂 token），不是 agent.internal-token
    @Value("${opsagent.internal.token:dev-internal-token}")
    private String internalToken;

    @PostMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<String>> stream(@RequestBody Map<String, Object> body) {
        String message = String.valueOf(body.getOrDefault("message", ""));
        String taskId = String.valueOf(body.getOrDefault("taskId", "chatops-" + UUID.randomUUID()));
        if (message.isBlank()) {
            return Flux.just(ServerSentEvent.<String>builder()
                    .event("error").data(JsonUtils.toJson(Map.of("type", "error", "data", "message 不能为空"))).build());
        }

        Map<String, Object> forward = new HashMap<>();
        forward.put("taskId", taskId);
        forward.put("message", message);

        return webClient.post()
                .uri(agentBaseUrl + "/api/agent/stream")
                .header("X-Internal-Token", internalToken)
                .bodyValue(forward)
                .retrieve()
                .bodyToFlux(String.class)
                .map(raw -> ServerSentEvent.<String>builder().data(raw).build())
                .timeout(Duration.ofMinutes(5))
                .onErrorResume(e -> Flux.just(ServerSentEvent.<String>builder()
                        .event("error")
                        .data(JsonUtils.toJson(Map.of("type", "error", "data", "agent 不可用: " + e.getMessage()))).build()));
    }
}