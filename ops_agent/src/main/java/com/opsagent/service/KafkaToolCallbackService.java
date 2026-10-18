package com.opsagent.service;

import com.opsagent.model.KafkaToolRequestMessage;
import com.opsagent.model.KafkaToolResultMessage;
import com.opsagent.model.ToolExecutionRequest;
import com.opsagent.model.ToolExecutionResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Kafka 工具调用事件驱动通道。
 *
 * 双通道原则：HTTP 回调（/api/agent/callback/tool）保留不动，
 * 本服务只是新增一条 agent-tool-request -> 执行 -> agent-tool-result 的旁路。
 * Python 侧优先走 Kafka，发送/消费/等待任一环节异常时自动回退 HTTP。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class KafkaToolCallbackService {

    private final ToolRegistryService toolRegistry;
    private final KafkaTemplate<String, Object> kafkaTemplate;

    @Value("${opsagent.kafka.tool-result-topic:agent-tool-result}")
    private String resultTopic;

    @KafkaListener(topics = "${opsagent.kafka.tool-request-topic:agent-tool-request}", groupId = "agent-tool-group")
    public void consumeRequest(String message) {
        KafkaToolRequestMessage request = null;
        try {
            request = com.opsagent.utils.JsonUtils.fromJson(message, KafkaToolRequestMessage.class);
        } catch (Exception e) {
            log.error("Kafka 工具请求反序列化失败 message={}", message, e);
        }
        if (request == null || request.getToolName() == null || request.getToolName().isBlank()) {
            log.error("Kafka 工具请求无效，跳过并回复失败 requestId={}", request == null ? "unknown" : request.getRequestId());
            publishResult(buildFailure(request, "invalid tool request", 0L));
            return;
        }
        if (request.getRequestId() == null || request.getRequestId().isBlank()) {
            request.setRequestId(UUID.randomUUID().toString());
        }
        // contextId 同时放进 parameters，与 HTTP 通道保持一致的审计/备注关联
        Map<String, Object> parameters = request.getParameters() != null
                ? new LinkedHashMap<>(request.getParameters())
                : new LinkedHashMap<>();
        String contextId = request.getContextId() != null ? request.getContextId() : request.getTaskId();
        if (contextId != null && !contextId.isBlank()) {
            parameters.putIfAbsent("contextId", contextId);
        }

        ToolExecutionRequest executionRequest = new ToolExecutionRequest();
        executionRequest.setToolName(request.getToolName());
        executionRequest.setParameters(parameters);
        executionRequest.setContextId(contextId);
        ToolExecutionResult execution = toolRegistry.execute(executionRequest, true);

        KafkaToolResultMessage result = new KafkaToolResultMessage(
                request.getRequestId(),
                request.getTaskId(),
                execution.isSuccess(),
                execution.getMessage(),
                toMap(execution.getData()),
                execution.getDurationMs());
        publishResult(result);
    }

    private void publishResult(KafkaToolResultMessage result) {
        try {
            // 同步等待发送结果，能立刻发现序列化/broker 异常，
            // 不会因为异步回调没触发而丢失错误现场
            var sendResult = kafkaTemplate.send(resultTopic, result.getRequestId(), result).get(5, java.util.concurrent.TimeUnit.SECONDS);
            log.info("Kafka 工具结果已发送 requestId={} toolSuccess={} offset={}",
                    result.getRequestId(), result.isSuccess(), sendResult.getRecordMetadata().offset());
        } catch (Exception e) {
            log.error("Kafka 工具结果发送异常 requestId={} topic={}", result.getRequestId(), resultTopic, e);
        }
    }

    private KafkaToolResultMessage buildFailure(KafkaToolRequestMessage request, String message, long durationMs) {
        return new KafkaToolResultMessage(
                request == null ? null : request.getRequestId(),
                request == null ? null : request.getTaskId(),
                false,
                message,
                new LinkedHashMap<>(),
                durationMs);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> toMap(Object data) {
        if (data == null) {
            return new LinkedHashMap<>();
        }
        if (data instanceof Map) {
            return (Map<String, Object>) data;
        }
        return Map.of("value", data);
    }
}