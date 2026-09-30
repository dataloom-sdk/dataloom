package io.dataloom.governance.audit

import io.dataloom.api.state.DurableStateScopeKeyEncoder
import kotlin.jvm.JvmInline

/**
 * Identifies which durable audit chain a [DurableAuditStore] call addresses --
 * for example one per `DataLoom` instance, one per tenant, or one per
 * subsystem sharing a single [io.dataloom.api.state.DurableStateStore].
 *
 * Deliberately the same host-supplied, single-field shape
 * [io.dataloom.api.operational.OperationalEventOutboxScope] already uses for
 * its own durable stream: the domain (here, the audit chain) does not decide
 * partitioning, the host does, by choosing how many [DurableAuditStore]
 * instances it constructs and which scope each uses.
 *
 * `AuditLog` itself is a single serialized chain per instance (one writer
 * mutex, one store) -- it has no concept of multiple tenants sharing one
 * chain. Constructing one [DurableAuditStore] with a fixed scope reproduces
 * that today's [InMemoryAuditStore]-backed usage already has; a host that
 * wants one independent chain per tenant constructs one [DurableAuditStore]
 * (and one [AuditLog]) per [AuditStoreScope] value, sharing the same
 * underlying [io.dataloom.api.state.DurableStateStore]. Neither this type nor
 * [DurableAuditStore] enforces or assumes any particular partitioning scheme.
 */
@JvmInline
public value class AuditStoreScope(
    public val value: String,
) {
    init {
        require(value.isNotBlank()) { "AuditStoreScope must not be blank." }
    }

    override fun toString(): String = value

    public companion object {
        /**
         * Reference [DurableStateScopeKeyEncoder] for [AuditStoreScope]. [value]
         * is already validated non-blank and is the entire scope identity, so
         * no escaping/composition is needed -- the same reasoning
         * [io.dataloom.api.operational.OperationalEventOutboxScope] documents
         * for its own single-field scope.
         */
        public val KeyEncoder: DurableStateScopeKeyEncoder<AuditStoreScope> = DurableStateScopeKeyEncoder { it.value }
    }
}
