package com.opsagent.service;

import com.opsagent.entity.AlertEntity;
import com.opsagent.repository.AlertRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.time.LocalDateTime;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * 生产级闭环状态机的规则测试。
 *
 * 这里锁住三条业务规则，防止后续改动把它们改坏：
 * 1. 拒绝冷却：冷却期内抑制、冷却到期放行；
 * 2. 验证重试预算：未达上限 RETRY 且轮次 +1，达到上限 EXHAUSTED 且禁止再自动弹窗；
 * 3. 验证通过：告警 RESOLVED 并清空冷却/耗尽标记，彻底闭环。
 *
 * 用 Mockito 的 mock 仓储，不依赖 MySQL —— 规则是纯状态计算，不需要真库。
 */
class AlertLifecycleServiceTest {

    private AlertRepository repository;
    private AlertLifecycleService service;

    @BeforeEach
    void setUp() throws Exception {
        repository = mock(AlertRepository.class);
        when(repository.save(any(AlertEntity.class))).thenAnswer(inv -> inv.getArgument(0));
        service = new AlertLifecycleService(repository, null, null);
        // @Value 字段在纯单测里不会被注入，反射写入规则参数
        setField(service, "rejectCooldownMinutes", 15L);
        setField(service, "maxVerificationRounds", 3);
    }

    private static void setField(Object target, String name, Object value) throws Exception {
        Field f = AlertLifecycleService.class.getDeclaredField(name);
        f.setAccessible(true);
        f.set(target, value);
    }

    private AlertEntity alert(String status, int occ) {
        AlertEntity a = new AlertEntity();
        a.setId("alert-1");
        a.setStatus(status);
        a.setOccurrenceCount(occ);
        a.setMaxRetries(3);
        a.setVerificationRound(0);
        return a;
    }

    // ---------------- 规则一：拒绝冷却 ----------------

    @Test
    void enterCooldown_setsDeadlineAndKeepsPending() {
        AlertEntity a = alert("REJECTED", 4);
        when(repository.findById("alert-1")).thenReturn(Optional.of(a));

        service.enterRejectCooldownByAlertId("alert-1", "风险太高", "task-1");

        assertNotNull(a.getRejectCooldownUntil(), "进入冷却必须写入截止时间");
        assertTrue(a.getRejectCooldownUntil().isAfter(LocalDateTime.now().plusMinutes(14)),
                "冷却期应为 15 分钟");
        assertEquals("PENDING", a.getStatus(), "拒绝后告警必须保持未解决（PENDING）");
        assertEquals(4, a.getCooldownBaseOccurrence(), "基线次数应记录进入冷却时的值");
        assertEquals(4, a.getOccurrenceCount(), "进入冷却本身不累加次数");
    }

    @Test
    void checkSuppress_withinCooldown_returnsCooldown() {
        AlertEntity a = alert("PENDING", 5);
        a.setRejectCooldownUntil(LocalDateTime.now().plusMinutes(10));
        a.setCooldownBaseOccurrence(5);

        assertEquals(AlertLifecycleService.SuppressAction.COOLDOWN, service.checkSuppress(a),
                "冷却期内必须抑制，避免骚扰");
    }

    @Test
    void checkSuppress_afterCooldown_clearsAndAllows() {
        AlertEntity a = alert("PENDING", 9);
        a.setRejectCooldownUntil(LocalDateTime.now().minusMinutes(1));
        a.setCooldownBaseOccurrence(5);

        assertEquals(AlertLifecycleService.SuppressAction.NONE, service.checkSuppress(a),
                "冷却到期应放行，让仍在报错的故障重新拉起诊断");
        assertNull(a.getRejectCooldownUntil(), "到期后应清空冷却标记，避免反复误判");
        assertEquals("PENDING", a.getStatus(), "放行不代表解决，状态仍是 PENDING");
    }

    @Test
    void checkSuppress_exhausted_returnsExhaustedForever() {
        AlertEntity a = alert("FAILED", 12);
        a.setRetryExhaustedAt(LocalDateTime.now().minusMinutes(30));

        assertEquals(AlertLifecycleService.SuppressAction.EXHAUSTED, service.checkSuppress(a),
                "重试耗尽的故障只累加次数，永远不再自动弹窗");
    }

    // ---------------- 规则二：验证重试预算 ----------------

    @Test
    void regressed_belowMax_incrementsRoundAndKeepsPending() {
        AlertEntity a = alert("VERIFYING", 2);
        a.setVerificationRound(1);
        a.setVerificationDeadline(LocalDateTime.now().plusMinutes(3));

        var decision = service.onVerificationRegressed(a, true);

        assertEquals(AlertLifecycleService.VerificationDecision.RETRY, decision,
                "第 1 轮失败、上限 3，应允许重试");
        assertEquals(2, a.getVerificationRound(), "轮次必须 +1，前端显示 2/3");
        assertEquals("PENDING", a.getStatus(), "重试前告警回到未解决");
        assertNull(a.getVerificationDeadline(), "本轮已出结论，倒计时应清空");
        assertNull(a.getRetryExhaustedAt(), "未达上限不能标记耗尽");
    }

    @Test
    void regressed_clearsRejectCooldownSoRetryApprovalIsNotBlocked() {
        // 回归：验证失败后的重试是系统主动发起的，若不清冷却标记，
        // 第 2 轮审批会被防骚扰闸门挡下，重试预算永远走不到第 3 轮。
        AlertEntity a = alert("VERIFYING", 2);
        a.setVerificationRound(1);
        a.setRejectCooldownUntil(LocalDateTime.now().plusMinutes(12));
        a.setCooldownBaseOccurrence(1);

        service.onVerificationRegressed(a, true);

        assertNull(a.getRejectCooldownUntil(), "验证重试必须清空冷却，否则重试审批被误拦");
        assertNull(a.getCooldownBaseOccurrence());
    }

    @Test
    void regressed_atMax_exhaustsAndLocksFailed() {
        AlertEntity a = alert("VERIFYING", 3);
        a.setVerificationRound(3);

        var decision = service.onVerificationRegressed(a, true);

        assertEquals(AlertLifecycleService.VerificationDecision.EXHAUSTED, decision,
                "第 3/3 轮失败必须停止自动重试");
        assertEquals("FAILED", a.getStatus(), "耗尽后告警强制 FAILED");
        assertNotNull(a.getRetryExhaustedAt(), "必须写入耗尽标记，后续告警才能被永久抑制");
    }

    @Test
    void regressed_readonlyDiagnosis_doesNotConsumeBudget() {
        AlertEntity a = alert("VERIFYING", 1);
        a.setVerificationRound(3); // 已到上限，但只读诊断不占预算

        var decision = service.onVerificationRegressed(a, false);

        assertEquals(AlertLifecycleService.VerificationDecision.RETRY, decision,
                "只读诊断的观察期复现不应被修复重试预算锁死");
        assertEquals("PENDING", a.getStatus());
        assertNull(a.getRetryExhaustedAt());
    }

    @Test
    void beginVerification_resetsRoundToOneOnFreshAlert() {
        AlertEntity a = alert("PENDING", 1);

        int round = service.beginVerification(a,
                LocalDateTime.now(), LocalDateTime.now().plusMinutes(5));

        assertEquals(1, round, "首次验证应为第 1 轮");
        assertEquals("VERIFYING", a.getStatus());
        assertNotNull(a.getVerificationDeadline(), "必须写入倒计时终点供前端展示");
        assertEquals(3, a.getMaxRetries(), "默认最大轮次 3");
    }

    @Test
    void beginVerification_keepsIncrementedRoundOnRetry() {
        AlertEntity a = alert("PENDING", 1);
        a.setVerificationRound(2); // 上一轮失败时状态机已 +1

        int round = service.beginVerification(a,
                LocalDateTime.now(), LocalDateTime.now().plusMinutes(5));

        assertEquals(2, round, "重试任务沿用状态机已推进的轮次，前端显示 2/3");
    }

    // ---------------- 规则三：验证通过闭环 ----------------

    @Test
    void onVerificationPassed_resolvesAndClearsAllMarkers() {
        AlertEntity a = alert("VERIFYING", 8);
        a.setRejectCooldownUntil(LocalDateTime.now().plusMinutes(5));
        a.setCooldownBaseOccurrence(3);
        a.setVerificationDeadline(LocalDateTime.now().plusMinutes(2));

        service.onVerificationPassed(a);

        assertEquals("RESOLVED", a.getStatus(), "验证通过必须闭环为 RESOLVED");
        assertNull(a.getRejectCooldownUntil(), "闭环后清空冷却标记");
        assertNull(a.getCooldownBaseOccurrence());
        assertNull(a.getVerificationDeadline(), "闭环后不再展示倒计时");
        assertNull(a.getRetryExhaustedAt());
    }

    @Test
    void exhaustedReport_containsRoundAndManualGuidance() {
        AlertEntity a = alert("FAILED", 3);
        a.setServiceName("leaky-app");
        a.setTitle("KubePodCrashLooping");

        String report = service.buildExhaustedReport(a, 3, 3, "restartCount 持续增长");

        assertTrue(report.contains("3/3"), "最终失败报告必须写明轮次");
        assertTrue(report.contains("leaky-app"));
        assertTrue(report.contains("不会再自动弹窗"), "必须明确告知已停止自动修复");
        assertTrue(report.contains("人工介入"), "必须给出人工接手建议");
    }
}
