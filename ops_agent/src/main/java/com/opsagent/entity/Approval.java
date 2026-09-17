package com.opsagent.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 高危操作审批记录（MySQL 终态审计）。
 *
 * 运行态（PENDING 状态、轮询等待）在 Redis 的 approval:{requestId} 里，
 * 这里只落终态：创建即落 PENDING，决定/超时后更新——这样历史查询
 * 不依赖 Redis（Redis 数据有 TTL，重启也可能丢），审计永久可查。
 *
 * Python 侧通过 HumanApprovalTool 间接使用，前端审批页直接读写。
 */
@Entity
@Table(name = "approvals")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class Approval {

    /** 格式 apr-xxxxxxxx，与工具返回给 LLM 的 requestId 一致 */
    @Id
    private String requestId;

    /** 申请的高危操作，如 rollback nginx */
    private String operation;

    /** agent 给出的申请理由 */
    @Column(columnDefinition = "TEXT")
    private String reason;

    /** 发起审批的任务 ID，用于前端跳转实时页查看推理过程 */
    private String taskId;

    /** PENDING / APPROVED / REJECTED / TIMEOUT */
    private String status;

    /** 决定人（当前只有 admin，超时固定为 system） */
    private String decidedBy;

    /** 人工决定时附带的备注（拒绝原因等），超时为空 */
    @Column(columnDefinition = "TEXT")
    private String decisionNote;

    private LocalDateTime createdAt;

    private LocalDateTime decidedAt;
}
