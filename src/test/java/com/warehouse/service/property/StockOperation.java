package com.warehouse.service.property;

/**
 * Одна операция из случайно сгенерированной последовательности складских движений,
 * используемая property-тестами инвариантов остатков ({@link StockInvariantsPropertyTest}).
 *
 * <p>{@link Kind#RECEIVE} и {@link Kind#WRITE_OFF} всегда применяются к дефолтному складу —
 * ровно так же, как это делает публичный API {@code StockMovementService}: приход и списание
 * не принимают ID склада, склад определяется сервисом самостоятельно. {@link Kind#TRANSFER}
 * задаёт индексы склада-источника и склада-получателя в пуле складов, поднятом
 * {@link StockMovementTestHarness}, — так в сценарий попадают несколько складов.</p>
 *
 * @param kind               тип операции
 * @param quantity           количество единиц товара
 * @param fromWarehouseIndex индекс склада-источника (используется только для {@link Kind#TRANSFER})
 * @param toWarehouseIndex   индекс склада-получателя (используется только для {@link Kind#TRANSFER})
 */
record StockOperation(Kind kind, int quantity, int fromWarehouseIndex, int toWarehouseIndex) {

    /** Тип складской операции, генерируемой property-тестом. */
    enum Kind {
        /** Приход товара на дефолтный склад. */
        RECEIVE,
        /** Списание товара с дефолтного склада. */
        WRITE_OFF,
        /** Перемещение товара между двумя складами. */
        TRANSFER
    }

    static StockOperation receive(int quantity) {
        return new StockOperation(Kind.RECEIVE, quantity, -1, -1);
    }

    static StockOperation writeOff(int quantity) {
        return new StockOperation(Kind.WRITE_OFF, quantity, -1, -1);
    }

    static StockOperation transfer(int fromWarehouseIndex, int toWarehouseIndex, int quantity) {
        return new StockOperation(Kind.TRANSFER, quantity, fromWarehouseIndex, toWarehouseIndex);
    }

    @Override
    public String toString() {
        return switch (kind) {
            case RECEIVE -> "RECEIVE(" + quantity + ")";
            case WRITE_OFF -> "WRITE_OFF(" + quantity + ")";
            case TRANSFER -> "TRANSFER(" + fromWarehouseIndex + "->" + toWarehouseIndex + ", " + quantity + ")";
        };
    }
}
