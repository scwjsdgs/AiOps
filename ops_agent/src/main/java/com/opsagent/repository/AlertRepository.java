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
     * 找某个服务最近一条处于 REJECTED/REGRESSED/PENDING 的旧告警，
     * 用于恢复观察期复现后的降噪；避免同一服务的 CrashLoopBackOff 新告警
     * 又建一条新告警、新任务，而不是在同一条旧告警上横跳。
     */
    List<AlertEntity> findFirstByServiceNameAndStatusInOrderByCreateTimeDesc(
            String serviceName, java.util.Collection<String> statuses);

    /**
     * 同服务指定状态的最近若干条记录（按创建时间倒序）。
     *
     * 与上面的 findFirst 版本区别：这里返回列表，用于在「冷却 / 耗尽」判定时
     * 跳过最新一条后继续找下一条仍处于冷却中的主记录——REST 通道每次告警都会
     * 新建一行，只看第一条会漏掉真正在冷却期的那条。
     */
    List<AlertEntity> findByServiceNameAndStatusInOrderByCreateTimeDesc(
            String serviceName, java.util.Collection<String> statuses);

    /** 按来源 + 状态集合 + 创建时间截止查询，用于启动清理僵尸预测性预警。 */
    List<AlertEntity> findBySourceAndStatusInAndCreateTimeBefore(
            String source, java.util.Collection<String> statuses, LocalDateTime createTimeBefore);

    /**
     * 扫描「冷却期已到期、但故障仍在持续」的告警，用于自动重新拉起诊断与审批。
     *
     * 拒绝冷却（防骚扰）的核心：用户点了拒绝，但我们并不把故障当成已解决。
     * 冷却期内只累加 occurrenceCount、维持 PENDING；冷却到期时若 occurrenceCount
     * 相比进入冷却时的快照还在增长（说明仍在报错），才强制拉起新任务并重新弹审批。
     *
     * 只取 PENDING（未解决）且 occurrenceCount > cooldownBaseOccurrence 的记录，
     * 把「期间有没有新告警」的判断交给一次 SQL，定时扫描不用全表遍历内存。
     */
    @Query("select a from AlertEntity a where a.status = 'PENDING' " +
            "and a.rejectCooldownUntil is not null and a.rejectCooldownUntil <= :now " +
            "and a.occurrenceCount > a.cooldownBaseOccurrence")
    List<AlertEntity> findCoolingDownAndStillFiring(@Param("now") LocalDateTime now);

    /**
     * 带筛选的分页查询。前端告警页的搜索框/筛选器必须打到后端：
     * 只在前端过滤当前页数据的话，搜索"nginx"只能命中这 10 条里的，
     * 用户以为搜了全库，实际是假搜索——所以筛选条件全部下推到 SQL。
     *
     * 各条件为 null（或空串）时该条件不生效，一个方法覆盖所有组合：
     *   severity 级别、status 状态、keyword 标题/服务名/描述模糊匹配。
     *
     * 去重清理：历史遗留的「解除聚合」重复记录（同一 fingerprint 多条割裂行）
     * 在这里按 fingerprint + createTime 兜住，同一 fingerprint 只展示最新一条主记录。
     * fingerprint 为空的记录（非 Webhook 通道）不受影响，全部保留。
     */
    @Query("select a from AlertEntity a where " +
            "(:severity is null or :severity = '' or a.severity = :severity) and " +
            "(:status is null or :status = '' or a.status = :status) and " +
            "(:keyword is null or :keyword = '' or " +
            "  lower(a.title) like lower(concat('%', :keyword, '%')) " +
            "  or lower(a.serviceName) like lower(concat('%', :keyword, '%')) " +
            "  or lower(a.description) like lower(concat('%', :keyword, '%'))) and " +
            "(a.fingerprint is null or a.createTime = " +
            "   (select max(a2.createTime) from AlertEntity a2 " +
            "    where a2.fingerprint = a.fingerprint))")
    Page<AlertEntity> search(@Param("severity") String severity,
                             @Param("status") String status,
                             @Param("keyword") String keyword,
                             Pageable pageable);
}