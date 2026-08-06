package com.warehouse.service.property;

import com.warehouse.dto.request.movement.TransferStockRequest;
import com.warehouse.exception.InvalidMovementRequestException;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.Example;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Property-based тесты (jqwik) для инвариантов остатков склада. Задача QA-6: проверять
 * инварианты на случайных последовательностях операций, а не на единичных примерах.
 *
 * <p>Проверяются два инварианта домена (см. тикет QA-9 "Валидация остатков"):</p>
 * <ul>
 *     <li>остаток ни на одном складе никогда не уходит в минус — списание/перемещение
 *     сверх доступного остатка отклоняется без изменения состояния;</li>
 *     <li>Σ(приходы) − Σ(успешных списаний) == текущий суммарный остаток товара по всем
 *     складам — перемещения между складами глобальную сумму не меняют.</li>
 * </ul>
 *
 * <p>Операции применяются через {@link StockMovementTestHarness}, который прогоняет их
 * через реальные {@code StockMovementServiceImpl}/{@code BatchServiceImpl} (не переизобретает
 * бизнес-логику в тесте). Последовательность включает приход и списание на дефолтном складе
 * (так работает публичный API) и перемещения между произвольными складами из пула —
 * это единственный способ у сервиса менять остаток не на дефолтном складе, поэтому именно
 * перемещения покрывают многосклад в этих свойствах.</p>
 *
 * <p><b>Вне скоупа этих свойств:</b> резервирование остатка (harness всегда стабит активный
 * резерв нулём — см. {@code StockMovementTestHarness.stubStockReserveRepository}), поэтому
 * ветка {@code StockAvailabilityService.getAvailable}, вычитающая резерв, этими свойствами не
 * покрывается; и stocktake/{@code ADJUSTMENT} — он перезаписывает остаток внешним физическим
 * значением, а не выводится из истории приходов/списаний, поэтому не обязан подчиняться
 * проверяемой здесь формуле баланса (harness также пока не стабит
 * {@code StockRepository.findByItemIdForUpdate}, нужный {@code stocktake()}).</p>
 */
class StockInvariantsPropertyTest {

    private static final int MIN_SEQUENCE_LENGTH = 1;
    private static final int MAX_SEQUENCE_LENGTH = 40;
    private static final int MAX_QUANTITY = 50;
    private static final int MIN_QUANTITY = 0;

    /**
     * Остаток на каждом складе никогда не становится отрицательным ни после одной операции
     * случайной последовательности: списание и перемещение сверх остатка отклоняются сервисом
     * ({@code InsufficientStockException}) и не меняют состояние.
     *
     * @param operations случайная последовательность складских операций (см. {@link #ops()})
     */
    @Property(tries = 1000)
    void stockNeverGoesNegative(@ForAll("ops") List<StockOperation> operations) {
        StockMovementTestHarness harness = new StockMovementTestHarness();

        for (StockOperation operation : operations) {
            harness.apply(operation);
            assertAllWarehousesNonNegative(harness, operation);
        }
    }

    /**
     * Сумма прихода минус сумма успешных списаний равна текущему суммарному остатку товара
     * по всем складам — после каждой операции, а не только в конце последовательности, чтобы
     * shrinking сходился к минимальному префиксу, где инвариант впервые нарушается.
     *
     * @param operations случайная последовательность складских операций (см. {@link #ops()})
     */
    @Property(tries = 1000)
    void totalStockEqualsReceivedMinusWrittenOff(@ForAll("ops") List<StockOperation> operations) {
        StockMovementTestHarness harness = new StockMovementTestHarness();

        for (StockOperation operation : operations) {
            harness.apply(operation);
            assertBalanceInvariant(harness, operation);
        }
    }

    /**
     * Регрессия зафиксированного примера, который jqwik сгенерировал на первом же случайном
     * прогоне (никакой shrinking не потребовался — контрпример уже был минимальным: список
     * длины 1). Списание, вызванное раньше самого первого прихода на складе, приводило к
     * необработанному {@code EntityNotFoundException} внутри {@code BatchServiceImpl.lockStock}
     * вместо ожидаемого доменного отказа: строка {@code Stock} для склада создаётся только
     * приходом ({@code stockRepository.createEmptyStockIfAbsent}), а до этого момента
     * "списывать нечего" сигнализируется другим исключением, чем "списываем больше, чем есть".
     * Сам инвариант остатка при этом не нарушается — состояние не меняется в обоих случаях, —
     * но для property-теста это означало неотловленное исключение, роняющее прогон. Зафиксировано
     * как детерминированный пример, чтобы не потерять этот найденный кейс при последующих
     * запусках со случайным сидом.
     */
    @Example
    void writeOffBeforeAnyReceiptIsRejectedWithoutChangingState() {
        StockMovementTestHarness harness = new StockMovementTestHarness();

        boolean applied = harness.apply(StockOperation.writeOff(1));

        assertThat(applied).isFalse();
        assertAllWarehousesNonNegative(harness, StockOperation.writeOff(1));
        assertBalanceInvariant(harness, StockOperation.writeOff(1));
    }

    /**
     * Регрессия кейса, найденного при расширении диапазона {@code quantity} для TRANSFER до
     * границы 0 наравне с приходом/списанием: перемещение с {@code quantity=0} приводило к
     * необработанному {@code StockMovementInvariantException} вместо ожидаемого доменного
     * отказа, потому что {@code StockMovementServiceImpl.transfer} полагался только на
     * {@code @Positive} в {@code TransferStockRequest}, которую enforces {@code @Valid} на
     * контроллере (этот harness вызывает сервис напрямую, минуя контроллер). Гэп закрыт в
     * QA-8: {@code transfer()} теперь сам отклоняет {@code quantity <= 0} доменным
     * {@link InvalidMovementRequestException} до похода в репозиторий, симметрично
     * {@code registerReceipt}. Тест обновлён вместе с фиксом — проверяет не просто отказ, а
     * конкретный тип и сообщение исключения, чтобы не откатиться обратно на неявное поведение.
     */
    @Example
    void transferWithZeroQuantityIsRejectedWithoutChangingState() {
        StockMovementTestHarness harness = new StockMovementTestHarness();
        TransferStockRequest zeroQuantityTransfer = new TransferStockRequest(
                harness.itemId(), harness.warehouseId(0), harness.warehouseId(1), 0);

        assertThatThrownBy(() -> harness.service().transfer(zeroQuantityTransfer, harness.userContext()))
                .isInstanceOf(InvalidMovementRequestException.class)
                .hasMessage("Quantity must be greater than 0");

        assertAllWarehousesNonNegative(harness, StockOperation.transfer(0, 1, 0));
        assertBalanceInvariant(harness, StockOperation.transfer(0, 1, 0));
    }

    /**
     * Генератор случайных последовательностей складских операций: приход, списание на
     * дефолтном складе и перемещения между двумя случайными различными складами пула — все
     * три с количеством от {@link #MIN_QUANTITY} (граничный случай — 0) до
     * {@link #MAX_QUANTITY}. Смешаны так, чтобы списания и перемещения регулярно запрашивали
     * больше, чем доступно, — иначе свойство "остаток не уходит в минус" почти никогда не
     * задействовало бы ветку отклонения.
     *
     * @return генератор списков {@link StockOperation} длиной от 1 до {@link #MAX_SEQUENCE_LENGTH}
     */
    @Provide
    Arbitrary<List<StockOperation>> ops() {
        Arbitrary<Integer> quantity = Arbitraries.integers().between(MIN_QUANTITY, MAX_QUANTITY);
        Arbitrary<Integer> warehouseIndex =
                Arbitraries.integers().between(0, StockMovementTestHarness.WAREHOUSE_COUNT - 1);

        Arbitrary<StockOperation> receive = quantity.map(StockOperation::receive);
        Arbitrary<StockOperation> writeOff = quantity.map(StockOperation::writeOff);
        Arbitrary<StockOperation> transfer = Combinators.combine(warehouseIndex, warehouseIndex, quantity)
                .as(StockOperation::transfer)
                .filter(operation -> operation.fromWarehouseIndex() != operation.toWarehouseIndex());

        Arbitrary<StockOperation> operation = Arbitraries.oneOf(receive, receive, writeOff, writeOff, transfer);

        return operation.list().ofMinSize(MIN_SEQUENCE_LENGTH).ofMaxSize(MAX_SEQUENCE_LENGTH);
    }

    private void assertAllWarehousesNonNegative(StockMovementTestHarness harness, StockOperation operation) {
        for (int warehouseIndex = 0; warehouseIndex < StockMovementTestHarness.WAREHOUSE_COUNT; warehouseIndex++) {
            assertThat(harness.stockAt(warehouseIndex))
                    .as("Stock at warehouse #%d must never go negative after %s", warehouseIndex, operation)
                    .isGreaterThanOrEqualTo(0);
        }
    }

    private void assertBalanceInvariant(StockMovementTestHarness harness, StockOperation operation) {
        long expectedBalance = harness.totalReceived() - harness.totalWrittenOff();
        assertThat(harness.totalStock())
                .as("Total stock across warehouses must equal receipts minus write-offs after %s", operation)
                .isEqualTo(expectedBalance);
    }
}
