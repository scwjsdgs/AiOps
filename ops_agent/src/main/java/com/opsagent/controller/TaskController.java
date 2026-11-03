package com.opsagent.controller;

import com.opsagent.dto.ApiResponse;
import com.opsagent.model.Task;
import com.opsagent.service.TaskSchedulerService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;

import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/tasks")
@RequiredArgsConstructor
public class TaskController {


    private final TaskSchedulerService taskScheduler;

    /**
     * 任务详情。
     *
     * 任务不存在时返回**真实的 HTTP 404**（body 仍保留 code=404）。
     * 之前是 HTTP 200 + body code=404，脚本靠 HTTP 状态码判断会误以为查到了。
     */
    @GetMapping("/{id}")
    public Mono<ResponseEntity<ApiResponse<Task>>> getTask(@PathVariable String id) {
        return taskScheduler.getTask(id)
                .map(t -> ResponseEntity.ok(ApiResponse.success(t)))
                .switchIfEmpty(Mono.just(ResponseEntity.status(HttpStatus.NOT_FOUND)
                        .body(ApiResponse.<Task>error(404, "Task not found"))));
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
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String keyword,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int size) {
        // list 与 count 必须传同一组过滤条件：过滤条件不一致会让分页 total
        // 与实际结果数对不上（搜出 3 条却显示"共 100 条"）。
        return Mono.zip(taskScheduler.listTasks(alertId, status, keyword, page, size),
                        taskScheduler.countTasks(alertId, status, keyword))
                .map(tuple -> {
                    Map<String, Object> data = new LinkedHashMap<>();
                    data.put("records", tuple.getT1());
                    data.put("total", tuple.getT2());
                    return ApiResponse.success(data);
                });
    }

    /**
     * 清理残留任务（运维用）。默认只清 24 小时前的 PENDING 僵尸任务，避免误删活跃任务。
     *
     * ?status=FAILED&olderThanMinutes=60  清理 1 小时前的失败任务
     * ?status=PENDING&olderThanMinutes=5  清理 5 分钟前的排队僵尸
     * 不带参数时清理 24 小时前的所有状态任务。
     */
    @DeleteMapping("/purge")
    public Mono<ApiResponse<Map<String, Object>>> purgeTasks(
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "0") int olderThanMinutes) {
        int older = olderThanMinutes <= 0 ? 24 * 60 : olderThanMinutes;
        return taskScheduler.purgeTasks(status, older)
                .map(cleaned -> {
                    Map<String, Object> data = new LinkedHashMap<>();
                    data.put("cleaned", cleaned);
                    data.put("status", status == null || status.isBlank() ? "ALL" : status);
                    data.put("olderThanMinutes", older);
                    return ApiResponse.success(data);
                });
    }
}