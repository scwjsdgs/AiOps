package com.opsagent.controller;

import com.opsagent.dto.ApiResponse;
import com.opsagent.model.Task;
import com.opsagent.service.TaskSchedulerService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;

import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/tasks")
@RequiredArgsConstructor
public class TaskController {


    private final TaskSchedulerService taskScheduler;

    @GetMapping("/{id}")
    public Mono<ApiResponse<Task>> getTask(@PathVariable String id) {
        return taskScheduler.getTask(id)
                .map(ApiResponse::<Task>success)
                .switchIfEmpty(Mono.just(ApiResponse.<Task>error(404, "Task not found")));
    }

    /**
     * 分页任务列表，?alertId= 可按关联告警过滤。
     *
     * 告警页"查看分析"靠它打通：告警 → alertId → 任务 → Agent 报告。
     * 放在 /{id} 前面没有路由冲突（Spring 按精确度匹配），但 list 必须在
     * 类里先声明吗？不需要 —— /{id} 是路径变量，"list" 不会命中它，
     * 因为 Spring MVC/WebFlux 对字面量段的优先级高于路径变量。
     */
    @GetMapping
    public Mono<ApiResponse<Map<String, Object>>> listTasks(
            @RequestParam(required = false) String alertId,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int size) {
        return Mono.zip(taskScheduler.listTasks(alertId, page, size),
                        taskScheduler.countTasks(alertId))
                .map(tuple -> {
                    Map<String, Object> data = new LinkedHashMap<>();
                    data.put("records", tuple.getT1());
                    data.put("total", tuple.getT2());
                    return ApiResponse.success(data);
                });
    }
}