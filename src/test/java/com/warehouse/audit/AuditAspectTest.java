package com.warehouse.audit;

import com.warehouse.AbstractIntegrationTest;
import com.warehouse.audit.entity.AuditAction;
import com.warehouse.audit.entity.AuditLogEntity;
import com.warehouse.audit.entity.EntityType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

@SpringBootTest
@Import(AuditAspectTest.AspectTestConfig.class)
class AuditAspectTest extends AbstractIntegrationTest {

    @MockitoBean
    private AuditRepository auditRepository;

    @Autowired
    private DummyAuditedService dummyService;

    @Autowired
    private AuditContext auditContext;

    @Test
    @DisplayName("Should intercept method with @Auditable and save log")
    void shouldInterceptAndSaveAuditLog() {
        dummyService.doSomethingAuditable();

        verify(auditRepository, times(1)).save(any(AuditLogEntity.class));
    }

    // =================================================================
    // Внутренняя тестовая конфигурация (существует только для этого теста)
    // =================================================================

    @TestConfiguration
    static class AspectTestConfig {
        @Bean
        public DummyAuditedService dummyAuditedService(AuditContext auditContext) {
            return new DummyAuditedService(auditContext);
        }
    }

    static class DummyAuditedService {

        private final AuditContext auditContext;

        DummyAuditedService(AuditContext auditContext) {
            this.auditContext = auditContext;
        }

        @Auditable(action = AuditAction.ADJUSTMENT, entityType = EntityType.STOCK)
        public void doSomethingAuditable() {
            auditContext.setEntityId(999L);
            auditContext.setNewValue(
                    com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.textNode("test_value"));
        }
    }
}
