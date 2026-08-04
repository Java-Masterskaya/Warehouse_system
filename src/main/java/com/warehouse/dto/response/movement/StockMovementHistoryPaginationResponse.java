package com.warehouse.dto.response.movement;

import com.warehouse.dto.response.CursorPageResponse;
import com.warehouse.dto.response.PageResponse;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

/**
 * Typed offset and cursor response variants for movement history.
 */
@Schema(
        name = "StockMovementHistoryPaginationResponse",
        oneOf = {
            StockMovementHistoryPaginationResponse.OffsetPage.class,
            StockMovementHistoryPaginationResponse.CursorPage.class
        }
)
public sealed interface StockMovementHistoryPaginationResponse
        permits StockMovementHistoryPaginationResponse.OffsetPage,
        StockMovementHistoryPaginationResponse.CursorPage {

    List<StockMovementHistoryResponse> content();

    static OffsetPage from(PageResponse<StockMovementHistoryResponse> response) {
        return new OffsetPage(
                response.content(),
                response.totalElements(),
                response.totalPages(),
                response.page(),
                response.size()
        );
    }

    static CursorPage from(CursorPageResponse<StockMovementHistoryResponse> response) {
        return new CursorPage(response.content(), response.nextCursor(), response.hasNext());
    }

    @Schema(name = "StockMovementHistoryOffsetPageResponse")
    record OffsetPage(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) List<StockMovementHistoryResponse> content,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) long totalElements,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) int totalPages,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) int page,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) int size
    ) implements StockMovementHistoryPaginationResponse {
    }

    @Schema(name = "StockMovementHistoryCursorPageResponse")
    record CursorPage(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) List<StockMovementHistoryResponse> content,
            @Schema(nullable = true, requiredMode = Schema.RequiredMode.REQUIRED) String nextCursor,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) boolean hasNext
    ) implements StockMovementHistoryPaginationResponse {
    }
}
