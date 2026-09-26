package com.opsagent.model;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 告警模型。
 *
 * 字段别名：Java 侧一直是 severity/title/description/serviceName，但外部
 * 上报方（脚本、第三方监控、旧文档）习惯写 level/message/service/namespace。
 * 之前两套名字不兼容，写错字段会**静默丢数据**——接口返回 200，
 * 落库后 severity/title/serviceName 全是 null，调用方以为发成功了。
 * 这里用 @JsonAlias 让两套都能读，不改变落库字段名。
 *
 * ignoreUnknown=true：Alertmanager 与各监控系统版本升级会加字段，
 * 反序列化直接失败等于整条告警被丢，不能因噎废食。
 */
@JsonIgnoreProperties(ignoreUnknown = true)
@Data
public class Alert {
    private String id;

    private String source;      // 监控系统名称

    /** CRITICAL, WARNING, INFO。兼容旧字段 level。 */
    @JsonAlias({"level"})
    private String severity;

    /** 兼容旧字段 message / summary。 */
    @JsonAlias({"message", "summary"})
    private String title;

    /** 兼容旧字段 msg。 */
    @JsonAlias({"msg"})
    private String description;

    /** 兼容旧字段 service / svc。注意 namespace 是"告警来源命名空间"，
     *  K8s 里常与 deployment 名同值，但语义不同，故不并进来。 */
    @JsonAlias({"service", "svc"})
    private String serviceName;

    private String host;
    private String detail;
    private LocalDateTime timestamp;
    private boolean processed;
    private String status;      // PENDING, ANALYZING, RESOLVED, FAILED
}