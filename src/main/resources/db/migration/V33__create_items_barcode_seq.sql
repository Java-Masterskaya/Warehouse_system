-- OPS-5 V28: независимый sequence для генерации номера barcode

CREATE SEQUENCE IF NOT EXISTS items_barcode_seq START WITH 1 INCREMENT BY 1;

SELECT setval('items_barcode_seq', COALESCE((SELECT MAX(id) FROM items), 1));
