package com.warehouse.lock;

import java.time.Duration;
import java.util.Optional;

/**
 * Менеджер распределенных блокировок.
 */
public interface DistributedLockManager {

    /**
     * Пытается захватить блокировку без ожидания.
     *
     * @param name логическое имя блокировки
     * @param ttl максимальное время владения
     * @return захваченная блокировка или пустой результат
     */
    Optional<DistributedLock> tryAcquire(String name, Duration ttl);
}
