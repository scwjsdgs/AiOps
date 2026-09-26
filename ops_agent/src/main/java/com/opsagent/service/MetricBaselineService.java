package com.opsagent.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.opsagent.model.Alert;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 指标基线学习 + 异常预警（前瞻性增强方案一）。
 *
 * 解决的问题：系统对「正常水位」毫无记忆——{@link MetricService} 只在被查询时拉一次
 * Prometheus，用完即弃。于是只能等告警规则被触发（即故障已经发生）才有反应。
 *
 * 做法：周期采集各 Deployment 的 CPU / 内存 / 重启次数，在 Redis 里维护一个滑动窗口，
 * 实时算均值和标准差。当前值偏离基线超过 N 个标准差（默认 3σ）且连续命中，就生成一条
 * 「预测性预警」——此时 Prometheus 的告警规则通常还没触发，于是 agent 提前介入。
 *
 * 关键设计：预警本身就是一条普通告警，直接喂给 {@link AlertIngestionService}，
 * 下游（降噪 → ReAct 分析 → 报告 → 案例库）零新增逻辑，完全复用现有闭环。
 *
 * 为什么按 Deployment 聚合而不是按 Pod：Pod 会随滚动更新不断换名，
 * 拿单个 Pod 建基线意味着基线永远在冷启动。按 Deployment 聚合后基线稳定得多。
 *
 * 失败降级：Prometheus 不可达时不产生任何预警、只记日志，绝不影响主链路。
 */
@Slf4j
@Service
public class MetricBaselineService {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final TypeReference<List<Double>> DOUBLE_LIST = new TypeReference<>() {};

    private static final String BASELINE_PREFIX = "baseline:";
    private static final String COOLDOWN_PREFIX = "anomaly:cooldown:";

    /** 被基线的指标。cpu/memory 是瞬时水位，restarts 是累计计数器（看增量）。 */
    public static final String METRIC_CPU = "cpu";
    public static final String METRIC_MEMORY = "memory";
    public static final String METRIC_RESTARTS = "restarts";

    private final MetricService metricService;
    private final StringRedisTemplate redisTemplate;
    private final AlertIngestionService alertIngestionService;
    private final MeterRegistry meterRegistry;

    /**
     * @Lazy：AlertIngestionService 的下游回调链里挂着 AgentCallbackService，
     * 直接构造注入在多处容易绕成环。这里只在真要发预警时才解析它。
     */
    public MetricBaselineService(MetricService metricService,
                                 StringRedisTemplate redisTemplate,
                                 @Lazy AlertIngestionService alertIngestionService,
                                 MeterRegistry meterRegistry) {
        this.metricService = metricService;
        this.redisTemplate = redisTemplate;
        this.alertIngestionService = alertIngestionService;
        this.meterRegistry = meterRegistry;
    }

    @Value("${opsagent.anomaly.enabled:true}")
    private boolean enabled;

    @Value("${opsagent.anomaly.interval-seconds:60}")
    private long intervalSeconds;

    /** 偏离多少个标准差算异常。3σ 是经验值：误报率低，又足够灵敏。 */
    @Value("${opsagent.anomaly.sigma:3.0}")
    private double sigma;

    /** 连续命中多少次才预警。要求连续是为了过滤单点毛刺。 */
    @Value("${opsagent.anomaly.consecutive-hits:2}")
    private int consecutiveHits;

    /** 同一 Deployment 同一指标多久内只预警一次。 */
    @Value("${opsagent.anomaly.cooldown-minutes:30}")
    private long cooldownMinutes;

    /** 滑动窗口保留的采样点数。60 点 × 1 分钟 = 1 小时基线。 */
    @Value("${opsagent.anomaly.window-samples:60}")
    private int windowSamples;

    /** 冷启动保护：采样点少于这个数时不预警（均值/标准差还不可信）。 */
    @Value("${opsagent.anomaly.min-samples:12}")
    private int minSamples;

    /** 重启次数的绝对增量阈值（计数器不适合用 Z-score）。 */
    @Value("${opsagent.anomaly.restart-delta-threshold:3}")
    private int restartDeltaThreshold;

    /**
     * 噪声地板（占均值的百分比）——按指标分别设置。这是对「标准差过小」的兜底。
     *
     * 起因：内存这类指标在有 GC 的服务上极稳，实测 σ 仅占均值的 0.01%~0.6%
     * （nginx 约 0.01%、Grafana 约 0.3%，随采样窗口浮动），3σ 带因此被压到
     * 几百 KB 甚至几 KB。容器内存本来就会随页缓存抖动，于是「几千字节的正常抖动」
     * 被判成 5~9σ 异常并告警 —— 实测 24 小时内 58 条基线预警里有 19 条是这类内存噪声，
     * 而真正的故障（leaky-app 内存涨 208%）反而被淹没。
     *
     * 兜底逻辑：若实测 stdDev 小于「该指标的地板比例 × 均值」，就把 stdDev 抬到地板值。
     * 效果是给窄带指标一个最小判定宽度，让噪声不再越线，同时不削弱真实大偏差的检出。
     *
     * CPU 的地板故意设得很小：CPU 本身抖动比例就大（实测 12%~45%），
     * 用内存那么大的地板会把真正的 CPU 问题一起压掉。
     */
    @Value("${opsagent.anomaly.memory-noise-floor-percent:2.0}")
    private double memoryNoiseFloorPercent;

    @Value("${opsagent.anomaly.cpu-noise-floor-percent:0.5}")
    private double cpuNoiseFloorPercent;

    private Counter anomaliesDetected;
    private Counter anomalySuppressed;

    /** 连续命中计数：key = deployment|metric。Redis 里存会多一次往返，内存态足够。 */
    private final Map<String, Integer> consecutiveCounters = new ConcurrentHashMap<>();

    /** 上一次的累计重启数：key = deployment，用来算增量。 */
    private final Map<String, Long> lastRestartTotals = new ConcurrentHashMap<>();

    private Disposable collector;

    @PostConstruct
    public void start() {
        anomaliesDetected = Counter.builder("agent.anomaly.detected")
                .description("基线偏离检出次数（导致预警）").register(meterRegistry);
        anomalySuppressed = Counter.builder("agent.anomaly.suppressed")
                .description("检出但因冷却期被抑制的次数").register(meterRegistry);

        if (!enabled) {
            log.info("指标基线预警已关闭（opsagent.anomaly.enabled=false）");
            return;
        }
        // 与 WebSocket 心跳、回归验证保持同一套 Reactor 风格，不引入 @Scheduled
        long interval = Math.max(10, intervalSeconds);
        collector = Flux.interval(Duration.ofSeconds(interval), Duration.ofSeconds(interval))
                .onBackpressureDrop()
                .concatMap(tick -> Mono.fromRunnable(this::collectAndEvaluate)
                        .subscribeOn(Schedulers.boundedElastic())
                        .then())
                .subscribe(v -> { },
                        e -> log.error("指标基线采集流异常终止", e));
        log.info("指标基线预警已启动：每 {} 秒采样，{}σ 阈值，窗口 {} 点，冷启动 {} 点",
                interval, sigma, windowSamples, minSamples);
    }

    @PreDestroy
    public void stop() {
        if (collector != null && !collector.isDisposed()) {
            collector.dispose();
        }
    }

    /**
     * 一次采集 + 评估。
     *
     * 三个指标各拉一次 PromQL，按 Deployment 聚合后逐个进入「更新基线 → 判偏离」流程。
     */
    private void collectAndEvaluate() {
        try {
            Map<String, Double> cpu = aggregateByDeployment(
                    metricService.query("sum(rate(container_cpu_usage_seconds_total"
                            + "{container!=\"POD\",image!=\"\"}[3m])) by (pod)"));
            Map<String, Double> memory = aggregateByDeployment(
                    metricService.query("sum(container_memory_working_set_bytes"
                            + "{container!=\"POD\",image!=\"\"}) by (pod)"));
            Map<String, Double> restarts = aggregateByDeployment(
                    metricService.query("sum(kube_pod_container_status_restarts_total) by (pod)"));

            // Prometheus 三个查询全挂（返回 null/空）时直接跳过这一轮，不污染基线
            if (cpu.isEmpty() && memory.isEmpty() && restarts.isEmpty()) {
                log.debug("本轮未取到任何指标，跳过基线更新");
                return;
            }

            List<String> deployments = new ArrayList<>(union(cpu.keySet(), memory.keySet(), restarts.keySet()));
            deployments.sort(Comparator.naturalOrder());
            for (String deployment : deployments) {
                evaluateGauge(deployment, METRIC_CPU, cpu.get(deployment), "容器 CPU");
                evaluateGauge(deployment, METRIC_MEMORY, memory.get(deployment), "容器内存");
                evaluateRestarts(deployment, restarts.get(deployment));
            }
        } catch (Exception e) {
            // 基线采集是增强能力：失败只记日志，主链路（webhook/分析）完全不受影响
            log.warn("指标基线采集失败（可忽略）: {}", e.getMessage());
        }
    }

    /** 瞬时水位型指标：Z-score 判偏离。 */
    private void evaluateGauge(String deployment, String metric, Double value, String label) {
        if (value == null || !Double.isFinite(value)) {
            return;
        }
        List<Double> window = readWindow(deployment, metric);

        // 先判偏离（用不含当前点的历史窗口，否则当前异常值会把自己拉进正常范围）
        Deviation deviation = deviationOf(window, value, metric);
        boolean abnormal = deviation != null && deviation.sigmaAway >= sigma;

        // 再入窗，保证基线始终由"历史正常值"构成
        window.add(value);
        while (window.size() > Math.max(minSamples, windowSamples)) {
            window.remove(0);
        }
        writeWindow(deployment, metric, window);

        if (deviation == null) {
            return; // 冷启动期：样本不足，只积累不判定
        }

        String key = deployment + "|" + metric;
        if (!abnormal) {
            consecutiveCounters.remove(key);
            return;
        }

        int hits = consecutiveCounters.merge(key, 1, Integer::sum);
        if (hits < Math.max(1, consecutiveHits)) {
            log.debug("{} 偏离基线（{:.2f}σ）但连续命中不足：{}/{}",
                    deployment, deviation.sigmaAway, hits, consecutiveHits);
            return;
        }

        String direction = value > deviation.mean ? "高于" : "低于";
        raiseAlert(deployment, metric,
                label + "偏离基线",
                String.format("服务 %s 的%s为 %.4f，%s基线均值 %.4f（%.2fσ，阈值 %.1fσ），"
                                + "已连续 %d 个采样点异常。可能在告警规则触发前进一步恶化，建议提前介入。",
                        deployment, label, value, direction, deviation.mean,
                        deviation.sigmaAway, sigma, hits),
                Map.of(
                        "deployment", deployment,
                        "metric", metric,
                        "value", String.valueOf(value),
                        "baselineMean", String.valueOf(deviation.mean),
                        "baselineStdDev", String.valueOf(deviation.stdDev),
                        "rawStdDev", String.valueOf(deviation.rawStdDev),
                        "noiseFloorApplied", String.valueOf(deviation.noiseFloorApplied),
                        "sigmaAway", String.valueOf(deviation.sigmaAway)));
    }

    /**
     * 累计计数器型指标（重启次数）：看增量而不是绝对值。
     *
     * 重启总数会随 Pod 生命周期单调增长，用 Z-score 判它毫无意义；
     * 真正有意义的是「这一轮又多了几次重启」——那正是崩溃循环的信号。
     */
    private void evaluateRestarts(String deployment, Double total) {
        if (total == null || !Double.isFinite(total)) {
            return;
        }
        long current = total.longValue();
        Long previous = lastRestartTotals.put(deployment, current);
        if (previous == null) {
            return; // 首次见到，只记录基准
        }
        if (current < previous) {
            // 总数变小 = Pod 被替换过（新 Pod 计数器从 0 开始），重置基准不误报
            return;
        }
        long delta = current - previous;
        if (delta < Math.max(1, restartDeltaThreshold)) {
            return;
        }
        raiseAlert(deployment, METRIC_RESTARTS,
                "重启次数异常增长",
                String.format("服务 %s 的容器重启次数在最近一个采样周期内增加了 %d 次（累计 %d）。"
                                + "这是崩溃循环（CrashLoopBackOff）的典型信号，即使告警规则尚未触发也应尽早排查。",
                        deployment, delta, current),
                Map.of(
                        "deployment", deployment,
                        "metric", METRIC_RESTARTS,
                        "delta", String.valueOf(delta),
                        "total", String.valueOf(current)));
    }

    /**
     * 生成并投递一条预测性预警。
     *
     * 冷却窗口内同一 Deployment + 指标只报一次：基线偏离会持续好几个采样周期，
     * 不设冷却就会 5 分钟内刷出几十条几乎一样的告警，把 agent 淹没。
     */
    private void raiseAlert(String deployment, String metric, String title, String description,
                            Map<String, String> extra) {
        String cooldownKey = COOLDOWN_PREFIX + deployment + ":" + metric;
        Boolean fresh = redisTemplate.opsForValue()
                .setIfAbsent(cooldownKey, "1", Duration.ofMinutes(Math.max(1, cooldownMinutes)));
        if (Boolean.FALSE.equals(fresh)) {
            anomalySuppressed.increment();
            log.debug("预警处于冷却期，抑制：{}/{}", deployment, metric);
            return;
        }

        anomaliesDetected.increment();
        log.warn("基线偏离预警：{} - {}（{}）", deployment, title, description);

        Alert alert = new Alert();
        alert.setId(UUID.randomUUID().toString());
        alert.setSource("anomaly-detector");
        alert.setSeverity("WARNING");
        alert.setTitle("[预测性预警] " + deployment + " " + title);
        alert.setDescription(description);
        alert.setServiceName(deployment);
        alert.setHost("unknown");
        alert.setDetail("指标=" + metric + " " + extra);
        alert.setTimestamp(java.time.LocalDateTime.now());
        alert.setStatus("FIRING");

        // 与普通告警走完全相同的下游链路：落库 → 建任务 → 通知 agent 分析
        alertIngestionService.processAlert(alert)
                .subscribe(v -> { },
                        e -> log.warn("投递预测性预警失败 deployment={}: {}", deployment, e.getMessage()));
    }

    // ---------------- 基线窗口读写 ----------------

    private List<Double> readWindow(String deployment, String metric) {
        try {
            String json = redisTemplate.opsForValue().get(BASELINE_PREFIX + deployment + ":" + metric);
            if (json == null || json.isBlank()) {
                return new ArrayList<>();
            }
            List<Double> parsed = MAPPER.readValue(json, DOUBLE_LIST);
            return parsed == null ? new ArrayList<>() : new ArrayList<>(parsed);
        } catch (Exception e) {
            // 反序列化失败（格式被人手工改坏等）当作冷启动重来，不影响采集
            log.debug("读取基线窗口失败 {}/{}: {}", deployment, metric, e.getMessage());
            return new ArrayList<>();
        }
    }

    private void writeWindow(String deployment, String metric, List<Double> window) {
        try {
            redisTemplate.opsForValue().set(
                    BASELINE_PREFIX + deployment + ":" + metric,
                    MAPPER.writeValueAsString(window),
                    // 基线本身也带 TTL：服务下线后基线不该永久留在 Redis 里
                    Duration.ofHours(24));
        } catch (Exception e) {
            log.debug("写入基线窗口失败 {}/{}: {}", deployment, metric, e.getMessage());
        }
    }

    /**
     * 计算当前值相对历史窗口的偏离程度。
     * 样本不足时返回 null，表示"不可判定"而不是"正常"。
     *
     * @param metric 指标名，用来选择对应的噪声地板（不同指标的合理抖动比例差别很大）
     */
    private Deviation deviationOf(List<Double> window, double value, String metric) {
        if (window.size() < Math.max(2, minSamples)) {
            return null;
        }
        double mean = 0;
        for (double v : window) {
            mean += v;
        }
        mean /= window.size();

        double variance = 0;
        for (double v : window) {
            double d = v - mean;
            variance += d * d;
        }
        variance /= window.size();
        double stdDev = Math.sqrt(variance);

        // 噪声地板：实测标准差小于该指标的最小合理抖动时，抬到地板值。
        double rawStdDev = stdDev;
        double effectiveStdDev = applyNoiseFloor(stdDev, mean, metric);
        if (effectiveStdDev <= 0) {
            // 均值也为 0（如空闲服务的 CPU）：没有任何参照系，判为不可判定，
            // 比凭空造一个标准差更诚实。
            return null;
        }

        Deviation d = new Deviation();
        d.mean = mean;
        d.stdDev = effectiveStdDev;
        d.rawStdDev = rawStdDev;
        d.noiseFloorApplied = effectiveStdDev > rawStdDev;
        d.sigmaAway = Math.abs(value - mean) / effectiveStdDev;
        return d;
    }

    /**
     * 对实测标准差套用噪声地板，返回应实际使用的标准差。
     *
     * 抬地板的两种触发情形：
     *   1) stdDev 恰为 0（水位恒定或浮点下溢）—— 原先靠一个 1% 的临时兜底处理；
     *   2) stdDev 不为 0 但小得离谱（如仅占均值 0.01%）—— 更常见、也更会在生产上
     *      造成误报，原先完全没有保护。内存类指标在有 GC 的服务上极易落入这一档。
     *
     * 若地板比例配成 0 或负数，视为关闭地板，原样返回 —— 便于线上临时关掉对比。
     */
    static double applyNoiseFloor(double stdDev, double mean, String metric,
                                  double memoryFloorPercent, double cpuFloorPercent) {
        double ratio = METRIC_MEMORY.equals(metric)
                ? Math.max(0, memoryFloorPercent)
                : Math.max(0, cpuFloorPercent);
        if (ratio <= 0) {
            return stdDev;
        }
        double floor = Math.abs(mean) * ratio / 100.0;
        return Math.max(stdDev, floor);
    }

    private double applyNoiseFloor(double stdDev, double mean, String metric) {
        return applyNoiseFloor(stdDev, mean, metric,
                memoryNoiseFloorPercent, cpuNoiseFloorPercent);
    }

    // ---------------- 指标聚合 ----------------

    /**
     * 把 Prometheus 的逐 Pod 结果按 Deployment 聚合成一条。
     *
     * PromQL 只能 group by (pod)，Deployment 归属要在这里算：Pod 名形如
     * {@code <deploy>-<rs-hash>-<pod-hash>}，用已知 Deployment 名做最长前缀匹配。
     * CPU/内存等可加指标用求和聚合。
     *
     * 已知 Deployment 列表同时充当过滤器：PromQL 是集群全量的，会把
     * kube-proxy / kube-apiserver 这类控制面 Pod 也带进来。它们不属于目标
     * namespace 的任何 Deployment，按前缀匹配就会被排除在外。
     */
    private Map<String, Double> aggregateByDeployment(List<Map<String, Object>> rows) {
        Map<String, Double> result = new LinkedHashMap<>();
        if (rows == null || rows.isEmpty()) {
            return result;
        }

        List<String> known = knownDeployments();
        for (Map<String, Object> row : rows) {
            Object metricObj = row.get("metric");
            if (!(metricObj instanceof Map<?, ?> metric)) {
                continue;
            }
            Object pod = metric.get("pod");
            if (pod == null) {
                continue;
            }
            String deployment = deploymentOf(pod.toString(), known);
            if (deployment == null) {
                continue;
            }
            double value = parseValue(row.get("value"));
            if (!Double.isFinite(value)) {
                continue;
            }
            result.merge(deployment, value, Double::sum);
        }
        return result;
    }

    /**
     * Pod 名 → Deployment。
     *
     * 只接受「已知 Deployment 名的前缀」，不做无依据的字符串裁剪：从评论
     * {@code kube-apiserver-xxx} 裁出的 "kube" 并不是一个真实 Deployment，
     * 拿它建基线只会产生一堆永远无人看的噪音条目。
     * 已知列表为空（K8s 不可达）时返回 null，本轮不采集 —— 宁可少采一轮，
     * 也不产生错误的基线。
     */
    private String deploymentOf(String pod, List<String> knownDeployments) {
        if (knownDeployments == null || knownDeployments.isEmpty()) {
            return null;
        }
        String best = null;
        for (String deployment : knownDeployments) {
            if (pod.equals(deployment) || pod.startsWith(deployment + "-")) {
                // 最长前缀：app 与 app-v2 同时存在时，app-v2-xxx 应归给 app-v2
                if (best == null || deployment.length() > best.length()) {
                    best = deployment;
                }
            }
        }
        return best;
    }

    /** 当前 namespace 下的 Deployment 名列表，用于 Pod 归属推断。失败返回空表走兜底逻辑。 */
    private List<String> knownDeployments() {
        try {
            // query() 在 Prometheus 不可达时返回 null（不是空表），直接 stream 会 NPE
            List<Map<String, Object>> rows = metricService.query("kube_deployment_spec_replicas");
            if (rows == null || rows.isEmpty()) {
                return List.of();
            }
            return rows.stream()
                    .map(row -> row.get("metric"))
                    .filter(Map.class::isInstance)
                    .map(m -> ((Map<?, ?>) m).get("deployment"))
                    .filter(java.util.Objects::nonNull)
                    .map(Object::toString)
                    .distinct()
                    .toList();
        } catch (Exception e) {
            log.debug("获取 Deployment 列表失败，使用兜底推断: {}", e.getMessage());
            return List.of();
        }
    }

    private double parseValue(Object raw) {
        if (raw == null) {
            return Double.NaN;
        }
        try {
            return Double.parseDouble(raw.toString());
        } catch (NumberFormatException e) {
            return Double.NaN;
        }
    }

    private java.util.Set<String> union(java.util.Set<String>... sets) {
        java.util.Set<String> all = new java.util.TreeSet<>();
        for (java.util.Set<String> s : sets) {
            if (s != null) {
                all.addAll(s);
            }
        }
        return all;
    }

    private static final class Deviation {
        double mean;
        double stdDev;
        double sigmaAway;
        /** 窗口实测标准差（未抬地板）。用于区分"天然就这么稳"与"被地板压过"。 */
        double rawStdDev;
        /** stdDev 是否被噪声地板抬高过。 */
        boolean noiseFloorApplied;
    }

    // ---------------- 对外快照（前端「基线偏离」卡片） ----------------

    /**
     * 返回各 Deployment 当前值与基线区间的对照，供 Dashboard 展示。
     *
     * 只读、不触发预警，也不修改基线窗口 —— 前端刷新不会污染学习结果。
     */
    public Map<String, Object> snapshot() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("enabled", enabled);
        result.put("sigma", sigma);

        List<Map<String, Object>> rows = new ArrayList<>();
        try {
            Map<String, Double> cpu = aggregateByDeployment(
                    metricService.query("sum(rate(container_cpu_usage_seconds_total"
                            + "{container!=\"POD\",image!=\"\"}[3m])) by (pod)"));
            Map<String, Double> memory = aggregateByDeployment(
                    metricService.query("sum(container_memory_working_set_bytes"
                            + "{container!=\"POD\",image!=\"\"}) by (pod)"));

            for (String deployment : new java.util.TreeSet<>(union(cpu.keySet(), memory.keySet()))) {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("deployment", deployment);
                item.put("cpu", describe(deployment, METRIC_CPU, cpu.get(deployment)));
                item.put("memory", describe(deployment, METRIC_MEMORY, memory.get(deployment)));
                rows.add(item);
            }
            result.put("connected", !rows.isEmpty() || !cpu.isEmpty());
        } catch (Exception e) {
            log.warn("基线快照查询失败: {}", e.getMessage());
            result.put("connected", false);
        }
        result.put("deployments", rows);
        return result;
    }

    /** 单个指标的当前值 + 基线区间 + 偏离度。样本不足时 baseline 为 null。 */
    private Map<String, Object> describe(String deployment, String metric, Double value) {
        Map<String, Object> item = new LinkedHashMap<>();
        if (value == null) {
            return null;
        }
        item.put("current", value);

        List<Double> window = readWindow(deployment, metric);
        Deviation d = deviationOf(window, value, metric);
        if (d == null) {
            item.put("baselineReady", false);
            item.put("samples", window.size());
            item.put("minSamples", minSamples);
            // 区分两种"不可判定"：样本还不够 vs 信号长期恒定（如无流量时 CPU 恒为 0）。
            // 后者等再久也不会变成可判定，前端提示"积累中"会误导运维一直等下去。
            item.put("reason", window.size() < Math.max(2, minSamples) ? "ACCUMULATING" : "FLAT_SIGNAL");
            return item;
        }
        item.put("baselineReady", true);
        item.put("mean", d.mean);
        item.put("stdDev", d.stdDev);
        item.put("sigmaAway", d.sigmaAway);
        item.put("upper", d.mean + sigma * d.stdDev);
        item.put("lower", Math.max(0, d.mean - sigma * d.stdDev));
        item.put("abnormal", d.sigmaAway >= sigma);
        // 让前端能分辨"这个区间是实测出来的"还是"被噪声地板撑开的"：
        // 后者说明该指标天然极稳，带宽不代表它真的会抖这么多。
        item.put("rawStdDev", d.rawStdDev);
        item.put("noiseFloorApplied", d.noiseFloorApplied);
        // 偏离度百分比：前端按它给卡片标色
        item.put("deviationPercent", d.mean == 0 ? 0 : (value - d.mean) / d.mean * 100);
        return item;
    }
}
