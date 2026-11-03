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
    private final AlertLifecycleService alertLifecycleService;

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
        return processAlert(alert, false);
    }

    /**
     * 处理一条告警并触发根因分析。
     *
     * @param reuseExisting true 表示告警实体已由调用方落库（解除聚合复用老记录时），
     *                      这里不再新建行，直接以 alert.id 定位已有实体建任务并回填 taskId；
     *                      false 表示常规入口（REST/Kafka），保持原有「新建 + 保存」逻辑。
     *                      两种路径都保证新任务落到真实的告警记录上，且建任务成功后把
     *                      taskId 回写到告警，前端「查看分析」能直接打开报告。
     */
    public Mono<Void> processAlert(Alert alert, boolean reuseExisting) {
        alertReceivedCounter.increment();
        log.info("processAlert 入口: id={} reuseExisting={} fingerprint={} service={} title={}",
                alert.getId(), reuseExisting, alert.getFingerprint(),
                alert.getServiceName(), alert.getTitle());

        // 1. 确定根因分析任务的关联告警实体。
        //    reuseExisting 时绝不新建行：直接从库中读取调用方已复用/新建的实体，
        //    锚点就是它，任务与告警的关联不会因为「解除聚合」而割裂成两条记录。
        Mono<AlertEntity> anchorMono;
        if (reuseExisting) {
            anchorMono = Mono.fromCallable(() -> {
                        if (alert.getId() == null || alert.getId().isBlank()) {
                            return (AlertEntity) null;
                        }
                        return alertRepository.findById(alert.getId()).orElse(null);
                    })
                    .subscribeOn(Schedulers.boundedElastic())
                    .flatMap(existing -> {
                        if (existing == null) {
                            // 兜底：调用方所谓「已落库」实际上没查到（异常路径），
                            // 退化为新建，宁可多建也不让告警无任务。
                            log.warn("reuseExisting 但库中找不到 id={}（或 id 为空），退化为新建实体", alert.getId());
                            return Mono.fromCallable(() -> saveNewAlert(alert));
                        }
                        log.info("processAlert 复用已有告警实体 alertId={} status={} occurrenceCount={}",
                                existing.getId(), existing.getStatus(), existing.getOccurrenceCount());
                        return Mono.just(existing);
                    });
        } else {
            // 常规入口：新建告警并保存（Webhook 以外的 REST/Kafka 场景）。
            anchorMono = Mono.fromCallable(() -> saveNewAlert(alert));
        }

        // 任何锚点解析异常（数据库抖动、findById 抛异常等）都不能让告警静默丢失：
        // 退化为新建一条并继续建任务。
        Mono<AlertEntity> safeAnchor = anchorMono
                .onErrorResume(e -> {
                    log.error("解析告警锚点异常，退化为新建（避免任务静默丢失） id={} reuseExisting={}: {}",
                            alert.getId(), reuseExisting, e.getMessage(), e);
                    return Mono.fromCallable(() -> saveNewAlert(alert));
                });

        // 2. 创建根因分析任务
        return safeAnchor
                .subscribeOn(Schedulers.boundedElastic())
                .flatMap(saved -> {
                    String alertId = saved.getId();
                    // 【业务规则一】拒绝冷却 / 重试耗尽 的二次防线：
                    // Webhook 通道已在入口按 fingerprint 拦截，但 REST / Kafka 通道直接
                    // 调用 processAlert，且不做降噪聚合（每次新建一行），必须在这里同样拦住。
                    // 抑制对象按「本次锚点 → 同服务最近一条冷却/耗尽记录」顺序查找：
                    // 同一服务持续报错就该被抑制，不该因为 REST 通道不聚合而绕开防骚扰规则。
                    AlertLifecycleService.SuppressAction suppress = alertLifecycleService.checkSuppress(saved);
                    AlertEntity suppressTarget = saved;
                    if (suppress == AlertLifecycleService.SuppressAction.NONE) {
                        AlertEntity sibling = findCoolingSibling(saved);
                        if (sibling != null) {
                            suppress = alertLifecycleService.checkSuppress(sibling);
                            suppressTarget = sibling;
                        }
                    }
                    if (suppress != AlertLifecycleService.SuppressAction.NONE) {
                        int next = (suppressTarget.getOccurrenceCount() == null ? 1 : suppressTarget.getOccurrenceCount()) + 1;
                        suppressTarget.setOccurrenceCount(next);
                        alertRepository.save(suppressTarget);
                        // 新落库的这行是重复噪音：直接删除，避免列表里堆满同服务的重复告警。
                        // 发生次数已累加到冷却中的主记录上，前端看到的是「该故障已持续 X 次」。
                        if (!java.util.Objects.equals(suppressTarget.getId(), saved.getId())) {
                            try {
                                alertRepository.deleteById(saved.getId());
                            } catch (Exception delEx) {
                                log.warn("清理重复告警行失败 alertId={}: {}", saved.getId(), delEx.getMessage());
                            }
                        }
                        log.info("[{}] 告警 {} 命中抑制（主记录 {}），仅累加次数至 {}，不建任务不弹审批",
                                suppress, saved.getId(), suppressTarget.getId(), next);
                        return Mono.empty();
                    }
                    log.info("processAlert 锚点确认 alertId={} reuseExisting={} -> 开始建任务", alertId, reuseExisting);
                    // 触发分析前先把影响面算出来，拼进任务输入 ——
                    // 让 LLM 一开始就知道「这影响了哪些流量入口、是否已断流」，
                    // 而不必自己从零散日志里猜。查不到就退回原始描述，不阻塞建任务。
                    String input = enrichWithImpact(
                            alert.getDescription() != null && !alert.getDescription().isBlank()
                                    ? alert.getDescription()
                                    : "服务 " + safeServiceName(alert.getServiceName()) + " 发生告警：" + alert.getTitle());
                    return taskScheduler.createTask("ROOT_CAUSE_ANALYSIS", alertId, input)
                            .flatMap(task -> {
                                log.info("processAlert 已建任务 taskId={} alertId={} inputLen={}",
                                        task.getId(), alertId, input == null ? 0 : input.length());
                                // 建任务成功后把 taskId 回写到告警实体：解除聚合/复用的老记录
                                // 踢出新任务后必须知道「这次是哪个任务在分析」，否则前端
                                // 无法从告警跳转到新报告，列表也看不到本次分析的关联。
                                return Mono.fromCallable(() -> {
                                            if (alertId == null || alertId.isBlank()) {
                                                return (AlertEntity) null;
                                            }
                                            return alertRepository.findById(alertId).orElse(null);
                                        })
                                        .flatMap(e -> {
                                            if (e != null) {
                                                e.setTaskId(task.getId());
                                                alertRepository.save(e);
                                                log.info("processAlert 告警 {} taskId 已回写为 {}", alertId, task.getId());
                                            }
                                            return Mono.just(task);
                                        })
                                        // 回写失败不影响主链路
                                        .onErrorResume(err -> {
                                            log.warn("回写告警 taskId 失败 alertId={} taskId={}: {}",
                                                    alertId, task.getId(), err.getMessage());
                                            return Mono.just(task);
                                        })
                                        .flatMap(t -> {
                                            // 这里是 fire-and-forget，绝不能等 agent 跑完。
                                            // ReAct 循环要几十秒到几分钟，而前端 axios 只等 10 秒，
                                            // 同步等结果会让 POST /api/alerts 必定超时。
                                            // 注意必须用 onComplete 回调标记 ANALYZING：notifyAgent
                                            // 返回 Mono<Void>，完成时不发任何元素，两参数 subscribe
                                            // 的 onNext 永远不会触发，告警会永远停在 PENDING。
                                            agentCallbackService.notifyAgent(t.getId(), input,
                                                    alert.getTitle(), alert.getServiceName(), alert.isReadonly())
                                                    .subscribe(
                                                            null,
                                                            e -> markAlert(alertId, "FAILED", false),
                                                            () -> markAlert(alertId, "ANALYZING", true));
                                            log.info("processAlert 已通知 Agent taskId={}，等待回传", t.getId());
                                            return Mono.just(t);
                                        });
                            });
                })
                .then();
    }

    /** 新建一条告警实体（常规入口专用）。 */
    private AlertEntity saveNewAlert(Alert alert) {
        AlertEntity entity = new AlertEntity();
        BeanUtils.copyProperties(alert, entity);
        entity.setFingerprint(alert.getFingerprint());
        entity.setId(alert.getId() != null ? alert.getId() : UUID.randomUUID().toString());
        entity.setCreateTime(LocalDateTime.now());
        entity.setProcessed(false);
        entity.setStatus("PENDING");
        return alertRepository.save(entity);
    }

    /**
     * 查找同服务下仍在冷却期/已重试耗尽的记录。
     *
     * REST / Kafka 通道每次告警都会新建一行，无法像 Webhook 那样按 fingerprint 命中
     * 主记录。防骚扰规则必须基于「同一故障」而不是「同一行」，所以这里按服务名
     * 找最近一条仍处于冷却中（或已耗尽）的记录，把新告警的次数累加到它身上。
     *
     * 找不到返回 null（正常放行）。
     */
    private AlertEntity findCoolingSibling(AlertEntity current) {
        String service = current == null ? null : current.getServiceName();
        if (service == null || service.isBlank()) {
            return null;
        }
        try {
            // 冷却中的记录状态是 PENDING；耗尽的是 FAILED，两种都要看。
            java.util.List<AlertEntity> recent = alertRepository
                    .findByServiceNameAndStatusInOrderByCreateTimeDesc(
                            service, java.util.List.of("PENDING", "FAILED"));
            for (AlertEntity candidate : recent) {
                if (candidate == null || java.util.Objects.equals(candidate.getId(), current.getId())) {
                    continue;
                }
                boolean cooling = candidate.getRejectCooldownUntil() != null;
                boolean exhausted = candidate.getRetryExhaustedAt() != null;
                if (cooling || exhausted) {
                    return candidate;
                }
            }
            return null;
        } catch (Exception e) {
            log.warn("查找同服务冷却记录失败（放行新告警） service={}: {}", service, e.getMessage());
            return null;
        }
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

    private String safeServiceName(String name) {
        return name == null || name.isBlank() ? "unknown" : name;
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
