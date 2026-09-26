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
 * RollbackTool 单元测试 —— 高危动作（回滚会改线上镜像版本）。
 *
 * 防守点：任何缺失/非法参数都必须在真实触发回滚（更新 deployment image）之前被拦截。
 * 校验失败路径不应访问任何 client API。
 */
@ExtendWith(MockitoExtension.class)
class RollbackToolTest {

    @Mock
    private KubernetesClient client;

    private RollbackTool tool;

    @BeforeEach
    void setUp() {
        tool = new RollbackTool();
        tool.client = client;
        tool.namespace = "default";
    }

    private ToolExecutionResult execute(Map<String, Object> params) {
        return tool.execute(params);
    }

    @Test
    void missingDeployment_returnsFailure_andNeverTouchesClient() {
        // 高危：连回滚哪个服务都没给，绝不能发起任何 API 调用
        Map<String, Object> params = new HashMap<>();
        params.put("version", "previous");

        ToolExecutionResult r = execute(params);

        assertFalse(r.isSuccess(), "缺 deployment 参数必须失败");
        assertTrue(r.getMessage().contains("deployment"),
                "应提示缺 deployment，实际: " + r.getMessage());
        verifyNoInteractions(client);
    }

    @Test
    void missingDeployment_andMissingVersion_returnsFailure() {
        // 极简空参数（可能来自 LLM 漏参）——同样必须失败且零 client 调用
        Map<String, Object> params = new HashMap<>();

        ToolExecutionResult r = execute(params);

        assertFalse(r.isSuccess(), "空参数必须失败");
        assertTrue(r.getMessage().contains("deployment"), "应提示缺 deployment，实际: " + r.getMessage());
        verifyNoInteractions(client);
    }
}