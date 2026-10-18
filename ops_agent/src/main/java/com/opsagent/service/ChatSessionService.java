package com.opsagent.service;

import com.opsagent.utils.JsonUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.ReactiveRedisTemplate;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * ChatOps 多会话索引：按用户把 session_id、摘要、更新时间保存在 Redis。
 *
 * 实际对话历史由 Python LangGraph Checkpointer 按 thread_id=session_id 持久化，
 * Java 只维护用户可见的会话列表（按用户隔离），不保存大段对话内容。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ChatSessionService {

    private static final String SESSION_PREFIX = "chatops:user:";
    private static final int MAX_SESSIONS = 50;

    private final ReactiveRedisTemplate<String, Object> redisTemplate;

    public Mono<Map<String, Object>> create(String username) {
        String sessionId = "chat-" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        return touchInternal(username, sessionId, "新对话")
                .thenReturn(Map.of(
                        "session_id", sessionId,
                        "title", "新对话",
                        "updated_at", Instant.now().toString()
                ));
    }

    public Mono<List<Map<String, Object>>> list(String username) {
        return redisTemplate.opsForHash().values(key(username))
                .cast(String.class)
                .collectList()
                .map(rows -> {
                    Map<String, Map<String, Object>> byId = new LinkedHashMap<>();
                    for (String row : rows) {
                        try {
                            @SuppressWarnings("unchecked")
                            Map<String, Object> meta = (Map<String, Object>) JsonUtils.fromJson(row, Map.class);
                            if (meta == null) {
                                continue;
                            }
                            String sid = String.valueOf(meta.getOrDefault("session_id", ""));
                            if (!sid.isBlank()) {
                                byId.put(sid, meta);
                            }
                        } catch (Exception e) {
                            log.warn("解析 ChatOps 会话元数据失败：{}", e.getMessage());
                        }
                    }
                    return byId.values().stream()
                            .sorted((a, b) -> String.valueOf(b.getOrDefault("updated_at", ""))
                                    .compareTo(String.valueOf(a.getOrDefault("updated_at", ""))))
                            .limit(MAX_SESSIONS)
                            .toList();
                })
                .onErrorResume(e -> {
                    log.warn("读取 ChatOps 会话列表失败 username={}: {}", username, e.getMessage());
                    return Mono.just(List.of());
                });
    }

    public Mono<Void> touch(String username, String sessionId, String title) {
        return touchInternal(username, sessionId, title);
    }

    public Mono<Boolean> has(String username, String sessionId) {
        if (username == null || username.isBlank() || sessionId == null || sessionId.isBlank()) {
            return Mono.just(false);
        }
        return redisTemplate.opsForHash().hasKey(key(username), sessionId)
                .onErrorReturn(false);
    }

    private Mono<Void> touchInternal(String username, String sessionId, String title) {
        if (username == null || username.isBlank() || sessionId == null || sessionId.isBlank()) {
            return Mono.empty();
        }
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("session_id", sessionId);
        meta.put("title", title == null || title.isBlank() ? "新对话"
                : (title.length() > 30 ? title.substring(0, 30) + "…" : title));
        meta.put("updated_at", Instant.now().toString());
        String json;
        try {
            json = new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(meta);
        } catch (Exception e) {
            log.warn("序列化 ChatOps 会话元数据失败：{}", e.getMessage());
            return Mono.empty();
        }
        return redisTemplate.opsForHash().put(key(username), sessionId, json)
                .then();
    }

    private String key(String username) {
        return SESSION_PREFIX + username;
    }
}