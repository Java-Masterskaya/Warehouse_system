package com.warehouse.batch;

import com.warehouse.entity.Item;
import com.warehouse.repository.ItemRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Батчевая джоба для безопасного backfill колонки {@code items.barcode}.
 * <p>
 * <strong>Когда использовать:</strong>
 * <ul>
 *   <li>Таблица items большая ({@code > 100 000} строк) и одиночный SQL UPDATE
 *       будет блокировать строки слишком долго или раздувать таблицу.</li>
 *   <li>Нужен возобновляемый процесс с видимым прогрессом.</li>
 * </ul>
 * <p>
 * <strong>Как работает:</strong>
 * <ol>
 *   <li>Читает небольшой батч строк, где {@code barcode IS NULL}, упорядоченных по id.</li>
 *   <li>Генерирует и присваивает barcode каждому товару.</li>
 *   <li>Сразу коммитит батч (короткая транзакция).</li>
 *   <li>Повторяет, пока не закончатся NULL-строки или пока не запрошена остановка.</li>
 * </ol>
 * <p>
 * Джоба <strong>идемпотентна</strong>: повторный запуск безопасен, т.к. трогает только
 * строки, где {@code barcode IS NULL}.
 */
@Slf4j
@Component
public class ItemBarcodeBackfillJob {

    /**
     * Размер батча по умолчанию. Держим маленьким, чтобы транзакции были короткими.
     * Подстраивайте под размер строки, состояние индексов и lock_wait_timeout.
     */
    public static final int DEFAULT_BATCH_SIZE = 500;

    private final ItemRepository itemRepository;
    private final AtomicBoolean stopped = new AtomicBoolean(false);

    public ItemBarcodeBackfillJob(ItemRepository itemRepository) {
        this.itemRepository = itemRepository;
    }

    /**
     * Мягко запросить остановку после завершения текущего батча.
     */
    public void stop() {
        stopped.set(true);
        log.info("Запрошена остановка backfill. Завершу текущий батч и выйду.");
    }

    /**
     * Запустить backfill до конца (или до остановки).
     *
     * @param batchSize сколько строк обрабатывать за одну транзакцию
     * @return сводка по выполнению
     */
    public Result run(int batchSize) {
        stopped.set(false);
        AtomicLong totalProcessed = new AtomicLong(0);
        long lastId = 0L;
        int iterations = 0;

        log.info("Старт backfill barcode с batchSize={}", batchSize);

        while (!stopped.get()) {
            List<Item> batch = fetchNextBatch(lastId, batchSize);

            if (batch.isEmpty()) {
                log.info("Больше нет строк для backfill. Всего обработано: {}", totalProcessed.get());
                break;
            }

            int processed = processBatch(batch);
            totalProcessed.addAndGet(processed);
            iterations++;

            // Следующая итерация начинается после максимального id в текущем батче
            lastId = batch.get(batch.size() - 1).getId();

            if (iterations % 10 == 0) {
                log.info("Прогресс backfill: {} строк обработано, lastId={}", totalProcessed.get(), lastId);
            }
        }

        boolean hasRemainingNulls = itemRepository.existsByBarcodeIsNull();
        Status status = hasRemainingNulls ? Status.PARTIAL : Status.COMPLETE;

        if (stopped.get() && hasRemainingNulls) {
            status = Status.STOPPED;
            log.warn("Backfill остановлен до завершения. Обработано {} строк.", totalProcessed.get());
        }

        Result result = new Result(status, totalProcessed.get(), lastId, iterations);
        log.info("Backfill завершён: {}", result);
        return result;
    }

    /**
     * Перегрузка с размером батча по умолчанию.
     */
    public Result run() {
        return run(DEFAULT_BATCH_SIZE);
    }

    /**
     * Получить следующий батч товаров, нуждающихся в barcode.
     * <p>
     * Не аннотирован @Transactional, т.к. вызывается из {@link #run}
     * (самовызов внутри бина обходит Spring-прокси). Нижележащий
     * Spring Data репозиторий сам управляет границами транзакций для чтения.
     */
    public List<Item> fetchNextBatch(long lastProcessedId, int batchSize) {
        Pageable pageable = PageRequest.of(0, batchSize);
        return itemRepository.findByBarcodeIsNullAndIdGreaterThanOrderByIdAsc(lastProcessedId, pageable);
    }

    @Transactional
    public int processBatch(List<Item> batch) {
        for (Item item : batch) {
            // Идемпотентная генерация: детерминированная и уникальная для каждой строки
            String barcode = generateBarcode(item);
            item.setBarcode(barcode);
        }
        itemRepository.saveAll(batch);
        return batch.size();
    }

    /**
     * Детерминированная генерация barcode. Использование id гарантирует
     * уникальность и делает операцию идемпотентной (один вход → один выход).
     */
    private String generateBarcode(Item item) {
        return String.format("ITEM-%010d", item.getId());
    }

    // -------------------------------------------------------------------------
    // DTO результата
    // -------------------------------------------------------------------------

    public record Result(Status status, long rowsProcessed, long lastId, int iterations) {
    }

    public enum Status {
        COMPLETE,   // Всё заполнено
        PARTIAL,    // Остановлен не по запросу, остались NULL
        STOPPED     // Остановлен по запросу, остались NULL
    }
}
