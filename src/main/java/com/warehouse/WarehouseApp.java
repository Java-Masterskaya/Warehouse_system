package com.warehouse;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.boot.autoconfigure.cache.CacheAutoConfiguration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@ConfigurationPropertiesScan
@SpringBootApplication(exclude = CacheAutoConfiguration.class)
@EnableScheduling
@EnableAsync
public class WarehouseApp {

    public static void main(String[] args) {
        SpringApplication.run(WarehouseApp.class, args);
    }
}