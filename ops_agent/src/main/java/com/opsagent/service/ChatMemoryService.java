package com.opsagent.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.ReactiveRedisTemplate;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * ChatOps 多轮对话 Memory（Redis List）。
 *
 * 网关在发起新的流式提问前，只取最近 5 轮上下发给 Python Agent；
 * 流正常结束后把本轮 user + assistant 消息异步追加回 Redis，并给整个
 * Memory 续上 TTL。前端主动断开时不落这轮消息，也不会中断历史。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ChatMemoryService {

    private static final String MEMORY_PREFIX = "chatops:memory:";
    private static final Duration MEMORY_TTL = Duration.ofMinutes(30);
    private static final int MAX_ROUNDS = 5;
    private static final int MAX_MESSAGES = MAX_ROUNDS * 2;

    private final ReactiveRedisTemplate<String, Object> redisTemplate;
    private final ObjectMapper objectMapper;

    public Mono<List<Map<String, String>>> recent(String username, String sessionId) {
        if (username == null || username.isBlank() || sessionId == null || sessionId.isBlank()) {
            return Mono.just(List.of());
        }
        return redisTemplate.opsForList()
                .range(key(username, sessionId), -MAX_MESSAGES, -1)
                .cast(String.class)
                .collectList()
                .map(ChatMemoryService::parseMessages)
                .onErrorResume(e -> {
                    log.warn("读取 ChatOps Memory 失败 user={} session={}: {}",
                            username, sessionId, e.getMessage());
                    return Mono.just(List.of());
                });
    }

    public void append(String username, String sessionId, Map<String, String> userMsg,
                       Map<String, String> assistantMsg) {
        appendAsync(username, sessionId, List.of(userMsg, assistantMsg));
    }

    public void appendAsync(String username, String sessionId, List<Map<String, String>> messages) {
        if (username == null || username.isBlank() || sessionId == null || sessionId.isBlank()) {
            return;
        }
        List<String> jsonList = new ArrayList<>();
        for (Map<String, String> msg : messages) {
            if (msg == null || msg.get("content") == null || msg.get("content").isBlank()) {
                continue;
            }
            try {
                jsonList.add(objectMapper.writeValueAsString(msg));
            } catch (Exception e) {
                log.warn("序列化 ChatOps Memory 消息失败: {}", e.getMessage());
            }
        }
        if (jsonList.isEmpty()) {
            return;
        }

        String key = key(username, sessionId);
        redisTemplate.opsForList().rightPushAll(key, jsonList.toArray(new String[0]))
                .then(redisTemplate.expire(key, MEMORY_TTL))
                .then(redisTemplate.opsForList().trim(key, -MAX_MESSAGES, -1))
                .subscribe(
                        null,
                        e -> log.warn("写入 ChatOps Memory 失败 user={} session={}: {}",
                                username, sessionId, e.getMessage())
                );
    }

    public Mono<Boolean> delete(String username, String sessionId) {
        if (username == null || username.isBlank() || sessionId == null || sessionId.isBlank()) {
            return Mono.just(false);
        }
        return redisTemplate.delete(key(username, sessionId))
                .map(deleted -> deleted > 0)
                .onErrorReturn(false);
    }

    private static List<Map<String, String>> parseMessages(List<String> rows) {
        List<Map<String, String>> result = new ArrayList<>();
        if (rows == null) {
            return result;
        }
        ObjectMapper mapper = new ObjectMapper();
        for (String row : rows) {
            if (row == null || row.isBlank()) {
                continue;
            }
            try {
                @SuppressWarnings("unchecked")
                Map<String, Object> obj = mapper.readValue(row, Map.class);
                Map<String, String> msg = new LinkedHashMap<>();
                msg.put("role", String.valueOf(obj.getOrDefault("role", "user")));
                msg.put("content", String.valueOf(obj.getOrDefault("content", "")));
                if (!msg.get("content").isBlank()) {
                    result.add(msg);
                }
            } catch (Exception e) {
                log.debug("解析 ChatOps Memory 消息失败: {}", e.getMessage());
            }
        }
        return result;
    }

    private String key(String username, String sessionId) {
        return MEMORY_PREFIX + username + ":" + sessionId;
    }
}