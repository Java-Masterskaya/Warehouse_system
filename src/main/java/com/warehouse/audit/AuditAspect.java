package com.warehouse.audit;

import com.warehouse.dto.UserContext;
import com.warehouse.security.UserPrincipal;
import lombok.RequiredArgsConstructor;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

@RequiredArgsConstructor
@Aspect
@Component
public class AuditAspect {

    private final AuditService auditService;
    private final AuditContext auditContext;

    @Around("@annotation(auditable)")
    public Object audit(
            ProceedingJoinPoint joinPoint,
            Auditable auditable
    ) throws Throwable {

        Authentication authentication =
                SecurityContextHolder.getContext().getAuthentication();

        UserPrincipal principal =
                (UserPrincipal) authentication.getPrincipal();

        UserContext userContext =
                new UserContext(
                        principal.getId(),
                        principal.getUsername()
                );

        try {
            Object result = joinPoint.proceed();

            auditService.saveAudit(auditable, userContext);

            return result;
        } finally {
            auditContext.clear();
        }

    }
}