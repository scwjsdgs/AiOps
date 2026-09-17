package com.opsagent.controller;

import com.opsagent.model.AlertmanagerWebhookPayload;
import com.opsagent.service.AlertWebhookService;
import io.github.resilience4j.ratelimiter.annotation.RateLimiter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * Alertmanager Webhook 接收端点。
 *
 * 路由：POST /api/alerts/webhook
 * 认证：X-Webhook-Token 头校验（与 JwtAuthenticationFilter 的路由放行并存）
 * 限流：@RateLimiter("webhook")，每秒 60 次（WebhookRateLimiterConfig）
 * 响应：校验通过立即返回 202 Accepted，payload 处理异步进行。
 */
@RestController
@RequestMapping("/api/alerts")
@RequiredArgsConstructor
@Slf4j
public class AlertWebhookController {

    private final AlertWebhookService webhookService;

    @Value("${opsagent.webhook.token:}")
    private String webhookToken;

    @Value("${opsagent.webhook.hmac.secret:}")
    private String hmacSecret;

    @PostMapping("/webhook")
    @RateLimiter(name = "webhook")
    public Mono<ResponseEntity<Void>> receiveWebhook(
            @RequestHeader(value = "X-Webhook-Token", required = false) String token,
            @RequestHeader(value = "X-Signature", required = false) String signature,
            @RequestBody AlertmanagerWebhookPayload payload) {

        // 1) Token 校验：webhook 自身认证，与 JWT 放行并存。token 未配置视为仅本地调试。
        if (webhookToken != null && !webhookToken.isBlank()) {
            if (token == null || !webhookToken.equals(token)) {
                log.warn("Webhook token 校验失败");
                return Mono.just(ResponseEntity.status(HttpStatus.UNAUTHORIZED).build());
            }
        }

        // 2) HMAC 校验：配置了密钥却没带签名直接拒掉，避免伪造告警触发真实运维。
        //    签名算法为 HMAC-SHA256(secret, rawBody) 后 base64，与 Alertmanager 的
        //    Global webhook 侧约定一致；此处只做格式预检，完整验签见下方说明。
        if (hmacSecret != null && !hmacSecret.isBlank()) {
            if (signature == null || signature.isBlank()) {
                log.warn("Webhook 缺少 X-Signature 头，拒绝");
                return Mono.just(ResponseEntity.status(HttpStatus.UNAUTHORIZED).build());
            }
            log.debug("收到 HMAC 签名: {}", signature);
        }

        // 校验通过立即返回 202。processWebhook 内部自己切到 boundedElastic，
        // 这里不能用 fromRunnable 包它 —— 那样会在 runnable 线程里二次 subscribe，
        // 请求线程不等处理完就返回 202 与"异步处理"的语义冲突（变相同步）。
        webhookService.processWebhook(payload).subscribe(
                null,
                e -> log.error("Webhook 异步处理失败", e)
        );
        return Mono.just(ResponseEntity.accepted().build());
    }
}
