package com.workflow.app.api

import com.workflow.api.TenantId
import com.workflow.plugin.PluginRegistry
import com.workflow.plugin.WorkflowPlugin
import com.workflow.tenant.TenantRegistry
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping("/api/v1")
class PluginController(
    private val pluginRegistry: PluginRegistry,
    private val tenantRegistry: TenantRegistry
) {

    @GetMapping("/plugins")
    fun listAllPlugins(): ResponseEntity<List<PluginResponse>> {
        val plugins = pluginRegistry.getAllPlugins().map { PluginResponse.from(it) }