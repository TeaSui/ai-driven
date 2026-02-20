/**
 * Tenant-aware service management for multi-tenant SaaS deployment.
 *
 * <p>Key classes:</p>
 * <ul>
 *   <li>{@link com.aidriven.core.tenant.TenantServiceFactory} — Creates and caches per-tenant service sets</li>
 *   <li>{@link com.aidriven.core.tenant.TenantServices} — Holds resolved modules for a tenant</li>
 * </ul>
 *
 * <p>Works with the SPI module ({@code com.aidriven.spi}) for module discovery
 * and tenant configuration.</p>
 */
package com.aidriven.core.tenant;
