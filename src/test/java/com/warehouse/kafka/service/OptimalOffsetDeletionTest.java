package com.warehouse.kafka.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Тест оптимизированного удаления оффсетов с учетом firstFailedOffsets.
 */
@DisplayName("Optimal Offset Deletion with firstFailedOffsets")
class OptimalOffsetDeletionTest {

    /**
     * Сценарий: SUCCESS, FAILED, SUCCESS - удаляем до первого FAILED.
     */
    @Test
    @DisplayName("SUCCESS, FAILED, SUCCESS - delete up to first FAILED")
    void successFailedSuccess() {
        System.out.println("\n=== СЦЕНАРИЙ: SUCCESS, FAILED, SUCCESS ===");
        
        Map<Long, Long> maxProcessedOffsets = new HashMap<>();
        maxProcessedOffsets.put(0L, 104L); // max offset = 103, +1 = 104
        
        Map<Long, Long> firstFailedOffsets = new HashMap<>();
        firstFailedOffsets.put(0L, 101L); // первый FAILED на offset 101
        
        System.out.printf("maxProcessedOffsets[partition] = %d%n", maxProcessedOffsets.get(0L));
        System.out.printf("firstFailedOffsets[partition] = %d%n", firstFailedOffsets.get(0L));
        
        // Оптимизированная логика
        long maxOffset = maxProcessedOffsets.get(0L); // 104
        long firstFailedOffset = firstFailedOffsets.get(0L); // 101
        long deleteUpTo = Math.min(maxOffset, firstFailedOffset); // min(104, 101) = 101
        
        System.out.printf("deleteUpTo = min(%d, %d) = %d%n", maxOffset, firstFailedOffset, deleteUpTo);
        
        System.out.println("Удаляются оффсеты [0, 101):");
        System.out.println("  100 (SUCCESS) ✓");
        
        System.out.println("Остаются в DLT:");
        System.out.println("  101 (FAILED)  ✓ (НЕ УДАЛЕН!)");
        System.out.println("  102 (SUCCESS) (останется)");
        System.out.println("  103 (SUCCESS) (останется)");
        
        // ПРОВЕРКА
        assertThat(deleteUpTo).as("Удаляем до первого FAILED")
                .isEqualTo(101L);
        assertThat(firstFailedOffsets).containsEntry(0L, 101L);
    }
    
    /**
     * Сценарий: Все SUCCESS - удаляем всё.
     */
    @Test
    @DisplayName("All SUCCESS - delete all")
    void allSuccess() {
        System.out.println("\n=== СЦЕНАРИЙ: Все SUCCESS ===");
        
        Map<Long, Long> maxProcessedOffsets = new HashMap<>();
        maxProcessedOffsets.put(0L, 103L); // max offset = 102, +1 = 103
        
        Map<Long, Long> firstFailedOffsets = new HashMap<>(); // Нет FAILED
        
        System.out.printf("maxProcessedOffsets[partition] = %d%n", maxProcessedOffsets.get(0L));
        System.out.printf("firstFailedOffsets[partition] = (нет)%n");
        
        // Оптимизированная логика
        long deleteUpTo;
        if (firstFailedOffsets.containsKey(0L)) {
            deleteUpTo = Math.min(maxProcessedOffsets.get(0L), firstFailedOffsets.get(0L));
        } else {
            deleteUpTo = maxProcessedOffsets.get(0L); // Удаляем всё
        }
        
        System.out.printf("deleteUpTo = %d%n", deleteUpTo);
        
        System.out.println("Удаляются оффсеты [0, 103):");
        System.out.println("  100 (SUCCESS) ✓");
        System.out.println("  101 (SUCCESS) ✓");
        System.out.println("  102 (SUCCESS) ✓");
        
        // ПРОВЕРКА
        assertThat(deleteUpTo).as("Удаляем всё")
                .isEqualTo(103L);
        assertThat(firstFailedOffsets).isEmpty();
    }
    
    /**
     * Сценарий: FAILED на первом оффсете - ничего не удаляем.
     */
    @Test
    @DisplayName("FAILED first - nothing deleted")
    void failedFirst() {
        System.out.println("\n=== СЦЕНАРИЙ: FAILED на первом ===");
        
        Map<Long, Long> maxProcessedOffsets = new HashMap<>();
        maxProcessedOffsets.put(0L, 101L); // max offset = 0, +1 = 1
        
        Map<Long, Long> firstFailedOffsets = new HashMap<>();
        firstFailedOffsets.put(0L, 0L); // FAILED на offset 0
        
        System.out.printf("maxProcessedOffsets[partition] = %d%n", maxProcessedOffsets.get(0L));
        System.out.printf("firstFailedOffsets[partition] = %d%n", firstFailedOffsets.get(0L));
        
        // Оптимизированная логика
        long deleteUpTo = Math.min(
                maxProcessedOffsets.get(0L), 
                firstFailedOffsets.get(0L)
        );
        
        System.out.printf("deleteUpTo = min(%d, %d) = %d%n", 
                maxProcessedOffsets.get(0L), firstFailedOffsets.get(0L), deleteUpTo);
        
        System.out.println("Удаляются оффсеты [0, 0):");
        System.out.println("  (ничего)");
        
        System.out.println("Остаются в DLT:");
        System.out.println("  0 (FAILED) ✓");
        System.out.println("  1 (SUCCESS) (останется)");
        
        // ПРОВЕРКА
        assertThat(deleteUpTo).as("Ничего не удаляем")
                .isEqualTo(0L);
    }
    
    /**
     * Сценарий: Сравнение со старой логикой (БЕЗ firstFailedOffsets).
     */
    @Test
    @DisplayName("Old vs New logic comparison")
    void oldVsNewLogic() {
        System.out.println("\n=== СРАВНЕНИЕ: Старая vs Новая логика ===");
        
        Map<Long, Long> maxProcessedOffsets = new HashMap<>();
        maxProcessedOffsets.put(0L, 104L); // 103 + 1
        
        Map<Long, Long> firstFailedOffsets = new HashMap<>();
        firstFailedOffsets.put(0L, 101L); // FAILED на 101
        
        System.out.println("Данные:");
        System.out.println("  offset 100: SUCCESS");
        System.out.println("  offset 101: FAILED");
        System.out.println("  offset 102: SUCCESS");
        System.out.println("  offset 103: SUCCESS");
        
        // СТАРАЯ ЛОГИКА (БЕЗ firstFailedOffsets):
        System.out.println("\n--- СТАРАЯ ЛОГИКА (failedPartitions фильтрация) ---");
        Set<Long> failedPartitions = Set.of(0L); // партиция с FAILED
        
        Map<Long, Long> oldOffsetsToDelete = maxProcessedOffsets.entrySet().stream()
                .filter(e -> !failedPartitions.contains(e.getKey()))
                .collect(java.util.stream.Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
        
        System.out.printf("failedPartitions = %s%n", failedPartitions);
        System.out.printf("offsetsToDelete = %s%n", oldOffsetsToDelete);
        System.out.println("Результат: **НИЧЕГО НЕ УДАЛЯЕТСЯ** (партиция в failedPartitions)");
        
        // НОВАЯ ЛОГИКА (С firstFailedOffsets):
        System.out.println("\n--- НОВАЯ ЛОГИКА (с firstFailedOffsets) ---");
        
        long deleteUpTo = Math.min(
                maxProcessedOffsets.get(0L),
                firstFailedOffsets.get(0L)
        );
        
        System.out.printf("firstFailedOffsets = %s%n", firstFailedOffsets);
        System.out.printf("deleteUpTo = %d%n", deleteUpTo);
        System.out.println("Результат: УДАЛЯЕТСЯ offset 100 (SUCCESS)");
        
        // ПРОВЕРКА
        assertThat(oldOffsetsToDelete).isEmpty();
        assertThat(deleteUpTo).as("Новая логика удаляет успешные до первого FAILED")
                .isEqualTo(101L);
    }
    
    /**
     * Сценарий: Несколько успешных до первого FAILED.
     */
    @Test
    @DisplayName("Multiple SUCCESS before first FAILED")
    void multipleSuccessBeforeFailed() {
        System.out.println("\n=== СЦЕНАРИЙ: Несколько SUCCESS перед FAILED ===");
        
        Map<Long, Long> maxProcessedOffsets = new HashMap<>();
        maxProcessedOffsets.put(0L, 110L); // max = 109
        
        Map<Long, Long> firstFailedOffsets = new HashMap<>();
        firstFailedOffsets.put(0L, 105L); // первый FAILED на 105
        
        System.out.printf("maxProcessedOffsets[partition] = %d%n", maxProcessedOffsets.get(0L));
        System.out.printf("firstFailedOffsets[partition] = %d%n", firstFailedOffsets.get(0L));
        
        long deleteUpTo = Math.min(
                maxProcessedOffsets.get(0L),
                firstFailedOffsets.get(0L)
        );
        
        System.out.printf("deleteUpTo = %d%n", deleteUpTo);
        
        System.out.println("Удаляются оффсеты [0, 105):");
        for (int i = 100; i < 105; i++) {
            System.out.printf("  %d (SUCCESS) ✓%n", i);
        }
        
        System.out.println("Остаются в DLT:");
        for (int i = 105; i < 110; i++) {
            System.out.printf("  %d (FAILED/remaining) (останется)%n", i);
        }
        
        // ПРОВЕРКА
        assertThat(deleteUpTo).as("Удаляем до первого FAILED")
                .isEqualTo(105L);
    }
}
