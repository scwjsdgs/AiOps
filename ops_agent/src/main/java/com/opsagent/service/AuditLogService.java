package com.opsagent.service;

import com.opsagent.entity.AuditLog;
import com.opsagent.repository.AuditLogRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ServerWebExchange;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 安全审计服务：统一写 MySQL，失败仅告警不阻断业务主链路。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AuditLogService {

    private final AuditLogRepository auditLogRepository;

    public void record(String category, String action, String operator,
                       String channel, String target, String detail, boolean success) {
        try {
            AuditLog entity = new AuditLog();
            entity.setId(UUID.randomUUID().toString());
            entity.setTimestamp(LocalDateTime.now());
            entity.setCategory(category);
            entity.setAction(action);
            entity.setOperator(operator == null || operator.isBlank() ? "unknown" : operator);
            entity.setChannel(channel);
            entity.setTarget(target == null ? "" : target);
            entity.setDetail(detail == null ? "" : detail);
            entity.setSuccess(success);
            auditLogRepository.save(entity);
        } catch (Exception e) {
            log.warn("审计日志写入失败 category={} action={}: {}", category, action, e.getMessage());
        }
    }

    /** 从 JWT 过滤器放入的 username 取操作者；缺失返回 unknown。 */
    public String operator(ServerWebExchange exchange) {
        if (exchange == null) {
            return "unknown";
        }
        Object v = exchange.getAttributes().get("username");
        return v == null ? "unknown" : v.toString();
    }

    /** 分页查询审计日志。 */
    public Page<AuditLog> search(String category, String operator, String keyword, int page, int size) {
        PageRequest pageable = PageRequest.of(Math.max(page - 1, 0), size,
                Sort.by(Sort.Direction.DESC, "timestamp"));
        return auditLogRepository.search(blankToNull(category), blankToNull(operator),
                blankToNull(keyword), pageable);
    }

    /** 最近 7 天分类统计，供安全审计页概览。 */
    public Map<String, Object> stats() {
        Map<String, Object> result = new LinkedHashMap<>();
        try {
            List<Object[]> rows = auditLogRepository.countByCategorySince(LocalDateTime.now().minusDays(7));
            for (Object[] row : rows) {
                result.put(String.valueOf(row[0]), ((Number) row[1]).longValue());
            }
        } catch (Exception e) {
            log.warn("审计统计失败: {}", e.getMessage());
        }
        return result;
    }

    private static String blankToNull(String s) {
        return (s == null || s.isBlank()) ? null : s;
    }
}
