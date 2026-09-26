package com.opsagent.controller;

import com.opsagent.dto.ApiResponse;
import com.opsagent.entity.AlertEntity;
import com.opsagent.model.Alert;
import com.opsagent.repository.AlertRepository;
import com.opsagent.service.AlertIngestionService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/alerts")
@RequiredArgsConstructor
public class AlertController {


    private final AlertIngestionService alertIngestionService;
    private final AlertRepository alertRepository;

    @PostMapping
    public Mono<ApiResponse<Void>> receiveAlert(@RequestBody Alert alert) {
        return alertIngestionService.processAlert(alert)
                .then(Mono.just(ApiResponse.success(null)));
    }

    /**
     * Dashboard 统计：最近 N 天每日分级计数 + 级别分布，供 ECharts 渲染。
     * JPA 是阻塞 IO，必须挪到 boundedElastic，否则跑在 Netty event loop 上并发一上来整个服务会被拖死。
     */
    @GetMapping("/stats")
    public Mono<ApiResponse<Map<String, Object>>> stats(
            @RequestParam(defaultValue = "7") int days) {
        return Mono.fromCallable(() -> ApiResponse.success(alertIngestionService.stats(days)))
                .subscribeOn(Schedulers.boundedElastic());
    }

    /**
     * 分页查询告警列表，支持级别/状态/关键词筛选。
     * JPA 是阻塞 IO，必须挪到 boundedElastic，否则跑在 Netty event loop 上并发一上来整个服务会被拖死。
     *
     * 筛选（severity/status/keyword）全部下推到 SQL，不在前端做"当前页过滤"——
     * 否则搜索只能命中这一页的十条，用户以为搜了全库。
     */
    @GetMapping
    public Mono<ApiResponse<Map<String, Object>>> listAlerts(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int size,
            @RequestParam(required = false) String severity,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String keyword) {
        return Mono.fromCallable(() -> {
                    // 前端页码从 1 开始，Spring Data 从 0 开始
                    PageRequest pageable = PageRequest.of(
                            Math.max(page - 1, 0), size, Sort.by(Sort.Direction.DESC, "createTime"));
                    // 空串归一成 null：JPQL 里 :param is null 才代表"不筛选"
                    Page<AlertEntity> result = alertRepository.search(
                            blankToNull(severity), blankToNull(status), blankToNull(keyword), pageable);
                    Map<String, Object> data = new LinkedHashMap<>();
                    data.put("records", result.getContent());
                    data.put("total", result.getTotalElements());
                    return ApiResponse.success(data);
                })
                .subscribeOn(Schedulers.boundedElastic());
    }

    private static String blankToNull(String s) {
        return (s == null || s.isBlank()) ? null : s;
    }
}
