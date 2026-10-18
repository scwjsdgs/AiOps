package com.opsagent.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

/**
 * Python AI 大脑通过 Kafka 请求 Java 执行运维工具的消息体。
 * requestId 由 Python 侧生成，用于并发场景下把结果精确匹配回等待方；
 * contextId 与 HTTP 通道的 ToolExecutionRequest.contextId 语义一致。
 */
@Data
@AllArgsConstructor
@NoArgsConstructor
public class KafkaToolRequestMessage {
    private String requestId;
    private String taskId;
    private String toolName;
    private Map<String, Object> parameters;
    private String contextId;
}