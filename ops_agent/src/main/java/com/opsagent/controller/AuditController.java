package com.opsagent.controller;

import com.opsagent.dto.ApiResponse;
import com.opsagent.entity.AuditLog;
import com.opsagent.service.AuditLogService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 安全审计接口：查询操作历史、分类统计、手动记录操作回放备注。
 */
@RestController
@RequestMapping("/api/audit")
@RequiredArgsConstructor
public class AuditController {

    private final AuditLogService auditLogService;

    @GetMapping
    public Mono<ApiResponse<Map<String, Object>>> list(
            @RequestParam(required = false) String category,
            @RequestParam(required = false) String operator,
            @RequestParam(required = false) String keyword,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int size) {
        return Mono.fromCallable(() -> {
            Page<AuditLog> result = auditLogService.search(category, operator, keyword, page, size);
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("records", result.getContent());
            data.put("total", result.getTotalElements());
            data.put("stats", auditLogService.stats());
            return ApiResponse.success(data);
        }).subscribeOn(Schedulers.boundedElastic());
    }

    @GetMapping("/stats")
    public Mono<ApiResponse<Map<String, Object>>> stats() {
        return Mono.fromCallable(() -> ApiResponse.success(auditLogService.stats()))
                .subscribeOn(Schedulers.boundedElastic());
    }

    /**
     * 操作回放：前端做“安全演练”时可手动记录一条回放说明，
     * 便于把一次人工演练的步骤沉淀进审计时间线。
     */
    @PostMapping("/replay")
    public Mono<ApiResponse<Void>> replay(@RequestBody ReplayRequest body, ServerWebExchange exchange) {
        if (body == null || body.getAction() == null || body.getAction().isBlank()) {
            return Mono.just(ApiResponse.error(400, "action 不能为空"));
        }
        return Mono.fromCallable(() -> {
            auditLogService.record("REPLAY", body.getAction(),
                    auditLogService.operator(exchange), "REST",
                    body.getTarget(), body.getDetail(), true);
            return ApiResponse.<Void>success(null);
        }).subscribeOn(Schedulers.boundedElastic());
    }

    public static class ReplayRequest {
        private String action;
        private String target;
        private String detail;

        public String getAction() { return action; }
        public String getTarget() { return target; }
        public String getDetail() { return detail; }
    }
}
