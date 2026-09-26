package com.opsagent.repository;

import com.opsagent.entity.AlertEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
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

    /**
     * 带筛选的分页查询。前端告警页的搜索框/筛选器必须打到后端：
     * 只在前端过滤当前页数据的话，搜索"nginx"只能命中这 10 条里的，
     * 用户以为搜了全库，实际是假搜索——所以筛选条件全部下推到 SQL。
     *
     * 各条件为 null（或空串）时该条件不生效，一个方法覆盖所有组合：
     *   severity 级别、status 状态、keyword 标题/服务名/描述模糊匹配。
     */
    @Query("select a from AlertEntity a where " +
            "(:severity is null or a.severity = :severity) and " +
            "(:status is null or a.status = :status) and " +
            "(:keyword is null or lower(a.title) like lower(concat('%', :keyword, '%')) " +
            "  or lower(a.serviceName) like lower(concat('%', :keyword, '%')) " +
            "  or lower(a.description) like lower(concat('%', :keyword, '%')))")
    Page<AlertEntity> search(@Param("severity") String severity,
                             @Param("status") String status,
                             @Param("keyword") String keyword,
                             Pageable pageable);
}