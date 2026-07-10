package com.warehouse.kafka.service;

import com.warehouse.dto.response.DltReprocessResponse;

import java.util.concurrent.CompletableFuture;

public interface DltReprocessingService {
    CompletableFuture<DltReprocessResponse> reprocessAllDltMessages();
}
