package com.warehouse.lock;

/**
 * Захваченная распределенная блокировка.
 */
public interface DistributedLock extends AutoCloseable {

    /**
     * Освобождает блокировку, если вызывающий остается ее владельцем.
     */
    @Override
    void close();
}
