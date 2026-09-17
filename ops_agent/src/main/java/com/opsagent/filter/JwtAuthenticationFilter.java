package com.opsagent.filter;

import com.opsagent.utils.JwtUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;

@Slf4j
@Component
@RequiredArgsConstructor
public class JwtAuthenticationFilter implements WebFilter {

    /** Python agent 回调时携带的内部共享密钥，与 opsagent.internal.token 对应 */
    private static final String INTERNAL_TOKEN_HEADER = "X-Internal-Token";

    private final JwtUtil jwtUtil;

    @Value("${opsagent.internal.token:dev-internal-token}")
    private String internalToken;

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        String path = exchange.getRequest().getPath().pathWithinApplication().value();
        HttpMethod method = exchange.getRequest().getMethod();

        // 1) CORS 预检请求不携带 Authorization，必须放行，
        //    否则浏览器的跨域请求会在预检阶段就被 401 挡掉
        if (HttpMethod.OPTIONS.equals(method)) {
            return chain.filter(exchange);
        }

        // 2) 登录接口
        if (path.equals("/api/auth/login")) {
            return chain.filter(exchange);
        }

        // 3) 健康检查。
        //    这里刻意不写成 startsWith("/actuator")：那会把 /actuator/env 一起放行，
        //    而 /actuator/env 能读到数据库密码等配置。
        if (path.startsWith("/actuator/health") || path.startsWith("/actuator/info")) {
            return chain.filter(exchange);
        }

        // 4) WebSocket 握手。浏览器的 WebSocket API 无法携带 Authorization 头，
        //    前端约定把 JWT 放在 query string 的 token 参数里（/ws/agent?taskId=xxx&token=yyy）。
        //    放空等于任何人连上就能看所有任务的推理过程，必须校验。
        if (path.equals("/ws/agent")) {
            String wsToken = exchange.getRequest().getHeaders().getFirst(HttpHeaders.AUTHORIZATION);
            if (wsToken == null || !wsToken.startsWith("Bearer ")) {
                // 没带头时从 query string 里取 token
                wsToken = queryParam(exchange, "token");
            }
            if (wsToken != null && wsToken.startsWith("Bearer ")) {
                wsToken = wsToken.substring(7);
            }
            if (wsToken == null || !jwtUtil.validateToken(wsToken)) {
                log.warn("WebSocket 握手校验失败: uri={}", exchange.getRequest().getURI());
                return unauthorized(exchange, "invalid websocket token");
            }
            return chain.filter(exchange);
        }

        // 5) Alertmanager Webhook：自身带 X-Webhook-Token 校验（Controller 内），
        //    这里放行路由，不校验 JWT（Alertmanager 无法携带 JWT）。
        if (path.equals("/api/alerts/webhook")) {
            return chain.filter(exchange);
        }

        // 6) Python agent 的回调接口。
        //    用内部共享密钥校验，而不是直接放行 —— 这些接口能触发 restart/scale
        //    等真实运维操作，放空等于给内网任何人一个远程执行入口。
        if (path.startsWith("/api/agent/callback/")) {
            String token = exchange.getRequest().getHeaders().getFirst(INTERNAL_TOKEN_HEADER);
            if (internalToken.equals(token)) {
                return chain.filter(exchange);
            }
            log.warn("回调接口内部密钥校验失败: path={}", path);
            return unauthorized(exchange, "invalid internal token");
        }

        // 7) 其余请求走 Bearer JWT
        String authHeader = exchange.getRequest().getHeaders().getFirst(HttpHeaders.AUTHORIZATION);
        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            return unauthorized(exchange, "missing bearer token");
        }

        String token = authHeader.substring(7);
        if (!jwtUtil.validateToken(token)) {
            return unauthorized(exchange, "invalid token");
        }

        exchange.getAttributes().put("username", jwtUtil.getUsernameFromToken(token));
        return chain.filter(exchange);
    }

    /** 从 query string 里取指定参数值（WebSocket 握手的 token 用）。 */
    private String queryParam(ServerWebExchange exchange, String name) {
        String query = exchange.getRequest().getURI().getQuery();
        if (query == null || query.isBlank()) {
            return null;
        }
        for (String pair : query.split("&")) {
            int eq = pair.indexOf('=');
            if (eq > 0 && name.equals(pair.substring(0, eq))) {
                try {
                    return java.net.URLDecoder.decode(pair.substring(eq + 1), java.nio.charset.StandardCharsets.UTF_8);
                } catch (Exception e) {
                    return pair.substring(eq + 1);
                }
            }
        }
        return null;
    }

    /**
     * 401 必须带 JSON 响应体：前端响应拦截器要读 res.code 才能触发登出跳转，
     * Python 侧也需要 resp.json() 能正常解析。
     */
    private Mono<Void> unauthorized(ServerWebExchange exchange, String reason) {
        ServerHttpResponse response = exchange.getResponse();
        response.setStatusCode(HttpStatus.UNAUTHORIZED);
        response.getHeaders().setContentType(MediaType.APPLICATION_JSON);
        String body = "{\"code\":401,\"message\":\"Unauthorized: " + reason + "\",\"data\":null}";
        DataBuffer buffer = response.bufferFactory().wrap(body.getBytes(StandardCharsets.UTF_8));
        return response.writeWith(Mono.just(buffer));
    }
}
