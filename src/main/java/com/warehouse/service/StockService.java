package com.warehouse.service;

import com.warehouse.dto.internal.StockMovementInternalRequest;

public interface StockService {
    void writeOff(StockMovementInternalRequest request);

    void receive(StockMovementInternalRequest request);
}