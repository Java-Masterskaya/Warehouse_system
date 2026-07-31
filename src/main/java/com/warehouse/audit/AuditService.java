package com.warehouse.audit;

import com.warehouse.audit.dto.AuditEvent;
import com.warehouse.audit.entity.AuditLogEntity;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

@Slf4j
@Service
@RequiredArgsConstructor
public class AuditService {

    private final AuditRepository auditRepository;
    private final JdbcTemplate    jdbcTemplate;

    @Value("${app.audit.retention.days:547}")
    private int retentionDays;

    @Value("${app.audit.retention.batch-size:500}")
    private int batchSize;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void saveAudit(AuditEvent event) {

        AuditLogEntity entity = AuditLogEntity.builder().auditAction(event.action()).entityType(event.entityType())
                                              .entityId(event.entityId()).userId(event.userId())
                                              .username(event.username()).oldValue(event.oldValue())
                                              .newValue(event.newValue()).createdAt(LocalDateTime.now()).build();

        auditRepository.save(entity);
    }

    @Scheduled(cron = "0 0 3 * * ?")
    public void cleanupOldAuditLogs() {
        log.info("Starting audit log retention cleanup (older than {} days)...", retentionDays);
        int totalDeleted = 0;
        int deletedInBatch;

        do {
            // Вызываем один батч в отдельной короткой транзакции
            deletedInBatch = deleteBatch(retentionDays, batchSize);
            totalDeleted += deletedInBatch;
        } while (deletedInBatch == batchSize); // Крутим, пока батчи полные

        log.info("Audit log retention finished. Total deleted rows: {}", totalDeleted);
    }

    public int deleteBatch(int retention, int batch) {
        String sql = "SELECT purge_old_audit_logs_batch(?, ?)";
        Integer deletedCount = jdbcTemplate.queryForObject(sql, Integer.class, retention, batch);

        if (deletedCount == null) {
            return 0;
        }

        return deletedCount;
    }
}
