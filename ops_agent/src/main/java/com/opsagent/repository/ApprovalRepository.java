package com.opsagent.repository;

import com.opsagent.entity.Approval;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

@Repository
public interface ApprovalRepository extends JpaRepository<Approval, String> {

    /** 按状态分页查询，供审批页筛选（PENDING / APPROVED / REJECTED / TIMEOUT） */
    org.springframework.data.domain.Page<Approval> findByStatus(String status, org.springframework.data.domain.PageRequest pageRequest);

    /** 超时扫描：查找仍为 PENDING 且创建时间早于 deadline 的审批单 */
    List<Approval> findByStatusAndCreatedAtBefore(String status, LocalDateTime createdAt);

    /** 某任务是否存在处于 PENDING 的未决审批单（任务超时兜底用来跳过审批挂起的任务） */
    boolean existsByStatusAndTaskId(String status, String taskId);

    /**
     * 某任务是否存在指定状态的审批单。
     *
     * 生产闭环用它判定「这次任务是不是一次已获人工批准的修复」：
     * 只有批准过的任务执行成功后才进入 VERIFYING 验证轮次，
     * 纯只读诊断任务不应该被当成修复来计数和重试。
     */
    boolean existsByTaskIdAndStatus(String taskId, String status);
}
