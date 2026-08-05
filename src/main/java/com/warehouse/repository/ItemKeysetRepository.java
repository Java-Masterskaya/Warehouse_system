package com.warehouse.repository;

import com.warehouse.entity.Item;
import jakarta.persistence.EntityManager;
import jakarta.persistence.TypedQuery;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Locale;

/**
 * Executes keyset item queries without count or offset operations.
 */
@Repository
@RequiredArgsConstructor
public class ItemKeysetRepository {

    private final EntityManager entityManager;

    /**
     * Loads the next item page in a stable tuple order.
     *
     * @param sortField allowed item sort field
     * @param direction sort direction for both the field and id
     * @param category optional exact category filter
     * @param search optional case-insensitive name filter
     * @param lastSortValue nullable keyset sort value
     * @param lastId nullable keyset id
     * @param limit maximum result count
     * @return ordered items after the supplied keyset
     */
    public List<Item> findNextPage(
            String sortField,
            Sort.Direction direction,
            String category,
            String search,
            String lastSortValue,
            Long lastId,
            int limit
    ) {
        String property = resolveProperty(sortField);
        validatePosition(lastSortValue, lastId);
        String sortExpression = "item." + property;
        KeysetOrder keysetOrder = resolveOrder(direction);
        String hql = buildHql(
                sortExpression,
                keysetOrder,
                category != null,
                search != null,
                lastSortValue != null
        );
        TypedQuery<Item> query = entityManager.createQuery(hql, Item.class);
        bindParameters(query, category, search, lastSortValue, lastId);

        return query.setMaxResults(limit).getResultList();
    }

    private String buildHql(
            String sortExpression,
            KeysetOrder keysetOrder,
            boolean filterByCategory,
            boolean filterBySearch,
            boolean hasPosition
    ) {
        StringBuilder hql = new StringBuilder("""
                select item
                from Item item
                join fetch item.category
                where item.active = true
                """);
        if (filterByCategory) {
            hql.append(" and item.category.name = :category");
        }
        if (filterBySearch) {
            hql.append(" and lower(item.name) like :search");
        }
        if (hasPosition) {
            hql.append(" and (")
                    .append(sortExpression)
                    .append(", item.id) ")
                    .append(keysetOrder.comparison())
                    .append(" (:lastSortValue, :lastId)");
        }
        hql.append(" order by ")
                .append(sortExpression)
                .append(' ')
                .append(keysetOrder.direction())
                .append(", item.id ")
                .append(keysetOrder.direction());
        return hql.toString();
    }

    private void bindParameters(
            TypedQuery<Item> query,
            String category,
            String search,
            String lastSortValue,
            Long lastId
    ) {
        if (category != null) {
            query.setParameter("category", category);
        }
        if (search != null) {
            query.setParameter("search", "%" + search.toLowerCase(Locale.ROOT) + "%");
        }
        if (lastSortValue != null) {
            query.setParameter("lastSortValue", lastSortValue);
            query.setParameter("lastId", lastId);
        }
    }

    private String resolveProperty(String sortField) {
        return switch (sortField) {
            case "name" -> "name";
            case "sku" -> "sku";
            default -> throw new IllegalArgumentException("Unsupported item sort field: " + sortField);
        };
    }

    private void validatePosition(String lastSortValue, Long lastId) {
        if ((lastSortValue == null) != (lastId == null)) {
            throw new IllegalArgumentException("Both item keyset values must be supplied together");
        }
    }

    private KeysetOrder resolveOrder(Sort.Direction direction) {
        if (direction.isDescending()) {
            return new KeysetOrder("<", "desc");
        }
        return new KeysetOrder(">", "asc");
    }

    private record KeysetOrder(String comparison, String direction) {
    }
}
