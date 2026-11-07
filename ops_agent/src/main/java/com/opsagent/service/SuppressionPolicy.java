package com.opsagent.service;

import com.opsagent.entity.AlertEntity;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 告警抑制策略（防骚扰闸门）。
 *
 * 职责：
 * - checkSuppress：判断告警是否应被抑制（拒绝冷却期内、或重试预算已耗尽）。
 *   冷却到期时自动清标记并返回 NONE，让调用方恢复正常建任务流程。
 *
 * 只做抑制决策，不建任务、不聚合。命中时调用方只累加 occurrenceCount 并直接返回。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SuppressionPolicy {

    /** 告警被抑制的原因。NONE = 正常放行。 */
    public enum SuppressAction {
        /** 冷却期内：只累加次数，不建任务、不弹窗 */
        COOLDOWN,
        /** 重试预算耗尽：只累加次数，永远不再自动拉起 */
        EXHAUSTED,
        /** 无需抑制，调用方继续走正常建任务流程 */
        NONE
    }

    private final AlertLifecycleService alertLifecycleService;

    /**
     * 判断一条告警当前是否应该抑制自动处理。
     *
     * 被调用方（Webhook）在命中抑制时应**只累加 occurrenceCount** 并直接返回，
     * 不建任务、不聚合、不弹审批。
     *
     * 冷却已到期时这里会顺手清空冷却字段并返回 NONE。
     */
    public SuppressAction checkSuppress(AlertEntity alert) {
        if (alert == null) {
            return SuppressAction.NONE;
        }
        AlertLifecycleService.SuppressAction raw = alertLifecycleService.checkSuppress(alert);
        return toSuppressAction(raw);
    }

    private SuppressAction toSuppressAction(AlertLifecycleService.SuppressAction raw) {
        if (raw == null) {
            return SuppressAction.NONE;
        }
        return switch (raw) {
            case COOLDOWN -> SuppressAction.COOLDOWN;
            case EXHAUSTED -> SuppressAction.EXHAUSTED;
            default -> SuppressAction.NONE;
        };
    }
}
