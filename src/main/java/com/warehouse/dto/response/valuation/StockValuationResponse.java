package com.warehouse.dto.response.valuation;

import java.math.BigDecimal;
import java.util.List;

public record StockValuationResponse(
        BigDecimal totalValuation,
        List<CategoryValuation> byCategory) {

}
