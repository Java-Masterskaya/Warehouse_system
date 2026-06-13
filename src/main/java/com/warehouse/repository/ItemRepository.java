package com.warehouse.repository;

import com.warehouse.dto.response.item.ItemDetailsResponse;
import com.warehouse.entity.Item;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Optional;

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

    @Query("""
            SELECT new com.warehouse.dto.response.item.ItemDetailsResponse(
                i.id,
                i.sku,
                i.name,
                i.category,
                i.minStock,
                s.quantity,
                i.active,
                i.createdAt,
                i.updatedAt
            )
            FROM Item i
            JOIN Stock s on s.item.id = i.id
            WHERE i.id = :itemId
            """)
    Optional<ItemDetailsResponse> findWithStock(@Param("itemId") Long itemId);
}