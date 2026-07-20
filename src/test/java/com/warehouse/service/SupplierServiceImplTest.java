package com.warehouse.service;

import com.warehouse.dto.request.supplier.CreateSupplierRequest;
import com.warehouse.dto.request.supplier.UpdateSupplierRequest;
import com.warehouse.entity.Supplier;
import com.warehouse.exception.EntityNotFoundException;
import com.warehouse.mapper.SupplierMapper;
import com.warehouse.repository.SupplierRepository;
import com.warehouse.service.supplier.SupplierService;
import com.warehouse.service.supplier.SupplierServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mapstruct.factory.Mappers;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SupplierServiceImplTest {

    @Mock
    private SupplierRepository supplierRepository;

    private SupplierService supplierService;

    @BeforeEach
    void setUp() {
        SupplierMapper supplierMapper = Mappers.getMapper(SupplierMapper.class);
        supplierService = new SupplierServiceImpl(supplierRepository, supplierMapper);
    }

    @Test
    void shouldCreateSupplier() {
        CreateSupplierRequest request = new CreateSupplierRequest("Samsung");

        Supplier savedSupplier = Supplier.builder()
                .id(1L)
                .name("Samsung")
                .active(true)
                .build();

        when(supplierRepository.save(org.mockito.ArgumentMatchers.any(Supplier.class)))
                .thenReturn(savedSupplier);

        var result = supplierService.createSupplier(request);

        assertThat(result.id()).isEqualTo(1L);
        assertThat(result.name()).isEqualTo("Samsung");
        assertThat(result.active()).isTrue();

        verify(supplierRepository).save(org.mockito.ArgumentMatchers.any(Supplier.class));
    }

    @Test
    void shouldUpdateActiveSupplier() {
        Supplier supplier = Supplier.builder()
                .id(1L)
                .name("Old name")
                .active(true)
                .build();

        when(supplierRepository.findById(1L)).thenReturn(Optional.of(supplier));

        var result = supplierService.updateSupplier(
                1L,
                new UpdateSupplierRequest("New name")
        );

        assertThat(result.name()).isEqualTo("New name");
        assertThat(supplier.getName()).isEqualTo("New name");
    }

    @Test
    void shouldReturnSupplierEvenWhenInactive() {
        Supplier supplier = Supplier.builder()
                .id(1L)
                .name("Samsung")
                .active(false)
                .build();

        when(supplierRepository.findById(1L)).thenReturn(Optional.of(supplier));

        var result = supplierService.getSupplier(1L);

        assertThat(result.id()).isEqualTo(1L);
        assertThat(result.active()).isFalse();
    }

    @Test
    void shouldReturnAllSuppliers() {
        Supplier first = Supplier.builder()
                .id(1L)
                .name("Samsung")
                .active(true)
                .build();

        Supplier second = Supplier.builder()
                .id(2L)
                .name("LG")
                .active(false)
                .build();

        when(supplierRepository.findAll()).thenReturn(List.of(first, second));

        var result = supplierService.getSuppliers();

        assertThat(result).hasSize(2);
    }

    @Test
    void shouldDeactivateActiveSupplier() {
        Supplier supplier = Supplier.builder()
                .id(1L)
                .name("Samsung")
                .active(true)
                .build();

        when(supplierRepository.findById(1L)).thenReturn(Optional.of(supplier));

        supplierService.deactivateSupplier(1L);

        assertThat(supplier.isActive()).isFalse();
    }

    @Test
    void shouldThrowWhenUpdatingInactiveSupplier() {
        Supplier supplier = Supplier.builder()
                .id(1L)
                .name("Samsung")
                .active(false)
                .build();

        when(supplierRepository.findById(1L)).thenReturn(Optional.of(supplier));

        assertThrows(
                EntityNotFoundException.class,
                () -> supplierService.updateSupplier(
                        1L,
                        new UpdateSupplierRequest("New name")
                )
        );
    }

    @Test
    void shouldThrowWhenSupplierNotFound() {
        when(supplierRepository.findById(99L)).thenReturn(Optional.empty());

        assertThrows(
                EntityNotFoundException.class,
                () -> supplierService.getSupplier(99L)
        );
    }
}
