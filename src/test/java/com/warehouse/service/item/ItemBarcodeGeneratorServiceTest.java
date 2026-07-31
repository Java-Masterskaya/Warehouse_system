package com.warehouse.service.item;

import com.warehouse.repository.ItemRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/**
 * Юнит-тест для {@link ItemBarcodeGeneratorService} (OPS-5): генерация из независимого
 * sequence {@code items_barcode_seq}, fallback при коллизии, проверка
 * зарезервированного формата.
 */
@ExtendWith(MockitoExtension.class)
class ItemBarcodeGeneratorServiceTest {

    @Mock
    private ItemRepository itemRepository;

    private ItemBarcodeGeneratorService barcodeGenerator;

    @BeforeEach
    void setUp() {
        barcodeGenerator = new ItemBarcodeGeneratorService(itemRepository);
    }

    @Test
    void generateReturnsFormattedSequenceValueWhenNoCollision() {
        when(itemRepository.nextBarcodeSequenceValue()).thenReturn(42L);
        when(itemRepository.existsByBarcode("ITEM-0000000042")).thenReturn(false);

        String result = barcodeGenerator.generate();

        assertThat(result).isEqualTo("ITEM-0000000042");
    }

    @Test
    void generateFallsBackWithSuffixOnCollision() {
        when(itemRepository.nextBarcodeSequenceValue()).thenReturn(42L);
        when(itemRepository.existsByBarcode("ITEM-0000000042")).thenReturn(true);

        String result = barcodeGenerator.generate();

        assertThat(result)
                .startsWith("ITEM-0000000042-")
                .isNotEqualTo("ITEM-0000000042");
    }

    @Test
    void matchesReservedFormatTrueForExactPattern() {
        assertThat(barcodeGenerator.matchesReservedFormat("ITEM-0000000042")).isTrue();
    }

    @Test
    void matchesReservedFormatFalseForNull() {
        assertThat(barcodeGenerator.matchesReservedFormat(null)).isFalse();
    }

    @Test
    void matchesReservedFormatFalseForWrongDigitCount() {
        assertThat(barcodeGenerator.matchesReservedFormat("ITEM-42")).isFalse();
    }

    @Test
    void matchesReservedFormatFalseForFallbackSuffix() {
        // Fallback-коды из generate() не должны сами попадать под "зарезервированный
        // формат" — иначе повторно сгенерированный fallback заблокировал бы сам себя.
        assertThat(barcodeGenerator.matchesReservedFormat("ITEM-0000000042-a1b2c3d4")).isFalse();
    }

    @Test
    void matchesReservedFormatFalseForUnrelatedValue() {
        assertThat(barcodeGenerator.matchesReservedFormat("MANUAL-01")).isFalse();
    }
}
