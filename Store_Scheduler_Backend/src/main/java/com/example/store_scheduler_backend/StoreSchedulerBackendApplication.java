package com.example.store_scheduler_backend;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class StoreSchedulerBackendApplication {

    public static void main(String[] args) {
        SpringApplication.run(StoreSchedulerBackendApplication.class, args);
    }

}
