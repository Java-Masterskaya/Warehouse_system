package com.warehouse.service.supplier;

import com.warehouse.dto.request.supplier.CreateSupplierRequest;
import com.warehouse.dto.request.supplier.UpdateSupplierRequest;
import com.warehouse.dto.response.supplier.SupplierResponse;
import com.warehouse.entity.Supplier;
import com.warehouse.exception.EntityNotFoundException;
import com.warehouse.mapper.SupplierMapper;
import com.warehouse.repository.SupplierRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
@Slf4j
public class SupplierServiceImpl implements SupplierService {

    private final SupplierRepository supplierRepository;
    private final SupplierMapper supplierMapper;

    @Override
    @Transactional
    public SupplierResponse createSupplier(CreateSupplierRequest request) {
        log.debug("Creating supplier with name '{}'", request.name());

        Supplier supplier = supplierMapper.toEntity(request);
        Supplier savedSupplier = supplierRepository.save(supplier);

        log.info("Supplier created: id={}, name='{}'", savedSupplier.getId(), savedSupplier.getName());
        return supplierMapper.toResponse(savedSupplier);
    }

    @Override
    @Transactional
    public SupplierResponse updateSupplier(Long supplierId, UpdateSupplierRequest request) {

        log.debug("Updating supplier with id={}", supplierId);

        Supplier supplier = getActiveSupplierOrThrow(supplierId);

        supplierMapper.updateSupplierFromRequest(request, supplier);

        log.info("Supplier updated: id={}, name='{}'",
                supplier.getId(), supplier.getName());

        return supplierMapper.toResponse(supplier);
    }

    @Override
    @Transactional(readOnly = true)
    public SupplierResponse getSupplier(Long supplierId) {
        log.debug("Getting supplier with id={}", supplierId);

        Supplier supplier = getSupplierOrThrow(supplierId);
        return supplierMapper.toResponse(supplier);
    }

    @Override
    @Transactional(readOnly = true)
    public List<SupplierResponse> getSuppliers() {
        log.debug("Getting suppliers");

        return supplierRepository.findAll().stream()
                .map(supplierMapper::toResponse)
                .toList();
    }

    @Override
    @Transactional
    public void deactivateSupplier(Long supplierId) {
        log.debug("Deactivating supplier with id={}", supplierId);

        Supplier supplier = getActiveSupplierOrThrow(supplierId);

        supplier.setActive(false);

        log.info("Supplier deactivated: id={}, name='{}'",
                supplier.getId(), supplier.getName());
    }

    private Supplier getSupplierOrThrow(Long supplierId) {
        return supplierRepository.findById(supplierId)
                .orElseThrow(() -> {
                    log.warn("Supplier with id={} not found", supplierId);
                    return EntityNotFoundException.forId("Supplier", supplierId);
                });
    }

    private Supplier getActiveSupplierOrThrow(Long supplierId) {
        Supplier supplier = getSupplierOrThrow(supplierId);

        if (!supplier.isActive()) {
            log.warn("Supplier with id={} is inactive", supplierId);
            throw EntityNotFoundException.forId("Supplier", supplierId);
        }

        return supplier;
    }
}
