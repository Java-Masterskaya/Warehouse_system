package com.warehouse.audit;

import com.warehouse.audit.entity.AuditLogEntity;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AuditRepository extends JpaRepository<AuditLogEntity, Long> {
    AuditLogEntity findTopByOrderByIdDesc();
}
