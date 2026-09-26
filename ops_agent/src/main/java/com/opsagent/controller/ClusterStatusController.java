package com.opsagent.controller;

import com.opsagent.dto.ApiResponse;
import com.opsagent.service.ClusterStatusService;
import com.opsagent.service.ImpactAnalysisService;
import com.opsagent.service.MetricBaselineService;
import com.opsagent.service.MetricService;
import com.opsagent.service.TopologyService;
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
    private final MetricService metricService;
    private final MetricBaselineService metricBaselineService;
    private final TopologyService topologyService;
    private final ImpactAnalysisService impactAnalysisService;

    @GetMapping("/status")
    public Mono<ApiResponse<Map<String, Object>>> status() {
        return Mono.fromCallable(() -> ApiResponse.success(clusterStatusService.status()))
                .subscribeOn(Schedulers.boundedElastic());
    }

    /**
     * Dashboard「指标大盘」数据源：返回各 Deployment 的副本数、容器 CPU/内存。
     * 只读、无副作用；Prometheus 不可达时 overview() 内部降级返回 null 字段，不报错。
     */
    @GetMapping("/metrics")
    public Mono<ApiResponse<Map<String, Object>>> metrics() {
        return Mono.fromCallable(() -> ApiResponse.success(metricService.overview()))
                .subscribeOn(Schedulers.boundedElastic());
    }

    /**
     * Dashboard「基线偏离」卡片数据源：各 Deployment 当前指标 vs 学习到的基线区间。
     *
     * 只读：调用的 snapshot() 不触发预警、也不修改基线窗口，前端反复刷新不会污染学习结果。
     * Prometheus 不可达时 connected=false，其余卡片照常工作。
     */
    @GetMapping("/baseline")
    public Mono<ApiResponse<Map<String, Object>>> baseline() {
        // 内部要读 Redis 并查 Prometheus，都是阻塞 IO
        return Mono.fromCallable(() -> ApiResponse.success(metricBaselineService.snapshot()))
                .subscribeOn(Schedulers.boundedElastic());
    }

    /**
     * 服务拓扑关系图数据源（Service → Pod，含就绪状态与断流标记）。
     *
     * @param deployment 可选过滤：只看与该 Deployment 相关的服务。
     *                   不传则返回整个 namespace —— 服务多了关系图会糊成一团，
     *                   前端从告警详情跳进来时应该带上服务名。
     */
    @GetMapping("/topology")
    public Mono<ApiResponse<Map<String, Object>>> topology(
            @RequestParam(required = false) String deployment) {
        // K8s HTTP + Redis 缓存读写，都是阻塞 IO
        return Mono.fromCallable(() -> ApiResponse.success(topologyService.snapshot(deployment)))
                .subscribeOn(Schedulers.boundedElastic());
    }

    /**
     * 故障影响面分析：受影响的流量入口、是否断流、异常实例清单。
     * 告警详情页展示，agent 也可通过 query_impact 工具拿到同一份结论。
     */
    @GetMapping("/impact")
    public Mono<ApiResponse<Map<String, Object>>> impact(@RequestParam String deployment) {
        return Mono.fromCallable(() -> ApiResponse.success(impactAnalysisService.analyze(deployment)))
                .subscribeOn(Schedulers.boundedElastic());
    }
}
