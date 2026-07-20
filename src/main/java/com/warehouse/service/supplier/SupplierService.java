package com.warehouse.service.supplier;

import com.warehouse.dto.request.supplier.CreateSupplierRequest;
import com.warehouse.dto.request.supplier.UpdateSupplierRequest;
import com.warehouse.dto.response.supplier.SupplierResponse;

import java.util.List;

public interface SupplierService {

    SupplierResponse createSupplier(CreateSupplierRequest request);

    SupplierResponse updateSupplier(Long supplierId, UpdateSupplierRequest request);

    SupplierResponse getSupplier(Long supplierId);

    List<SupplierResponse> getSuppliers();

    void deactivateSupplier(Long supplierId);
}
