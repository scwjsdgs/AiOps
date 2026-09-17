package com.opsagent.repository;

import com.opsagent.entity.AlertEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

@Repository
public interface AlertRepository extends JpaRepository<AlertEntity, String> {

    /**
     * 按天统计告警数（最近 N 天），返回 [日期, 级别, 数量] 行。
     * 日期在 Java 侧格式化，JPQL 里用 function('date', ...) 取 DATE 部分；
     * 按日期+级别分组，前端才能画"分级堆叠柱状图"。
     */
    @Query("select function('date', a.createTime) as day, a.severity, count(a) " +
            "from AlertEntity a " +
            "where a.createTime >= :since " +
            "group by function('date', a.createTime), a.severity " +
            "order by day")
    List<Object[]> countDailyBySeverity(@Param("since") LocalDateTime since);

    /** 按级别统计总数，返回 [级别, 数量]，Dashboard 饼图数据源。 */
    @Query("select a.severity, count(a) from AlertEntity a " +
            "group by a.severity")
    List<Object[]> countBySeverity();

    /** 按状态统计总数，返回 [状态, 数量]，Dashboard 概览卡片（待处理/分析中/已解决/失败）数据源。 */
    @Query("select a.status, count(a) from AlertEntity a " +
            "group by a.status")
    List<Object[]> countByStatus();

    /**
     * 按 fingerprint 找最近的告警记录。Alertmanager 的 resolved 事件靠它
     * 找回 firing 时落库的那条（ID 是随机 UUID，无法直接对应）。
     * 取最新一条：同一告警可能多次 firing，最新的才是当前活跃记录。
     */
    List<AlertEntity> findByFingerprintOrderByCreateTimeDesc(String fingerprint);
}