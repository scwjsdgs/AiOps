package com.opsagent.service;

import com.opsagent.tool.KubernetesToolBase;
import io.fabric8.kubernetes.client.KubernetesClient;
import org.springframework.stereotype.Component;

/**
 * 共享 KubernetesClient 的持有者。
 *
 * 不直接注入 KubernetesToolBase：它是工具层的基类，把 service 层耦合上去
 * 会让依赖方向反了。这里单独包一层，ClusterStatusService 从这里拿 client。
 */
@Component
public class KubernetesToolClientHolder extends KubernetesToolBase {

    public KubernetesClient client() {
        return this.client;
    }

    public String namespace() {
        return this.namespace;
    }
}
