package com.warehouse.specification;

import com.warehouse.entity.Item;
import org.springframework.data.jpa.domain.Specification;

/**
 * Спецификации для фильтрации товаров по различным критериям.
 * Использует Spring Data JPA Specification API.
 */
public class ItemSpecification {

    private ItemSpecification() {
    }

    /**
     * Фильтрует активные товары (active = true).
     *
     * @return спецификация для фильтрации активных товаров
     */
    public static Specification<Item> isActive() {
        return (root, query, cb) -> cb.isTrue(root.get("active"));
    }

    /**
     * Фильтрует товары по категории.
     *
     * @param category название категории
     * @return спецификация для фильтрации по категории
     */
    public static Specification<Item> hasCategory(String category) {
        return (root, query, cb) -> cb.equal(root.get("category"), category);
    }

    /**
     * Ищет товары, в названии которых содержится подстрока (без учёта регистра).
     *
     * @param search поисковая строка
     * @return спецификация для поиска по названию
     */
    public static Specification<Item> nameContains(String search) {
        return (root, query, cb) ->
                cb.like(cb.lower(root.get("name")), "%" + search.toLowerCase() + "%");
    }
}