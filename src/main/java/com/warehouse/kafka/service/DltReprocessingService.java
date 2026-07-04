package com.warehouse.kafka.service;

import com.warehouse.dto.response.DltReprocessResponse;

public interface DltReprocessingService {
    DltReprocessResponse reprocessAllDltMessages();
}
