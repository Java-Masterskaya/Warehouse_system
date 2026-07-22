package com.warehouse.audit;

import com.warehouse.audit.dto.AuditEvent;
import com.warehouse.audit.entity.AuditLogEntity;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import lombok.experimental.FieldDefaults;
import lombok.experimental.NonFinal;
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
@FieldDefaults(level = AccessLevel.PRIVATE, makeFinal = true)
public class AuditService {

    AuditRepository auditRepository;
    JdbcTemplate    jdbcTemplate;

    @NonFinal
    @Value("${app.audit.retention.days:547}")
    private int retentionDays;

    @NonFinal
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
    @Transactional
    public void cleanupOldAuditLogs() {
        log.info("Starting audit log retention cleanup (older than {} days)...", retentionDays);

        Integer deletedRows = jdbcTemplate.queryForObject("SELECT purge_old_audit_logs(?, ?)", Integer.class,
                retentionDays, batchSize);

        log.info("Audit log cleanup finished. Total deleted rows: {}", deletedRows);
    }
}
