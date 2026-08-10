package com.warehouse.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Включает выполнение задач по расписанию. Отключается свойством {@code app.scheduling.enabled}.
 *
 * <p>По умолчанию расписание работает: свойство не задано — {@code matchIfMissing = true}.
 * На нескольких инстансах джобы выполняются на всех, а дублирование снимает ShedLock —
 * см. {@link ShedLockConfig} и {@code @SchedulerLock} на самих джобах.
 *
 * <p>Свойство держат в {@code false} интеграционные тесты. Фоновые джобы
 * ({@code expireReservations} — раз в минуту, релей outbox — раз в 5 секунд, очистка ключей
 * идемпотентности) работают с той же общей базой, что и тесты: срабатывая посреди чужого теста,
 * они меняют его данные и дают невоспроизводимые падения. Значение задано в
 * {@code src/test/resources/application-test.yml}.
 *
 * <p>Тесты, проверяющие сами джобы, вызывают их методы напрямую — расписание для этого не нужно.
 */
@Configuration
@ConditionalOnProperty(name = "app.scheduling.enabled", havingValue = "true", matchIfMissing = true)
@EnableScheduling
public class SchedulingConfig {
}
