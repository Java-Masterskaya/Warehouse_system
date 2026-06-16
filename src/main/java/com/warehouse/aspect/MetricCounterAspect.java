package com.warehouse.aspect;

import com.warehouse.annotation.MetricCounter;
import com.warehouse.exception.InsufficientStockException;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.AfterThrowing;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.security.core.AuthenticationException;
import org.springframework.stereotype.Component;

/**
 * Универсальный аспект для автоматической регистрации метрик Prometheus.
 * Использует Spring AOP для инкрементирования метрик при вызове методов с аннотацией @MetricCounter.
 * 
 * <p>Обрабатывает:
 * <ul>
 *   <li>Успешные операции через @Around</li>
 *   <li>Исключения InsufficientStockException и AuthenticationException через @AfterThrowing</li>
 * </ul>
 */
@Aspect
@Component
@RequiredArgsConstructor
@Slf4j
public class MetricCounterAspect {

    private final MeterRegistry registry;

    /**
     * Адвис для методов с аннотацией @MetricCounter.
     * Инкрементирует метрику после успешного выполнения метода.
     *
     * @param joinPoint точка соединения (метод)
     * @param annotation аннотация с параметрами метрики
     * @return результат выполнения метода
     * @throws Throwable если метод выбрасывает исключение
     */
    @Around("@annotation(annotation)")
    public Object trackMetric(ProceedingJoinPoint joinPoint, MetricCounter annotation) throws Throwable {
        String metricName = annotation.value();
        boolean success = false;

        try {
            Object result = joinPoint.proceed();
            success = true;
            return result;
        } finally {
            if (success) {
                Counter.builder(metricName)
                    .register(registry)
                    .increment();
                log.debug("Metric incremented: {}", metricName);
            }
        }
    }

    /**
     * Адвис для обработки исключений InsufficientStockException.
     * Инкрементирует метрику отклоненных списаний при любой операции с @MetricCounter.
     *
     * @param e исключение недостаточного остатка
     */
    @AfterThrowing(
        pointcut = "@annotation(com.warehouse.annotation.MetricCounter)",
        throwing = "e"
    )
    public void trackRejectedMetric(InsufficientStockException e) {
        Counter.builder("warehouse.movements.writeoff.rejected.total")
            .register(registry)
            .increment();
        log.debug("Rejected write-off metric incremented");
    }

    /**
     * Адвис для обработки исключений BadCredentialsException.
     * Инкрементирует метрику неудачных логинов.
     *
     * @param e исключение аутентификации
     */
    @AfterThrowing(
        pointcut = "@annotation(com.warehouse.annotation.MetricCounter)",
        throwing = "e"
    )
    public void trackLoginFailure(AuthenticationException e) {
        Counter.builder("warehouse.auth.login.failure.total")
            .register(registry)
            .increment();
        log.debug("Login failure metric incremented");
    }
}