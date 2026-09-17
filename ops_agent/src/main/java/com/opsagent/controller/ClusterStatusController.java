package com.opsagent.controller;

import com.opsagent.dto.ApiResponse;
import com.opsagent.service.ClusterStatusService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.Map;

/**
 * 集群状态查询接口，Dashboard「集群状态」卡片用。
 *
 * 只读、无副作用。K8s HTTP 是阻塞 IO，必须挪到 boundedElastic，
 * 否则跑在 Netty event loop 上并发一上来整个服务会被拖死。
 */
@RestController
@RequestMapping("/api/cluster")
@RequiredArgsConstructor
public class ClusterStatusController {

    private final ClusterStatusService clusterStatusService;

    @GetMapping("/status")
    public Mono<ApiResponse<Map<String, Object>>> status() {
        return Mono.fromCallable(() -> ApiResponse.success(clusterStatusService.status()))
                .subscribeOn(Schedulers.boundedElastic());
    }
}
