package com.opsagent.tool;

import com.opsagent.model.ToolExecutionResult;
import com.opsagent.service.ImpactAnalysisService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 查询故障影响面（根因定位的拓扑维度）。
 *
 * 与其它诊断工具的区别：get_status / query_log / query_pod_events 回答的是
 * 「这个服务怎么了」，本工具回答的是「这件事影响到了谁」。
 *
 * 返回结构化的影响面：关联 Service、各入口是否断流、异常实例清单，外加一段
 * 可直接引用进报告的 summary。Python 侧读 data.summary / affectedServices /
 * outage / affectedPods。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class QueryImpactTool implements Tool {

    private final ImpactAnalysisService impactAnalysisService;

    @Override
    public String getName() {
        return "query_impact";
    }

    @Override
    public String getDescription() {
        return "分析故障的影响面：该服务的流量入口（Service）有哪些、是否已断流、哪些实例异常。"
                + "用于判断故障影响范围与紧急程度，根因报告应包含这段结论。";
    }

    @Override
    public boolean isIdempotent() {
        return true;
    }

    @Override
    public ToolExecutionResult execute(Map<String, Object> parameters) {
        long start = System.currentTimeMillis();
        ToolParams p = ToolParams.of(parameters);
        String name = p.str("deployment", "service");
        if (name == null) {
            return Tool.failure("Missing 'deployment' parameter（也接受 'service'）", start);
        }

        Map<String, Object> analysis = impactAnalysisService.analyze(name);
        if (!Boolean.TRUE.equals(analysis.get("available"))) {
            // 「查不到」不代表工具执行失败：把原因如实带回，LLM 会据此说明
            // "该服务可能未部署或没有流量入口"，而不是去重试别的工具。
            return Tool.failure(String.valueOf(analysis.getOrDefault("reason", "影响面不可用")), start);
        }

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("deployment", analysis.get("deployment"));
        data.put("namespace", analysis.get("namespace"));
        data.put("outage", analysis.get("outage"));
        data.put("selfDegraded", analysis.get("selfDegraded"));
        data.put("desiredReplicas", analysis.get("desiredReplicas"));
        data.put("readyReplicas", analysis.get("readyReplicas"));
        data.put("unavailableReplicas", analysis.get("unavailableReplicas"));
        data.put("affectedServices", analysis.get("affectedServices"));
        data.put("affectedPodCount", ((java.util.List<?>) analysis.getOrDefault("affectedPods", java.util.List.of())).size());
        data.put("affectedPods", analysis.get("affectedPods"));
        // summary 是可直接引用进报告的结论句
        data.put("summary", analysis.get("summary"));
        data.put("affectedServiceCount",
                ((java.util.List<?>) analysis.getOrDefault("affectedServices", java.util.List.of())).size());

        return Tool.success(String.valueOf(analysis.get("summary")), data, start);
    }
}
