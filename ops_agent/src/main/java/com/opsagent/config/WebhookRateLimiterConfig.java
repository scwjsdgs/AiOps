package com.opsagent.config;

import io.github.resilience4j.ratelimiter.RateLimiter;
import io.github.resilience4j.ratelimiter.RateLimiterConfig;
import io.github.resilience4j.ratelimiter.RateLimiterRegistry;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

/**
 * Webhook 入口限流 bean。
 *
 * Alertmanager 在告警风暴时会短时间堆积大量 webhook 请求，
 * 限流避免瞬间压垮 AlertIngestion + AI 推理链路。
 */
@Configuration
public class WebhookRateLimiterConfig {

    @Bean
    public RateLimiterRegistry rateLimiterRegistry() {
        RateLimiterConfig config = RateLimiterConfig.custom()
                .timeoutDuration(Duration.ofMillis(200))
                .limitForPeriod(60)          // 每秒 60 次
                .limitRefreshPeriod(Duration.ofSeconds(1))
                .build();
        return RateLimiterRegistry.of(config);
    }

    @Bean
    public RateLimiter webhookRateLimiter(RateLimiterRegistry registry) {
        return registry.rateLimiter("webhook");
    }
}
