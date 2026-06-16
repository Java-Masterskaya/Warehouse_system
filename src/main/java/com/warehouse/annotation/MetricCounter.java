package com.warehouse.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Аннотация для автоматического инкрементирования метрик Prometheus.
 * Используется AOP аспектом {@link com.warehouse.aspect.MetricCounterAspect}.
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface MetricCounter {
    
    /**
     * Название метрики для инкрементирования.
     *
     * @return имя метрики
     */
    String value();
}