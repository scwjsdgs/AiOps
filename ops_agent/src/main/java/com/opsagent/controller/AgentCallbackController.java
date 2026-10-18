package com.opsagent.controller;

import com.opsagent.dto.ApiResponse;
import com.opsagent.model.AgentMessage;
import com.opsagent.model.ToolExecutionRequest;
import com.opsagent.service.AgentCallbackService;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;

import java.util.HashMap;

@RestController
@RequestMapping("/api/agent/callback")
@RequiredArgsConstructor
public class AgentCallbackController {


    private final AgentCallbackService callbackService;

    @PostMapping("/step")
    public Mono<ApiResponse<Void>> recordStep(@RequestBody AgentMessage message) {
        return callbackService.handleAgentStep(message)
                .then(Mono.just(ApiResponse.success(null)));
    }

    @PostMapping("/tool")
    public Mono<ApiResponse<Object>> executeTool(@RequestBody ToolExecutionRequest request) {
        // 把 contextId 一并放进参数：工具写审计信息时（比如 rollback 打在
        // kubernetes.io/change-cause 上的备注）需要它来关联是哪个任务触发的变更
        if (request.getParameters() == null) {
            request.setParameters(new HashMap<>());
        }
        if (request.getContextId() != null) {
            request.getParameters().putIfAbsent("contextId", request.getContextId());
        }
        return callbackService.executeTool(request)
                .map(ApiResponse::success);
    }

    /**
     * 兼容两种上报方式：
     * - 新协议：JSON body {result, status}。最终报告里通常含 # 号、空格和换行，
     *   放进 query string 会被 # 截断、被 Netty 的请求行长度上限拒绝。
     * - 旧协议：?result=xxx&status=yyy，保留以保证灰度期两端不同步时不会 400。
     */
    @PostMapping("/complete/{taskId}")
    public Mono<ApiResponse<Void>> completeTask(@PathVariable String taskId,
                                                @RequestBody(required = false) CompleteRequest body,
                                                @RequestParam(required = false) String result,
                                                @RequestParam(required = false) String status) {
        String text = body != null && body.getResult() != null ? body.getResult() : result;
        if (text == null) {
            return Mono.just(ApiResponse.error(400, "缺少 result"));
        }
        String finalStatus = body != null && body.getStatus() != null
                ? body.getStatus()
                : (status != null && !status.isBlank() ? status : "SUCCESS");
        return callbackService.taskComplete(taskId, text, finalStatus)
                .then(Mono.just(ApiResponse.success(null)));
    }

    
    @Data
    public static class CompleteRequest {
        private String result;
        private String status;
    }
}
