package com.opsagent.service;

import com.opsagent.entity.AlertEntity;
import com.opsagent.model.Alert;
import com.opsagent.repository.AlertRepository;
import com.opsagent.utils.JsonUtils;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
@Slf4j
@RequiredArgsConstructor
public class AlertIngestionService {

    private final TaskSchedulerService taskScheduler;
    private final AgentCallbackService agentCallbackService;
    private final AlertRepository alertRepository;
    private final MeterRegistry meterRegistry;
    private final ImpactAnalysisService impactAnalysisService;

    private Counter alertReceivedCounter;
    private Counter alertProcessedCounter;
    private Counter alertFailedCounter;

    @PostConstruct
    public void initMetrics() {
        alertReceivedCounter = Counter.builder("agent.alert.received")
                .description("Total alerts received")
                .register(meterRegistry);
        alertProcessedCounter = Counter.builder("agent.alert.processed")
                .description("Alerts successfully processed")
                .register(meterRegistry);
        alertFailedCounter = Counter.builder("agent.alert.failed")
                .description("Alerts failed to process")
                .register(meterRegistry);
    }

    /**
     * 注意这里没有 @Transactional —— 它对返回 Mono 的方法完全无效，
     * 只会让人误以为 JPA 操作在一个事务里。真正的修正是把阻塞调用挪到
     * boundedElastic，而不是加个不起作用的注解。
     */
    public Mono<Void> processAlert(Alert alert) {
        alertReceivedCounter.increment();
        log.info("Received alert: {}", alert);

        // 1. 保存告警到数据库
        AlertEntity entity = new AlertEntity();
        BeanUtils.copyProperties(alert, entity);
        entity.setId(alert.getId() != null ? alert.getId() : UUID.randomUUID().toString());
        entity.setCreateTime(LocalDateTime.now());
        entity.setProcessed(false);
        entity.setStatus("PENDING");

        // 2. 创建根因分析任务
        return Mono.fromCallable(() -> alertRepository.save(entity))
                // JPA 是阻塞 IO，直接跑在 Netty event loop 上，并发一上来整个服务会被拖死
                .subscribeOn(Schedulers.boundedElastic())
                .flatMap(saved -> {
                    String alertId = saved.getId();
                    // 触发分析前先把影响面算出来，拼进任务输入 ——
                    // 让 LLM 一开始就知道「这影响了哪些流量入口、是否已断流」，
                    // 而不必自己从零散日志里猜。查不到就退回原始描述，不阻塞建任务。
                    String input = enrichWithImpact(alert.getDescription());
                    return taskScheduler.createTask("ROOT_CAUSE_ANALYSIS", alertId, input)
                            .flatMap(task -> {
                                // 这里是 fire-and-forget，绝不能等 agent 跑完。
                                // ReAct 循环要几十秒到几分钟，而前端 axios 只等 10 秒，
                                // 同步等结果会让 POST /api/alerts 必定超时。
                                // 注意必须用 onComplete 回调标记 ANALYZING：notifyAgent
                                // 返回 Mono<Void>，完成时不发任何元素，两参数 subscribe
                                // 的 onNext 永远不会触发，告警会永远停在 PENDING。
                                agentCallbackService.notifyAgent(task.getId(), input)
                                        .subscribe(
                                                null,
                                                e -> markAlert(alertId, "FAILED", false),
                                                () -> markAlert(alertId, "ANALYZING", true));
                                return Mono.just(task);
                            });
                })
                .then();
    }

    /**
     * 把影响面摘要追加到任务输入末尾。
     *
     * 失败（K8s 不可达、解析不出服务名）一律退回原始描述：影响面是增强信息，
     * 拿不到时分析该怎么做还怎么做，绝不能因为它让告警处理失败。
     */
    private String enrichWithImpact(String description) {
        try {
            String service = impactAnalysisService.resolveServiceFromText(description);
            if (service == null) {
                return description;
            }
            Map<String, Object> impact = impactAnalysisService.analyze(service);
            if (!Boolean.TRUE.equals(impact.get("available"))) {
                return description;
            }
            Object summary = impact.get("summary");
            if (summary == null || summary.toString().isBlank()) {
                return description;
            }
            return description + "\n\n" + summary;
        } catch (Exception e) {
            log.warn("影响面注入失败（不影响分析）: {}", e.getMessage());
            return description;
        }
    }

    private void markAlert(String alertId, String status, boolean processed) {
        Mono.fromRunnable(() -> alertRepository.findById(alertId).ifPresent(e -> {
                    e.setStatus(status);
                    e.setProcessed(processed);
                    alertRepository.save(e);
                }))
                .subscribeOn(Schedulers.boundedElastic())
                .subscribe(v -> { },
                        e -> log.error("更新告警状态失败 alertId={} status={}", alertId, status, e));
    }

    /**
     * Dashboard 统计数据源：最近 N 天的每日分级计数 + 级别分布 + 状态计数。
     *
     * 返回结构：
     * - daily: [{date, severity, count}]  最近 days 天，按日期+级别聚合
     * - bySeverity: [{severity, count}]   全量按级别聚合
     * - byStatus: {PENDING: n, ANALYZING: n, RESOLVED: n, FAILED: n}  供概览卡片
     *
     * JPA 阻塞调用，调用方（Controller）负责挪到 boundedElastic。
     */
    public Map<String, Object> stats(int days) {
        LocalDateTime since = LocalDateTime.now().minusDays(Math.max(days, 1)).toLocalDate().atStartOfDay();
        List<Object[]> daily = alertRepository.countDailyBySeverity(since);
        List<Object[]> bySeverity = alertRepository.countBySeverity();
        List<Object[]> byStatus = alertRepository.countByStatus();

        List<Map<String, Object>> dailyList = new ArrayList<>();
        for (Object[] row : daily) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("date", String.valueOf(row[0]));
            item.put("severity", row[1] == null ? "UNKNOWN" : row[1]);
            item.put("count", ((Number) row[2]).longValue());
            dailyList.add(item);
        }

        List<Map<String, Object>> severityList = new ArrayList<>();
        for (Object[] row : bySeverity) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("severity", row[0] == null ? "UNKNOWN" : row[0]);
            item.put("count", ((Number) row[1]).longValue());
            severityList.add(item);
        }

        Map<String, Object> statusMap = new LinkedHashMap<>();
        for (Object[] row : byStatus) {
            statusMap.put(row[0] == null ? "UNKNOWN" : String.valueOf(row[0]), ((Number) row[1]).longValue());
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("daily", dailyList);
        result.put("bySeverity", severityList);
        result.put("byStatus", statusMap);
        return result;
    }

    /**
     * 注意 message 可能是多行 pretty-print 的 JSON（比如用 kafka-console-producer
     * 粘贴进去的测试消息）。Kafka 记录本身是完整的，但某些写入方会把一条 JSON
     * 按行拆开写；先压平再反序列化，否则 readValue 在第一个换行处就报错，
     * 告警凭空消失、消费组 offset 停滞、堆积越滚越大。
     */
    @KafkaListener(topics = "alerts", groupId = "agent-group")
    public void consumeAlert(String message) {
        String compact = message == null ? null : message.replaceAll("\\s+", " ").trim();
        Alert alert = JsonUtils.fromJson(compact, Alert.class);
        if (alert == null) {
            // JsonUtils 已经打过反序列化失败的日志；这里补上业务后果，
            // 否则这条告警就凭空消失了，排查时毫无线索
            log.error("告警反序列化失败，消息被丢弃: {}", compact);
            alertFailedCounter.increment();
            return;
        }
        if (alert.getId() == null || alert.getId().isEmpty()) {
            alert.setId(UUID.randomUUID().toString());
        }
        log.info("Kafka 告警已消费: id={} title={} service={}", alert.getId(), alert.getTitle(), alert.getServiceName());
        processAlert(alert).subscribe(
                v -> { },
                e -> {
                    log.error("处理告警失败 alertId={}", alert.getId(), e);
                    alertFailedCounter.increment();
                });
    }
}
