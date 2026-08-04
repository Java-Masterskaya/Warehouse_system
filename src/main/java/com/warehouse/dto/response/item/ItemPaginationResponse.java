package com.warehouse.dto.response.item;

import com.warehouse.dto.response.CursorPageResponse;
import com.warehouse.dto.response.PageResponse;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

/**
 * Typed offset and cursor response variants for the item list endpoint.
 */
@Schema(
        name = "ItemPaginationResponse",
        oneOf = {
            ItemPaginationResponse.OffsetPage.class,
            ItemPaginationResponse.CursorPage.class
        }
)
public sealed interface ItemPaginationResponse
        permits ItemPaginationResponse.OffsetPage, ItemPaginationResponse.CursorPage {

    List<ItemResponse> content();

    static OffsetPage from(PageResponse<ItemResponse> response) {
        return new OffsetPage(
                response.content(),
                response.totalElements(),
                response.totalPages(),
                response.page(),
                response.size()
        );
    }

    static CursorPage from(CursorPageResponse<ItemResponse> response) {
        return new CursorPage(response.content(), response.nextCursor(), response.hasNext());
    }

    @Schema(name = "ItemOffsetPageResponse")
    record OffsetPage(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) List<ItemResponse> content,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) long totalElements,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) int totalPages,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) int page,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) int size
    ) implements ItemPaginationResponse {
    }

    @Schema(name = "ItemCursorPageResponse")
    record CursorPage(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) List<ItemResponse> content,
            @Schema(nullable = true, requiredMode = Schema.RequiredMode.REQUIRED) String nextCursor,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) boolean hasNext
    ) implements ItemPaginationResponse {
    }
}
