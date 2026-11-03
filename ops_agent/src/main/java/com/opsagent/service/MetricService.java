package com.opsagent.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Prometheus 指标查询服务，供 Dashboard「指标大盘」与其它只读展示使用。
 *
 * 只读、无副作用。数据源是 kind 集群里的 kube-prometheus-stack，
 * 通过 port-forward 暴露到宿主机 {@code opsagent.prometheus.url}（默认 localhost:9090）。
 *
 * 复用与 QueryMetricsTool 相同的坑位处理：WebClient 的 uri(String) 会把 {} 当 URI
 * 模板二次解析，所以必须用 java.net.URI 传已编码好的完整 URL，否则 PromQL 里的
 * {} 直接 400。K8s/Prometheus HTTP 是阻塞 IO，调用方（Controller）必须挪到 boundedElastic。
 */
@Slf4j
@Service
public class MetricService {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Duration TIMEOUT = Duration.ofSeconds(10);

    private final WebClient webClient;
    private final String prometheusUrl;

    public MetricService(WebClient webClient,
                         @Value("${opsagent.prometheus.url:http://localhost:9090}") String prometheusUrl) {
        this.webClient = webClient;
        this.prometheusUrl = prometheusUrl;
    }

    /**
     * 执行一条 PromQL 查询，返回 Prometheus 的 result 数组（转换后的 Map 列表）。
     * 失败时返回 null 并记日志 —— 调用方据此决定降级展示，不让 Prometheus 故障拖垮 Dashboard。
     */
    public List<Map<String, Object>> query(String promql) {
        try {
            String body = webClient.get()
                    .uri(java.net.URI.create(prometheusUrl + "/api/v1/query?query=" + urlEncode(promql)))
                    .header(HttpHeaders.ACCEPT, "application/json")
                    .retrieve()
                    .bodyToMono(String.class)
                    .timeout(TIMEOUT)
                    .block();

            JsonNode root = MAPPER.readTree(body);
            if (!"success".equals(root.path("status").asText())) {
                log.warn("Prometheus 查询失败: {} query={}", root.path("error").asText("unknown"), promql);
                return null;
            }

            JsonNode result = root.path("data").path("result");
            List<Map<String, Object>> rows = new ArrayList<>();
            for (JsonNode item : result) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("metric", MAPPER.convertValue(item.path("metric"), Map.class));
                JsonNode value = item.path("value");
                if (value.isArray() && value.size() >= 2) {
                    row.put("value", value.get(1).asText());
                }
                rows.add(row);
            }
            return rows;
        } catch (Exception e) {
            log.warn("Prometheus 查询异常: {} query={}", e.getMessage(), promql);
            return null;
        }
    }

    /**
     * 一次返回多个常用指标，供 Dashboard 大盘直接渲染：
     * - Prometheus 可达状态；cAdvisor 目标状态单独回报，便于前端区分“Prometheus 挂了”
     *   和“只是容器指标没采集”
     * - 各 Deployment 的就绪/期望副本（kube-state-metrics，一定有）
     * - 各 Deployment 的容器 CPU(cores) / 内存(bytes)（container_*，依赖 cAdvisor）
     *
     * cAdvisor down 时容器 CPU/内存为空，但不影响副本/集群健康卡片。
     */
    public Map<String, Object> overview() {
        Map<String, Object> result = new LinkedHashMap<>();
        // up{job="prometheus"} 能查到 = Prometheus 本体可达；null 视为未连通
        List<Map<String, Object>> upRows = query("up{job=\"prometheus\"}");
        result.put("connected", upRows != null);

        List<Map<String, Object>> cAdvisorUp = query("up{job=\"cAdvisor\"}");
        result.put("cadvisorConnected", isUp(cAdvisorUp));

        result.put("deploymentReplicas", query("kube_deployment_status_replicas_available"));
        result.put("deploymentReplicasDesired", query("kube_deployment_spec_replicas"));
        List<Map<String, Object>> cpu = query(
                "sum(rate(container_cpu_usage_seconds_total{container!=\"POD\",image!=\"\"}[3m])) by (pod)");
        List<Map<String, Object>> memory = query(
                "sum(container_memory_working_set_bytes{container!=\"POD\",image!=\"\"}) by (pod)");
        result.put("containerCpu", cpu);
        result.put("containerMemory", memory);
        result.put("containerMetricsAvailable", cpu != null && !cpu.isEmpty() && memory != null && !memory.isEmpty());
        return result;
    }

    private String urlEncode(String query) {
        try {
            return URLEncoder.encode(query, StandardCharsets.UTF_8);
        } catch (Exception e) {
            return query;
        }
    }

    /** Prometheus value 形如 [timestamp, "1"]，这里只取字符串值判断目标是否 up。 */
    static boolean isUp(List<Map<String, Object>> rows) {
        if (rows == null || rows.isEmpty()) {
            return false;
        }
        for (Map<String, Object> row : rows) {
            Object value = row.get("value");
            Object raw = null;
            if (value instanceof List<?> list && list.size() >= 2) {
                raw = list.get(1);
            } else if (value != null) {
                // MetricService.query 为了兼容旧调用方，把 value 转成了字符串 "1"
                raw = value;
            }
            if (raw != null && !"0".equals(String.valueOf(raw))) {
                return true;
            }
        }
        return false;
    }
}