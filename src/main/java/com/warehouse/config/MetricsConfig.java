package com.warehouse.config;

import io.micrometer.common.KeyValue;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationFilter;
import io.micrometer.tracing.Tracer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class MetricsConfig {

    @Bean
    public ObservationFilter traceIdObservationFilter(Tracer tracer) {
        return new ObservationFilter() {
            @Override
            public Observation.Context map(Observation.Context context) {
                String traceId = "unknown";

                Object traceIdFromContext = context.get("traceId");
                if (traceIdFromContext != null) {
                    traceId = traceIdFromContext.toString();
                } else {
                    try {
                        if (tracer.currentSpan() != null) {
                            traceId = tracer.currentSpan().context().traceId();
                        }
                    } catch (Exception ignored) {

                    }
                }

                context.addLowCardinalityKeyValue(KeyValue.of("trace_id", traceId));
                return context;
            }
        };
    }
}