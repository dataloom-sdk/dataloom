package io.dataloom.runtime.facade

import io.dataloom.api.circuit.CircuitBreakerStateStore
import io.dataloom.api.scheduling.SchedulingDelay
import io.dataloom.runtime.retry.CircuitBreakerConfiguration
import io.dataloom.runtime.retry.CircuitBreakerFailureClassifier
import io.dataloom.runtime.retry.StorageCircuitBreakerFailureClassifier
import io.dataloom.runtime.retry.StorageCircuitScopes
import io.dataloom.runtime.retry.TransportCircuitBreakerFailureClassifier
import io.dataloom.runtime.retry.TransportCircuitScopes

/**
 * Immutable storage protection specification used by [DataLoomBuilder].
 *
 * The state store is supplied explicitly; the builder never creates an
 * in-memory fallback. Construction performs no provider, store, clock, timeout,
 * I/O, identifier, or coroutine activity.
 */
public class DataLoomStorageProtectionSpec(
    public val circuitBreakerConfiguration: CircuitBreakerConfiguration,
    public val circuitBreakerStateStore: CircuitBreakerStateStore,
    public val scopes: StorageCircuitScopes,
    public val providerTimeout: SchedulingDelay? = null,
    public val failureClassifier: CircuitBreakerFailureClassifier =
        StorageCircuitBreakerFailureClassifier,
) {
    /** Bounded diagnostic representation that excludes the state store and classifier. */
    override fun toString(): String =
        "DataLoomStorageProtectionSpec(" +
            "providerTimeoutConfigured=${providerTimeout != null}, " +
            "scopeCount=8" +
            ")"
}

/**
 * Immutable transport protection specification used by [DataLoomBuilder].
 *
 * The state store is supplied explicitly; the builder never creates an
 * in-memory fallback. Construction performs no provider, store, clock, timeout,
 * I/O, identifier, or coroutine activity.
 */
public class DataLoomTransportProtectionSpec(
    public val circuitBreakerConfiguration: CircuitBreakerConfiguration,
    public val circuitBreakerStateStore: CircuitBreakerStateStore,
    public val scopes: TransportCircuitScopes,
    public val providerTimeout: SchedulingDelay? = null,
    public val failureClassifier: CircuitBreakerFailureClassifier =
        TransportCircuitBreakerFailureClassifier,
) {
    /** Bounded diagnostic representation that excludes the state store and classifier. */
    override fun toString(): String =
        "DataLoomTransportProtectionSpec(" +
            "providerTimeoutConfigured=${providerTimeout != null}, " +
            "scopeCount=5" +
            ")"
}

/**
 * Complete builder specification for protected direct synchronization.
 *
 * Storage and transport policy are independent. No timeout, circuit store,
 * classifier, scope, tenant, workflow, or provider identity is inferred from
 * the other side.
 */
public class DataLoomProviderProtectionSpec(
    public val storage: DataLoomStorageProtectionSpec,
    public val transport: DataLoomTransportProtectionSpec,
) {
    /** Bounded diagnostic representation that excludes stores and classifiers. */
    override fun toString(): String =
        "DataLoomProviderProtectionSpec(storage=$storage, transport=$transport)"
}
