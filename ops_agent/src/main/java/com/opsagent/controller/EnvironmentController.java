package com.opsagent.controller;

import com.opsagent.dto.ApiResponse;
import com.opsagent.service.KubernetesToolClientHolder;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 单集群 namespace 轻量环境隔离状态接口。
 */
@RestController
@RequestMapping("/api/environment")
@RequiredArgsConstructor
public class EnvironmentController {

    private final KubernetesToolClientHolder holder;

    @GetMapping
    public Mono<ApiResponse<Map<String, Object>>> current() {
        return Mono.just(ApiResponse.success(Map.of(
                "ok", true,
                "namespace", holder.namespace(),
                "mode", "single-cluster-namespace",
                "label", "当前平台使用单 kind 集群 + namespace 做轻量环境隔离"
        )));
    }
}