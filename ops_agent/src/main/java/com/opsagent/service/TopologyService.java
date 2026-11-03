package com.opsagent.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.fabric8.kubernetes.api.model.Endpoints;
import io.fabric8.kubernetes.api.model.EndpointSubset;
import io.fabric8.kubernetes.api.model.ObjectReference;
import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.api.model.apps.Deployment;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 服务拓扑（前瞻性增强方案二的数据层）。
 *
 * 解决的问题：系统原本只知道「哪个 Pod 挂了」，不知道「这个 Pod 挂着谁的流量」。
 * 降噪聚合键（namespace + pod 前缀）能判断同源，但判断不了影响面。根因定位因此
 * 完全依赖 LLM 从零散日志里猜，拿不出「影响了哪些调用方」这种结构化结论。
 *
 * 数据来源：K8s 原生对象，不需要额外组件——
 *   Deployment → selector → Pod            （服务由哪些实例组成）
 *   Service    → Endpoints → 地址的 targetRef → Pod   （谁把流量导给这些实例）
 *   Service    → selector → Pod            （Endpoints 缺失时的兜底关联）
 *
 * 只读、无副作用。Redis 缓存 TTL 默认 60 秒：拓扑变化不频繁，而告警触发分析时
 * 对延迟敏感（前端 axios 只等 10 秒），不能每次都去问 apiserver。
 *
 * K8s HTTP 是阻塞 IO，调用方必须挪到 boundedElastic。
 */
@Slf4j
@Service
public class TopologyService {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final TypeReference<Map<String, List<String>>> STRING_LIST_MAP = new TypeReference<>() {};
    private static final String CACHE_PREFIX = "topology:pod-to-services:";

    private final KubernetesToolClientHolder holder;
    private final StringRedisTemplate redisTemplate;

    public TopologyService(KubernetesToolClientHolder holder, StringRedisTemplate redisTemplate) {
        this.holder = holder;
        this.redisTemplate = redisTemplate;
    }

    /** 拓扑缓存 TTL（秒）。告警爆发时同一 namespace 会被反复查询，缓存能挡掉绝大部分 apiserver 调用。 */
    @Value("${opsagent.topology.cache-seconds:60}")
    private long cacheSeconds;

    /**
     * Pod 名 → 指向它的 Service 名列表（默认 namespace）。
     *
     * 优先读缓存；缓存未命中时现场构建一次。构建失败返回空表 —— 查询类能力，
     * 拿不到影响面不应该是错误，agent 照常按日志分析。
     */
    public Map<String, List<String>> podToServices() {
        return podToServices(null);
    }

    /**
     * Pod 名 → 指向它的 Service 名列表。
     *
     * @param namespace 目标 namespace；null/空白时回退默认配置。
     *                  优先读该 namespace 的缓存；缓存未命中时现场构建。
     */
    public Map<String, List<String>> podToServices(String namespace) {
        String ns = resolveNamespace(namespace);
        Map<String, List<String>> cached = readCache(ns);
        if (cached != null) {
            return cached;
        }
        Map<String, List<String>> built = buildPodToServices(ns);
        writeCache(ns, built);
        return built;
    }

    /** 解析 namespace：空值回退到默认配置，避免各处重复写三元表达式。 */
    private String resolveNamespace(String namespace) {
        return (namespace == null || namespace.isBlank())
                ? holder.namespace() : namespace.trim();
    }

    /** 现场构建 Pod → Services 映射（指定 namespace）。 */
    private Map<String, List<String>> buildPodToServices(String namespace) {
        Map<String, Set<String>> index = new LinkedHashMap<>();
        try {
            List<io.fabric8.kubernetes.api.model.Service> services =
                    holder.client().services().inNamespace(namespace).list().getItems();

            for (io.fabric8.kubernetes.api.model.Service svc : services) {
                if (svc.getMetadata() == null) {
                    continue;
                }
                String svcName = svc.getMetadata().getName();
                Set<String> targets = new LinkedHashSet<>();

                // 通道一：Service → Endpoints → targetRef.name（Pod 名）。
                // 这是最准的关联：Endpoints 里的地址就是此刻真正在接流量的 Pod。
                targets.addAll(podsBehindService(namespace, svcName));

                // 通道二：selector 匹配。Endpoints 对象可能还没来得及创建/更新
                // （比如 Pod 刚起来），selector 能补上这层静态关系。
                targets.addAll(podsBySelector(namespace, svc.getSpec() != null ? svc.getSpec().getSelector() : null));

                for (String pod : targets) {
                    index.computeIfAbsent(pod, k -> new LinkedHashSet<>()).add(svcName);
                }
            }
        } catch (Exception e) {
            log.warn("构建服务拓扑失败（影响面分析将降级）: {}", e.getMessage());
        }

        Map<String, List<String>> result = new LinkedHashMap<>();
        index.forEach((pod, svcs) -> result.put(pod, new ArrayList<>(svcs)));
        return result;
    }

    /** 从 Endpoints 取该 Service 后端的 Pod 名（含未就绪地址 —— 未就绪也要算作"受影响"）。 */
    private Set<String> podsBehindService(String namespace, String serviceName) {
        Set<String> pods = new LinkedHashSet<>();
        try {
            Endpoints endpoints = holder.client().endpoints().inNamespace(namespace).withName(serviceName).get();
            if (endpoints == null || endpoints.getSubsets() == null) {
                return pods;
            }
            for (EndpointSubset subset : endpoints.getSubsets()) {
                collectPodNames(subset.getAddresses(), pods);
                // notReadyAddresses：Pod 存在但没通过就绪探针 —— 正是故障态，
                // 恰恰最需要算进影响面，不能只统计健康的后端。
                collectPodNames(subset.getNotReadyAddresses(), pods);
            }
        } catch (Exception e) {
            log.debug("读取 Endpoints 失败 service={}: {}", serviceName, e.getMessage());
        }
        return pods;
    }

    private void collectPodNames(List<io.fabric8.kubernetes.api.model.EndpointAddress> addresses, Set<String> sink) {
        if (addresses == null) {
            return;
        }
        for (var addr : addresses) {
            ObjectReference ref = addr.getTargetRef();
            // 只有 Pod 类型的后端才有 pod 名；ExternalName/裸 IP 后端跳过
            if (ref != null && "Pod".equals(ref.getKind()) && ref.getName() != null) {
                sink.add(ref.getName());
            }
        }
    }

    /** 按 Service 的 selector 反查 Pod 名。selector 为空（ExternalName 等）返回空表。 */
    private Set<String> podsBySelector(String namespace, Map<String, String> selector) {
        Set<String> pods = new LinkedHashSet<>();
        if (selector == null || selector.isEmpty()) {
            return pods;
        }
        try {
            for (Pod pod : holder.client().pods().inNamespace(namespace).withLabels(selector).list().getItems()) {
                if (pod.getMetadata() != null && pod.getMetadata().getName() != null) {
                    pods.add(pod.getMetadata().getName());
                }
            }
        } catch (Exception e) {
            log.debug("按 selector 查询 Pod 失败: {}", e.getMessage());
        }
        return pods;
    }

    /**
     * Deployment → 指向它的 Service 列表（去重）。
     *
     * 两条路径互为兜底：
     * 1. Pod 反查：Deployment 的 Pod 在跑时，凭 Pod 名 → Service 的解析最准
     *    （含未就绪 Pod，故障态也要算）。
     * 2. 静态 label 匹配：Deployment 副本为 0（没有 Pod 可反查）时，通过
     *    「Service.selector ⊆ Deployment template.labels」直接关联 ——
     *    否则 0 副本服务的 Service 永远「未关联」，影响面分析报假「无入口」。
     */
    public List<String> servicesOfDeployment(String deploymentName) {
        return servicesOfDeployment(deploymentName, null);
    }

    /**
     * Deployment → 指向它的 Service 列表（去重），可指定 namespace。
     *
     * @param namespace 目标 namespace；null/空白时回退默认配置。
     */
    public List<String> servicesOfDeployment(String deploymentName, String namespace) {
        String ns = resolveNamespace(namespace);
        Map<String, List<String>> index = podToServices(ns);
        Set<String> services = new LinkedHashSet<>();
        log.info("[Topology] servicesOfDeployment 入口 deployment={} ns={} 索引大小={}",
                deploymentName, ns, index.size());
        try {
            Deployment deployment = holder.client().apps().deployments()
                    .inNamespace(ns).withName(deploymentName).get();
            log.info("[Topology] 查询 Deployment ns={} name={} => found={}",
                    ns, deploymentName, deployment != null);
            if (deployment == null) {
                log.warn("[Topology] Deployment 不存在 ns={} name={}，无法反查 Service", ns, deploymentName);
                return new ArrayList<>();
            }

            // 路径一：Deployment 下所有 Pod（含 0 副本 = 空）通过 Pod 名反查。
            List<Pod> pods = holder.client().pods().inNamespace(ns)
                    .withLabelSelector(deployment.getSpec().getSelector()).list().getItems();
            log.info("[Topology] 路径一 Pod 反查 ns={} deployment={} pods={} 个",
                    ns, deploymentName, pods.size());
            for (Pod pod : pods) {
                if (pod.getMetadata() == null) {
                    continue;
                }
                List<String> svcs = index.get(pod.getMetadata().getName());
                log.debug("[Topology]   Pod {} => services={}", pod.getMetadata().getName(), svcs);
                if (svcs != null) {
                    services.addAll(svcs);
                }
            }

            // 路径二：静态 label 匹配。0 副本部署没有 Pod，路径一必然为空；
            // 改从 Service 侧直接看 selector 是否与 Deployment 的 template label 匹配。
            if (services.isEmpty()) {
                log.info("[Topology] 路径一未命中任何 Service（0 副本或无 Pod），进入路径二静态 label 匹配");
                if (deployment.getSpec() != null
                        && deployment.getSpec().getTemplate() != null
                        && deployment.getSpec().getTemplate().getMetadata() != null
                        && deployment.getSpec().getTemplate().getMetadata().getLabels() != null) {
                    Map<String, String> templateLabels = deployment.getSpec().getTemplate().getMetadata().getLabels();
                    log.info("[Topology] Deployment template labels = {}", templateLabels);
                    List<io.fabric8.kubernetes.api.model.Service> allSvc =
                            holder.client().services().inNamespace(ns).list().getItems();
                    log.info("[Topology] ns={} 下共 {} 个 Service，开始静态匹配", ns, allSvc.size());
                    for (io.fabric8.kubernetes.api.model.Service svc : allSvc) {
                        if (svc.getMetadata() == null) {
                            continue;
                        }
                        Map<String, String> selector = svc.getSpec() != null ? svc.getSpec().getSelector() : null;
                        if (selector == null || selector.isEmpty()) {
                            continue;
                        }
                        // selector 每个键值都必须在 template label 同名命中才算匹配
                        boolean matches = selector.entrySet().stream()
                                .allMatch(e -> Objects.equals(e.getValue(), templateLabels.get(e.getKey())));
                        log.info("[Topology]   Service {} selector={} => 匹配={}", svc.getMetadata().getName(), selector, matches);
                        if (matches) {
                            services.add(svc.getMetadata().getName());
                        }
                    }
                } else {
                    log.warn("[Topology] Deployment 无 template labels，静态匹配无法执行");
                }
            }
        } catch (Exception e) {
            log.warn("解析 Deployment 对应服务失败 deployment={} ns={}: {}", deploymentName, ns, e.getMessage());
        }
        log.info("[Topology] servicesOfDeployment 结果 deployment={} ns={} => {}",
                deploymentName, ns, services);
        return new ArrayList<>(services);
    }

    /**
     * Service 当前的端点健康度：就绪地址数 / 未就绪地址数（默认 namespace）。
     * 「就绪为 0 且有未就绪」= 服务已断流，这是影响面分析里最关键的一个信号。
     */
    public Map<String, Object> serviceEndpointHealth(String serviceName) {
        return serviceEndpointHealth(serviceName, null);
    }

    /**
     * Service 当前的端点健康度，可指定 namespace。
     * 「就绪为 0 且有未就绪」= 服务已断流。
     */
    public Map<String, Object> serviceEndpointHealth(String serviceName, String namespace) {
        String ns = resolveNamespace(namespace);
        Map<String, Object> result = new LinkedHashMap<>();
        int ready = 0;
        int notReady = 0;
        try {
            Endpoints endpoints = holder.client().endpoints()
                    .inNamespace(ns).withName(serviceName).get();
            if (endpoints != null && endpoints.getSubsets() != null) {
                for (EndpointSubset subset : endpoints.getSubsets()) {
                    ready += subset.getAddresses() != null ? subset.getAddresses().size() : 0;
                    notReady += subset.getNotReadyAddresses() != null ? subset.getNotReadyAddresses().size() : 0;
                }
            }
        } catch (Exception e) {
            log.debug("读取服务端点失败 service={}: {}", serviceName, e.getMessage());
        }
        result.put("service", serviceName);
        result.put("readyEndpoints", ready);
        result.put("notReadyEndpoints", notReady);
        // 就绪端点为 0 且总数大于 0 = 有实例但全部不可用 => 断流
        result.put("outage", ready == 0 && notReady > 0);
        return result;
    }

    /** 拓扑快照，供前端关系图渲染：服务 → Pod（含就绪状态）。 */
    public Map<String, Object> snapshot(String deploymentFilter) {
        Map<String, Object> result = new LinkedHashMap<>();
        try {
            String namespace = holder.namespace();
            List<io.fabric8.kubernetes.api.model.Service> services =
                    holder.client().services().inNamespace(namespace).list().getItems();
            Map<String, List<String>> podIndex = podToServices();

            // Pod 名 -> 就绪状态（关系图上节点着色用）
            Map<String, Boolean> podReady = new LinkedHashMap<>();
            Map<String, String> podDeployment = new LinkedHashMap<>();
            for (Pod pod : holder.client().pods().inNamespace(namespace).list().getItems()) {
                if (pod.getMetadata() == null) {
                    continue;
                }
                String name = pod.getMetadata().getName();
                boolean ready = pod.getStatus() != null && pod.getStatus().getContainerStatuses() != null
                        && pod.getStatus().getContainerStatuses().stream()
                        .allMatch(cs -> Boolean.TRUE.equals(cs.getReady()));
                podReady.put(name, ready);
                podDeployment.put(name, ownerDeployment(pod));
            }

            List<Map<String, Object>> nodes = new ArrayList<>();
            List<Map<String, Object>> links = new ArrayList<>();
            Set<String> emitted = new LinkedHashSet<>();

            for (io.fabric8.kubernetes.api.model.Service svc : services) {
                if (svc.getMetadata() == null) {
                    continue;
                }
                String svcName = svc.getMetadata().getName();
                List<String> backendPods = new ArrayList<>();
                for (Map.Entry<String, List<String>> e : podIndex.entrySet()) {
                    if (e.getValue().contains(svcName)) {
                        backendPods.add(e.getKey());
                    }
                }
                if (deploymentFilter != null && !deploymentFilter.isBlank()) {
                    // 只看与目标 Deployment 相关的服务，避免关系图变成一坨毛线
                    boolean related = backendPods.stream()
                            .anyMatch(p -> deploymentFilter.equals(podDeployment.get(p)));
                    if (!related) {
                        continue;
                    }
                }

                Map<String, Object> svcHealth = serviceEndpointHealth(svcName);
                Map<String, Object> node = new LinkedHashMap<>();
                node.put("id", "svc:" + svcName);
                node.put("name", svcName);
                node.put("category", "service");
                node.put("outage", svcHealth.get("outage"));
                node.put("readyEndpoints", svcHealth.get("readyEndpoints"));
                node.put("notReadyEndpoints", svcHealth.get("notReadyEndpoints"));
                nodes.add(node);

                for (String pod : backendPods) {
                    String podId = "pod:" + pod;
                    if (emitted.add(podId)) {
                        Map<String, Object> podNode = new LinkedHashMap<>();
                        podNode.put("id", podId);
                        podNode.put("name", pod);
                        podNode.put("category", "pod");
                        podNode.put("ready", podReady.getOrDefault(pod, false));
                        podNode.put("deployment", podDeployment.get(pod));
                        nodes.add(podNode);
                    }
                    Map<String, Object> link = new LinkedHashMap<>();
                    link.put("source", "svc:" + svcName);
                    link.put("target", podId);
                    links.add(link);
                }
            }

            result.put("connected", true);
            result.put("namespace", namespace);
            result.put("nodes", nodes);
            result.put("links", links);
        } catch (Exception e) {
            // 集群连不上不算接口错误：前端显示"拓扑不可用"，其它卡片照常
            log.warn("查询服务拓扑失败: {}", e.getMessage());
            result.put("connected", false);
            result.put("error", e.getMessage());
            result.put("nodes", List.of());
            result.put("links", List.of());
        }
        return result;
    }

    /** Pod 的 owner Deployment 名（经 ReplicaSet 名字推断，去掉两段哈希）。 */
    private String ownerDeployment(Pod pod) {
        if (pod.getMetadata() == null) {
            return null;
        }
        String name = pod.getMetadata().getName();
        String[] parts = name.split("-");
        if (parts.length >= 3) {
            return String.join("-", java.util.Arrays.copyOfRange(parts, 0, parts.length - 2));
        }
        return name;
    }

    // ---------------- 缓存 ----------------

    private Map<String, List<String>> readCache(String namespace) {
        try {
            String json = redisTemplate.opsForValue().get(CACHE_PREFIX + namespace);
            if (json == null || json.isBlank()) {
                return null;
            }
            return MAPPER.readValue(json, STRING_LIST_MAP);
        } catch (Exception e) {
            log.debug("读取拓扑缓存失败: {}", e.getMessage());
            return null;
        }
    }

    private void writeCache(String namespace, Map<String, List<String>> topology) {
        try {
            redisTemplate.opsForValue().set(
                    CACHE_PREFIX + namespace,
                    MAPPER.writeValueAsString(topology),
                    Duration.ofSeconds(Math.max(5, cacheSeconds)));
        } catch (Exception e) {
            log.debug("写入拓扑缓存失败: {}", e.getMessage());
        }
    }
}
