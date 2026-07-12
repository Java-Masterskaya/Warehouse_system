package com.warehouse.security.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties(prefix = "rate-limiting")
public record RateLimitProperties(
        LimitConfig login,
        LimitConfig movements
) {
    public record LimitConfig(
            BandwidthConfig ip,
            BandwidthConfig username
    ) {}

    public record BandwidthConfig(
            long capacity,
            long refillTokens,
            Duration duration
    ) {}
}
