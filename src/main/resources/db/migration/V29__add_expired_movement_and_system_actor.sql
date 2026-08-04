ALTER TABLE stock_movements
    DROP CONSTRAINT IF EXISTS stock_movements_quantity_check,
    DROP CONSTRAINT IF EXISTS stock_movements_type_check;

ALTER TABLE stock_movements
    ADD CONSTRAINT stock_movements_quantity_check
        CHECK (
            (type IN (
                'RECEIVE',
                'WRITE_OFF',
                'EXPIRED',
                'TRANSFER_OUT',
                'TRANSFER_IN'
            ) AND quantity > 0)
            OR (type = 'ADJUSTMENT' AND quantity <> 0)
        ),
    ADD CONSTRAINT stock_movements_type_check
        CHECK (type IN (
            'RECEIVE',
            'WRITE_OFF',
            'EXPIRED',
            'ADJUSTMENT',
            'TRANSFER_OUT',
            'TRANSFER_IN'
        ));

INSERT INTO users (username, password, role, is_active)
VALUES ('system-batch-cleanup', '!disabled-system-actor!', 'ROLE_USER', FALSE);
