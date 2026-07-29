package com.warehouse.batch;

import com.warehouse.AbstractIntegrationTest;
import com.warehouse.entity.Category;
import com.warehouse.entity.Item;
import com.warehouse.repository.CategoryRepository;
import com.warehouse.repository.ItemRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

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
 *   <li>Ручной barcode, совпадающий с форматом автогенерации другого товара,
 *       не приводит к дублю (см. {@code com.warehouse.service.item.ItemBarcodeGenerator}).</li>
 * </ol>
 * <p>
 * Изоляция между тестами: {@code @BeforeEach}/{@code deleteAll()} здесь не нужен потому, что
 * каждый тест использует уникальные SKU/barcode-префиксы и не пересекается по данным
 * с остальными. Если это когда-нибудь перестанет быть так — тесты начнут падать
 * друг на друге, и понадобится либо {@code @Transactional} на класс, либо ручная очистка.
 */
@SpringBootTest
class MigrationCompatibilityTest extends AbstractIntegrationTest {

    @Autowired
    private ItemRepository itemRepository;

    @Autowired
    private ItemBarcodeBackfillJob backfillJob;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private CategoryRepository categoryRepository;

    @Test
    @DisplayName("Старый код: INSERT без barcode проходит (колонка nullable)")
    void oldCodeCanInsertWithoutBarcode() {
        Item item = new Item();
        item.setSku("SKU-OLD-001");
        item.setName("Legacy Item");
        item.setCategory(createCategory("Категория"));
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
        item.setCategory(createCategory("Категория"));
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
            item.setCategory(createCategory("Категория"));
            item.setMinStock(0);
            legacyItems.add(item);
        }
        itemRepository.saveAll(legacyItems);

        Item modern = new Item();
        modern.setSku("SKU-MODERN-001");
        modern.setName("Modern");
        modern.setCategory(createCategory("Категория"));
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
            item.setCategory(createCategory("Категория"));
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

    @Test
    @DisplayName("Ручной barcode, повторяющий будущее значение sequence, не создаёт дубль при backfill")
    void backfillFallsBackOnManualCollision() {
        long originalLastValue = jdbcTemplate.queryForObject(
                "SELECT last_value FROM items_barcode_seq", Long.class);
        long forcedSeqValue = 999_999_000L;
        jdbcTemplate.queryForObject("SELECT setval('items_barcode_seq', ?, false)", Long.class, forcedSeqValue);
        String reservedFormatBarcode = String.format("ITEM-%010d", forcedSeqValue);

        try {
            // "Захватываем" ровно тот barcode, который backfill получит от nextval()
            // следующим — так, как это может сделать пользователь вручную через API.
            Item poison = new Item();
            poison.setSku("SKU-COLLISION-POISON");
            poison.setName("Poison");
            poison.setCategory(createCategory("Категория"));
            poison.setMinStock(0);
            poison.setBarcode(reservedFormatBarcode);
            Item savedPoison = itemRepository.save(poison);

            // Legacy-строка без barcode — именно она первой заберёт форсированное
            // значение sequence из nextval() внутри backfill.
            Item legacy = new Item();
            legacy.setSku("SKU-COLLISION-LEGACY");
            legacy.setName("Legacy");
            legacy.setCategory(createCategory("Категория"));
            legacy.setMinStock(0);
            Item savedLegacy = itemRepository.save(legacy);

            ItemBarcodeBackfillJob.Result result = backfillJob.run(10);

            assertThat(result.status()).isEqualTo(ItemBarcodeBackfillJob.Status.COMPLETE);
            assertThat(itemRepository.existsByBarcodeIsNull()).isFalse();

            Item reloadedLegacy = itemRepository.findById(savedLegacy.getId()).orElseThrow();
            assertThat(reloadedLegacy.getBarcode())
                    .as("backfill не должен был перезаписать/продублировать уже занятый barcode")
                    .isNotNull()
                    .isNotEqualTo(reservedFormatBarcode);

            Item reloadedPoison = itemRepository.findById(savedPoison.getId()).orElseThrow();
            assertThat(reloadedPoison.getBarcode())
                    .as("backfill не должен трогать строки, у которых barcode уже был не NULL")
                    .isEqualTo(reservedFormatBarcode);
        } finally {
            jdbcTemplate.queryForObject("SELECT setval('items_barcode_seq', ?, true)", Long.class, originalLastValue);
        }
    }

    private Category createCategory(String name) {
        return categoryRepository.findByNameIgnoreCase(name)
                .orElseGet(() -> categoryRepository.save(Category.builder().name(name).build()));
    }
}
