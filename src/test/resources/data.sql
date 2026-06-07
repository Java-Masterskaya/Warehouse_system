-- data.sql
-- Insert test user
INSERT INTO users (username, password, role, is_active, created_at)
VALUES ('testuser', '$2a$10$dummyhash', 'ROLE_USER', true, NOW());

-- Insert test item
INSERT INTO items (sku, name, category, min_stock, is_active, created_at, updated_at)
VALUES ('SKU001', 'Тестовый товар', 'Категория', 5, true, NOW(), NOW());

-- Insert test stock for item
INSERT INTO stock (item_id, quantity, updated_at)
VALUES (1, 100, NOW());

-- Insert test stock movement
INSERT INTO stock_movements (item_id, user_id, type, quantity, created_at)
VALUES (1, 1, 'RECEIVE', 100, NOW());
