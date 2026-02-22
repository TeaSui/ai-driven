package com.workflow.tenant

import com.workflow.api.TenantId

/**
 * Thread-local holder for the current tenant context.
 * Used to propagate tenant identity through the call stack.
 */
object TenantContext {

    private val currentTenant = ThreadLocal<TenantId?>()

    fun set(tenantId: TenantId) {
        currentTenant.set(tenantId)
    }

    fun get(): TenantId = currentTenant.get()
        ?: throw IllegalStateException("No tenant context set for current thread")

    fun getOrNull(): TenantId? = currentTenant.get()

    fun clear() {
        currentTenant.remove()
    }

    fun <T> withTenant(tenantId: TenantId, block: () -> T): T {
        val previous = currentTenant.get()
        return try {
            set(tenantId)
            block()
        } finally {
            if (previous != null) set(previous) else clear()
        }
    }
}
