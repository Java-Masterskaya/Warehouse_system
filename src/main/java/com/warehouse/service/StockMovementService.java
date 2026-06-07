package com.warehouse.service;

import com.warehouse.dto.request.CreateStockMovementRequest;
import com.warehouse.dto.response.StockMovementResponse;
import com.warehouse.entity.User;

public interface StockMovementService {
    StockMovementResponse receiveStock(User user, CreateStockMovementRequest request);
}