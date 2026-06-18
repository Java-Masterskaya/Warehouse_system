package com.warehouse.metric;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import lombok.experimental.FieldDefaults;
import org.springframework.stereotype.Service;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Сервис для инкрементирования метрик Prometheus.
 * Использует кэширование Counter для избежания повторной регистрации.
 */
@Service
@RequiredArgsConstructor
@FieldDefaults(level = AccessLevel.PRIVATE, makeFinal = true)
public class MetricService {

    MeterRegistry registry;
    ConcurrentMap<String, Counter> counters = new ConcurrentHashMap<>();

    /**
     * Инкрементирует метрику по имени.
     * Counter кэшируется для избежания повторной регистрации.
     *
     * @param name имя метрики
     */
    public void increment(String name) {
        counters.computeIfAbsent(name, n -> Counter.builder(n).register(registry)).increment();
    }

    /**
     * Инкрементирует метрику с указанным количеством.
     *
     * @param name имя метрики
     * @param amount количество для инкремента
     */
    public void increment(String name, int amount) {
        counters.computeIfAbsent(name, n -> Counter.builder(n).register(registry)).increment(amount);
    }
}
