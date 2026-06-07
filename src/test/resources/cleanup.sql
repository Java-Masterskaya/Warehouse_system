-- cleanup.sql
-- Delete test data
DELETE FROM stock_movements WHERE item_id = 1;
DELETE FROM stock WHERE item_id = 1;
DELETE FROM items WHERE id = 1;
DELETE FROM users WHERE id = 1;
