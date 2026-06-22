package com.warehouse.specification;

import com.warehouse.entity.Item;
import org.springframework.data.jpa.domain.Specification;

public class ItemSpecification {

    private ItemSpecification() {
    }

    public static Specification<Item> isActive() {
        return (root, query, cb) -> cb.isTrue(root.get("active"));
    }

    public static Specification<Item> hasCategory(String category) {
        return (root, query, cb) -> cb.equal(root.get("category"), category);
    }

    public static Specification<Item> nameContains(String search) {
        return (root, query, cb) ->
                cb.like(cb.lower(root.get("name")), "%" + search.toLowerCase() + "%");
    }
}