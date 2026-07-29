package com.warehouse.repository;

import com.warehouse.entity.Warehouse;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;

@Repository
public interface WarehouseRepository extends JpaRepository<Warehouse, Long> {

    boolean existsByNameIgnoreCase(String name);

    Optional<Warehouse> findByDefaultWarehouseTrue();

    List<Warehouse> findAllByOrderByNameAsc();
}
