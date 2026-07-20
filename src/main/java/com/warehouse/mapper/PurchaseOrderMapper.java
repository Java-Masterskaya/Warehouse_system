package com.warehouse.mapper;

import com.warehouse.dto.response.purchaseorder.PurchaseOrderItemResponse;
import com.warehouse.dto.response.purchaseorder.PurchaseOrderResponse;
import com.warehouse.entity.PurchaseOrder;
import com.warehouse.entity.PurchaseOrderItem;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

@Mapper(componentModel = "spring")
public interface PurchaseOrderMapper {

    @Mapping(target = "supplierId", source = "supplier.id")
    @Mapping(target = "supplierName", source = "supplier.name")
    PurchaseOrderResponse toResponse(PurchaseOrder purchaseOrder);

    @Mapping(target = "itemId", source = "item.id")
    @Mapping(target = "itemName", source = "item.name")
    PurchaseOrderItemResponse toItemResponse(PurchaseOrderItem purchaseOrderItem);
}
