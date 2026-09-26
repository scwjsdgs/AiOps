package com.opsagent.service;

import com.opsagent.model.ToolExecutionResult;
import io.fabric8.kubernetes.api.model.ObjectMeta;
import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.api.model.PodStatus;
import io.fabric8.kubernetes.api.model.apps.Deployment;
import io.fabric8.kubernetes.api.model.apps.DeploymentSpec;
import io.fabric8.kubernetes.api.model.apps.DeploymentStatus;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.dsl.MixedOperation;
import io.fabric8.kubernetes.client.dsl.Resource;
import io.fabric8.kubernetes.client.dsl.RollableScalableResource;
import io.fabric8.kubernetes.client.dsl.AppsAPIGroupDSL;
import io.fabric8.kubernetes.client.dsl.PodResource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * 影响面分析单元测试。
 *
 * 防守点：影响面是喂给 LLM 的结论性信息，错报比查不到更糟 ——
 * 明明断流却报「入口正常」会让运维误判紧急程度，反之则会造成无谓的慌乱。
 * 所以断流判定（就绪端点为 0 且有未就绪端点）必须有测试兜住。
 */
@ExtendWith(MockitoExtension.class)
class ImpactAnalysisServiceTest {

    @Mock
    private TopologyService topologyService;
    @Mock
    private KubernetesToolClientHolder holder;
    @Mock
    private KubernetesClient client;
    @Mock
    private AppsAPIGroupDSL appsApi;

    private ImpactAnalysisService service;

    @BeforeEach
    void setUp() {
        service = new ImpactAnalysisService(topologyService, holder);
    }

    @Test
    void blankDeploymentName_reportsUnavailable_withoutTouchingCluster() {
        Map<String, Object> result = service.analyze("  ");

        assertFalse((Boolean) result.get("available"), "空服务名应返回 available=false");
        assertTrue(result.get("reason").toString().contains("未提供"));
        verifyNoInteractions(holder);
    }

    @Test
    void deploymentMissing_reportsUnavailable() {
        stubDeploymentLookup(null);

        Map<String, Object> result = service.analyze("ghost-app");

        assertFalse((Boolean) result.get("available"));
        assertTrue(result.get("reason").toString().contains("不存在"),
                "应说明 Deployment 不存在，实际: " + result.get("reason"));
    }

    @Test
    void replicasShortOfDesired_marksSelfDegraded_andSummarizesOutage() {
        Deployment deployment = deploymentWith(3, 1, 2);
        stubDeploymentLookup(deployment);
        stubPods(deployment, List.of());

        // 入口已断流：就绪端点为 0，但有 2 个未就绪端点
        when(topologyService.servicesOfDeployment("leaky-app")).thenReturn(List.of("leaky-app"));
        when(topologyService.serviceEndpointHealth("leaky-app"))
                .thenReturn(Map.of("service", "leaky-app", "readyEndpoints", 0,
                        "notReadyEndpoints", 2, "outage", true));

        Map<String, Object> result = service.analyze("leaky-app");

        assertTrue((Boolean) result.get("available"));
        assertTrue((Boolean) result.get("selfDegraded"), "就绪 1/3 应判为降级");
        assertTrue((Boolean) result.get("outage"), "就绪端点为 0 且有未就绪端点应判为断流");
        assertTrue(result.get("summary").toString().contains("已断流"),
                "摘要必须点明断流，实际: " + result.get("summary"));
    }

    @Test
    void allReplicasReady_noOutage_reportsHealthy() {
        Deployment deployment = deploymentWith(2, 2, 0);
        stubDeploymentLookup(deployment);
        stubPods(deployment, List.of());

        when(topologyService.servicesOfDeployment("nginx")).thenReturn(List.of("nginx"));
        when(topologyService.serviceEndpointHealth("nginx"))
                .thenReturn(Map.of("service", "nginx", "readyEndpoints", 2,
                        "notReadyEndpoints", 0, "outage", false));

        Map<String, Object> result = service.analyze("nginx");

        assertFalse((Boolean) result.get("selfDegraded"), "全部就绪不应判为降级");
        assertFalse((Boolean) result.get("outage"));
        assertTrue(result.get("summary").toString().contains("未受直接影响"),
                "未断流时应明确说明业务未受直接影响，实际: " + result.get("summary"));
    }

    @Test
    void resolveServiceFromText_parsesAlarmDescription() {
        assertEquals("leaky-app", service.resolveServiceFromText("服务 leaky-app 发生告警：Pod 崩溃"));
        assertEquals("user-svc", service.resolveServiceFromText("服务 user-svc 发生告警"));
        // 解析不出时返回 null，调用方据此跳过影响面注入（不阻塞主链路）
        assertNull(service.resolveServiceFromText("一段没有服务名的描述"));
        assertNull(service.resolveServiceFromText(null));
    }

    // ---------------- 测试桩 ----------------

    private Deployment deploymentWith(int desired, int ready, int unavailable) {
        Deployment deployment = new Deployment();
        ObjectMeta meta = new ObjectMeta();
        meta.setName("app");
        deployment.setMetadata(meta);

        DeploymentSpec spec = new DeploymentSpec();
        spec.setReplicas(desired);
        var selector = new io.fabric8.kubernetes.api.model.LabelSelector();
        selector.setMatchLabels(Map.of("app", "app"));
        spec.setSelector(selector);
        deployment.setSpec(spec);

        DeploymentStatus status = new DeploymentStatus();
        status.setReadyReplicas(ready);
        status.setUnavailableReplicas(unavailable);
        deployment.setStatus(status);
        return deployment;
    }

    @SuppressWarnings("unchecked")
    private void stubDeploymentLookup(Deployment deployment) {
        when(holder.client()).thenReturn(client);
        when(holder.namespace()).thenReturn("default");
        when(client.apps()).thenReturn(appsApi);
        MixedOperation<Deployment, io.fabric8.kubernetes.api.model.apps.DeploymentList,
                RollableScalableResource<Deployment>> deployments = mock(MixedOperation.class);
        when(appsApi.deployments()).thenReturn(deployments);
        when(deployments.inNamespace(anyString())).thenReturn(deployments);
        when(deployments.withName(anyString())).thenReturn(mock(RollableScalableResource.class));
        when(deployments.withName(anyString()).get()).thenReturn(deployment);
    }

    @SuppressWarnings("unchecked")
    private void stubPods(Deployment deployment, List<Pod> pods) {
        MixedOperation<Pod, io.fabric8.kubernetes.api.model.PodList, PodResource> podOp = mock(MixedOperation.class);
        when(client.pods()).thenReturn(podOp);
        when(podOp.inNamespace(anyString())).thenReturn(podOp);
        when(podOp.withLabelSelector(any(io.fabric8.kubernetes.api.model.LabelSelector.class))).thenReturn(podOp);
        var podList = new io.fabric8.kubernetes.api.model.PodList();
        podList.setItems(pods);
        when(podOp.list()).thenReturn(podList);
    }
}
