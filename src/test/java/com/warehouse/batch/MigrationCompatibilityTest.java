package com.warehouse.batch;

import com.warehouse.AbstractIntegrationTest;
import com.warehouse.entity.Item;
import com.warehouse.repository.ItemRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Интеграционные тесты, проверяющие контракт zero-downtime миграции.
 * <p>
 * Убеждаемся, что:
 * <ol>
 *   <li>Старый код (не знает про barcode) может INSERT без ошибок.</li>
 *   <li>Новый код может писать и читать barcode.</li>
 *   <li>Backfill-джоба корректно заполняет legacy-строки.</li>
 *   <li>После backfill ни одна строка не остаётся с NULL barcode.</li>
 * </ol>
 * <p>
 * Не нужен @BeforeEach с deleteAll(): AbstractIntegrationTest уже обеспечивает
 * изоляцию через @Rollback(true) — каждый тест откатывается после выполнения.
 */
class MigrationCompatibilityTest extends AbstractIntegrationTest {

    @Autowired
    private ItemRepository itemRepository;

    @Autowired
    private ItemBarcodeBackfillJob backfillJob;

    @Test
    @DisplayName("Старый код: INSERT без barcode проходит (колонка nullable)")
    void oldCodeCanInsertWithoutBarcode() {
        Item item = new Item();
        item.setSku("SKU-OLD-001");
        item.setName("Legacy Item");
        item.setCategory("Test");
        item.setMinStock(0);

        Item saved = itemRepository.save(item);

        assertThat(saved.getId()).isNotNull();
        assertThat(saved.getBarcode()).isNull();
    }

    @Test
    @DisplayName("Новый код: INSERT с barcode проходит")
    void newCodeCanInsertWithBarcode() {
        Item item = new Item();
        item.setSku("SKU-NEW-001");
        item.setName("New Item");
        item.setCategory("Test");
        item.setMinStock(0);
        item.setBarcode("ITEM-0000000001");

        Item saved = itemRepository.save(item);

        assertThat(saved.getBarcode()).isEqualTo("ITEM-0000000001");
    }

    @Test
    @DisplayName("Backfill-джоба заполняет все NULL barcode и идемпотентна")
    void backfillJobFillsNullBarcodes() {
        List<Item> legacyItems = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            Item item = new Item();
            item.setSku("SKU-LEGACY-" + i);
            item.setName("Legacy-" + i);
            item.setCategory("Test");
            item.setMinStock(0);
            legacyItems.add(item);
        }
        itemRepository.saveAll(legacyItems);

        Item modern = new Item();
        modern.setSku("SKU-MODERN-001");
        modern.setName("Modern");
        modern.setCategory("Test");
        modern.setMinStock(0);
        modern.setBarcode("MANUAL-01");
        itemRepository.save(modern);

        assertThat(itemRepository.existsByBarcodeIsNull()).isTrue();

        ItemBarcodeBackfillJob.Result result = backfillJob.run(3);

        assertThat(result.status()).isEqualTo(ItemBarcodeBackfillJob.Status.COMPLETE);
        assertThat(result.rowsProcessed()).isEqualTo(10);
        assertThat(itemRepository.existsByBarcodeIsNull()).isFalse();

        List<Item> all = itemRepository.findAll();
        Set<String> seenBarcodes = new HashSet<>();
        for (Item item : all) {
            String barcode = item.getBarcode();
            assertThat(barcode).isNotNull();
            assertThat(seenBarcodes).doesNotContain(barcode);
            seenBarcodes.add(barcode);
        }

        ItemBarcodeBackfillJob.Result secondRun = backfillJob.run(3);
        assertThat(secondRun.status()).isEqualTo(ItemBarcodeBackfillJob.Status.COMPLETE);
        assertThat(secondRun.rowsProcessed()).isZero();
    }

    @Test
    @DisplayName("Частичный backfill дожимается через публичный API")
    void partialBackfillResumedByPublicApi() {
        List<Item> items = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            Item item = new Item();
            item.setSku("SKU-RESUME-" + i);
            item.setName("Item-" + i);
            item.setCategory("Test");
            item.setMinStock(0);
            items.add(item);
        }
        itemRepository.saveAll(items);

        items.get(0).setBarcode("MANUAL-00");
        items.get(1).setBarcode("MANUAL-01");
        itemRepository.saveAll(items);

        assertThat(itemRepository.existsByBarcodeIsNull()).isTrue();

        ItemBarcodeBackfillJob.Result result = backfillJob.run(2);

        assertThat(result.status()).isEqualTo(ItemBarcodeBackfillJob.Status.COMPLETE);
        assertThat(itemRepository.existsByBarcodeIsNull()).isFalse();

        List<Item> all = itemRepository.findAll();
        assertThat(all)
                .extracting(Item::getBarcode)
                .contains("MANUAL-00", "MANUAL-01");
    }
}
