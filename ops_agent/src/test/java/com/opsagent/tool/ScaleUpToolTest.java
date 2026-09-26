package com.opsagent.tool;

import com.opsagent.model.ToolExecutionResult;
import io.fabric8.kubernetes.client.KubernetesClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * ScaleUpTool 参数校验单元测试。
 *
 * 防守点：LLM 可能写出夸张副本数把集群打爆。
 * 重点验证缺参、越界（负数 / 超过 MAX_REPLICAS=50）时，在任何 client 调用之前就被拒绝，
 * 绝不真的触发 scale。用 mock client，但校验失败路径根本不访问 client ——
 * 这正是我们要验证的「先拦参数、再碰集群」的顺序。
 */
@ExtendWith(MockitoExtension.class)
class ScaleUpToolTest {

    @Mock
    private KubernetesClient client;

    private ScaleUpTool tool;

    @BeforeEach
    void setUp() {
        tool = new ScaleUpTool();
        tool.client = client;
        tool.namespace = "default";
    }

    private ToolExecutionResult execute(Map<String, Object> params) {
        return tool.execute(params);
    }

    @Test
    void missingDeployment_returnsFailure_andNeverTouchesClient() {
        Map<String, Object> params = new HashMap<>();
        params.put("replicas", 3);

        ToolExecutionResult r = execute(params);

        assertFalse(r.isSuccess(), "缺 deployment 参数必须失败");
        assertTrue(r.getMessage().contains("deployment"),
                "应提示缺 deployment，实际: " + r.getMessage());
        // 校验失败时不访问任何 client API
        verifyNoInteractions(client);
    }

    @Test
    void missingReplicas_returnsFailure() {
        Map<String, Object> params = new HashMap<>();
        params.put("deployment", "nginx");

        ToolExecutionResult r = execute(params);

        assertFalse(r.isSuccess(), "缺 replicas 必须失败");
        assertTrue(r.getMessage().contains("replicas"),
                "应提示缺 replicas，实际: " + r.getMessage());
        verifyNoInteractions(client);
    }

    @Test
    void negativeReplicas_returnsFailure() {
        Map<String, Object> params = new HashMap<>();
        params.put("deployment", "nginx");
        params.put("replicas", -5);

        ToolExecutionResult r = execute(params);

        assertFalse(r.isSuccess(), "负数副本必须失败");
        assertTrue(r.getMessage().contains("0..50"), "应提示范围 0..50，实际: " + r.getMessage());
        verifyNoInteractions(client);
    }

    @Test
    void tooManyReplicas_returnsFailure_notEnumeratedToClients() {
        Map<String, Object> params = new HashMap<>();
        params.put("deployment", "nginx");
        params.put("replicas", 9999);

        ToolExecutionResult r = execute(params);

        assertFalse(r.isSuccess(), "9999 副本必须被拦截（防 LLM 打爆集群）");
        assertTrue(r.getMessage().contains("0..50"), "应提示范围 0..50，实际: " + r.getMessage());
        // 关键：超限绝不落地到真实 scale
        verifyNoInteractions(client);
    }

    @Test
    void nonNumericReplicas_returnsFailure() {
        Map<String, Object> params = new HashMap<>();
        params.put("deployment", "nginx");
        params.put("replicas", "not-a-number");

        ToolExecutionResult r = execute(params);

        assertFalse(r.isSuccess(), "非数字副本必须失败");
        assertTrue(r.getMessage().contains("replicas"), "应提示 replicas 无效，实际: " + r.getMessage());
        verifyNoInteractions(client);
    }
}