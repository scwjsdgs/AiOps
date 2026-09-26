package com.opsagent.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 回归验证的解析规则单元测试。
 *
 * 任务 input 格式不统一（告警描述、压测制造的告警、预测性预警）都会流进来，
 * 如果解析规则太宽，任务里会绑定错 Deployment，后续去查 K8s 健康度就全是误判。
 * 这个测试把边界保护住——解析不到就返回 null，调用方会跳过观察，不会把 A 服务
 * 的修复绑定到 B 服务的健康上。
 */
class RecoveryVerificationServiceParsingTest {

    @Test
    void parsesAlarmDescription() {
        String input = "服务 leaky-app 发生告警：Pod 频繁重启";
        assertEquals("leaky-app", RecoveryVerificationService.parseDeployment(input));
    }

    @Test
    void parsesAnomalyAlertWithServicePrefix() {
        String input = "服务 coredns 的容器 CPU为 0.0015，低于基线均值 0.0038（3.10σ）";
        assertEquals("coredns", RecoveryVerificationService.parseDeployment(input));
    }

    @Test
    void parsesWhenServiceAppearsMidSentence() {
        String input = "【影响面分析】服务 nginx：就绪 2/3";
        assertEquals("nginx", RecoveryVerificationService.parseDeployment(input));
    }

    @Test
    void doesNotMatchTestAlarmWithoutServicePrefix() {
        String input = "压测告警 #1: leaky-app KubePodCrashLooping [关联告警 CRITICAL]";
        assertNull(RecoveryVerificationService.parseDeployment(input), "压测格式前无 '服务' 前缀，不应强行匹配");
    }

    @Test
    void handlesNullBlankInput() {
        assertNull(RecoveryVerificationService.parseDeployment(null));
        assertNull(RecoveryVerificationService.parseDeployment(""));
        assertNull(RecoveryVerificationService.parseDeployment("   "));
    }

    @Test
    void longestAllowedNameStillWorks() {
        String input = "服务 my-app.v2_foo-bar123 发生告警";
        assertEquals("my-app.v2_foo-bar123", RecoveryVerificationService.parseDeployment(input));
    }

    @Test
    void ignoresTrailingPunctuation() {
        String input = "服务 nginx! 发生了故障";
        // 正则只接受字母数字._-，遇到 ! 会在匹配后停住，group 仍是 nginx
        assertEquals("nginx", RecoveryVerificationService.parseDeployment(input));
    }

    @Test
    void consecutiveFailuresCounting() {
        RecoveryVerificationService.ObservationSummary s = new RecoveryVerificationService.ObservationSummary(2);
        var bad1 = new RecoveryVerificationService.ObservationResult();
        bad1.healthy = false;
        assertFalse(s.regressed);
        s.merge(bad1);
        assertFalse(s.regressed, "第一次失败不应判定复现");
        s.merge(bad1);
        assertTrue(s.regressed, "连续两次失败应判定 REGRESSED");
        // 恢复健康的复查应该把连续失败计数归零
        var good = new RecoveryVerificationService.ObservationResult();
        good.healthy = true;
        s = new RecoveryVerificationService.ObservationSummary(2);
        s.merge(bad1);
        s.merge(good);
        s.merge(bad1);
        assertFalse(s.regressed, "健康复查后计数重置，单次失败不应判定复现");
    }
}
