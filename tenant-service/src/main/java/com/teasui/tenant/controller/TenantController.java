package com.teasui.tenant.controller;

import com.teasui.common.dto.ApiResponse;
import com.teasui.tenant.dto.CreateTenantRequest;
import com.teasui.tenant.dto.TenantResponse;
import com.teasui.tenant.service.TenantService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/tenants")
@RequiredArgsConstructor
public class TenantController {

    private final TenantService tenantService;

    @PostMapping
    public ResponseEntity<ApiResponse<TenantResponse>> createTenant(
            @Valid @RequestBody CreateTenantRequest request) {
        TenantResponse response = tenantService.createTenant(request);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.success(response, "Tenant created successfully"));
    }

    @GetMapping("/{id}")
    public ResponseEntity<ApiResponse<TenantResponse>> getTenantById(@PathVariable String id) {
        TenantResponse response = tenantService.getTenantById(id);
        return ResponseEntity.ok(ApiResponse.success(response));
    }

    @GetMapping("/slug/{slug}")
    public ResponseEntity<ApiResponse<TenantResponse>> getTenantBySlug(@PathVariable String slug) {
        TenantResponse response = tenantService.getTenantBySlug(slug);
        return ResponseEntity.ok(ApiResponse.success(response));
    }

    @GetMapping
    public ResponseEntity<ApiResponse<List<TenantResponse>>> getAllActiveTenants() {
        List<TenantResponse> tenants = tenantService.getAllActiveTenants();
        return ResponseEntity.ok(ApiResponse.success(tenants));
    }

    @PutMapping("/{id}/suspend")
    public ResponseEntity<ApiResponse<TenantResponse>> suspendTenant(@PathVariable String id) {
        TenantResponse response = tenantService.suspendTenant(id);
        return ResponseEntity.ok(ApiResponse.success(response, "Tenant suspended"));
    }

    @PutMapping("/{id}/activate")
    public ResponseEntity<ApiResponse<TenantResponse>> activateTenant(@PathVariable String id) {
        TenantResponse response = tenantService.activateTenant(id);
        return ResponseEntity.ok(ApiResponse.success(response, "Tenant activated"));
    }
}
