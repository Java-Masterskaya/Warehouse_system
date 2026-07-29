package com.warehouse.service.item;

import com.warehouse.repository.ItemRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Единая точка генерации автоматического barcode для {@code com.warehouse.entity.Item}.
 * Используется и в {@link ItemServiceImpl} (автоприсвоение при создании товара),
 * и в {@code ItemBarcodeBackfillJob} (батчевый backfill legacy-строк) — чтобы
 * правило генерации и защита от коллизий не расходились между двумя местами.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ItemBarcodeGenerator {

    private static final String FORMAT = "ITEM-%010d";
    private static final int FALLBACK_SUFFIX_LENGTH = 8;
    private static final Pattern RESERVED_FORMAT = Pattern.compile("^ITEM-\\d{10}$");

    private final ItemRepository itemRepository;

    /**
     * Сгенерировать barcode для нового товара.
     * <p>
     * Тянет следующее значение {@code items_barcode_seq} и форматирует как
     * {@code ITEM-<10 цифр>}. В штатном случае коллизий не бывает (значения
     * sequence уникальны по построению), но проверка на существование barcode
     * оставлена как defensive-check — на случай ручных вставок в обход
     * приложения или восстановленных из бэкапа данных.
     *
     * @return сгенерированный, гарантированно не коллизирующий на момент проверки barcode
     */
    public String generate() {
        long seqValue = itemRepository.nextBarcodeSequenceValue();
        String candidate = String.format(FORMAT, seqValue);
        if (!itemRepository.existsByBarcode(candidate)) {
            return candidate;
        }

        String fallback = candidate + "-" + UUID.randomUUID().toString().substring(0, FALLBACK_SUFFIX_LENGTH);
        log.warn("Коллизия автогенерации barcode: '{}' уже занят (вероятно, ручной ввод в обход "
                        + "валидации ItemServiceImpl). Использую fallback '{}'.",
                candidate, fallback);
        return fallback;
    }

    /**
     * Проверить, выглядит ли barcode как зарезервированный формат автогенерации
     * ({@code ITEM-<10 цифр>}).
     *
     * @param barcode проверяемое значение (может быть {@code null})
     * @return {@code true}, если формат совпадает с {@code ITEM-<10 цифр>}
     */
    public boolean matchesReservedFormat(String barcode) {
        return barcode != null && RESERVED_FORMAT.matcher(barcode).matches();
    }
}
