CREATE TABLE categories
(
    id   BIGSERIAL PRIMARY KEY,
    name VARCHAR(100) NOT NULL
);

CREATE UNIQUE INDEX idx_categories_name_lower
    ON categories (LOWER(name));

-- Переносим существующие категории
INSERT INTO categories (name)
SELECT MIN(category)
FROM items
GROUP BY LOWER(category);

-- Добавляем новую колонку
ALTER TABLE items
    ADD COLUMN category_id BIGINT;

-- Заполняем category_id
UPDATE items i
SET category_id = c.id
FROM categories c
WHERE LOWER(c.name) = LOWER(i.category);

-- Делаем колонку обязательной
ALTER TABLE items
    ALTER COLUMN category_id SET NOT NULL;

-- Добавляем внешний ключ
ALTER TABLE items
    ADD FOREIGN KEY (category_id) REFERENCES categories (id);

-- Старый индекс больше не нужен
DROP INDEX idx_items_category;

-- Новый индекс
CREATE INDEX idx_items_category_id
    ON items (category_id) WHERE is_active = TRUE;

-- Удаляем старую колонку
ALTER TABLE items
    DROP COLUMN category;