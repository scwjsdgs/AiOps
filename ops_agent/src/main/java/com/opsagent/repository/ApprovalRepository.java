package com.opsagent.repository;

import com.opsagent.entity.Approval;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface ApprovalRepository extends JpaRepository<Approval, String> {

    /** 按状态分页查询，供审批页筛选（PENDING / APPROVED / REJECTED / TIMEOUT） */
    org.springframework.data.domain.Page<Approval> findByStatus(String status, org.springframework.data.domain.PageRequest pageRequest);
}
