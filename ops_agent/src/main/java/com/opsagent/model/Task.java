package com.opsagent.model;

import lombok.Data;
import java.time.LocalDateTime;
import java.util.List;

@Data
public class Task {
    private String id;
    private String type;            // ROOT_CAUSE_ANALYSIS, REPAIR, REPORT
    private String alertId;         // 关联告警ID

    /**
     * 任务状态机：
     *   PENDING（排队）→ RUNNING（分析/执行）→ VERIFYING（修复已执行，验证中）
     *   → SUCCESS（验证通过，彻底闭环）
     *   → REGRESSED（验证失败，可自动重试）  → (新任务) …
     *   → FAILED（重试耗尽 / 执行失败，终态）
     *   → REJECTED（人工拒绝审批，终态）
     * VERIFYING 是「批准并执行完成」之后、结论之前的关键中间态：只有 5 分钟
     * 验证通过才算闭环，避免“执行成功即宣布修复”。
     */
    private String status;

    private String input;           // 输入参数（JSON）
    private String output;          // 输出结果（JSON）
    private List<String> agentSteps; // Agent 推理步骤摘要
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private String createdBy;       // 系统或用户

    // ------------------------------------------------------------------
    // 验证轮次（前端倒计时与 (n/3) 展示的数据源）
    // ------------------------------------------------------------------

    /** 当前验证轮次，从 1 开始。未进入验证为 0。 */
    private Integer verificationRound;
    /** 允许的最大自动重试轮次（默认 3），来自告警/配置。达到即 FAILED。 */
    private Integer maxRetries;
    /** 本轮验证开始时间。 */
    private LocalDateTime verificationStartedAt;
    /** 本轮验证截止时间（开始 + 验证窗口，默认 5 分钟），前端倒计时用。 */
    private LocalDateTime verificationDeadline;
    /** 关联告警 ID 的快照说明：验证失败后由 Java 侧决定是否重试/终止。 */
    private String verifyNote;
}