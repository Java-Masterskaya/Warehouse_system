package com.warehouse.repository;

import com.warehouse.entity.Item;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.stereotype.Repository;

/**
 * Репозиторий для управления товарами.
 * Расширяет JpaRepository с поддержкой JPA Specification.
 */
@Repository
public interface ItemRepository extends JpaRepository<Item, Long>, JpaSpecificationExecutor<Item> {
    /**
     * Проверяет наличие товара с указанным SKU.
     *
     * @param sku артикул товара
     * @return true, если товар существует, иначе false
     */
    boolean existsBySku(String sku);
}