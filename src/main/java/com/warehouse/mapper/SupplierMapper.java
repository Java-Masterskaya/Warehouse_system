package com.warehouse.mapper;

import com.warehouse.dto.request.supplier.CreateSupplierRequest;
import com.warehouse.dto.request.supplier.UpdateSupplierRequest;
import com.warehouse.dto.response.supplier.SupplierResponse;
import com.warehouse.entity.Supplier;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.MappingTarget;

@Mapper(componentModel = "spring")
public interface SupplierMapper {

    @Mapping(target = "id", ignore = true)
    @Mapping(target = "active", ignore = true)
    @Mapping(target = "createdAt", ignore = true)
    @Mapping(target = "updatedAt", ignore = true)
    Supplier toEntity(CreateSupplierRequest request);

    SupplierResponse toResponse(Supplier supplier);

    @Mapping(target = "id", ignore = true)
    @Mapping(target = "active", ignore = true)
    @Mapping(target = "createdAt", ignore = true)
    @Mapping(target = "updatedAt", ignore = true)
    void updateSupplierFromRequest(UpdateSupplierRequest request, @MappingTarget Supplier supplier);
}
