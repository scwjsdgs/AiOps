package com.opsagent.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

import java.util.List;
import java.util.Map;

/**
 * Alertmanager webhook 负载的结构映射。
 *
 * Alertmanager POST 过来的真实格式（字段名是官方固定的，不能改）：
 * {
 *   "version": "4",
 *   "groupKey": "...",
 *   "status": "firing",
 *   "receiver": "aiops",
 *   "alerts": [
 *     {
 *       "status": "firing",
 *       "labels": { "alertname": "...", "severity": "critical", "service": "...", "namespace": "..." },
 *       "annotations": { "summary": "...", "description": "..." },
 *       "startsAt": "...", "endsAt": "...", "generatorURL": "..."
 *     }
 *   ]
 * }
 *
 * 用 JsonIgnoreProperties 忽略未知字段：Alertmanager 版本升级会加字段，
 * 反序列化直接失败等于整条告警被丢。
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class AlertmanagerWebhookPayload {

    private String version;
    private String groupKey;
    /** firing | resolved（组级状态） */
    private String status;
    private String receiver;
    private List<AmAlert> alerts;

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class AmAlert {
        /** firing | resolved（单条状态，与组级 status 不一致时以此为准） */
        private String status;
        private Map<String, String> labels;
        private Map<String, String> annotations;
        private String startsAt;
        private String endsAt;
        private String generatorURL;
    }
}
