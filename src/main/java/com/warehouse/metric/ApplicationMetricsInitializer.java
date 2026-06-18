package com.warehouse.metric;

import com.warehouse.repository.ItemRepository;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import lombok.experimental.FieldDefaults;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationStartedEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.stereotype.Component;

/**
 * Компонент для регистрации метрик при старте приложения.
 * Использует Gauge для отслеживания количества активных товаров.
 */
@Component
@RequiredArgsConstructor
@Slf4j
@FieldDefaults(level = AccessLevel.PRIVATE, makeFinal = true)
public class ApplicationMetricsInitializer implements ApplicationListener<ApplicationStartedEvent> {

    MeterRegistry registry;
    ItemRepository itemRepository;

    /**
     * Инициализирует метрики при старте приложения.
     *
     * @param event событие старта приложения
     */
    @Override
    public void onApplicationEvent(ApplicationStartedEvent event) {
        registerActiveItemsMetric();
    }

    /**
     * Регистрирует метрику количества активных товаров.
     * Значение автоматически обновляется при каждом опросе метрик.
     */
    private void registerActiveItemsMetric() {
        Gauge.builder("warehouse.items.active.total", itemRepository, ItemRepository::countByActiveTrue)
            .register(registry);
        log.info("Registered metric: warehouse.items.active.total");
    }
}