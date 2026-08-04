package com.warehouse.repository;

import com.warehouse.entity.MovementType;
import com.warehouse.entity.StockMovement;
import jakarta.persistence.EntityManager;
import jakarta.persistence.TypedQuery;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Executes movement history keyset queries without count or offset operations.
 */
@Repository
@RequiredArgsConstructor
public class StockMovementKeysetRepository {

    private final EntityManager entityManager;

    /**
     * Loads the next history page ordered by creation time and id descending.
     *
     * @param itemId item identifier
     * @param type optional movement type
     * @param lastCreatedAt nullable cursor timestamp
     * @param lastId nullable cursor id
     * @param limit maximum result count
     * @return ordered movement entities after the supplied keyset
     */
    public List<StockMovement> findNextPage(
            Long itemId,
            MovementType type,
            LocalDateTime lastCreatedAt,
            Long lastId,
            int limit
    ) {
        if ((lastCreatedAt == null) != (lastId == null)) {
            throw new IllegalArgumentException("Both movement keyset values must be supplied together");
        }

        StringBuilder hql = new StringBuilder("""
                select movement
                from StockMovement movement
                join fetch movement.user
                join fetch movement.warehouse
                where movement.item.id = :itemId
                """);
        if (type != null) {
            hql.append(" and movement.type = :type");
        }
        if (lastCreatedAt != null && lastId != null) {
            hql.append(" and (movement.createdAt, movement.id) < (:lastCreatedAt, :lastId)");
        }
        hql.append(" order by movement.createdAt desc, movement.id desc");

        TypedQuery<StockMovement> query = entityManager.createQuery(hql.toString(), StockMovement.class)
                .setParameter("itemId", itemId);
        if (type != null) {
            query.setParameter("type", type);
        }
        if (lastCreatedAt != null) {
            query.setParameter("lastCreatedAt", lastCreatedAt);
            query.setParameter("lastId", lastId);
        }

        return query.setMaxResults(limit).getResultList();
    }
}
