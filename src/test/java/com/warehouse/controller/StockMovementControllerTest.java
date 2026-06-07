package com.warehouse.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.warehouse.dto.request.CreateStockMovementRequest;
import com.warehouse.entity.MovementType;
import com.warehouse.entity.User;
import com.warehouse.service.StockMovementService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.LocalDateTime;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Контроллерные тесты для StockMovementController.
 * Тестирует HTTP эндпоинты для управления движениями товаров.
 */
@ExtendWith(MockitoExtension.class)
class StockMovementControllerTest {

    @Mock
    private StockMovementService stockMovementService;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        StockMovementController controller = new StockMovementController(stockMovementService);
        mockMvc = MockMvcBuilders.standaloneSetup(controller).build();
    }

    /**
     * Тест: Успешное пополнение остатков товара через API.
     */
    @Test
    void receiveStockSuccess() throws Exception {
        Long itemId = 1L;
        int quantity = 10;
        CreateStockMovementRequest request = new CreateStockMovementRequest(itemId, quantity);

        when(stockMovementService.receiveStock(any(User.class), any(CreateStockMovementRequest.class)))
            .thenReturn(new com.warehouse.dto.response.StockMovementResponse(
                itemId, 1L, MovementType.RECEIVE, quantity, 100, LocalDateTime.now()
            ));

        mockMvc.perform(post("/api/movements/receive")
                .contentType(MediaType.APPLICATION_JSON)
                .content(new ObjectMapper().writeValueAsString(request)))
            .andExpect(status().isOk());

        verify(stockMovementService).receiveStock(any(User.class), any(CreateStockMovementRequest.class));
    }
}
