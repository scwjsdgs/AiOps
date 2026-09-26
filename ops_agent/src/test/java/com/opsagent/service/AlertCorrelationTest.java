package com.opsagent.service;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 告警降噪（聚合窗口）单元测试。
 *
 * 防守点：一个 Pod 崩溃触发 4 条告警（alertname 不同 → fingerprint 不同），
 * 聚合键必须把同一 Deployment 的 Pod 聚到一起——pod 名带随机后缀
 * （leaky-app-b7c4d9cf4-zqd24），不去后缀就聚不中，降噪形同虚设。
 */
class AlertCorrelationTest {

    private String buildCorrelationKey(java.util.Map<String, String> labels) throws Exception {
        AlertWebhookService service = new AlertWebhookService(
                null, null, null, null, null);
        Method m = AlertWebhookService.class.getDeclaredMethod("buildCorrelationKey", Map.class);
        m.setAccessible(true);
        return (String) m.invoke(service, labels);
    }

    @Test
    void sameDeployment_differentPodHashes_sameKey() throws Exception {
        // 同一 Deployment 前后两个 Pod（rs hash 与 pod hash 不同）→ 必须同键
        String k1 = buildCorrelationKey(Map.of(
                "namespace", "default", "pod", "leaky-app-b7c4d9cf4-zqd24", "alertname", "KubePodCrashLooping"));
        String k2 = buildCorrelationKey(Map.of(
                "namespace", "default", "pod", "leaky-app-b7c4d9cf4-abcde", "alertname", "KubePodNotReady"));
        assertEquals(k1, k2, "同一 Deployment 的不同 Pod 必须聚到同一键");
        assertTrue(k1.startsWith("default|leaky-app"), "键应含 namespace + deployment 前缀，实际: " + k1);
    }

    @Test
    void differentNamespaces_differentKeys() throws Exception {
        String k1 = buildCorrelationKey(Map.of("namespace", "default", "pod", "leaky-app-a-b-c"));
        String k2 = buildCorrelationKey(Map.of("namespace", "monitoring", "pod", "leaky-app-a-b-c"));
        assertNotEquals(k1, k2, "不同 namespace 的同名 Pod 不应聚合");
    }

    @Test
    void alertsWithoutNamespaceOrPod_emptyKey() throws Exception {
        // 没有 namespace/pod 标签的告警不参与降噪
        String k = buildCorrelationKey(Map.of("alertname", "SomethingElse"));
        assertEquals("", k, "无 namespace/pod 应返回空键（不降噪）");
    }

    @Test
    void serviceLabel_fallbackUsed() throws Exception {
        // 只有 service 没有 pod 的告警（如 Node 级告警）用 service 兜底
        String k = buildCorrelationKey(Map.of("namespace", "default", "service", "nginx"));
        assertEquals("default|nginx", k);
    }
}
