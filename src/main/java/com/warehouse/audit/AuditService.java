package com.warehouse.audit;

import com.warehouse.audit.entity.AuditLogEntity;
import com.warehouse.dto.UserContext;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import lombok.experimental.FieldDefaults;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

@Slf4j
@Service
@RequiredArgsConstructor
@FieldDefaults(level = AccessLevel.PRIVATE, makeFinal = true)
public class AuditService {

    AuditRepository auditRepository;
    AuditContext auditContext;

    public void saveAudit(Auditable auditable, UserContext ctx) {

        AuditLogEntity entity = AuditLogEntity.builder().auditAction(auditable.action())
                                              .entityType(auditable.entityType()).entityId(auditContext.getEntityId())
                                              .userId(ctx.userId()).username(ctx.username())
                                              .oldValue(auditContext.getOldValue()).newValue(auditContext.getNewValue())
                                              .createdAt(LocalDateTime.now()).build();

        auditRepository.save(entity);
    }
}
