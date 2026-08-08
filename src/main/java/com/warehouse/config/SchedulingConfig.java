package com.warehouse.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Включает выполнение задач по расписанию. Отключается свойством {@code app.scheduling.enabled}.
 *
 * <p>По умолчанию расписание работает: свойство не задано — {@code matchIfMissing = true},
 * поведение в продакшене прежнее. Выключение пригодится там, где фоновые джобы инстансу не нужны,
 * например на части инстансов при горизонтальном масштабировании.
 *
 * <p>Первым потребителем стали интеграционные тесты. Фоновые джобы
 * ({@code expireReservations} — раз в минуту, релей outbox — раз в 5 секунд, очистка ключей
 * идемпотентности) работают с той же общей базой, что и тесты. Срабатывая посреди чужого теста,
 * они меняют его данные и дают плавающие падения, которые невозможно воспроизвести.
 * Поэтому {@code src/test/resources/application-test.yml} ставит свойство в {@code false}.
 *
 * <p>Тесты, проверяющие сами джобы, вызывают их методы напрямую — расписание для этого не нужно.
 */
@Configuration
@ConditionalOnProperty(name = "app.scheduling.enabled", havingValue = "true", matchIfMissing = true)
@EnableScheduling
public class SchedulingConfig {
}
