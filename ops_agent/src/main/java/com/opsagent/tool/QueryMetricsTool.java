package com.opsagent.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.opsagent.model.ToolExecutionResult;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 查询 Prometheus 指标。
 *
 * 这是 Agent 数据层的关键补强：之前 Agent 分析问题时只有 get_status 一个维度，
 * 看不到 CPU/内存/副本趋势。现在它能直接问 Prometheus 要数据。
 *
 * 典型查询（LLM 可自行组合 PromQL）：
 * - 副本状态：kube_deployment_status_replicas{deployment="nginx"}
 * - 容器 CPU：sum by (pod) (rate(container_cpu_usage_seconds_total{pod=~"nginx.*"}[5m]))
 * - 容器内存：sum by (pod) (container_memory_working_set_bytes{pod=~"nginx.*"})
 *
 * Python 侧读 data.result / resultCount / query。
 */
@Component
public class QueryMetricsTool implements Tool {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Value("${opsagent.prometheus.url:http://localhost:9090}")
    private String prometheusUrl;

    private final WebClient webClient;

    public QueryMetricsTool(WebClient.Builder builder) {
        this.webClient = builder.build();
    }

    @Override
    public String getName() {
        return "query_metrics";
    }

    @Override
    public String getDescription() {
        return "查询 Prometheus 监控指标（PromQL）。可查容器 CPU/内存、副本数、节点资源等，用于根因分析时获取真实数据。";
    }

    @Override
    public boolean isIdempotent() {
        return true;
    }

    @Override
    public ToolExecutionResult execute(Map<String, Object> parameters) {
        long start = System.currentTimeMillis();
        ToolParams p = ToolParams.of(parameters);
        String query = p.str("query", "promql");
        if (query == null) {
            return Tool.failure("Missing 'query' parameter（PromQL 表达式，如 kube_deployment_status_replicas{deployment=\"nginx\"}）", start);
        }

        try {
            String body = webClient.get()
                    // WebClient 的 uri(String) 会把 {} 当 URI 模板二次解析，PromQL 里的 {}
                    // 会直接 400。必须用 java.net.URI 硬编码已编码好的完整 URL 绕过模板解析。
                    .uri(java.net.URI.create(prometheusUrl + "/api/v1/query?query=" + urlEncode(query)))
                    .header(HttpHeaders.ACCEPT, "application/json")
                    .retrieve()
                    .bodyToMono(String.class)
                    .timeout(Duration.ofSeconds(10))
                    .block();

            JsonNode root = MAPPER.readTree(body);
            if (!"success".equals(root.path("status").asText())) {
                // Prometheus 返回 error type（如 PromQL 语法错误）：把错误信息原样带回，
                // LLM 能据此修正表达式重试
                String error = root.path("error").asText("unknown error");
                return Tool.failure("Prometheus 查询失败: " + error, start);
            }

            JsonNode result = root.path("data").path("result");
            List<Map<String, Object>> rows = new ArrayList<>();
            for (JsonNode item : result) {
                Map<String, Object> row = new LinkedHashMap<>();
                // metric 里是标签（pod、deployment、namespace 等），LLM 靠它对上是哪条数据
                row.put("metric", MAPPER.convertValue(item.path("metric"), Map.class));
                JsonNode value = item.path("value");
                if (value.isArray() && value.size() >= 2) {
                    row.put("timestamp", value.get(0).asDouble());
                    row.put("value", value.get(1).asText());
                }
                rows.add(row);
            }

            Map<String, Object> data = new LinkedHashMap<>();
            data.put("query", query);
            data.put("resultCount", rows.size());
            data.put("result", rows);

            return Tool.success("查询到 " + rows.size() + " 条指标数据: " + query, data, start);
        } catch (Exception e) {
            // Prometheus 不可达（未部署/网络问题）：如实报错，LLM 会走别的诊断路径
            return Tool.failure("查询 Prometheus 失败: " + e.getMessage(), start);
        }
    }

    /** PromQL 里有 { } = " ~ 等字符，必须编码后拼进 URL。 */
    private String urlEncode(String query) {
        try {
            return java.net.URLEncoder.encode(query, java.nio.charset.StandardCharsets.UTF_8);
        } catch (Exception e) {
            return query;
        }
    }
}
