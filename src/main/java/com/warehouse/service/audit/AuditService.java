package com.warehouse.service.audit;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.warehouse.dto.request.audit.AuditLogRequest;
import com.warehouse.entity.AuditLogEntity;
import com.warehouse.entity.User;
import com.warehouse.mapper.AuditMapper;
import com.warehouse.repository.AuditRepository;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import lombok.experimental.FieldDefaults;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
@FieldDefaults(level = AccessLevel.PRIVATE, makeFinal = true)
public class AuditService {

    AuditRepository auditRepository;
    AuditMapper auditMapper;
    ObjectMapper objectMapper;

    public void saveAudit(AuditLogRequest request, User oldUser, User newUser) throws JsonProcessingException {
        String oldValue = objectMapper.writeValueAsString(oldUser);
        String newValue = objectMapper.writeValueAsString(newUser);

        auditRepository.save(auditMapper.mapRequestToEntity(request, oldValue, newValue));

        List<AuditLogEntity> list = auditRepository.findAll();
        log.info("Now in audit repository: {}", list.stream().map(a -> a.toString()).toList());
    }
}
