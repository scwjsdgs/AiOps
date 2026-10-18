package com.opsagent.controller;

import com.opsagent.dto.ApiResponse;
import com.opsagent.service.AuditLogService;
import com.opsagent.service.RootCauseGraphService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;

/**
 * 根因图谱、历史相似故障推荐、混沌评测状态等智能能力接口。
 */
@RestController
@RequestMapping("/api/intelligence")
@RequiredArgsConstructor
public class IntelligenceController {

    private final RootCauseGraphService rootCauseGraphService;
    private final AuditLogService auditLogService;

    @GetMapping("/root-cause-graph")
    public Mono<ApiResponse<Map<String, Object>>> rootCauseGraph(
            @RequestParam(required = false) String deployment,
            @RequestParam(defaultValue = "100") int limit) {
        return rootCauseGraphService.graph(deployment, limit)
                .map(ApiResponse::success);
    }

    @GetMapping("/similar-tasks")
    public Mono<ApiResponse<List<Map<String, Object>>>> similarTasks(
            @RequestParam(required = false) String deployment,
            @RequestParam(required = false) String keyword,
            @RequestParam(defaultValue = "10") int limit) {
        return rootCauseGraphService.similarTasks(deployment, keyword, limit)
                .map(ApiResponse::success);
    }

    @PostMapping("/chaos/run")
    public Mono<ApiResponse<Map<String, Object>>> chaosRun(@RequestBody(required = false) Map<String, Object> body,
                                                           ServerWebExchange exchange) {
        String llm = body == null ? "" : String.valueOf(body.getOrDefault("llmJudge", "false"));
        auditLogService.record("CHAOS", "chaos_run", auditLogService.operator(exchange),
                "REST", "", "触发混沌评测 llmJudge=" + llm, true);
        return Mono.just(ApiResponse.success(Map.of(
                "ok", true,
                "type", "chaos",
                "status", "scheduled",
                "detail", "Python 侧已提供 /api/chaos/run，这里记录触发审计；请从 RAG 评测页查看结果"
        )));
    }
}