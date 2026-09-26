package com.opsagent.exception;

import com.opsagent.dto.ApiResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.util.concurrent.TimeoutException;

@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(WebClientResponseException.class)
    public ResponseEntity<ApiResponse<?>> handleWebClientError(WebClientResponseException e) {
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                .body(ApiResponse.error(502, "Upstream service error: " + e.getMessage()));
    }

    @ExceptionHandler(TimeoutException.class)
    public ResponseEntity<ApiResponse<?>> handleTimeout(TimeoutException e) {
        return ResponseEntity.status(HttpStatus.GATEWAY_TIMEOUT)
                .body(ApiResponse.error(504, "Request timeout"));
    }

    /**
     * 请求体反序列化失败（JSON 格式错误、字段类型不匹配等）。
     *
     * 注意捕获的是 ServerWebInputException 而不是 DecodingException：
     * WebFlux 在 AbstractJackson2Decoder 里把 JsonParseException 包成
     * DecodingException，再往上包成 ServerWebInputException 才抛出，
     * 直接 @ExceptionHandler(DecodingException) 匹配不到，会漏到下面的
     * handleException 变成 500 —— 掩盖了"其实是客户端 body 写错了"这个事实，
     * 排查时容易被误导去查服务端。
     *
     * ServerWebInputException 的身份就是 400 BAD_REQUEST，这里沿用它的状态码，
     * 只把根因（JSON 到底哪里错了）带进 message。
     */
    @ExceptionHandler(org.springframework.web.server.ServerWebInputException.class)
    public ResponseEntity<ApiResponse<?>> handleServerWebInput(
            org.springframework.web.server.ServerWebInputException e) {
        Throwable cause = e.getCause();
        while (cause != null && cause.getCause() != null
                && !(cause instanceof com.fasterxml.jackson.core.JsonProcessingException)) {
            cause = cause.getCause();
        }
        String detail = cause != null ? cause.getMessage() : e.getMessage();
        return ResponseEntity.badRequest()
                .body(ApiResponse.error(400, "Invalid request body: " + detail));
    }

    /**
     * 业务异常。
     *
     * BusinessException 自带 code（400/404/409 等）。之前它被下面的
     * handleException 一把兜住统一成 500，HTTP 层也恒为 200 —— 监控/脚本
     * 只看 HTTP 状态码时完全看不出"这是客户端错误还是服务端错误"。
     * 这里按 code 映射成真实 HTTP 状态码，body 里仍保留同一个 code，
     * 老调用方（前端 request.js 读 res.code）行为不变。
     */
    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<ApiResponse<?>> handleBusiness(BusinessException e) {
        HttpStatus status = HttpStatus.resolve(e.getCode());
        if (status == null) {
            status = HttpStatus.BAD_REQUEST;
        }
        return ResponseEntity.status(status)
                .body(ApiResponse.error(e.getCode(), e.getMessage()));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<?>> handleException(Exception e) {
        e.printStackTrace();
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ApiResponse.error(500, "Internal Server Error: " + e.getMessage()));
    }
}