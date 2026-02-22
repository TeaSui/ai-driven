package com.teasui.integration.controller;

import com.teasui.common.dto.ApiResponse;
import com.teasui.integration.dto.CreateIntegrationRequest;
import com.teasui.integration.dto.IntegrationResponse;
import com.teasui.integration.service.IntegrationService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/integrations")
@RequiredArgsConstructor
public class IntegrationController {

    private final IntegrationService integrationService;

    @PostMapping
    public ResponseEntity<ApiResponse<IntegrationResponse>> createIntegration(
            @Valid @RequestBody CreateIntegrationRequest request) {
        IntegrationResponse response = integrationService.createIntegration(request);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.success(response, "Integration created successfully"));
    }

    @GetMapping
    public ResponseEntity<ApiResponse<List<IntegrationResponse>>> getIntegrations() {
        List<IntegrationResponse> integrations = integrationService.getIntegrationsByTenant();
        return ResponseEntity.ok(ApiResponse.success(integrations));
    }

    @GetMapping(