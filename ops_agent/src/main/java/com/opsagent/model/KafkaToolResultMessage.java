package com.opsagent.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

/**
 * Java 执行完工具后发回 Python 的结果消息。
 * requestId 必须原样带回，Python 侧据此唤醒对应的 asyncio.Future。
 */
@Data
@AllArgsConstructor
@NoArgsConstructor
public class KafkaToolResultMessage {
    private String requestId;
    private String taskId;
    private boolean success;
    private String message;
    private Map<String, Object> data;
    private long durationMs;
}