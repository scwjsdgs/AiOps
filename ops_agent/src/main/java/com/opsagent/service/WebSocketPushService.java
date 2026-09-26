package com.opsagent.service;


import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.opsagent.model.AgentMessage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * WebSocket 推送中枢。
 *
 * 关键设计：这里**不再持有 WebSocketSession**，每个连接拿到一个自己的 Sinks.Many，
 * 由 AgentWebSocketHandler 把它和心跳 merge 成唯一一条出站流。
 *
 * 改造前 pushMessage 里直接 session.send(...).subscribe()，与 handler 里的
 * session.send(pingFlux) 并发写同一个会话，Reactor 会抛
 * IllegalStateException: Concurrent send not allowed —— agent 每推进一步就炸一次。
 * 而且那次 send 的返回值被丢弃，失败也没人知道。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WebSocketPushService {

    /** taskId -> 订阅该任务的所有连接。一个任务可能同时被多个页面订阅。 */
    private final Map<String, Set<Sinks.Many<String>>> sinks = new ConcurrentHashMap<>();

    /**
     * 全局订阅者：不绑定任何 taskId，用于接收审批这类"与具体任务无关、
     * 所有页面都该知道"的事件。
     *
     * 审批必须广播：agent 发起审批时用户很可能停在仪表板或告警页，
     * 只推给该任务的订阅者等于没推——没人会恰好在任务详情页等着。
     */
    private final Set<Sinks.Many<String>> broadcastSinks = ConcurrentHashMap.newKeySet();

    /** 广播频道标识：前端连 /ws/agent（不带 taskId）即订阅全部广播事件。 */
    public static final String BROADCAST_CHANNEL = "broadcast";

    private final Sinks.Many<AgentMessage> messageSink = Sinks.many().replay().latest();
    private final ObjectMapper objectMapper;

    /**
     * 注册一个连接，返回它专属的出站 sink。
     *
     * 每个连接都同时加入广播集合：审批事件必须让所有人看到，
     * 不管他当前停在哪个页面、订阅的是哪个 taskId。
     */
    public Sinks.Many<String> register(String taskId) {
        Sinks.Many<String> sink = Sinks.many().unicast().onBackpressureBuffer();
        sinks.computeIfAbsent(taskId, k -> ConcurrentHashMap.newKeySet()).add(sink);
        broadcastSinks.add(sink);
        return sink;
    }

    public void unregister(String taskId, Sinks.Many<String> sink) {
        broadcastSinks.remove(sink);
        Set<Sinks.Many<String>> set = sinks.get(taskId);
        if (set == null) {
            return;
        }
        set.remove(sink);
        if (set.isEmpty()) {
            sinks.remove(taskId, set);
        }
    }

    /**
     * 向所有连接广播一条消息（不区分 taskId）。
     * 审批创建/决定都走这里——用户在任何页面都应立即收到。
     */
    public void broadcast(AgentMessage message) {
        messageSink.tryEmitNext(message);

        if (broadcastSinks.isEmpty()) {
            // 没人订阅广播频道（例如只在任务详情页）：不是错误，事件仍写进了库和审计
            log.debug("广播 {} 时无全局订阅者，跳过实时推送", message.getType());
            return;
        }

        String json;
        try {
            json = objectMapper.writeValueAsString(message);
        } catch (JsonProcessingException e) {
            log.error("序列化广播消息失败 type={}", message.getType(), e);
            return;
        }

        for (Sinks.Many<String> sink : broadcastSinks) {
            Sinks.EmitResult result = sink.tryEmitNext(json);
            if (result.isFailure()) {
                log.warn("广播推送失败 type={} result={}", message.getType(), result);
            }
        }
    }

    public void pushMessage(String taskId, AgentMessage message) {
        // 无论有没有 WebSocket 订阅者，都往广播流里放一份，供其它订阅者消费
        messageSink.tryEmitNext(message);

        Set<Sinks.Many<String>> set = sinks.get(taskId);
        if (set == null || set.isEmpty()) {
            // 前端没连着不是错误：agent 照常推理，结果仍会写进 Task
            log.debug("任务 {} 当前没有 WebSocket 订阅者，跳过推送", taskId);
            return;
        }

        String json;
        try {
            json = objectMapper.writeValueAsString(message);
        } catch (JsonProcessingException e) {
            log.error("序列化 AgentMessage 失败，taskId={}", taskId, e);
            return;
        }

        for (Sinks.Many<String> sink : set) {
            Sinks.EmitResult result = sink.tryEmitNext(json);
            if (result.isFailure()) {
                log.warn("推送失败 taskId={} result={}", taskId, result);
            }
        }
    }

    // 广播消息（可选）
    public Flux<AgentMessage> getMessageStream() {
        return messageSink.asFlux();
    }
}
