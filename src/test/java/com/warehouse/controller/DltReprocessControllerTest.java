package com.warehouse.controller;

import com.warehouse.controller.advice.GlobalExceptionHandler;
import com.warehouse.dto.response.DltReprocessResponse;
import com.warehouse.exception.DltReprocessingInProgressException;
import com.warehouse.kafka.service.DltReprocessingService;
import com.warehouse.web.ApiPaths;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.concurrent.CompletableFuture;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class DltReprocessControllerTest {

    @Mock
    private DltReprocessingService dltReprocessingService;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        DltReprocessController controller = new DltReprocessController(dltReprocessingService);
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    void shouldKeepAsynchronousAcceptedResponse() throws Exception {
        when(dltReprocessingService.reprocessAllDltMessages())
                .thenReturn(CompletableFuture.completedFuture(DltReprocessResponse.empty()));

        mockMvc.perform(post(ApiPaths.V1_API_ROOT + "/admin/dlq/low-stock/reprocess"))
                .andExpect(status().isAccepted());
    }

    @Test
    void shouldReturnConflictWhenAnotherPodOwnsReprocessingLock() throws Exception {
        when(dltReprocessingService.reprocessAllDltMessages())
                .thenThrow(new DltReprocessingInProgressException());

        mockMvc.perform(post(ApiPaths.V1_API_ROOT + "/admin/dlq/low-stock/reprocess"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("DLT_REPROCESSING_IN_PROGRESS"))
                .andExpect(jsonPath("$.message").value("DLT reprocessing is already running"));
    }
}
