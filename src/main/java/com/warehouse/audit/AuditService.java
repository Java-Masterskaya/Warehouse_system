package com.warehouse.audit;

import com.warehouse.audit.dto.AuditEvent;
import com.warehouse.audit.entity.AuditLogEntity;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import lombok.experimental.FieldDefaults;
import lombok.extern.slf4j.Slf4j;
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

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void saveAudit(AuditEvent event) {

        AuditLogEntity entity = AuditLogEntity.builder().auditAction(event.action()).entityType(event.entityType())
                                              .entityId(event.entityId()).userId(event.userId())
                                              .username(event.username()).oldValue(event.oldValue())
                                              .newValue(event.newValue()).createdAt(LocalDateTime.now()).build();

        auditRepository.save(entity);
    }
}
