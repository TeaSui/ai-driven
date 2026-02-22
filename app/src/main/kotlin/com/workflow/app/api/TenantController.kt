package com.workflow.app.api

import com.workflow.api.TenantId
import com.workflow.tenant.*
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping("/api/v1/tenants")
class TenantController(
    private val tenantRegistry: TenantRegistry
) {

    @PostMapping
    fun registerTenant(
        @Valid @RequestBody request: RegisterTenantRequest
    ): ResponseEntity<TenantConfigResponse> {
        val config = TenantConfiguration(
            tenantId = TenantId.of(request.tenantId),
            name = request.name,
            plan = TenantPlan.valueOf(request.plan),
            enabledPlugins = request.enabledPlugins.toSet(),
            featureFlags = request.featureFlags
        )
        tenantRegistry.register(config)
        return ResponseEntity.status(HttpStatus.CREATED).body(TenantConfigResponse.from(config))
    }

    @GetMapping
    fun listTenants(): ResponseEntity<List<TenantConfigResponse>> {
        val tenants = tenantRegistry.getAll().map { TenantConfigResponse.from(it) }
        return ResponseEntity.ok(tenants)
    }

    @GetMapping("/{tenantId}")
    fun getTenant(@PathVariable tenantId: String): ResponseEntity<TenantConfigResponse> {
        val config = tenantRegistry.findOrNull(TenantId.of(tenantId))
            ?: return ResponseEntity.notFound().build()
        return ResponseEntity.ok(TenantConfigResponse.from(config))
    }

    @DeleteMapping("/{tenantId}")
    fun deregisterTenant(@PathVariable tenantId: String): ResponseEntity<Void> {
        if (!tenantRegistry.exists(TenantId.of(tenantId))) {
            return ResponseEntity.notFound().build()
        }
        tenantRegistry.deregister(TenantId.of(tenantId))
        return ResponseEntity.noContent().build()
    }
}

data class RegisterTenantRequest(
    @field:NotBlank val tenantId: String,
    @field:NotBlank val name: String,
    val plan: String = "STARTER",
    val enabledPlugins: List<String> = emptyList(),
    val featureFlags: Map<String, Boolean> = emptyMap()
)

data class TenantConfigResponse(
    val tenantId: String,
    val name: String,
    val plan: String,
    val enabledPlugins: List<String>
) {
    companion object {
        fun from(config: TenantConfiguration) = TenantConfigResponse(
            tenantId = config.tenantId.value,
            name = config.name,
            plan = config.plan.name,
            enabledPlugins = config.enabledPlugins.toList()
        )
    }
}
