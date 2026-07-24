package com.warehouse.service.warehouse;

import com.warehouse.dto.request.warehouse.CreateWarehouseRequest;
import com.warehouse.dto.response.warehouse.WarehouseResponse;
import com.warehouse.entity.Warehouse;
import com.warehouse.exception.DuplicateWarehouseNameException;
import com.warehouse.repository.WarehouseRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
@Slf4j
public class WarehouseServiceImpl implements WarehouseService {

    private final WarehouseRepository warehouseRepository;

    @Override
    @Transactional
    public WarehouseResponse createWarehouse(CreateWarehouseRequest request) {
        String name = request.name().trim();
        log.debug("Creating warehouse with name '{}'", name);

        if (warehouseRepository.existsByNameIgnoreCase(name)) {
            throw DuplicateWarehouseNameException.forName(name);
        }

        Warehouse warehouse = Warehouse.builder()
                .name(name)
                .defaultWarehouse(false)
                .build();

        try {
            Warehouse savedWarehouse = warehouseRepository.saveAndFlush(warehouse);
            log.info("Warehouse created: id={}, name='{}'", savedWarehouse.getId(), savedWarehouse.getName());
            return toResponse(savedWarehouse);
        } catch (DataIntegrityViolationException ex) {
            log.warn("Warehouse with name '{}' already exists", name);
            throw DuplicateWarehouseNameException.forName(name);
        }
    }

    @Override
    @Transactional(readOnly = true)
    public List<WarehouseResponse> getWarehouses() {
        return warehouseRepository.findAllByOrderByNameAsc().stream()
                .map(this::toResponse)
                .toList();
    }

    private WarehouseResponse toResponse(Warehouse warehouse) {
        return new WarehouseResponse(
                warehouse.getId(),
                warehouse.getName(),
                warehouse.isDefaultWarehouse()
        );
    }
}
