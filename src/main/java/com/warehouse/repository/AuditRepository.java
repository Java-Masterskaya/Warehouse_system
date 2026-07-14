package com.warehouse.repository;

import com.warehouse.entity.AuditLogEntity;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AuditRepository extends JpaRepository<AuditLogEntity, Long> {
}
