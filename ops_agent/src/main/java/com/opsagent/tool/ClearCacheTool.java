package com.opsagent.tool;

import com.opsagent.model.ToolExecutionResult;
import io.fabric8.kubernetes.api.model.apps.Deployment;
import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.client.dsl.ExecListener;
import io.fabric8.kubernetes.client.dsl.ExecWatch;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * 清理服务缓存。
 *
 * 三种实现由 opsagent.cache.mode 决定：
 * - exec（默认）：进 Pod 执行 opsagent.cache.clear-command
 * - restart：滚动重启，等价于清掉进程内缓存，且不中断服务
 * - ssh：在 opsagent.ssh.host 上执行同一条命令
 *
 * Python 侧通过 execute_repair_action(action=clear_cache) 间接触发。
 */
@Component
public class ClearCacheTool extends KubernetesToolBase implements Tool {

    private static final int EXEC_TIMEOUT_MS = 30_000;

    private final SshCommandRunner sshRunner;

    @Value("${opsagent.cache.mode:exec}")
    private String cacheMode;

    @Value("${opsagent.cache.clear-command:}")
    private String clearCommand;

    public ClearCacheTool(SshCommandRunner sshRunner) {
        this.sshRunner = sshRunner;
    }

    @Override
    public String getName() {
        return "clear_cache";
    }

    @Override
    public String getDescription() {
        return "清除服务的缓存（通过进入 Pod、滚动重启或 SSH 执行）。";
    }

    @Override
    public boolean isDangerous() {
        return true;
    }

    @Override
    public ToolExecutionResult execute(Map<String, Object> parameters) {
        long start = System.currentTimeMillis();
        ToolParams p = ToolParams.of(parameters);
        String name = p.str("deployment", "service");
        if (name == null) {
            return Tool.failure("Missing 'deployment' parameter（也接受 'service'）", start);
        }

        String mode = p.strOr(cacheMode == null || cacheMode.isBlank() ? "exec" : cacheMode.trim(),
                "mode", "target");
        String command = p.strOr(clearCommand == null ? "" : clearCommand, "command");

        try {
            switch (mode.toLowerCase()) {
                case "restart":
                    return rollingRestart(name, start);
                case "ssh":
                    return overSsh(name, command, start);
                case "exec":
                default:
                    return execInPod(name, command, start);
            }
        } catch (Exception e) {
            return Tool.failure("Clear cache failed (" + mode + "): " + e.getMessage(), start);
        }
    }

    /** 滚动重启：比"缩容到 0 再扩容"安全得多，全程有可用副本。 */
    private ToolExecutionResult rollingRestart(String name, long start) {
        Deployment deployment = client.apps().deployments().inNamespace(namespace).withName(name).get();
        if (deployment == null) {
            return Tool.failure("Deployment not found: " + name + " (namespace=" + namespace + ")", start);
        }
        client.apps().deployments().inNamespace(namespace).withName(name).rolling().restart();

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("deployment", name);
        data.put("namespace", namespace);
        data.put("method", "restart");
        data.put("output", "rolling restart triggered");
        return Tool.success("Cache cleared by rolling restart of " + name, data, start);
    }

    private ToolExecutionResult execInPod(String name, String command, long start) throws InterruptedException {
        if (command == null || command.isBlank()) {
            return Tool.failure("未配置 opsagent.cache.clear-command，无法执行 exec 模式清理", start);
        }

        Deployment deployment = client.apps().deployments().inNamespace(namespace).withName(name).get();
        if (deployment == null) {
            return Tool.failure("Deployment not found: " + name + " (namespace=" + namespace + ")", start);
        }

        List<Pod> pods = client.pods().inNamespace(namespace)
                .withLabelSelector(deployment.getSpec().getSelector())
                .list().getItems();
        Pod target = pods.stream()
                .filter(pod -> pod.getStatus() != null && "Running".equals(pod.getStatus().getPhase()))
                .findFirst()
                .orElse(null);
        if (target == null) {
            return Tool.failure("找不到处于 Running 状态的 Pod，无法执行清理命令（" + name + "）", start);
        }
        String podName = target.getMetadata().getName();

        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        ByteArrayOutputStream stderr = new ByteArrayOutputStream();
        // ExecWatch 没有"还在跑吗"这类查询接口，只能靠监听器回调 + 闩锁等到命令结束。
        // 同时必须自己盯超时：远端命令卡住时 exec 不会自己返回。
        CountDownLatch closed = new CountDownLatch(1);
        boolean finished;
        try (ExecWatch watch = client.pods().inNamespace(namespace).withName(podName)
                .writingOutput(stdout)
                .writingError(stderr)
                .usingListener(new ExecListener() {
                    @Override
                    public void onClose(int code, String reason) {
                        closed.countDown();
                    }
                })
                .exec("sh", "-c", command)) {
            finished = closed.await(EXEC_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        }

        String out = stdout.toString(StandardCharsets.UTF_8);
        String err = stderr.toString(StandardCharsets.UTF_8);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("deployment", name);
        data.put("namespace", namespace);
        data.put("method", "exec");
        data.put("pod", podName);
        data.put("command", command);
        data.put("output", out);

        if (!finished) {
            return Tool.failure("清理命令在 " + (EXEC_TIMEOUT_MS / 1000) + " 秒内未结束，已放弃等待。输出：" + out, start);
        }
        return Tool.success("Cache cleared on pod " + podName + " via: " + command, data, start);
    }

    private ToolExecutionResult overSsh(String name, String command, long start) {
        if (command == null || command.isBlank()) {
            return Tool.failure("未配置 opsagent.cache.clear-command，无法执行 ssh 模式清理", start);
        }
        SshCommandRunner.SshResult result = sshRunner.run(command, 5_000, 30_000);

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("deployment", name);
        data.put("method", "ssh");
        data.put("command", command);
        data.put("output", result.stdout());

        if (result.timedOut()) {
            return Tool.failure("SSH 清理命令超时（" + name + "）", start);
        }
        if (!result.ok()) {
            return Tool.failure("SSH 清理失败，exit=" + result.exitCode() + "，stderr=" + result.stderr(), start);
        }
        return Tool.success("Cache cleared over SSH for " + name, data, start);
    }
}
