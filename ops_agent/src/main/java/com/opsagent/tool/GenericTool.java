package com.opsagent.tool;

import com.opsagent.model.ToolExecutionResult;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * 执行任意本地 shell 命令。
 *
 * 注意：这个工具**不在** opsagent.tools.agent-exposed 白名单里，
 * LLM 调不到它。它保留给运维人员通过 /api/tools/execute 手工使用
 * （/api/tools/list 仍会列出它）。
 */
@Component
public class GenericTool implements Tool {

    private static final int DEFAULT_TIMEOUT_SECONDS = 30;
    private static final int MAX_TIMEOUT_SECONDS = 300;
    private static final int MAX_OUTPUT_CHARS = 50_000;
    private static final boolean IS_WINDOWS =
            System.getProperty("os.name", "").toLowerCase().contains("win");

    @Override
    public String getName() {
        return "generic_command";
    }

    @Override
    public String getDescription() {
        return "在本地主机上执行任意 shell 命令。";
    }

    @Override
    public boolean isDangerous() {
        return true;
    }

    @Override
    public ToolExecutionResult execute(Map<String, Object> parameters) {
        long start = System.currentTimeMillis();
        ToolParams p = ToolParams.of(parameters);
        String command = p.str("command");
        if (command == null) {
            return Tool.failure("Missing 'command' parameter", start);
        }
        int timeoutSeconds = Math.max(1,
                Math.min(MAX_TIMEOUT_SECONDS, p.integerOr(DEFAULT_TIMEOUT_SECONDS, "timeoutSeconds", "timeout")));

        try {
            ProcessBuilder pb = new ProcessBuilder();
            if (IS_WINDOWS) {
                // Runtime.exec 在 Windows 下不经过 shell，管道、重定向、&& 全部无效；
                // ProcessBuilder 同样如此，必须显式套一层 cmd /c
                pb.command("cmd.exe", "/c", command);
            } else {
                pb.command("sh", "-c", command);
            }
            // stderr 合并进 stdout：分开读两路流时，只要有一路没被及时抽干，
            // 管道缓冲区写满就会把子进程挂住，waitFor() 永久阻塞
            pb.redirectErrorStream(true);

            Process process = pb.start();
            StringBuilder output = new StringBuilder();
            Thread pump = new Thread(() -> {
                try (BufferedReader reader = new BufferedReader(
                        new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        output.append(line).append('\n');
                    }
                } catch (Exception ignored) {
                    // 进程被强杀时流会先断开，这是预期路径
                }
            }, "generic-tool-output-pump");
            pump.setDaemon(true);
            pump.start();

            // 原实现用的是无参 waitFor()：一条 `tail -f` 就能永久占住线程，
            // 在 WebFlux 下这个线程最终会拖垮整个服务
            boolean finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                process.waitFor(5, TimeUnit.SECONDS);
                return Tool.failure(
                        "Command timed out after " + timeoutSeconds + "s and was killed: " + command, start);
            }
            pump.join(2000);

            String stdout = output.length() > MAX_OUTPUT_CHARS
                    ? output.substring(0, MAX_OUTPUT_CHARS) + "\n...（输出已截断）"
                    : output.toString();
            int exitCode = process.exitValue();

            Map<String, Object> data = new LinkedHashMap<>();
            data.put("command", command);
            data.put("exitCode", exitCode);
            data.put("stdout", stdout);
            // stderr 已合并进 stdout，这里保留字段是为了契约完整
            data.put("stderr", "");

            if (exitCode == 0) {
                return Tool.success("Command executed successfully", data, start);
            }
            // 失败但仍有输出：这里必须带上 data，否则调用方拿不到命令到底打印了什么
            return new ToolExecutionResult(false,
                    "Command failed with exit code " + exitCode, data,
                    System.currentTimeMillis() - start);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Tool.failure("Command interrupted: " + e.getMessage(), start);
        } catch (Exception e) {
            return Tool.failure("Execution error: " + e.getMessage(), start);
        }
    }
}
