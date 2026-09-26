package com.opsagent.controller;

import com.opsagent.dto.ApiResponse;
import com.opsagent.model.ToolExecutionRequest;
import com.opsagent.model.ToolExecutionResult;
import com.opsagent.service.ToolRegistryService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

@RestController
@RequestMapping("/api/tools")
@RequiredArgsConstructor
public class ToolController {


    private final ToolRegistryService toolRegistry;

    /**
     * 工具清单。每项含 name / description / agentExposed / dangerous / idempotent，
     * 这几个标记全部由后端权威计算，前端不硬编码 —— 改 application.yml 的白名单后
     * 前端刷新即生效，不会出现两边不一致。
     */
    @GetMapping("/list")
    public ApiResponse<?> listTools() {
        return ApiResponse.success(toolRegistry.getAllToolsInfo());
    }

    /**
     * 当前生效的 agent 白名单，供前端展示与排查"为什么 agent 说某工具不存在"。
     */
    @GetMapping("/agent-exposed")
    public ApiResponse<?> agentExposed() {
        return ApiResponse.success(toolRegistry.getAgentExposedNames());
    }

    @PostMapping("/execute")
    public Mono<ApiResponse<ToolExecutionResult>> execute(@RequestBody ToolExecutionRequest request) {
        // 工具执行是阻塞的（K8s HTTP 调用、SSH、clear_cache 里的 Thread.sleep），
        // 必须挪到 boundedElastic，否则跑在 Netty event loop 上会打印
        // "Blocking call! ... blocked for Nms" 并拖垮整个服务。
        // 注意这里走的是不带白名单的 execute —— 前端手工调用，generic_command 仍可用。
        return Mono.fromCallable(() -> ApiResponse.success(toolRegistry.execute(request)))
                .subscribeOn(Schedulers.boundedElastic());
    }
}
