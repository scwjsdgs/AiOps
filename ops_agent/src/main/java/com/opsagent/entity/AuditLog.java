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
 * 安全审计日志（MySQL 持久化）。
 *
 * 记录谁在什么时间、通过哪个通道、对哪个对象执行了什么操作、是否成功。
 * 覆盖登录、告警注入、工具执行、审批决策、配置变更、RAG 管理回调等场景。
 * 使用 {@code operator} 记录 JWT 用户名或内部通道标识（system/python-agent）。
 */
@Entity
@Table(name = "audit_logs")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class AuditLog {

    @Id
    private String id;

    private LocalDateTime timestamp;

    /** 操作类型：LOGIN / ALERT / TOOL / APPROVAL / CONFIG / RAG / CHAOS */
    @Column(length = 32)
    private String category;

    /** 操作动作，如 login_success、tool_execute、approval_decide */
    @Column(length = 64)
    private String action;

    /** 操作主体（JWT username 或 system/python-agent） */
    @Column(length = 64)
    private String operator;

    /** 来源通道：REST / WEBSOCKET / WEBHOOK / INTERNAL */
    @Column(length = 32)
    private String channel;

    /** 目标对象，如 taskId / deployment / requestId */
    @Column(length = 128)
    private String target;

    /** 请求或业务备注 */
    @Column(columnDefinition = "TEXT")
    private String detail;

    private boolean success;
}
