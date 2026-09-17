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
     * Alertmanager 告警指纹（alertname|instance|severity）。
     * resolved 事件靠它找回 firing 时落库的那条记录；没有它 resolved 只能盲目按 ID 找，
     * 而 ID 是随机 UUID，永远对不上。
     */
    @Column(name = "fingerprint")
    private String fingerprint;
}