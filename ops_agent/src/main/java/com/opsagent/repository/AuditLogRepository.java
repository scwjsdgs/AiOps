package com.opsagent.repository;

import com.opsagent.entity.AuditLog;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

@Repository
public interface AuditLogRepository extends JpaRepository<AuditLog, String> {

    @Query("select a from AuditLog a where " +
            "(:category is null or a.category = :category) and " +
            "(:operator is null or lower(a.operator) like lower(concat('%', :operator, '%'))) and " +
            "(:keyword is null or lower(a.action) like lower(concat('%', :keyword, '%')) " +
            "  or lower(a.target) like lower(concat('%', :keyword, '%')) " +
            "  or lower(a.detail) like lower(concat('%', :keyword, '%')))")
    Page<AuditLog> search(@Param("category") String category,
                          @Param("operator") String operator,
                          @Param("keyword") String keyword,
                          Pageable pageable);

    @Query("select a.category, count(a) from AuditLog a where a.timestamp >= :since group by a.category")
    List<Object[]> countByCategorySince(@Param("since") LocalDateTime since);
}
