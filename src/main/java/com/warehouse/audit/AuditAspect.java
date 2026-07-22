package com.warehouse.audit;

import com.warehouse.audit.dto.AuditEvent;
import com.warehouse.dto.UserContext;
import com.warehouse.security.UserPrincipal;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Slf4j
@RequiredArgsConstructor
@Aspect
@Component
public class AuditAspect {

    private final AuditService auditService;
    private final AuditContext auditContext;

    @Around("@annotation(auditable)")
    public Object audit(ProceedingJoinPoint joinPoint, Auditable auditable) throws Throwable {

        UserContext userContext = null;

        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();

        if (authentication != null && authentication.getPrincipal() instanceof UserPrincipal principal) {
            userContext = new UserContext(principal.getId(), principal.getUsername());
        }

        try {
            Object result = joinPoint.proceed();

            if (auditContext.getOldValue() == null && auditContext.getNewValue() == null) {
                return result;
            }

            AuditEvent event = new AuditEvent(auditable.action(), auditable.entityType(), auditContext.getEntityId(),
                    userContext != null ? userContext.userId() : null,
                    userContext != null ? userContext.username() : null, auditContext.getOldValue(),
                    auditContext.getNewValue());

            if (TransactionSynchronizationManager.isSynchronizationActive()) {
                TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                    @Override
                    public void afterCommit() {
                        saveAudit(event);
                    }
                });
            } else {
                saveAudit(event);
            }

            return result;
        } finally {
            auditContext.clear();
        }

    }

    private void saveAudit(AuditEvent event){
        try {
            auditService.saveAudit(event);
        } catch (Exception e) {
            log.error("Failed to save audit log: action={}, entityType={}, entityId={}", event.action(),
                    event.entityType(), event.entityId(), e);
        }
    }
}