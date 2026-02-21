package com.teasui.crm.gateway.controller;

import com.teasui.crm.common.dto.ApiResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Fallback controller for circuit breaker responses.
 */
@Slf4j
@RestController
@RequestMapping("/fallback")
public class FallbackController {

    @GetMapping("/workflow")
    public ResponseEntity<ApiResponse<Void>> workflowFallback() {
        log.warn("Workflow service circuit breaker triggered");
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(ApiResponse.error("Workflow service is temporarily unavailable. Please try again later."));
    }

    @GetMapping("/integration")
    public ResponseEntity<ApiResponse<Void>> integrationFallback() {
        log.warn("Integration service circuit breaker triggered");
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(ApiResponse.error("Integration service is temporarily unavailable. Please try again later."));
    }

    @GetMapping("/notification")
    public ResponseEntity<ApiResponse<Void>> notificationFallback() {
        log.warn("Notification service circuit breaker triggered");
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(ApiResponse.error("Notification service is temporarily unavailable. Please try again later."));
    }
}
