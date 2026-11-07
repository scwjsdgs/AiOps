package com.opsagent.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Map;

/**
 * 指纹/去重服务（从 AlertWebhookService 抽出，纯结构重构，逻辑不变）。
 *
 * 职责：
 * - buildFingerprint(labels)：读配置 fingerprintKeys，纯字符串拼接；
 * - tryDedup(fingerprint)：Redis {@code webhook:dup:{fp}} SETNX，命中返回 false，未命中返回 true；
 *   Redis 异常按未命中放行（宁可重复也不吞告警）；
 * - releaseAndReoccupy(fingerprint)：命中后删键再 SET 续 TTL（给“打破去重”用）。
 *
 * 只做 Redis 动作，不靠 DB，不依赖任务状态。“是否打破去重”的判定留在编排层
 *（AlertWebhookService 内部调用 CorrelationService.taskCanMerge）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FingerprintService {

    private final StringRedisTemplate redisTemplate;

    /** 去重键 TTL（分钟）。 */
    @Value("${opsagent.webhook.idempotency.ttlMinutes:5}")
    private long idempotencyTtlMinutes;

    /** 指纹字段键列表，逗号分隔。 */
    @Value("${opsagent.webhook.fingerprint-keys:alertname,instance,severity}")
    private String fingerprintKeys;

    /**
     * 构建告警指纹：按配置的 fingerprintKeys 从 labels 中取值并纯字符串拼接。
     */
    public String buildFingerprint(Map<String, String> labels) {
        Map<String, String> l = labels != null ? labels : Map.of();
        String[] keys = fingerprintKeys.split(",");
        StringBuilder sb = new StringBuilder();
        for (String k : keys) {
            String v = l.getOrDefault(k.trim(), "");
            sb.append(v).append("|");
        }
        return sb.toString();
    }

    /**
     * 幂等去重：SETNX 占住 webhook:dup:{fp}。
     *
     * @return true = 未命中（键已占位，本帧可处理）；false = 命中（5 分钟内已处理过，应去重忽略）。
     *         Redis 异常按未命中放行，宁可重复也不吞告警。
     */
    public boolean tryDedup(String fingerprint) {
        String dedupKey = "webhook:dup:" + fingerprint;
        try {
            Boolean set = redisTemplate.opsForValue().setIfAbsent(dedupKey, "1",
                    Duration.ofMinutes(idempotencyTtlMinutes));
            return Boolean.TRUE.equals(set);
        } catch (Exception e) {
            // Redis 挂了/超时：绝不能让去重键异常把告警吞掉。按「键未占用」放行。
            log.warn("[Fingerprint] Redis 幂等去重异常（放行本帧） dedupKey={}: {}", dedupKey, e.getMessage());
            return true;
        }
    }

    /**
     * 打破去重：删除占位键并重新 SET 续 TTL，防止并发重发把键再次占住。
     */
    public void releaseAndReoccupy(String fingerprint) {
        String dedupKey = "webhook:dup:" + fingerprint;
        try {
            redisTemplate.delete(dedupKey);
            redisTemplate.opsForValue().set(dedupKey, "1", Duration.ofMinutes(idempotencyTtlMinutes));
        } catch (Exception e) {
            log.warn("[Fingerprint] 释放并重占去重键失败 dedupKey={}: {}", dedupKey, e.getMessage());
        }
    }
}
