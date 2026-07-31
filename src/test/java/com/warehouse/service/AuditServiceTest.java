package com.warehouse.service;

import com.warehouse.audit.AuditRepository;
import com.warehouse.audit.AuditService;
import com.warehouse.audit.entity.AuditAction;
import com.warehouse.audit.entity.AuditLogEntity;
import com.warehouse.audit.entity.EntityType;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@Import(AuditService.class)
class AuditServiceTest {

    @Autowired
    private AuditRepository auditLogRepository;

    @Autowired
    private AuditService auditService;

    @Autowired
    private EntityManager entityManager;

    @Test
    void shouldDeleteOnlyOldAuditLogs() {
        LocalDateTime oldDate = LocalDateTime.now().minusDays(600);
        LocalDateTime newDate = LocalDateTime.now().minusDays(10);

        AuditLogEntity oldLog = createAuditLogWithDate(oldDate);
        AuditLogEntity newLog = createAuditLogWithDate(newDate);

        List<AuditLogEntity> saved = auditLogRepository.saveAll(List.of(oldLog, newLog));
        Long oldId = saved.get(0).getId();
        Long newId = saved.get(1).getId();

        auditService.cleanupOldAuditLogs();

        entityManager.flush();
        entityManager.clear();

        assertThat(auditLogRepository.findById(oldId))
                .as("Старая запись (600 дней) должна быть удалена retention-процедурой")
                .isEmpty();

        List<AuditLogEntity> list = auditLogRepository.findAll();
        assertThat(list.size()).isEqualTo(1);

        var foundNewLog = auditLogRepository.findById(newId);
        assertThat(foundNewLog)
                .as("Свежая запись (10 дней) должна сохраниться")
                .isPresent();

    }

    private AuditLogEntity createAuditLogWithDate(LocalDateTime date) {
        return AuditLogEntity.builder().userId(1L).username("admin").auditAction(AuditAction.CREATE)
                             .entityType(EntityType.USER).entityId(2L).oldValue(null).newValue(null).createdAt(date)
                             .build();
    }
}