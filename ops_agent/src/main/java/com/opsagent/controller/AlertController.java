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

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

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
     * 分页查询告警列表。JPA 是阻塞 IO，必须挪到 boundedElastic，
     * 否则跑在 Netty event loop 上并发一上来整个服务会被拖死。
     */
    @GetMapping
    public Mono<ApiResponse<Map<String, Object>>> listAlerts(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int size) {
        return Mono.fromCallable(() -> {
                    // 前端页码从 1 开始，Spring Data 从 0 开始
                    Page<AlertEntity> result = alertRepository.findAll(
                            PageRequest.of(Math.max(page - 1, 0), size, Sort.by(Sort.Direction.DESC, "createTime")));
                    Map<String, Object> data = new LinkedHashMap<>();
                    data.put("records", result.getContent());
                    data.put("total", result.getTotalElements());
                    return ApiResponse.success(data);
                })
                .subscribeOn(Schedulers.boundedElastic());
    }
}
