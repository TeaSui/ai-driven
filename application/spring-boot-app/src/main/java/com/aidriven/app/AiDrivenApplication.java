package com.aidriven.app;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

import com.aidriven.app.config.AppProperties;

/**
 * Main entry point for the AI-Driven Spring Boot application.
 * Replaces the Lambda handler architecture with a long-running Fargate service.
 *
 * <p>Scans {@code com.aidriven} to discover beans from all project modules,
 * and activates {@link AppProperties} for type-safe configuration binding.
 */
@SpringBootApplication(scanBasePackages = "com.aidriven")
@EnableConfigurationProperties(AppProperties.class)
public class AiDrivenApplication {

    public static void main(String[] args) {
        SpringApplication.run(AiDrivenApplication.class, args);
    }
}
