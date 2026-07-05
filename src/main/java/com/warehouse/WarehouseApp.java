package com.warehouse;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;

@SpringBootApplication
@EnableAsync
public class WarehouseApp {

    public static void main(String[] args) {
        SpringApplication.run(WarehouseApp.class, args);
    }
}