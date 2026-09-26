package com.opsagent.controller;

import com.opsagent.dto.ApiResponse;
import com.opsagent.entity.Approval;
import com.opsagent.service.ApprovalService;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 人工审批接口，供前端审批页使用。
 *
 * 路径与前端 api/approval.js 对齐：
 * - GET  /api/approvals?status=&page=&size=
 * - POST /api/approvals/{requestId}/decision
 */
@RestController
@RequestMapping("/api/approvals")
@RequiredArgsConstructor
public class ApprovalController {

    private final ApprovalService approvalService;

    @GetMapping
    public Mono<ApiResponse<Map<String, Object>>> list(
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int size) {
        // JPA 是阻塞 IO，必须挪到 boundedElastic，否则跑在 Netty event loop 上拖垮服务
        return Mono.fromCallable(() -> {
                    Page<Approval> result = approvalService.listApprovals(status, page, size);
                    Map<String, Object> data = new LinkedHashMap<>();
                    data.put("records", result.getContent());
                    data.put("total", result.getTotalElements());
                    return ApiResponse.success(data);
                })
                .subscribeOn(Schedulers.boundedElastic());
    }

    @PostMapping("/{requestId}/decision")
    public Mono<ResponseEntity<ApiResponse<Object>>> decide(@PathVariable String requestId,
                                                            @RequestBody DecisionRequest body,
                                                            ServerWebExchange exchange) {
        if (body.getApproved() == null) {
            return Mono.just(ResponseEntity.badRequest()
                    .body(ApiResponse.error(400, "缺少 approved 字段")));
        }
        // decidedBy 从 JWT filter 塞的 attribute 里取（JwtAuthenticationFilter 写入 "username"）
        String decidedBy = exchange.getAttributes().getOrDefault("username", "unknown").toString();
        return Mono.fromCallable(() -> {
                    ApprovalService.Decision d = approvalService.decide(
                            requestId, body.getApproved(), body.getNote(), decidedBy);
                    Map<String, Object> data = new LinkedHashMap<>();
                    data.put("approved", d.approved());
                    data.put("mode", d.mode());
                    data.put("note", d.note());
                    return ResponseEntity.ok(ApiResponse.success((Object) data));
                })
                // decide 里要写 MySQL + Redis，都是阻塞 IO
                .subscribeOn(Schedulers.boundedElastic())
                .onErrorResume(e -> {
                    // BusinessException（单号不存在/重复审批）返回 400，其余 500。
                    // HTTP 状态码同步映射，body 里仍保留 code，前端读 res.code 不受影响。
                    boolean business = e instanceof com.opsagent.exception.BusinessException;
                    int code = business ? 400 : 500;
                    HttpStatus status = business ? HttpStatus.BAD_REQUEST
                            : HttpStatus.INTERNAL_SERVER_ERROR;
                    return Mono.just(ResponseEntity.status(status)
                            .body(ApiResponse.error(code, e.getMessage())));
                });
    }

    @Data
    public static class DecisionRequest {
        private Boolean approved;
        private String note;
    }
}
