package io.dataloom.api.circuit

import io.dataloom.api.identifier.TenantId
import io.dataloom.api.identifier.WorkflowId
import io.dataloom.api.provider.ProviderId
import io.dataloom.api.retry.RetryOperation

/** Stable scope kinds for one circuit-breaker state record. */
public enum class CircuitBreakerScopeKind {
    GLOBAL,
    PROVIDER,
    PROVIDER_OPERATION,
    TENANT_PROVIDER_OPERATION,
    WORKFLOW,
}

/**
 * Immutable identity for one circuit breaker.
 *
 * Exactly one scope shape is valid for each [kind]. DataLoom does not apply
 * implicit scope inheritance or fallback; the integration selects the exact
 * scope whose state is loaded and updated.
 */
public data class CircuitBreakerScope(
    public val kind: CircuitBreakerScopeKind,
    public val providerId: ProviderId? = null,
    public val operation: RetryOperation? = null,
    public val tenantId: TenantId? = null,
    public val workflowId: WorkflowId? = null,
) {
    init {
        require(isValidShape()) {
            "CircuitBreakerScope fields do not match kind $kind."
        }
    }

    private fun isValidShape(): Boolean = when (kind) {
        CircuitBreakerScopeKind.GLOBAL ->
            providerId == null && operation == null && tenantId == null && workflowId == null

        CircuitBreakerScopeKind.PROVIDER ->
            providerId != null && operation == null && tenantId == null && workflowId == null

        CircuitBreakerScopeKind.PROVIDER_OPERATION ->
            providerId != null && operation != null && tenantId == null && workflowId == null

        CircuitBreakerScopeKind.TENANT_PROVIDER_OPERATION ->
            providerId != null && operation != null && tenantId != null && workflowId == null

        CircuitBreakerScopeKind.WORKFLOW ->
            providerId == null && operation == null && tenantId == null && workflowId != null
    }

    public companion object {
        /** Creates the single global circuit scope. */
        public fun global(): CircuitBreakerScope = CircuitBreakerScope(
            kind = CircuitBreakerScopeKind.GLOBAL,
        )

        /** Creates a provider-wide circuit scope. */
        public fun provider(providerId: ProviderId): CircuitBreakerScope = CircuitBreakerScope(
            kind = CircuitBreakerScopeKind.PROVIDER,
            providerId = providerId,
        )

        /** Creates a provider-operation circuit scope. */
        public fun providerOperation(
            providerId: ProviderId,
            operation: RetryOperation,
        ): CircuitBreakerScope = CircuitBreakerScope(
            kind = CircuitBreakerScopeKind.PROVIDER_OPERATION,
            providerId = providerId,
            operation = operation,
        )

        /** Creates a tenant/provider/operation circuit scope. */
        public fun tenantProviderOperation(
            tenantId: TenantId,
            providerId: ProviderId,
            operation: RetryOperation,
        ): CircuitBreakerScope = CircuitBreakerScope(
            kind = CircuitBreakerScopeKind.TENANT_PROVIDER_OPERATION,
            providerId = providerId,
            operation = operation,
            tenantId = tenantId,
        )

        /** Creates a workflow-local circuit scope. */
        public fun workflow(workflowId: WorkflowId): CircuitBreakerScope = CircuitBreakerScope(
            kind = CircuitBreakerScopeKind.WORKFLOW,
            workflowId = workflowId,
        )
    }
}
