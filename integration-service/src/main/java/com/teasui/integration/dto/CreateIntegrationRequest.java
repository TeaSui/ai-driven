package com.teasui.integration.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CreateIntegrationRequest {

    @NotBlank(message = "Integration name is required")
    private String name;

    @NotBlank(message = "Provider is required")
    private String provider;

    private String config;
    private String webhookUrl;
}
