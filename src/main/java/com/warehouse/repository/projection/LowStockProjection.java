package com.warehouse.repository.projection;

public interface LowStockProjection {

    Long getId();

    String getSku();

    String getName();

    String getCategory();

    int getCurrentStock();

    int getMinStock();
}
