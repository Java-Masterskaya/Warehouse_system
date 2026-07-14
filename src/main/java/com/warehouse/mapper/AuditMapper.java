package com.warehouse.mapper;

import com.warehouse.dto.request.audit.AuditLogRequest;
import com.warehouse.entity.AuditLogEntity;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

@Mapper(componentModel = "spring")
public interface AuditMapper {

    @Mapping(target = "id", ignore = true)
    @Mapping(target = "oldValue", source = "oldValue")
    @Mapping(target = "newValue", source = "newValue")
    AuditLogEntity mapRequestToEntity(AuditLogRequest request, String oldValue, String newValue);
}
