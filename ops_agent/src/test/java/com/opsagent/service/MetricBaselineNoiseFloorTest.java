package com.opsagent.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 指标基线「噪声地板」单元测试。
 *
 * 背景：内存类指标在有 GC 的服务上极稳，实测 σ 仅占均值的 0.01%~0.6%
 * （nginx 约 0.01%、Grafana 约 0.3%）。3σ 带因此被压到几百 KB 甚至几 KB，正常的
 * 页缓存抖动就被判成 5~9σ 异常并告警 —— 24 小时内 58 条基线预警里有 19 条是这类
 * 内存噪声，真正的故障（leaky-app 内存涨 208%）反而被淹没。
 *
 * 地板的作用是给窄带指标一个最小判定宽度。这里把两个方向都锁住：
 *   - 窄带噪声必须被抑制（否则误报照旧）；
 *   - 真实大偏差必须不受影响（地板绝不能变成"掩盖故障"的开关）。
 */
class MetricBaselineNoiseFloorTest {

    private static final double MEM = 2.0;   // 内存地板 2%（与 application.yml 一致）
    private static final double CPU = 0.5;   // CPU 地板 0.5%

    // ---------------- 窄带噪声：必须被抬到地板 ----------------

    @Test
    void liftsNearZeroStdDevForMemory() {
        // Grafana 内存实测量级：均值约 2.67e8，σ 约 8e5（占 0.3%），远低于 2% 地板
        double mean = 2.675e8, stdDev = 8.0e5;
        double got = MetricBaselineService.applyNoiseFloor(stdDev, mean, "memory", MEM, CPU);
        assertEquals(mean * 0.02, got, 1.0, "σ 应被抬到均值的 2%");
    }

    @Test
    void liftsExtremelyNarrowNginxMemory() {
        // nginx 内存实测：σ 占均值仅 0.01% —— 原实现下几千字节抖动会被判成 5~9σ
        double mean = 2.889e7, stdDev = 4525.7;
        double got = MetricBaselineService.applyNoiseFloor(stdDev, mean, "memory", MEM, CPU);
        assertEquals(mean * 0.02, got, 1.0);
    }

    @Test
    void suppressesNginxNoiseAlertThatPreviouslyFired() {
        // 回归：这条实测告警原报 5.39σ，加地板后必须落到 3σ 阈值以下
        double mean = 2.889e7;
        double value = mean + 3959;                 // 只波动 3959 字节
        double rawStdDev = Math.abs(value - mean) / 5.39;

        double eff = MetricBaselineService.applyNoiseFloor(rawStdDev, mean, "memory", MEM, CPU);
        double sigmaAway = Math.abs(value - mean) / eff;

        assertTrue(sigmaAway < 3.0,
                "nginx 内存 3959 字节抖动不应再告警，实际 " + sigmaAway + "σ");
    }

    @Test
    void handlesExactlyZeroStdDev() {
        // σ 恰为 0：原先靠一个 1% 的临时兜底，现在由地板统一处理
        double got = MetricBaselineService.applyNoiseFloor(0.0, 1.0e8, "memory", MEM, CPU);
        assertEquals(1.0e8 * 0.02, got, 1.0);
    }

    // ---------------- 真实偏差：绝不能误伤 ----------------

    @Test
    void keepsRealLeakyAppAnomaly() {
        // 实测真实故障：leaky-app 内存涨 208%（1.587e7 字节），原报 4.01σ。
        // 地板必须完全不影响它 —— 这是这套机制存在的意义所在。
        double mean = 1.587e7 / 2.085;      // 反推均值
        double value = mean * (1 + 2.085);
        double rawStdDev = Math.abs(value - mean) / 4.01;

        double eff = MetricBaselineService.applyNoiseFloor(rawStdDev, mean, "memory", MEM, CPU);
        assertEquals(rawStdDev, eff, 1e-6, "真实大偏差的 σ 不应被地板改动");

        double sigmaAway = Math.abs(value - mean) / eff;
        assertTrue(sigmaAway >= 3.0, "真实故障必须仍然告警，实际 " + sigmaAway + "σ");
    }

    @Test
    void keepsWideStdDevUntouched() {
        // 实测 σ 已明显高于地板（memory 占 5%）时原样返回
        double mean = 1.0e8, stdDev = mean * 0.05;
        double got = MetricBaselineService.applyNoiseFloor(stdDev, mean, "memory", MEM, CPU);
        assertEquals(stdDev, got, 1e-6);
    }

    // ---------------- CPU 地板必须远小于内存 ----------------

    @Test
    void cpuFloorIsMuchSmallerThanMemoryFloor() {
        // CPU 正常抖动比例本就很大（实测 2%~90%），用地板 2% 会压掉真实 CPU 问题。
        // 锁定两个指标的地板不可互换。
        double mean = 0.002, stdDev = mean * 0.02;   // CPU σ 占 2%

        double memFloored = MetricBaselineService.applyNoiseFloor(stdDev, mean, "memory", MEM, CPU);
        double cpuFloored = MetricBaselineService.applyNoiseFloor(stdDev, mean, "cpu", MEM, CPU);

        assertEquals(mean * 0.02, cpuFloored, 1e-9, "CPU 2% 抖动高于 0.5% 地板，应原样保留");
        assertEquals(mean * 0.02, memFloored, 1e-9, "同一 σ 在内存地板下也被抬高（2% > 2% 边界）");
        assertTrue(cpuFloored <= memFloored, "CPU 地板不应比内存地板更激进");
    }

    @Test
    void unknownMetricFallsBackToCpuFloor() {
        // 未知指标走小地板：少抑制一点噪声，比误抑制真实异常安全
        double mean = 1000, stdDev = 1;
        double got = MetricBaselineService.applyNoiseFloor(stdDev, mean, "some_new_metric", MEM, CPU);
        assertEquals(1000 * 0.005, got, 1e-6);
    }

    // ---------------- 关闭开关 ----------------

    @Test
    void zeroFloorDisablesTheMechanism() {
        // 地板配 0 视为关闭，原样返回 —— 便于线上临时关掉对比
        double got = MetricBaselineService.applyNoiseFloor(2781, 2.889e7, "memory", 0.0, 0.0);
        assertEquals(2781, got, 1e-9);
    }

    @Test
    void negativeFloorIsTreatedAsDisabled() {
        double got = MetricBaselineService.applyNoiseFloor(2781, 2.889e7, "memory", -5.0, -5.0);
        assertEquals(2781, got, 1e-9, "负数比例不应产生负带宽");
    }

    // ---------------- 退化输入 ----------------

    @Test
    void zeroMeanReturnsZeroSoCallerSkips() {
        // 均值为 0（空闲服务 CPU）：地板算出 0，调用方据此判为"不可判定"而不是造个假带宽
        double got = MetricBaselineService.applyNoiseFloor(0.0, 0.0, "cpu", MEM, CPU);
        assertEquals(0.0, got, 1e-12);
    }
}
