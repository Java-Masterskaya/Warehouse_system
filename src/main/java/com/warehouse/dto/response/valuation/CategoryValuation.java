package com.warehouse.dto.response.valuation;

import java.math.BigDecimal;

public record CategoryValuation(
        String category,
        BigDecimal valuation) {
}
