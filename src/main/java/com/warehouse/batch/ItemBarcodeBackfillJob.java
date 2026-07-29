package com.warehouse.batch;

import com.warehouse.exception.BackfillAlreadyRunningException;
import com.warehouse.repository.ItemRepository;
import com.warehouse.service.item.ItemBarcodeGenerator;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.concurrent.CompletableFuture;
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
 * <p>
 * <strong>Конкурентность:</strong> одновременно может выполняться только один {@link #run}.
 * Повторный вызов, пока джоба уже работает, кидает {@link BackfillAlreadyRunningException}
 * вместо того, чтобы молча сбросить флаг остановки первого запуска.
 */
@Slf4j
@Component
public class ItemBarcodeBackfillJob {

    /**
     * Размер батча по умолчанию. Держим маленьким, чтобы транзакции были короткими.
     * Подстраивайте под размер строки, состояние индексов и lock_wait_timeout.
     */
    public static final int DEFAULT_BATCH_SIZE = 500;

    /**
     * Интервал логирования прогресса (в итерациях).
     */
    private static final int LOG_INTERVAL = 10;

    private final ItemRepository itemRepository;
    private final ItemBarcodeGenerator barcodeGenerator;
    private final TransactionTemplate txTemplate;

    private final AtomicBoolean stopped = new AtomicBoolean(false);
    private final AtomicBoolean running = new AtomicBoolean(false);

    private volatile Result lastResult;

    public ItemBarcodeBackfillJob(ItemRepository itemRepository,
                                   ItemBarcodeGenerator barcodeGenerator,
                                   PlatformTransactionManager transactionManager) {
        this.itemRepository = itemRepository;
        this.barcodeGenerator = barcodeGenerator;
        this.txTemplate = new TransactionTemplate(transactionManager);
    }

    /**
     * Мягко запросить остановку после завершения текущего батча.
     */
    public void stop() {
        if (!running.get()) {
            log.warn("Запрошена остановка backfill, но джоба сейчас не выполняется.");
            return;
        }
        stopped.set(true);
        log.info("Запрошена остановка backfill. Завершу текущий батч и выйду.");
    }

    /**
     * Выполняется в отдельном потоке (пул {@code @Async}), не блокируя HTTP-поток
     * вызывающего контроллера. Прогресс/результат доступны через {@link #isRunning()}
     * и {@link #getLastResult()}.
     *
     * @param batchSize сколько строк обрабатывать за одну транзакцию
     * @return future с результатом (полезно для тестов; контроллер его не ждёт)
     */
    @Async
    public CompletableFuture<Result> runAsync(int batchSize) {
        return CompletableFuture.completedFuture(run(batchSize));
    }

    /**
     * Запустить backfill до конца (или до остановки). Блокирующий вызов —
     * снаружи ожидается запуск через {@link #runAsync}, а не напрямую из HTTP-потока.
     *
     * @param batchSize сколько строк обрабатывать за одну транзакцию
     * @return сводка по выполнению
     * @throws BackfillAlreadyRunningException если джоба уже выполняется в другом потоке
     */
    public Result run(int batchSize) {
        if (!running.compareAndSet(false, true)) {
            throw BackfillAlreadyRunningException.forJob("ItemBarcodeBackfillJob");
        }
        try {
            return doRun(batchSize);
        } finally {
            running.set(false);
        }
    }

    private Result doRun(int batchSize) {
        stopped.set(false);
        AtomicLong totalProcessed = new AtomicLong(0);
        long lastId = 0L;
        int iterations = 0;

        log.info("Старт backfill barcode с batchSize={}", batchSize);

        while (!stopped.get()) {
            List<Long> batch = fetchNextBatch(lastId, batchSize);

            if (batch.isEmpty()) {
                log.info("Больше нет строк для backfill. Всего обработано: {}", totalProcessed.get());
                break;
            }

            int processed = processBatch(batch);
            totalProcessed.addAndGet(processed);
            iterations++;

            lastId = batch.get(batch.size() - 1);

            if (iterations % LOG_INTERVAL == 0) {
                log.info("Прогресс backfill: {} строк обработано, lastId={}", totalProcessed.get(), lastId);
            }
        }

        boolean hasRemainingNulls = itemRepository.existsByBarcodeIsNull();
        Status status;
        if (hasRemainingNulls) {
            status = Status.PARTIAL;
        } else {
            status = Status.COMPLETE;
        }

        if (stopped.get() && hasRemainingNulls) {
            status = Status.STOPPED;
            log.warn("Backfill остановлен до завершения. Обработано {} строк.", totalProcessed.get());
        }

        Result result = new Result(status, totalProcessed.get(), lastId, iterations);
        lastResult = result;
        log.info("Backfill завершён: {}", result);
        return result;
    }

    /**
     * Перегрузка с размером батча по умолчанию.
     *
     * @return сводка по выполнению
     */
    public Result run() {
        return run(DEFAULT_BATCH_SIZE);
    }

    /**
     * @return выполняется ли джоба прямо сейчас
     */
    public boolean isRunning() {
        return running.get();
    }

    /**
     * @return результат последнего завершённого запуска, либо {@code null}, если джоба
     * ещё ни разу не запускалась (с момента старта приложения)
     */
    public Result getLastResult() {
        return lastResult;
    }

    /**
     * Получить id следующего батча товаров, нуждающихся в barcode.
     * <p>
     * Не аннотирован @Transactional, т.к. вызывается из {@link #doRun}
     * (самовызов внутри бина обходит Spring-прокси). Нижележащий
     * Spring Data репозиторий сам управляет границами транзакций для чтения.
     *
     * @param lastProcessedId id последнего обработанного товара
     * @param batchSize       размер батча
     * @return список id товаров без barcode
     */
    public List<Long> fetchNextBatch(long lastProcessedId, int batchSize) {
        Pageable pageable = PageRequest.of(0, batchSize);
        return itemRepository.findIdsByBarcodeIsNullAndIdGreaterThanOrderByIdAsc(lastProcessedId, pageable);
    }

    /**
     * Проставить barcode каждой строке батча точечным UPDATE в одной короткой транзакции.
     * <p>
     * Транзакция открывается явно через {@link TransactionTemplate}, а не через
     * {@code @Transactional} — этот метод вызывается из {@link #doRun} того же бина
     * (self-invocation), Spring AOP-прокси на таком вызове не участвует.
     * <p>
     * Обновляется только колонка barcode (см. {@link ItemRepository#updateBarcodeIfNull}) —
     * не читаем и не переписываем остальные поля строки, и UPDATE атомарно перепроверяет
     * {@code barcode IS NULL}, так что строку, которой barcode уже выставили конкурентно
     * (вручную через API или другим запуском джобы), мы не тронем — просто пропустим (0
     * затронутых строк).
     * <p>
     * Если сама генерация bаrcode коллизирует с чужим значением (UNIQUE), откатываем
     * батч и обрабатываем строки по одной, чтобы одна плохая строка не блокировала весь батч.
     *
     * @param batch список id товаров без barcode
     * @return сколько строк реально обновлено
     */
    public int processBatch(List<Long> batch) {
        try {
            Integer updated = txTemplate.execute(status -> {
                int count = 0;
                for (Long id : batch) {
                    count += itemRepository.updateBarcodeIfNull(id, barcodeGenerator.generate());
                }
                return count;
            });
            return updated == null ? 0 : updated;
        } catch (DataIntegrityViolationException e) {
            log.warn("Коллизия barcode внутри батча (первый id={}, размер={}), "
                            + "переключаюсь на построчную обработку: {}",
                    batch.get(0), batch.size(), e.getMessage());
            return processBatchItemByItem(batch);
        }
    }

    private int processBatchItemByItem(List<Long> batch) {
        int updated = 0;
        for (Long id : batch) {
            String barcode = barcodeGenerator.generate();
            try {
                Integer rows = txTemplate.execute(status -> itemRepository.updateBarcodeIfNull(id, barcode));
                updated += rows == null ? 0 : rows;
            } catch (DataIntegrityViolationException e) {
                log.error("Не удалось сохранить barcode для item id={} даже построчно: {}. "
                                + "Строка остаётся NULL, её подхватит следующий запуск джобы (идемпотентность).",
                        id, e.getMessage());
            }
        }
        return updated;
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
