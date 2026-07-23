package com.warehouse.service.warehouse;

import com.warehouse.dto.request.warehouse.CreateWarehouseRequest;
import com.warehouse.dto.response.warehouse.WarehouseResponse;

import java.util.List;

public interface WarehouseService {

    WarehouseResponse createWarehouse(CreateWarehouseRequest request);

    List<WarehouseResponse> getWarehouses();
}
