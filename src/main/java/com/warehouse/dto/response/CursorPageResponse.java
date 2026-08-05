package com.warehouse.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

/**
 * A keyset page without an offset or a count query.
 *
 * @param content page content
 * @param nextCursor cursor for the next page, or {@code null} for the last page
 * @param hasNext whether another page exists
 * @param <T> response element type
 */
public record CursorPageResponse<T>(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) List<T> content,
        @Schema(nullable = true, requiredMode = Schema.RequiredMode.REQUIRED) String nextCursor,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) boolean hasNext
) {
}
