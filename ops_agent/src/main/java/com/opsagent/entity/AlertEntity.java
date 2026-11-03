package com.opsagent.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Entity
@Table(name = "alerts")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class AlertEntity {
    @Id
    private String id;

    private String source;

    private String severity;

    private String title;

    private String description;

    @Column(name = "service_name")
    private String serviceName;

    private String host;

    @Column(columnDefinition = "TEXT")
    private String detail;

    private LocalDateTime timestamp;

    private boolean processed;

    private String status;   // PENDING, ANALYZING, RESOLVED, FAILED

    @Column(name = "create_time")
    private LocalDateTime createTime;

    /**
     * 聚合去重计数：同服务同指纹的告警重复到达时，不新增行，
     * 而是更新主记录并让该计数 +1，前端显示「该故障已持续发生 X 次」。
     *
     * 用包装类型 Integer：数据库中历史老数据的 occurrence_count 列可能是 NULL，
     * 基本类型 int 在 ORM 映射时对 NULL 列会抛 IllegalArgumentException
     * ("Can not set int field ... to null value")。默认值 1 保证新行不为 null。
     */
    @Column(name = "occurrence_count")
    private Integer occurrenceCount = 1;

    /**
     * Alertmanager 告警指纹（alertname|instance|severity）。
     * resolved 事件靠它找回 firing 时落库的那条记录；没有它 resolved 只能盲目按 ID 找，
     * 而 ID 是随机 UUID，永远对不上。
     */
    @Column(name = "fingerprint")
    private String fingerprint;

    /**
     * 聚合时指向主告警关联的任务 ID：当新告警被标记 CORRELATED 时，
     * 前端「查看分析」能直接定位到那条主任务，而不是报“没有关联任务”。
     */
    @Column(name = "task_id")
    private String taskId;

    // ------------------------------------------------------------------
    // 审批/验证状态机（生产级闭环）
    //
    // 拒绝冷却：用户拒绝（或审批超时）后，底层故障往往仍在持续。立刻再次弹窗
    // 只会变成骚扰。进入 rejectCooldownUntil 之前，只累加 occurrenceCount、
    // 维持 PENDING（未解决）状态，不建任务、不弹窗；冷却到期仍在报错才重新拉起。
    // ------------------------------------------------------------------

    /** 拒绝后的静默冷却截止时间。null = 不在冷却期。 */
    @Column(name = "reject_cooldown_until")
    private LocalDateTime rejectCooldownUntil;

    /** 进入冷却时的发生次数快照：冷却到期时用它判断“期间是否还在报错”。 */
    @Column(name = "cooldown_base_occurrence")
    private Integer cooldownBaseOccurrence;

    /** 最近一次人工/系统拒绝时间（审计用）。 */
    @Column(name = "last_rejected_at")
    private LocalDateTime lastRejectedAt;

    /** 正在进行/最近一轮验证的单轮时长截止时间，前端倒计时数据源。 */
    @Column(name = "verification_deadline")
    private LocalDateTime verificationDeadline;

    /** 本轮验证开始时间。 */
    @Column(name = "verification_started_at")
    private LocalDateTime verificationStartedAt;

    /** 已完成且失败的验证轮次（0 表示尚未失败过）。前端展示 (n/maxRetries)。 */
    @Column(name = "verification_round")
    private Integer verificationRound = 0;

    /** 最大自动重试轮次（默认 3）。达到后强制 FAILED，禁止再次自动弹窗。 */
    @Column(name = "max_retries")
    private Integer maxRetries = 3;

    /**
     * 重试耗尽时间。非 null 表示该故障已进入“仅人工处理”终态：
     * 后续同指纹告警只累加次数、维持 FAILED，绝不再自动建任务/弹审批。
     */
    @Column(name = "retry_exhausted_at")
    private LocalDateTime retryExhaustedAt;
}