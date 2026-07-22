package com.warehouse.repository;

import com.warehouse.entity.PurchaseOrder;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

@Repository
public interface PurchaseOrderRepository extends JpaRepository<PurchaseOrder, Long> {

    @Query("select po.id from PurchaseOrder po")
    Page<Long> findPageIds(Pageable pageable);

    @EntityGraph(attributePaths = {"supplier", "items", "items.item"})
    List<PurchaseOrder> findAllByIdIn(Collection<Long> ids);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @EntityGraph(attributePaths = {"supplier", "items", "items.item"})
    @Query("""
            select po
            from PurchaseOrder po
            where po.id = :purchaseOrderId
            """)
    Optional<PurchaseOrder> findByIdForReceive(
            @Param("purchaseOrderId") Long purchaseOrderId
    );
}
