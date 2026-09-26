package com.opsagent.service;

import com.opsagent.exception.BusinessException;
import com.opsagent.model.ToolExecutionRequest;
import com.opsagent.model.ToolExecutionResult;
import com.opsagent.tool.*;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class ToolRegistryService {

    private final ConcurrentHashMap<String, Tool> tools = new ConcurrentHashMap<>();

    private final RestartServiceTool restartTool;
    private final ScaleUpTool scaleUpTool;
    private final QueryLogTool queryLogTool;
    private final GenericTool genericTool;
    private final GetStatusTool getStatusTool;
    private final RollbackTool rollbackTool;
    private final ClearCacheTool clearCacheTool;
    private final HumanApprovalTool humanApprovalTool;
    private final QueryMetricsTool queryMetricsTool;
    private final PodEventsTool podEventsTool;
    private final QueryImpactTool queryImpactTool;

    private final MeterRegistry meterRegistry;

    @Value("${opsagent.tools.agent-exposed:}")
    private String agentExposedConfig;

    private Set<String> agentExposed = Set.of();

    private Counter toolExecutionCounter;
    private Counter toolSuccessCounter;
    private Counter toolFailureCounter;

    @PostConstruct
    public void init() {
        // 每个工具只在 map 里放一份。别名（比如同时按 service 和 deployment 注册）
        // 会让 getAllToolsInfo() 输出重复行，工具页上出现两个一模一样的条目。
        registerTool(restartTool);
        registerTool(scaleUpTool);
        registerTool(queryLogTool);
        registerTool(genericTool);
        registerTool(getStatusTool);
        registerTool(rollbackTool);
        registerTool(clearCacheTool);
        registerTool(humanApprovalTool);
        registerTool(queryMetricsTool);
        registerTool(podEventsTool);
        registerTool(queryImpactTool);

        agentExposed = Arrays.stream(agentExposedConfig.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toUnmodifiableSet());

        toolExecutionCounter = Counter.builder("agent.tool.execution")
                .description("Total tool executions")
                .register(meterRegistry);
        toolSuccessCounter = Counter.builder("agent.tool.success")
                .description("Successful tool executions")
                .register(meterRegistry);
        toolFailureCounter = Counter.builder("agent.tool.failure")
                .description("Failed tool executions")
                .register(meterRegistry);
    }

    public void registerTool(Tool tool) {
        tools.put(tool.getName(), tool);
    }

    public Tool getTool(String name) {
        Tool tool = tools.get(name);
        if (tool == null) {
            throw new BusinessException("Tool not found: " + name);
        }
        return tool;
    }

    /** 供运维人员从前端手工调用：不做白名单限制，generic_command 在这里仍可用。 */
    public ToolExecutionResult execute(ToolExecutionRequest request) {
        return execute(request, false);
    }

    /**
     * @param forAgent true 表示调用方是 AI agent，需要按 opsagent.tools.agent-exposed 校验。
     *                 这个开关是 generic_command（可执行任意 shell）不暴露给 LLM 的实现点。
     */
    public ToolExecutionResult execute(ToolExecutionRequest request, boolean forAgent) {
        toolExecutionCounter.increment();

        Tool tool = tools.get(request.getToolName());
        if (tool == null) {
            toolFailureCounter.increment();
            return failure("Tool not found: " + request.getToolName());
        }
        if (forAgent && !agentExposed.contains(tool.getName())) {
            toolFailureCounter.increment();
            return failure("tool not allowed for agent: " + tool.getName());
        }

        try {
            ToolExecutionResult result = tool.execute(request.getParameters());
            if (result.getData() == null) {
                // Python 侧读的是 result.data["status"] 这类取值，
                // data 为 null 会抛 AttributeError 被 except 吞掉，
                // 运维界面上表现为"（模拟数据）"，等于掩盖了真实执行结果。
                result.setData(new LinkedHashMap<String, Object>());
            }
            if (result.isSuccess()) {
                toolSuccessCounter.increment();
            } else {
                toolFailureCounter.increment();
            }
            return result;
        } catch (Exception e) {
            // 工具内部抛异常不能往外扔：抛出去会变成 HTTP 500，
            // Python 侧 raise_for_status() 一炸就中断整个 ReAct 循环，
            // 一个工具的小毛病就升级成整次故障分析失败。
            toolFailureCounter.increment();
            return failure("Tool execution error: " + e.getMessage());
        }
    }

    private ToolExecutionResult failure(String message) {
        return new ToolExecutionResult(false, message, new LinkedHashMap<String, Object>(), 0);
    }

    /**
     * 全部工具，包含 generic_command —— /api/tools/list 用它，前端工具页需要看到完整清单。
     *
     * agentExposed / dangerous 由后端权威声明，不经前端硬编码：
     *   agentExposed —— 是否在 opsagent.tools.agent-exposed 白名单内（决定 AI 能否自主调用）
     *   dangerous    —— Tool.isDangerous()，会改变线上状态、需要审批边界
     *   idempotent   —— Tool.isIdempotent()，失败可否安全重试
     * 前端只负责渲染。白名单调整（改 application.yml）后前端立即生效，
     * 不会出现"后端已放开、前端还标着仅人工执行"的漂移。
     */
    public List<Map<String, Object>> getAllToolsInfo() {
        return tools.values().stream()
                .map(t -> {
                    Map<String, Object> info = new LinkedHashMap<>();
                    info.put("name", t.getName());
                    info.put("description", t.getDescription());
                    info.put("agentExposed", agentExposed.contains(t.getName()));
                    info.put("dangerous", t.isDangerous());
                    info.put("idempotent", t.isIdempotent());
                    return info;
                })
                .collect(Collectors.toList());
    }

    /** 当前允许 AI agent 调用的工具名，用于排查"为什么 agent 说这个工具不存在"。 */
    public Set<String> getAgentExposedNames() {
        return agentExposed;
    }
}
