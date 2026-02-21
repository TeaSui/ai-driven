package com.teasui.crm.integration.controller;

import com.teasui.crm.common.dto.ApiResponse;
import com.teasui.crm.integration.domain.Integration;
import com.teasui.crm.integration.service.IntegrationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/integrations")
@RequiredArgsConstructor
public class IntegrationController {

    private final IntegrationService integrationService;

    @GetMapping("/plugins")
    public ResponseEntity<ApiResponse<List<Map<String, String>>>> listPlugins() {
        return ResponseEntity.ok(ApiResponse.success(integrationService.listAvailablePlugins()));
    }

    @PostMapping
    public ResponseEntity<ApiResponse<Integration>> createIntegration(
            @RequestHeader("X-Tenant-Id") String tenantId,
            @RequestHeader("X-User-Id") String userId,
            @RequestBody Integration integration) {
        Integration created = integrationService.createIntegration(tenantId, userId, integration);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.success(created, "Integration created successfully"));
    }

    @GetMapping
    public ResponseEntity<ApiResponse<List<Integration>>> listIntegrations(
            @RequestHeader("X-Tenant-Id") String tenantId) {
        return ResponseEntity.ok(ApiResponse.success(integrationService.listIntegrations(tenantId)));
    }

    @GetMapping("/{integrationId}")
    public ResponseEntity<ApiResponse<Integration>> getIntegration(
            @RequestHeader("X-Tenant-Id") String tenantId,
            @PathVariable String integrationId) {
        return ResponseEntity.ok(ApiResponse.success(integrationService.getIntegration(tenantId, integrationId)));
    }

    @PostMapping("/{integrationId}/test")
    public ResponseEntity<ApiResponse<Boolean>> testConnection(
            @RequestHeader("X-Tenant-Id") String tenantId,
            @PathVariable String integrationId) {
        boolean result = integrationService.testConnection(tenantId, integrationId);
        return ResponseEntity.ok(ApiResponse.success(result, result ? "Connection successful" : "Connection failed"));
    }

    @PostMapping("/{integrationId}/execute")
    public ResponseEntity<ApiResponse<Map<String, Object>>> executeIntegration(
            @RequestHeader("X-Tenant-Id") String tenantId,
            @PathVariable String integrationId,
            @RequestBody Map<String, Object> payload) {
        Map<String, Object> result = integrationService.executeIntegration(tenantId, integrationId, payload);
        return ResponseEntity.ok(ApiResponse.success(result));
    }
}
