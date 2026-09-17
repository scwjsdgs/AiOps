package com.opsagent.tool;

import com.opsagent.model.ToolExecutionResult;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Component
public class QueryLogTool implements Tool {

    private static final int MAX_LINES = 2000;
    private static final int MAX_RETURN_CHARS = 20_000;

    /**
     * 服务名与关键字会被拼进远端 shell 命令，必须白名单校验。
     * 原实现是 String.format("journalctl -u %s ...", service) 直接拼接，
     * 而这两个值来自 LLM —— 等于把命令注入的入口交给了大模型。
     */
    private static final Pattern SERVICE_PATTERN =
            Pattern.compile("^[A-Za-z0-9][A-Za-z0-9_.\\-@]{0,62}$");
    private static final Pattern KEYWORD_PATTERN =
            Pattern.compile("^[A-Za-z0-9_.:\\- ]{1,64}$");

    private final SshCommandRunner sshRunner;

    @Value("${opsagent.log.local-dir:}")
    private String localDir;

    @Value("${opsagent.log.unit-pattern:%s}")
    private String unitPattern;

    public QueryLogTool(SshCommandRunner sshRunner) {
        this.sshRunner = sshRunner;
    }

    @Override
    public String getName() {
        return "query_log";
    }

    @Override
    public String getDescription() {
        return "通过 SSH 查询服务的最近日志，可附带关键字过滤。";
    }

    @Override
    public boolean isIdempotent() {
        return true;
    }

    @Override
    public ToolExecutionResult execute(Map<String, Object> parameters) {
        long start = System.currentTimeMillis();
        ToolParams p = ToolParams.of(parameters);

        String service = p.str("service", "deployment");
        if (service == null) {
            return Tool.failure("Missing 'service' parameter（也接受 'deployment'）", start);
        }
        if (!SERVICE_PATTERN.matcher(service).matches()) {
            return Tool.failure("非法的服务名 '" + service + "'：只允许字母数字和 _ . - @，且不以符号开头", start);
        }

        // 原实现硬编码 -n 50，LLM 要多少行都只有 50 行
        int lines = Math.max(1, Math.min(MAX_LINES, p.integerOr(100, "lines", "tail", "n")));

        // 兼容旧的 level 参数
        String keyword = p.strOr("ERROR", "keyword", "level");
        if (!KEYWORD_PATTERN.matcher(keyword).matches()) {
            return Tool.failure(
                    "非法的 keyword '" + keyword + "'：只允许字母、数字、空格与 _ . : - ，最长 64 字符", start);
        }

        String command = buildCommand(service, lines, keyword);
        SshCommandRunner.SshResult result = sshRunner.run(command, 5_000, 30_000);

        if (!result.ok()) {
            // SSH 不通时按配置降级读本地日志，让开发/单机环境下这条链路仍然可用
            ToolExecutionResult local = readLocal(service, lines, keyword, command, start);
            if (local != null) {
                return local;
            }
            if (result.timedOut()) {
                return Tool.failure("日志查询超时（service=" + service + "）", start);
            }
            return Tool.failure(
                    "SSH 查询日志失败（exit=" + result.exitCode() + "）："
                            + (result.stderr().isBlank() ? result.stdout() : result.stderr()),
                    start);
        }

        return buildSuccess(result.stdout(), service, lines, keyword, command, "ssh", start);
    }

    private String buildCommand(String service, int lines, String keyword) {
        String unit;
        try {
            unit = String.format(unitPattern, service);
        } catch (Exception e) {
            // unit-pattern 配错（比如多了个 %d）不应该让整个工具炸掉
            unit = service;
        }
        StringBuilder command = new StringBuilder("journalctl -u ")
                .append(unit)
                .append(" -n ").append(lines)
                .append(" --no-pager");
        if (!keyword.isBlank()) {
            // keyword 已通过白名单校验，不含引号和 shell 元字符
            command.append(" | grep -i -e '").append(keyword).append('\'');
        }
        return command.toString();
    }

    /** local-dir 未配置时返回 null，表示不降级。 */
    private ToolExecutionResult readLocal(String service, int lines, String keyword,
                                          String command, long start) {
        if (localDir == null || localDir.isBlank()) {
            return null;
        }
        Path file = Paths.get(localDir, service + ".log");
        if (!Files.isReadable(file)) {
            return null;
        }
        try {
            List<String> all = Files.readAllLines(file, StandardCharsets.UTF_8);
            String keywordLower = keyword.toLowerCase();
            String content = all.stream()
                    .filter(line -> line.toLowerCase().contains(keywordLower))
                    .collect(Collectors.joining("\n"));
            if (content.isBlank()) {
                content = all.stream().collect(Collectors.joining("\n"));
            }
            List<String> kept = content.lines().collect(Collectors.toList());
            if (kept.size() > lines) {
                content = String.join("\n", kept.subList(kept.size() - lines, kept.size()));
            }
            return buildSuccess(content, service, lines, keyword, command, "local:" + file, start);
        } catch (IOException e) {
            return null;
        }
    }

    private ToolExecutionResult buildSuccess(String raw, String service, int lines,
                                             String keyword, String command, String source, long start) {
        String logs = raw == null ? "" : raw;
        int lineCount = logs.isBlank() ? 0 : logs.lines().count() > Integer.MAX_VALUE
                ? Integer.MAX_VALUE : (int) logs.lines().count();
        if (logs.length() > MAX_RETURN_CHARS) {
            // 整段日志会顺着回调塞进 Task.agentSteps 和 LLM 上下文，
            // 不截断的话一次查询就能把上下文打满
            logs = logs.substring(logs.length() - MAX_RETURN_CHARS)
                    + "\n...（已截断，仅保留末尾 " + MAX_RETURN_CHARS + " 字符）";
        }

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("logs", logs);
        // lineCount 是真实行数：Python 侧原来对 logs 做 len()，得到的是字符数
        data.put("lineCount", lineCount);
        data.put("command", command);
        data.put("source", source);

        return Tool.success(
                "Retrieved " + lineCount + " log line(s) for " + service
                        + " (keyword=" + keyword + ", source=" + source + ")",
                data, start);
    }
}
