package com.warehouse.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Включает выполнение задач по расписанию везде, кроме тестового профиля.
 *
 * <p>Фоновые джобы ({@code expireReservations} — раз в минуту, релей outbox — раз в 5 секунд,
 * очистка ключей идемпотентности) работают с той же общей базой, что и интеграционные тесты.
 * Срабатывая посреди чужого теста, они меняют его данные и дают плавающие падения,
 * которые невозможно воспроизвести.
 *
 * <p>Тесты, проверяющие сами джобы, вызывают их методы напрямую — расписание для этого не нужно.
 */
@Configuration
@Profile("!test")
@EnableScheduling
public class SchedulingConfig {
}
