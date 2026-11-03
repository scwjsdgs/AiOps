package com.opsagent.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 缩容到 0（就绪 0/0）的假健康判定测试。
 *
 * 防守点：回归验证里「末次就绪 0/0」绝不能判定为健康、绝不能给绿色通过 ——
 * 那是人为 scale 到 0（SCALED_TO_ZERO），不是服务恢复。即使观察期内连续失败
 * 阈值没到，0/0 也应像 restartCount 增长一样作为铁证立即判复现/失败。
 */
class RecoveryVerificationScaledToZeroTest {

    @Test
    void scaledToZero_immediatelyRegressed() {
        RecoveryVerificationService.ObservationResult r = new RecoveryVerificationService.ObservationResult();
        r.healthy = false;
        r.scaledToZero = true;
        r.readyReplicas = 0;
        r.desiredReplicas = 0;
        r.restartGrowth = false;

        RecoveryVerificationService.ObservationSummary summary =
                new RecoveryVerificationService.ObservationSummary(2);

        summary.merge(r);

        assertTrue(summary.regressed,
                "就绪 0/0 必须立即判复现，不能等连续失败阈值，更不能进入绿色通过分支");
    }

    @Test
    void scaledToZero_evenSingleCheck_flipsRegressed() {
        // 即便只复查过一次（观察期刚开始），0/0 也应立刻打回
        RecoveryVerificationService.ObservationResult r = new RecoveryVerificationService.ObservationResult();
        r.healthy = false;
        r.scaledToZero = true;
        r.readyReplicas = 0;
        r.desiredReplicas = 0;

        RecoveryVerificationService.ObservationSummary summary =
                new RecoveryVerificationService.ObservationSummary(10);

        summary.merge(r);

        assertTrue(summary.regressed);
        assertEquals(1, summary.rounds);
    }

    @Test
    void healthyNormal_doesNotRegress() {
        RecoveryVerificationService.ObservationResult r = new RecoveryVerificationService.ObservationResult();
        r.healthy = true;
        r.scaledToZero = false;
        r.readyReplicas = 2;
        r.desiredReplicas = 2;

        RecoveryVerificationService.ObservationSummary summary =
                new RecoveryVerificationService.ObservationSummary(2);

        summary.merge(r);

        assertFalse(summary.regressed, "正常就绪不能误判复现");
    }

    @Test
    void continuousFailuresBeyondThreshold_stillRegress() {
        // 非 0/0 的普通连续失败仍按阈值累积判复现，不因新增 0/0 分支而改变
        RecoveryVerificationService.ObservationSummary summary =
                new RecoveryVerificationService.ObservationSummary(2);

        for (int i = 0; i < 2; i++) {
            RecoveryVerificationService.ObservationResult r = new RecoveryVerificationService.ObservationResult();
            r.healthy = false;
            r.scaledToZero = false;
            r.readyReplicas = 1;
            r.desiredReplicas = 2;
            summary.merge(r);
        }

        assertTrue(summary.regressed, "连续失败达到阈值仍应判复现");
    }
}
