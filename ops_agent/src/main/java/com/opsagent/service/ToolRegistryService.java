package com.opsagent.service;

import com.opsagent.exception.BusinessException;
import com.opsagent.model.ToolExecutionRequest;
import com.opsagent.model.ToolExecutionResult;
import com.opsagent.tool.*;
import com.opsagent.service.AuditLogService;
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
    private final ConfigRollbackTool configRollbackTool;
    private final TrafficSwitchTool trafficSwitchTool;
    private final PodEvictTool podEvictTool;
    private final NodeDrainTool nodeDrainTool;
    private final ConnectionPoolTool connectionPoolTool;
    private final HealthCheckParamTool healthCheckParamTool;

    private final MeterRegistry meterRegistry;
    private final AuditLogService auditLogService;

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
        registerTool(configRollbackTool);
        registerTool(trafficSwitchTool);
        registerTool(podEvictTool);
        registerTool(nodeDrainTool);
        registerTool(connectionPoolTool);
        registerTool(healthCheckParamTool);

        agentExposed = Arrays.stream(agentosexualConfig.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toUnmodifiableSet());

