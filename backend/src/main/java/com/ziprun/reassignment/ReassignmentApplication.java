package com.ziprun.reassignment;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;

@SpringBootApplication
@EnableAsync
public class ReassignmentApplication {
    public static void main(String[] args) {
        SpringApplication.run(ReassignmentApplication.class, args);
    }
}
